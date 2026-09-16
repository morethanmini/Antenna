package ssafy.a507.backend.domain.chain.relay;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.chain.commit.EventCommits;
import ssafy.a507.backend.domain.chain.config.EventPoolProperties;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * web3j 로 PredictionEventPool 을 부르는 릴레이어 (ANT-EVENT-02).
 *
 * <p>{@link Web3jTokenRelayer} 를 복제했다(plan §⑥): ① {@code eth_call} 시뮬레이션으로 revert 이름을 먼저 읽는다
 * ② 통과하면 {@link TxSender}(키 락) 로 raw tx 전송 ③ receipt 는 기다리지 않는다. 같은 키({@code RELAYER_PRIVATE_KEY})가
 * 풀의 OPERATOR_ROLE 도 갖는다 — 배포 스크립트가 토큰의 operator 주소를 그대로 넣는다.
 *
 * <p>조회 5개는 struct 를 돌려준다. 멤버가 전부 정적 타입(uint·bool·address·bytes32)이라 ABI 인코딩이 32바이트 워드의
 * 나열이고 오프셋이 없다 — 그래서 web3j 의 tuple 디코더 대신 워드를 잘라 읽는다({@link #words}). 워드 순서는 컨트랙트 struct
 * 선언 순서이며 {@code Web3jPoolRelayerEncodingTest} 가 Hardhat 실호출 반환값으로 고정한다.
 *
 * <p>ABI 코드젠을 쓰지 않는다(CHAIN-01·05·10 판단 유지). 함수 14 · 에러 16 의 인코딩은 여기서 손으로 하고, 시그니처가
 * 컨트랙트와 같은지는 {@code EventPoolConfigTest} 가 ABI 리소스로, 셀렉터 값은 {@code Web3jPoolRelayerEncodingTest} 가 고정한다.
 */
@Slf4j
@Component
public class Web3jPoolRelayer implements PoolRelayer {

    /**
     * estimateGas 가 안 될 때의 상한. Hardhat 실측(EVENT-01): placeBet 160k · reveal 100건 ≈ 2.0M · settle 100건 ≈ 4.3M.
     * 토큰의 300k 로는 묶음 tx 가 out-of-gas 로 죽는다.
     */
    static final BigInteger GAS_LIMIT_FALLBACK = BigInteger.valueOf(6_000_000L);
    private static final Pattern BYTES32_HEX = Pattern.compile("^0x[0-9a-fA-F]{64}$");
    private static final int UINT16_MAX = 0xFFFF;

    /** custom error 셀렉터 → 이름. OZ 둘 + 풀 14. {@code contracts/README.md} 풀 절의 목록과 같다. */
    static final Map<String, String> ERROR_SELECTORS =
            Map.ofEntries(
                    Map.entry(Web3jAnchorRelayer.selector("ERC20InsufficientBalance(address,uint256,uint256)"), "ERC20InsufficientBalance"),
                    Map.entry(Web3jAnchorRelayer.selector("AccessControlUnauthorizedAccount(address,bytes32)"), "AccessControlUnauthorizedAccount"),
                    Map.entry(Web3jAnchorRelayer.selector("BadState(uint256,uint8)"), "BadState"),
                    Map.entry(Web3jAnchorRelayer.selector("ZeroAddress()"), "ZeroAddress"),
                    Map.entry(Web3jAnchorRelayer.selector("BadConfig()"), "BadConfig"),
                    Map.entry(Web3jAnchorRelayer.selector("WrongStock(uint16)"), "WrongStock"),
                    Map.entry(Web3jAnchorRelayer.selector("StakeOutOfRange(uint256,uint256,uint256)"), "StakeOutOfRange"),
                    Map.entry(Web3jAnchorRelayer.selector("AlreadyBet(address,uint16)"), "AlreadyBet"),
                    Map.entry(Web3jAnchorRelayer.selector("NoSuchBet(uint256)"), "NoSuchBet"),
                    Map.entry(Web3jAnchorRelayer.selector("AlreadyRevealed(uint256)"), "AlreadyRevealed"),
                    Map.entry(Web3jAnchorRelayer.selector("BadCommit(uint256)"), "BadCommit"),
                    Map.entry(Web3jAnchorRelayer.selector("LengthMismatch()"), "LengthMismatch"),
                    Map.entry(Web3jAnchorRelayer.selector("AlreadyResolved(uint16)"), "AlreadyResolved"),
                    Map.entry(Web3jAnchorRelayer.selector("NotAllResolved(uint16,uint16)"), "NotAllResolved"),
                    Map.entry(Web3jAnchorRelayer.selector("AlreadySettled(uint256)"), "AlreadySettled"),
                    Map.entry(Web3jAnchorRelayer.selector("NotAllSettled(uint256,uint256)"), "NotAllSettled"));

    /** Hardhat 이 {@code error.data} 를 객체로 싸서 줄 때 안쪽 revert 데이터. {@link Web3jTokenRelayer#decodeErrorName} 과 같은 함정. */
    private static final Pattern NESTED_REVERT_DATA = Pattern.compile("\"data\"\\s*:\\s*\"(0x[0-9a-fA-F]*)\"");

    private final EventPoolProperties pool;
    private final ChainConnection connection;
    private final TxSender txSender;

    public Web3jPoolRelayer(EventPoolProperties pool, ChainConnection connection, TxSender txSender) {
        this.pool = pool;
        this.connection = connection;
        this.txSender = txSender;
        if (isEnabled()) {
            log.info("풀 릴레이어 준비: 오퍼레이터 {}, PredictionEventPool {}", txSender.senderAddress(), pool.address());
        } else {
            log.info("풀 릴레이어 꺼짐 — CHAIN_RPC_URL · RELAYER_PRIVATE_KEY · CONTRACT_EVENT_POOL 를 확인하라");
        }
    }

    @Override
    public boolean isEnabled() {
        return txSender.isEnabled() && pool.isDeployed();
    }

    // ── 상태 변경 ────────────────────────────────────────────────────────

    @Override
    public String openEvent(long eventId, int stockCount, BigInteger minStake, BigInteger maxStake) {
        return send("openEvent", openEventFunction(eventId, stockCount, minStake, maxStake));
    }

    @Override
    public String placeBet(long eventId, String user, int stockIdx, BigInteger stake, byte[] commit) {
        return send("placeBet", placeBetFunction(eventId, user, stockIdx, stake, commit));
    }

    @Override
    public String seal(long eventId) {
        return send("seal", sealFunction(eventId));
    }

    @Override
    public String reveal(long eventId, List<Long> betIds, List<Prediction.Direction> sides, List<String> noteHashes) {
        return send("reveal", revealFunction(eventId, betIds, sides, noteHashes));
    }

    @Override
    public String revealDone(long eventId) {
        return send("revealDone", revealDoneFunction(eventId));
    }

    @Override
    public String resolveStock(long eventId, int stockIdx, BigInteger basePrice, BigInteger closePrice) {
        return send("resolveStock", resolveStockFunction(eventId, stockIdx, basePrice, closePrice));
    }

    @Override
    public String resolveDone(long eventId) {
        return send("resolveDone", resolveDoneFunction(eventId));
    }

    @Override
    public String settle(long eventId, List<Long> betIds) {
        return send("settle", settleFunction(eventId, betIds));
    }

    @Override
    public String sweep(long eventId) {
        return send("sweep", sweepFunction(eventId));
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    @Override
    public EventInfo eventInfo(long eventId) {
        return decodeEventInfo(call("eventInfo", eventInfoFunction(eventId)));
    }

    @Override
    public Bet bet(long eventId, long betId) {
        return decodeBet(call("getBet", getBetFunction(eventId, betId)));
    }

    @Override
    public StockResult result(long eventId, int stockIdx) {
        return decodeResult(call("getResult", getResultFunction(eventId, stockIdx)));
    }

    @Override
    public Pool pool(long eventId, int stockIdx) {
        return decodePool(call("getPool", getPoolFunction(eventId, stockIdx)));
    }

    @Override
    public long betCount(long eventId) {
        return words(call("betCount", betCountFunction(eventId)))[0].longValueExact();
    }

    // ── 내부 ────────────────────────────────────────────────────────────

    /** 시뮬 → 전송. 아홉 함수가 같은 길을 간다. */
    private String send(String what, Function fn) {
        requireEnabled();
        String data = FunctionEncoder.encode(fn);

        // ① 시뮬레이션 — Besu 의 receipt 에는 revert 데이터가 없어서, 보내기 전에 여기서 이름을 읽는다.
        Optional<String> revert = withReconnect(() -> simulate(data));
        if (revert.isPresent()) {
            throw mapRevert(what, revert.get());
        }

        // ② 전송 — receipt 는 안 기다린다.
        String txHash = withReconnect(() -> txSender.send(pool.normalizedAddress(), data, gasLimit(data)));
        log.info("풀 {} tx 전송: {}", what, txHash);
        return txHash;
    }

    /** 조회. revert(NoSuchBet · BadState 등)면 이름을 읽어 {@link PoolRevertException}. */
    private String call(String what, Function fn) {
        requireEnabled();
        String data = FunctionEncoder.encode(fn);
        return withReconnect(
                () -> {
                    EthCall res = ethCall(data);
                    if (res.isReverted() || res.getError() != null) {
                        throw mapRevert(what, decodeErrorName(revertData(res)));
                    }
                    if (res.getValue() == null || res.getValue().length() < 2 + 64) {
                        // 조회 함수는 항상 워드 하나 이상을 돌려준다. 빈 값은 주소가 틀렸거나(컨트랙트 없음) 노드가 이상한 것.
                        log.warn("풀 {} 조회가 빈 값을 돌려줬다 — CONTRACT_EVENT_POOL {} 이 맞나", what, pool.address());
                        throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
                    }
                    return res.getValue();
                });
    }

    /** revert 이름 → 예외. 사용자·운영자가 대응할 수 있는 둘만 BusinessException, 나머지는 서버 버그. */
    private RuntimeException mapRevert(String what, String name) {
        switch (name) {
            case "ERC20InsufficientBalance":
                return new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
            case "AccessControlUnauthorizedAccount":
                log.error(
                        "풀 {} 거부 — 릴레이어 키 {} 에 OPERATOR_ROLE 이 없다. RELAYER_PRIVATE_KEY 와 배포본의 operator 를 대조하라",
                        what,
                        txSender.senderAddress());
                return new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
            default:
                log.error("풀 {} 거부 — {} (호출자 검증·러너 순서를 뚫고 온 서버 버그)", what, name);
                return new PoolRevertException(name);
        }
    }

    /** @return revert 면 에러 이름, 아니면 empty */
    private Optional<String> simulate(String data) throws IOException {
        EthCall res = ethCall(data);
        if (!res.isReverted() && res.getError() == null) {
            return Optional.empty();
        }
        return Optional.of(decodeErrorName(revertData(res)));
    }

    private static String revertData(EthCall res) {
        if (res.getError() != null && res.getError().getData() != null) {
            return res.getError().getData();
        }
        return res.getValue();
    }

    private EthCall ethCall(String data) throws IOException {
        Transaction tx = Transaction.createEthCallTransaction(txSender.senderAddress(), pool.normalizedAddress(), data);
        return connection.web3j().ethCall(tx, DefaultBlockParameterName.LATEST).send();
    }

    private BigInteger gasLimit(String data) throws IOException {
        Transaction tx =
                Transaction.createFunctionCallTransaction(
                        txSender.senderAddress(), null, BigInteger.ZERO, null, pool.normalizedAddress(), data);
        EthEstimateGas est = connection.web3j().ethEstimateGas(tx).send();
        if (est.hasError() || est.getAmountUsed() == null) {
            return GAS_LIMIT_FALLBACK;
        }
        return est.getAmountUsed().multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
    }

    /** {@link Web3jTokenRelayer} 의 것과 같은 규칙. 통과시키는 예외에 {@link PoolRevertException} 이 더해진다. */
    private <T> T withReconnect(IoSupplier<T> op) {
        try {
            return op.get();
        } catch (Exception first) {
            if (first instanceof BusinessException be) {
                throw be;
            }
            if (first instanceof PoolRevertException pre) {
                throw pre;
            }
            if (!connection.resetIfConnectionError(first)) {
                log.warn("체인 호출 실패: {}", first.toString());
                throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
            }
            try {
                return op.get();
            } catch (Exception second) {
                if (second instanceof BusinessException be) {
                    throw be;
                }
                if (second instanceof PoolRevertException pre) {
                    throw pre;
                }
                log.warn("체인 재연결 후에도 실패: {}", second.toString());
                connection.reset();
                throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
            }
        }
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
        }
    }

    // ── 인코딩 (테스트가 고정하는 표면) ─────────────────────────────────

    static Function openEventFunction(long eventId, int stockCount, BigInteger minStake, BigInteger maxStake) {
        if (stockCount <= 0 || stockCount > UINT16_MAX) {
            throw new IllegalArgumentException("stockCount 는 1 ~ 65535 여야 한다: " + stockCount);
        }
        return new Function(
                "openEvent",
                List.of(eventId(eventId), new Uint16(stockCount), Web3jTokenRelayer.amount(minStake), Web3jTokenRelayer.amount(maxStake)),
                List.of());
    }

    static Function placeBetFunction(long eventId, String user, int stockIdx, BigInteger stake, byte[] commit) {
        if (commit == null || commit.length != 32) {
            throw new IllegalArgumentException("commit 은 32바이트여야 한다");
        }
        return new Function(
                "placeBet",
                List.of(eventId(eventId), Web3jTokenRelayer.address(user), stockIdx(stockIdx), Web3jTokenRelayer.amount(stake), new Bytes32(commit)),
                List.of());
    }

    static Function sealFunction(long eventId) {
        return new Function("seal", List.of(eventId(eventId)), List.of());
    }

    static Function revealFunction(long eventId, List<Long> betIds, List<Prediction.Direction> sides, List<String> noteHashes) {
        if (betIds == null || sides == null || noteHashes == null || betIds.isEmpty()
                || betIds.size() != sides.size() || betIds.size() != noteHashes.size()) {
            throw new IllegalArgumentException("betIds · sides · noteHashes 는 비어 있지 않고 길이가 같아야 한다");
        }
        List<Uint8> sideValues = new ArrayList<>(sides.size());
        for (Prediction.Direction s : sides) {
            if (s == null) {
                throw new IllegalArgumentException("side 가 없다");
            }
            sideValues.add(new Uint8(EventCommits.sideOf(s)));
        }
        List<Bytes32> hashes = new ArrayList<>(noteHashes.size());
        for (String h : noteHashes) {
            if (h == null || !BYTES32_HEX.matcher(h).matches()) {
                throw new IllegalArgumentException("noteHash 는 0x + 64 hex 여야 한다: " + h);
            }
            hashes.add(new Bytes32(Numeric.hexStringToByteArray(h)));
        }
        return new Function(
                "reveal",
                List.of(
                        eventId(eventId),
                        new DynamicArray<>(Uint256.class, betIds(betIds)),
                        new DynamicArray<>(Uint8.class, sideValues),
                        new DynamicArray<>(Bytes32.class, hashes)),
                List.of());
    }

    static Function revealDoneFunction(long eventId) {
        return new Function("revealDone", List.of(eventId(eventId)), List.of());
    }

    static Function resolveStockFunction(long eventId, int stockIdx, BigInteger basePrice, BigInteger closePrice) {
        return new Function(
                "resolveStock",
                List.of(eventId(eventId), stockIdx(stockIdx), Web3jTokenRelayer.amount(basePrice), Web3jTokenRelayer.amount(closePrice)),
                List.of());
    }

    static Function resolveDoneFunction(long eventId) {
        return new Function("resolveDone", List.of(eventId(eventId)), List.of());
    }

    static Function settleFunction(long eventId, List<Long> betIds) {
        if (betIds == null || betIds.isEmpty()) {
            throw new IllegalArgumentException("betIds 가 비어 있다");
        }
        return new Function(
                "settle", List.of(eventId(eventId), new DynamicArray<>(Uint256.class, betIds(betIds))), List.of());
    }

    static Function sweepFunction(long eventId) {
        return new Function("sweep", List.of(eventId(eventId)), List.of());
    }

    static Function eventInfoFunction(long eventId) {
        return new Function("eventInfo", List.of(eventId(eventId)), List.of());
    }

    static Function getBetFunction(long eventId, long betId) {
        return new Function("getBet", List.of(eventId(eventId), betId(betId)), List.of());
    }

    static Function getResultFunction(long eventId, int stockIdx) {
        return new Function("getResult", List.of(eventId(eventId), stockIdx(stockIdx)), List.of());
    }

    static Function getPoolFunction(long eventId, int stockIdx) {
        return new Function("getPool", List.of(eventId(eventId), stockIdx(stockIdx)), List.of());
    }

    static Function betCountFunction(long eventId) {
        return new Function("betCount", List.of(eventId(eventId)), List.of());
    }

    static Uint256 eventId(long eventId) {
        if (eventId < 0) {
            throw new IllegalArgumentException("eventId 는 0 이상이어야 한다: " + eventId);
        }
        return new Uint256(BigInteger.valueOf(eventId));
    }

    static Uint256 betId(long betId) {
        if (betId < 0) {
            throw new IllegalArgumentException("betId 는 0 이상이어야 한다: " + betId);
        }
        return new Uint256(BigInteger.valueOf(betId));
    }

    private static List<Uint256> betIds(List<Long> betIds) {
        List<Uint256> out = new ArrayList<>(betIds.size());
        for (Long id : betIds) {
            if (id == null) {
                throw new IllegalArgumentException("betId 가 없다");
            }
            out.add(betId(id));
        }
        return out;
    }

    static Uint16 stockIdx(int stockIdx) {
        if (stockIdx < 0 || stockIdx > UINT16_MAX) {
            throw new IllegalArgumentException("stockIdx 는 0 ~ 65535 여야 한다: " + stockIdx);
        }
        return new Uint16(stockIdx);
    }

    // ── 디코딩 (struct = 정적 워드 나열) ─────────────────────────────────

    /** {@code EventInfo{status, stockCount, resolvedCount, minStake, maxStake, betCount, settledCount, escrow}} — 워드 8개. */
    static EventInfo decodeEventInfo(String hex) {
        BigInteger[] w = words(hex);
        require(w, 8, "eventInfo");
        return new EventInfo(
                Status.values()[w[0].intValueExact()],
                w[1].intValueExact(),
                w[2].intValueExact(),
                w[3],
                w[4],
                w[5].longValueExact(),
                w[6].longValueExact(),
                w[7]);
    }

    /** {@code Bet{user, stockIdx, revealed, settled, side, stake, commit, payout}} — 워드 8개. */
    static Bet decodeBet(String hex) {
        BigInteger[] w = words(hex);
        require(w, 8, "getBet");
        String user = "0x" + Numeric.toHexStringNoPrefixZeroPadded(w[0], 40).toLowerCase(Locale.ROOT);
        return new Bet(
                user,
                w[1].intValueExact(),
                w[2].signum() != 0,
                w[3].signum() != 0,
                w[4].intValueExact() == 0 ? Prediction.Direction.UP : Prediction.Direction.DOWN,
                w[5],
                "0x" + Numeric.toHexStringNoPrefixZeroPadded(w[6], 64).toLowerCase(Locale.ROOT),
                w[7]);
    }

    /** {@code StockResult{resolved, winner, basePrice, closePrice}} — 워드 4개. */
    static StockResult decodeResult(String hex) {
        BigInteger[] w = words(hex);
        require(w, 4, "getResult");
        return new StockResult(
                w[0].signum() != 0, PredictionEventStock.Winner.ofChainValue(w[1].intValueExact()), w[2], w[3]);
    }

    /** {@code (uint256 up, uint256 down)} — 워드 2개. */
    static Pool decodePool(String hex) {
        BigInteger[] w = words(hex);
        require(w, 2, "getPool");
        return new Pool(w[0], w[1]);
    }

    /** 반환 데이터를 32바이트 워드 배열로. 정적 struct·정적 튜플은 오프셋 없이 멤버가 순서대로 이어진다. */
    static BigInteger[] words(String hex) {
        String body = Numeric.cleanHexPrefix(hex);
        if (body.length() % 64 != 0) {
            throw new IllegalStateException("반환 데이터가 32바이트 단위가 아니다: " + hex);
        }
        BigInteger[] out = new BigInteger[body.length() / 64];
        for (int i = 0; i < out.length; i++) {
            out[i] = new BigInteger(body.substring(i * 64, (i + 1) * 64), 16);
        }
        return out;
    }

    private static void require(BigInteger[] w, int n, String what) {
        if (w.length != n) {
            throw new IllegalStateException(what + " 반환 워드 수가 " + n + " 이 아니라 " + w.length + " 다 — ABI 가 바뀌었나");
        }
    }

    /** revert 데이터의 앞 4바이트로 이름을 찾는다. Besu 의 따옴표·Hardhat 의 객체 한 겹 함정은 {@link Web3jTokenRelayer#decodeErrorName} 과 같다. */
    static String decodeErrorName(String revertData) {
        if (revertData == null) {
            return "Unknown(no data)";
        }
        String cleaned = revertData.trim();
        if (cleaned.startsWith("{")) {
            Matcher inner = NESTED_REVERT_DATA.matcher(cleaned);
            cleaned = inner.find() ? inner.group(1) : "";
        }
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() >= 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.length() < 10 || !cleaned.startsWith("0x")) {
            return "Unknown(no data)";
        }
        String sel = cleaned.substring(0, 10).toLowerCase(Locale.ROOT);
        return ERROR_SELECTORS.getOrDefault(sel, "Unknown(" + sel + ")");
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws Exception;
    }
}
