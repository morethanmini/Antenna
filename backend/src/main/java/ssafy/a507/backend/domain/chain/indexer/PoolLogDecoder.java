package ssafy.a507.backend.domain.chain.indexer;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.EventValues;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.tx.Contract;
import org.web3j.utils.Numeric;

/**
 * PredictionEventPool 로그를 {@link PoolLog} 로 푼다 (ANT-EVENT-02).
 *
 * <p>ABI 코드젠을 쓰지 않는다(CHAIN-04·11 판단 유지). 이벤트 열의 정의를 여기 한 번 손으로 적고, 배포된 컨트랙트와 같은지는
 * {@code EventPoolConfigTest} 가 ABI 리소스로, 실제 바이트는 {@code PoolLogDecoderTest} 가 Hardhat 실로그로 고정한다.
 *
 * <p>OZ 의 {@code RoleGranted} 류와 같은 tx 의 ERC-20 {@code Transfer}(토큰 주소에서 뜬다)는 안 읽는다 — 원장은 BetPlaced ·
 * BetSettled · BetRefunded 로만 쓴다. Transfer 까지 읽으면 배팅 하나가 두 번 잡힌다.
 */
@Slf4j
public final class PoolLogDecoder {

    private static final TypeReference<Uint256> IDX_U256 = new TypeReference<>(true) {};
    private static final TypeReference<Address> IDX_ADDR = new TypeReference<>(true) {};
    private static final TypeReference<Uint16> IDX_U16 = new TypeReference<>(true) {};
    private static final TypeReference<Uint256> U256 = new TypeReference<>(false) {};
    private static final TypeReference<Uint16> U16 = new TypeReference<>(false) {};
    private static final TypeReference<Uint8> U8 = new TypeReference<>(false) {};
    private static final TypeReference<Bytes32> B32 = new TypeReference<>(false) {};

    /** {@code EventOpened(uint256 indexed eventId, uint16 stockCount, uint256 minStake, uint256 maxStake)} */
    public static final Event EVENT_OPENED = new Event("EventOpened", List.of(IDX_U256, U16, U256, U256));

    /** {@code BetPlaced(uint256 indexed eventId, uint256 indexed betId, address indexed user, uint16 stockIdx, uint256 stake, bytes32 commit)} */
    public static final Event BET_PLACED = new Event("BetPlaced", List.of(IDX_U256, IDX_U256, IDX_ADDR, U16, U256, B32));

    /** {@code EventSealed(uint256 indexed eventId)} */
    public static final Event EVENT_SEALED = new Event("EventSealed", List.of(IDX_U256));

    /** {@code BetRevealed(uint256 indexed eventId, uint256 indexed betId, uint8 side)} */
    public static final Event BET_REVEALED = new Event("BetRevealed", List.of(IDX_U256, IDX_U256, U8));

    /** {@code EventRevealed(uint256 indexed eventId)} */
    public static final Event EVENT_REVEALED = new Event("EventRevealed", List.of(IDX_U256));

    /** {@code StockResolved(uint256 indexed eventId, uint16 indexed stockIdx, uint256 basePrice, uint256 closePrice, uint8 winner)} */
    public static final Event STOCK_RESOLVED = new Event("StockResolved", List.of(IDX_U256, IDX_U16, U256, U256, U8));

    /** {@code EventResolved(uint256 indexed eventId)} */
    public static final Event EVENT_RESOLVED = new Event("EventResolved", List.of(IDX_U256));

    /** {@code BetSettled(uint256 indexed eventId, uint256 indexed betId, address indexed user, uint256 payout)} */
    public static final Event BET_SETTLED = new Event("BetSettled", List.of(IDX_U256, IDX_U256, IDX_ADDR, U256));

    /** {@code BetRefunded(uint256 indexed eventId, uint256 indexed betId, address indexed user, uint256 stake)} */
    public static final Event BET_REFUNDED = new Event("BetRefunded", List.of(IDX_U256, IDX_U256, IDX_ADDR, U256));

    /** {@code EventSettled(uint256 indexed eventId, uint256 dust)} */
    public static final Event EVENT_SETTLED = new Event("EventSettled", List.of(IDX_U256, U256));

    public static final String TOPIC_EVENT_OPENED = EventEncoder.encode(EVENT_OPENED);
    public static final String TOPIC_BET_PLACED = EventEncoder.encode(BET_PLACED);
    public static final String TOPIC_EVENT_SEALED = EventEncoder.encode(EVENT_SEALED);
    public static final String TOPIC_BET_REVEALED = EventEncoder.encode(BET_REVEALED);
    public static final String TOPIC_EVENT_REVEALED = EventEncoder.encode(EVENT_REVEALED);
    public static final String TOPIC_STOCK_RESOLVED = EventEncoder.encode(STOCK_RESOLVED);
    public static final String TOPIC_EVENT_RESOLVED = EventEncoder.encode(EVENT_RESOLVED);
    public static final String TOPIC_BET_SETTLED = EventEncoder.encode(BET_SETTLED);
    public static final String TOPIC_BET_REFUNDED = EventEncoder.encode(BET_REFUNDED);
    public static final String TOPIC_EVENT_SETTLED = EventEncoder.encode(EVENT_SETTLED);

    /** getLogs 의 topic0 필터(열 중 하나). {@code RoleGranted} 는 노드가 걸러 준다. */
    public static final List<String> TOPICS =
            List.of(
                    TOPIC_EVENT_OPENED, TOPIC_BET_PLACED, TOPIC_EVENT_SEALED, TOPIC_BET_REVEALED, TOPIC_EVENT_REVEALED,
                    TOPIC_STOCK_RESOLVED, TOPIC_EVENT_RESOLVED, TOPIC_BET_SETTLED, TOPIC_BET_REFUNDED, TOPIC_EVENT_SETTLED);

    private PoolLogDecoder() {}

    /**
     * @return topics[0] 이 열 중 하나가 아니면 empty — 필터를 믿고 예외를 내는 것보다 조용히 거르는 편이 낫다.
     *     eventId·betId 가 long 을 넘는 로그(수동 tx)도 empty + WARN — DB 의 bigint 에 못 넣는다.
     */
    public static Optional<PoolLog> decode(Log raw) {
        List<String> topics = raw.getTopics();
        if (topics == null || topics.isEmpty()) {
            return Optional.empty();
        }
        String topic0 = lower(topics.get(0));
        try {
            if (topic0.equals(TOPIC_EVENT_OPENED)) {
                EventValues v = params(EVENT_OPENED, raw);
                return Optional.of(build(raw, PoolLog.Kind.EVENT_OPENED, u256(v, 0), null, null, null, null, null, null, null, null, null,
                        u16(v, 0), u256n(v, 1), u256n(v, 2)));
            }
            if (topic0.equals(TOPIC_BET_PLACED)) {
                EventValues v = params(BET_PLACED, raw);
                return Optional.of(build(raw, PoolLog.Kind.BET_PLACED, u256(v, 0), u256(v, 1).longValueExact(), address(v, 2),
                        u16(v, 0), u256n(v, 1), bytes32(v, 2), null, null, null, null, null, null, null));
            }
            if (topic0.equals(TOPIC_EVENT_SEALED)) {
                EventValues v = params(EVENT_SEALED, raw);
                return Optional.of(build(raw, PoolLog.Kind.EVENT_SEALED, u256(v, 0), null, null, null, null, null, null, null, null, null, null, null, null));
            }
            if (topic0.equals(TOPIC_BET_REVEALED)) {
                EventValues v = params(BET_REVEALED, raw);
                return Optional.of(build(raw, PoolLog.Kind.BET_REVEALED, u256(v, 0), u256(v, 1).longValueExact(), null, null, null, null,
                        u8(v, 0), null, null, null, null, null, null));
            }
            if (topic0.equals(TOPIC_EVENT_REVEALED)) {
                EventValues v = params(EVENT_REVEALED, raw);
                return Optional.of(build(raw, PoolLog.Kind.EVENT_REVEALED, u256(v, 0), null, null, null, null, null, null, null, null, null, null, null, null));
            }
            if (topic0.equals(TOPIC_STOCK_RESOLVED)) {
                EventValues v = params(STOCK_RESOLVED, raw);
                return Optional.of(build(raw, PoolLog.Kind.STOCK_RESOLVED, u256(v, 0), null, null, idx16(v, 1), null, null, null,
                        u256n(v, 0), u256n(v, 1), u8(v, 2), null, null, null));
            }
            if (topic0.equals(TOPIC_EVENT_RESOLVED)) {
                EventValues v = params(EVENT_RESOLVED, raw);
                return Optional.of(build(raw, PoolLog.Kind.EVENT_RESOLVED, u256(v, 0), null, null, null, null, null, null, null, null, null, null, null, null));
            }
            if (topic0.equals(TOPIC_BET_SETTLED)) {
                EventValues v = params(BET_SETTLED, raw);
                return Optional.of(build(raw, PoolLog.Kind.BET_SETTLED, u256(v, 0), u256(v, 1).longValueExact(), address(v, 2), null,
                        u256n(v, 0), null, null, null, null, null, null, null, null));
            }
            if (topic0.equals(TOPIC_BET_REFUNDED)) {
                EventValues v = params(BET_REFUNDED, raw);
                return Optional.of(build(raw, PoolLog.Kind.BET_REFUNDED, u256(v, 0), u256(v, 1).longValueExact(), address(v, 2), null,
                        u256n(v, 0), null, null, null, null, null, null, null, null));
            }
            if (topic0.equals(TOPIC_EVENT_SETTLED)) {
                EventValues v = params(EVENT_SETTLED, raw);
                return Optional.of(build(raw, PoolLog.Kind.EVENT_SETTLED, u256(v, 0), null, null, null, u256n(v, 0), null, null, null, null, null, null, null, null));
            }
        } catch (ArithmeticException e) {
            log.warn("풀 로그 tx {} #{} — eventId·betId 가 bigint 를 넘는다(서버가 보낸 tx 가 아니다). 건너뜀", raw.getTransactionHash(), raw.getLogIndex());
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static PoolLog build(
            Log log,
            PoolLog.Kind kind,
            BigInteger eventId,
            Long betId,
            String user,
            Integer stockIdx,
            BigInteger amount,
            String commitHex,
            Integer side,
            BigInteger basePrice,
            BigInteger closePrice,
            Integer winner,
            Integer stockCount,
            BigInteger minStake,
            BigInteger maxStake) {
        return new PoolLog(
                lower(log.getTransactionHash()),
                log.getLogIndex().intValueExact(),
                log.getBlockNumber().longValueExact(),
                lower(log.getBlockHash()),
                lower(log.getAddress()),
                kind,
                eventId.longValueExact(),
                betId,
                user,
                stockIdx,
                amount,
                commitHex,
                side,
                basePrice,
                closePrice,
                winner,
                stockCount,
                minStake,
                maxStake);
    }

    private static EventValues params(Event event, Log log) {
        return Contract.staticExtractEventParameters(event, log);
    }

    private static BigInteger u256(EventValues v, int indexedPos) {
        return ((Uint256) v.getIndexedValues().get(indexedPos)).getValue();
    }

    private static int idx16(EventValues v, int indexedPos) {
        return ((Uint16) v.getIndexedValues().get(indexedPos)).getValue().intValueExact();
    }

    private static String address(EventValues v, int indexedPos) {
        return lower(((Address) v.getIndexedValues().get(indexedPos)).getValue());
    }

    private static BigInteger u256n(EventValues v, int nonIndexedPos) {
        return ((Uint256) v.getNonIndexedValues().get(nonIndexedPos)).getValue();
    }

    private static int u16(EventValues v, int nonIndexedPos) {
        return ((Uint16) v.getNonIndexedValues().get(nonIndexedPos)).getValue().intValueExact();
    }

    private static int u8(EventValues v, int nonIndexedPos) {
        return ((Uint8) v.getNonIndexedValues().get(nonIndexedPos)).getValue().intValueExact();
    }

    private static String bytes32(EventValues v, int nonIndexedPos) {
        return lower(Numeric.toHexString(((Bytes32) v.getNonIndexedValues().get(nonIndexedPos)).getValue()));
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
