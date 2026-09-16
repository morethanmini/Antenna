package ssafy.a507.backend.domain.chain.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.prediction.entity.Prediction.Direction;

/**
 * ANT-EVENT-02 — 풀 릴레이어가 손으로 인코딩하는 셀렉터·인자와, struct 반환값을 워드로 자르는 디코딩이 컨트랙트와 같은지.
 *
 * <p>셀렉터 기대값은 Solidity 규칙(keccak256("이름(타입,…)") 앞 4바이트)이고, 함수·이벤트의 존재는 {@code EventPoolConfigTest} 가
 * ABI 리소스로 본다. 반환값 픽스처는 2026-09-16 Hardhat 에서 전 흐름(배팅 4건 → 정산)을 돌린 뒤 {@code eth_call} 로 받은 원문이다.
 */
@DisplayName("풀 릴레이어 인코딩·디코딩")
class Web3jPoolRelayerEncodingTest {

    private static final String A = "0x00000000000000000000000000000000000000aa";
    private static final byte[] COMMIT = new byte[32];

    static {
        COMMIT[0] = 0x48;
        COMMIT[31] = 0x52;
    }

    @Test
    @DisplayName("placeBet(uint256,address,uint16,uint256,bytes32) — 셀렉터 + 정적 인자 5개 = 164바이트")
    void placeBet_calldata() {
        String data = FunctionEncoder.encode(Web3jPoolRelayer.placeBetFunction(9001, A, 3, BigInteger.valueOf(1500), COMMIT));

        assertThat(data).startsWith(Web3jAnchorRelayer.selector("placeBet(uint256,address,uint16,uint256,bytes32)"));
        assertThat(data).hasSize(2 + (4 + 32 * 5) * 2);
        assertThat(new BigInteger(data.substring(10, 74), 16)).isEqualTo(9001); // eventId
        assertThat(data.substring(74, 138)).endsWith("aa"); // user
        assertThat(new BigInteger(data.substring(138, 202), 16)).isEqualTo(3); // stockIdx — uint16 도 32바이트 워드
        assertThat(new BigInteger(data.substring(202, 266), 16)).isEqualTo(1500); // stake — 정수 ANT 그대로
        assertThat(data.substring(266)).isEqualTo("48" + "00".repeat(30) + "52"); // commit 원문
    }

    @Test
    @DisplayName("상태 변경 8개와 조회 5개의 셀렉터")
    void selectors() {
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.openEventFunction(1, 10, BigInteger.valueOf(500), BigInteger.valueOf(2000))))
                .startsWith(Web3jAnchorRelayer.selector("openEvent(uint256,uint16,uint256,uint256)"))
                .hasSize(2 + (4 + 32 * 4) * 2);
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.sealFunction(1))).startsWith(Web3jAnchorRelayer.selector("seal(uint256)")).hasSize(2 + 36 * 2);
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.revealDoneFunction(1))).startsWith(Web3jAnchorRelayer.selector("revealDone(uint256)"));
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.resolveStockFunction(1, 0, BigInteger.valueOf(9_100_000), BigInteger.valueOf(9_180_000))))
                .startsWith(Web3jAnchorRelayer.selector("resolveStock(uint256,uint16,uint256,uint256)"))
                .hasSize(2 + (4 + 32 * 4) * 2);
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.resolveDoneFunction(1))).startsWith(Web3jAnchorRelayer.selector("resolveDone(uint256)"));
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.sweepFunction(1))).startsWith(Web3jAnchorRelayer.selector("sweep(uint256)"));
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.eventInfoFunction(1))).startsWith(Web3jAnchorRelayer.selector("eventInfo(uint256)"));
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.getBetFunction(1, 2))).startsWith(Web3jAnchorRelayer.selector("getBet(uint256,uint256)")).hasSize(2 + 68 * 2);
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.getResultFunction(1, 2))).startsWith(Web3jAnchorRelayer.selector("getResult(uint256,uint16)"));
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.getPoolFunction(1, 2))).startsWith(Web3jAnchorRelayer.selector("getPool(uint256,uint16)"));
        assertThat(FunctionEncoder.encode(Web3jPoolRelayer.betCountFunction(1))).startsWith(Web3jAnchorRelayer.selector("betCount(uint256)"));
    }

    @Test
    @DisplayName("reveal(uint256,uint256[],uint8[],bytes32[]) — 동적 배열 셋의 오프셋·길이·원소")
    void reveal_calldata() {
        String n1 = "0x" + "11".repeat(32);
        String n2 = "0x" + "22".repeat(32);
        String data =
                FunctionEncoder.encode(
                        Web3jPoolRelayer.revealFunction(9001, List.of(0L, 1L), List.of(Direction.UP, Direction.DOWN), List.of(n1, n2)));

        assertThat(data).startsWith(Web3jAnchorRelayer.selector("reveal(uint256,uint256[],uint8[],bytes32[])"));
        String body = data.substring(10);
        // 헤드: eventId · 오프셋 ×3 (4 워드 = 128바이트 = 0x80 부터 첫 배열)
        assertThat(new BigInteger(body.substring(0, 64), 16)).isEqualTo(9001);
        assertThat(new BigInteger(body.substring(64, 128), 16)).isEqualTo(0x80);
        assertThat(new BigInteger(body.substring(128, 192), 16)).isEqualTo(0x80 + 32 * 3); // betIds: 길이 + 2
        assertThat(new BigInteger(body.substring(192, 256), 16)).isEqualTo(0x80 + 32 * 6); // sides: 길이 + 2
        // betIds
        assertThat(new BigInteger(body.substring(256, 320), 16)).isEqualTo(2);
        assertThat(new BigInteger(body.substring(320, 384), 16)).isEqualTo(0);
        assertThat(new BigInteger(body.substring(384, 448), 16)).isEqualTo(1);
        // sides — uint8 도 워드 하나씩
        assertThat(new BigInteger(body.substring(448, 512), 16)).isEqualTo(2);
        assertThat(new BigInteger(body.substring(512, 576), 16)).isEqualTo(0);
        assertThat(new BigInteger(body.substring(576, 640), 16)).isEqualTo(1);
        // noteHashes
        assertThat(new BigInteger(body.substring(640, 704), 16)).isEqualTo(2);
        assertThat(body.substring(704, 768)).isEqualTo("11".repeat(32));
        assertThat(body.substring(768, 832)).isEqualTo("22".repeat(32));
        assertThat(body).hasSize(832);
    }

    @Test
    @DisplayName("settle(uint256,uint256[]) — 오프셋 0x40 · 길이 · 원소")
    void settle_calldata() {
        String data = FunctionEncoder.encode(Web3jPoolRelayer.settleFunction(7, List.of(5L, 6L, 7L)));

        assertThat(data).startsWith(Web3jAnchorRelayer.selector("settle(uint256,uint256[])"));
        String body = data.substring(10);
        assertThat(new BigInteger(body.substring(0, 64), 16)).isEqualTo(7);
        assertThat(new BigInteger(body.substring(64, 128), 16)).isEqualTo(0x40);
        assertThat(new BigInteger(body.substring(128, 192), 16)).isEqualTo(3);
        assertThat(new BigInteger(body.substring(192, 256), 16)).isEqualTo(5);
        assertThat(new BigInteger(body.substring(320, 384), 16)).isEqualTo(7);
    }

    @Test
    @DisplayName("eventInfo 반환값(Hardhat 실호출) — status SETTLED · 종목 3 · 판정 3 · 500~2000 · 배팅 4 · 정산 4 · escrow 0")
    void decode_eventInfo() {
        PoolRelayer.EventInfo info =
                Web3jPoolRelayer.decodeEventInfo(
                        "0x0000000000000000000000000000000000000000000000000000000000000005"
                                + "0000000000000000000000000000000000000000000000000000000000000003"
                                + "0000000000000000000000000000000000000000000000000000000000000003"
                                + "00000000000000000000000000000000000000000000000000000000000001f4"
                                + "00000000000000000000000000000000000000000000000000000000000007d0"
                                + "0000000000000000000000000000000000000000000000000000000000000004"
                                + "0000000000000000000000000000000000000000000000000000000000000004"
                                + "0000000000000000000000000000000000000000000000000000000000000000");

        assertThat(info.status()).isEqualTo(PoolRelayer.Status.SETTLED);
        assertThat(info.stockCount()).isEqualTo(3);
        assertThat(info.resolvedCount()).isEqualTo(3);
        assertThat(info.minStake()).isEqualTo(BigInteger.valueOf(500));
        assertThat(info.maxStake()).isEqualTo(BigInteger.valueOf(2000));
        assertThat(info.betCount()).isEqualTo(4);
        assertThat(info.settledCount()).isEqualTo(4);
        assertThat(info.escrow()).isZero();
    }

    @Test
    @DisplayName("getBet 반환값(Hardhat 실호출) — alice · 종목 0 · 리빌·정산 됨 · UP · 1000 → 1500")
    void decode_bet() {
        PoolRelayer.Bet bet =
                Web3jPoolRelayer.decodeBet(
                        "0x00000000000000000000000090f79bf6eb2c4f870365e785982e1f101e93b906"
                                + "0000000000000000000000000000000000000000000000000000000000000000"
                                + "0000000000000000000000000000000000000000000000000000000000000001"
                                + "0000000000000000000000000000000000000000000000000000000000000001"
                                + "0000000000000000000000000000000000000000000000000000000000000000"
                                + "00000000000000000000000000000000000000000000000000000000000003e8"
                                + "489bf1900ebee098f75749f1b8fa8c1b9d92778e54683f5ee7713d9d12a40f52"
                                + "00000000000000000000000000000000000000000000000000000000000005dc");

        assertThat(bet.user()).isEqualTo("0x90f79bf6eb2c4f870365e785982e1f101e93b906");
        assertThat(bet.stockIdx()).isZero();
        assertThat(bet.revealed()).isTrue();
        assertThat(bet.settled()).isTrue();
        assertThat(bet.side()).isEqualTo(Direction.UP);
        assertThat(bet.stake()).isEqualTo(BigInteger.valueOf(1000));
        assertThat(bet.commitHex()).isEqualTo("0x489bf1900ebee098f75749f1b8fa8c1b9d92778e54683f5ee7713d9d12a40f52");
        assertThat(bet.payout()).isEqualTo(BigInteger.valueOf(1500));
    }

    @Test
    @DisplayName("getResult · getPool · betCount 반환값(Hardhat 실호출) — UP 승 · 9,100,000 → 9,180,000 · 풀 [1000, 500] · 4건")
    void decode_result_pool_count() {
        PoolRelayer.StockResult r =
                Web3jPoolRelayer.decodeResult(
                        "0x0000000000000000000000000000000000000000000000000000000000000001"
                                + "0000000000000000000000000000000000000000000000000000000000000000"
                                + "00000000000000000000000000000000000000000000000000000000008adae0"
                                + "00000000000000000000000000000000000000000000000000000000008c1360");
        assertThat(r.resolved()).isTrue();
        assertThat(r.winner()).isEqualTo(PredictionEventStock.Winner.UP);
        assertThat(r.basePrice()).isEqualTo(BigInteger.valueOf(9_100_000));
        assertThat(r.closePrice()).isEqualTo(BigInteger.valueOf(9_180_000));

        PoolRelayer.Pool p =
                Web3jPoolRelayer.decodePool(
                        "0x00000000000000000000000000000000000000000000000000000000000003e8"
                                + "00000000000000000000000000000000000000000000000000000000000001f4");
        assertThat(p.up()).isEqualTo(BigInteger.valueOf(1000));
        assertThat(p.down()).isEqualTo(BigInteger.valueOf(500));

        assertThat(Web3jPoolRelayer.words("0x0000000000000000000000000000000000000000000000000000000000000004")[0]).isEqualTo(BigInteger.valueOf(4));
        assertThatThrownBy(() -> Web3jPoolRelayer.decodeEventInfo("0x" + "00".repeat(32 * 7))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("custom error 셀렉터 16종 — OZ 둘 + 풀 14")
    void error_selectors() {
        assertThat(Web3jPoolRelayer.ERROR_SELECTORS).hasSize(16);
        assertThat(Web3jPoolRelayer.decodeErrorName(Web3jAnchorRelayer.selector("ERC20InsufficientBalance(address,uint256,uint256)") + "00".repeat(96)))
                .isEqualTo("ERC20InsufficientBalance");
        assertThat(Web3jPoolRelayer.decodeErrorName(Web3jAnchorRelayer.selector("AccessControlUnauthorizedAccount(address,bytes32)") + "00".repeat(64)))
                .isEqualTo("AccessControlUnauthorizedAccount");
        // BadState(uint256 eventId, Status current) — enum 은 ABI 에서 uint8 이다
        assertThat(Web3jPoolRelayer.decodeErrorName(Web3jAnchorRelayer.selector("BadState(uint256,uint8)") + "00".repeat(64))).isEqualTo("BadState");
        for (String name :
                List.of("ZeroAddress()", "BadConfig()", "WrongStock(uint16)", "StakeOutOfRange(uint256,uint256,uint256)", "AlreadyBet(address,uint16)",
                        "NoSuchBet(uint256)", "AlreadyRevealed(uint256)", "BadCommit(uint256)", "LengthMismatch()", "AlreadyResolved(uint16)",
                        "NotAllResolved(uint16,uint16)", "AlreadySettled(uint256)", "NotAllSettled(uint256,uint256)")) {
            assertThat(Web3jPoolRelayer.decodeErrorName(Web3jAnchorRelayer.selector(name))).isEqualTo(name.substring(0, name.indexOf('(')));
        }
        assertThat(Web3jPoolRelayer.decodeErrorName("0xdeadbeef")).isEqualTo("Unknown(0xdeadbeef)");
        // Besu 따옴표 · Hardhat 객체 한 겹 — 토큰 릴레이어와 같은 함정
        assertThat(Web3jPoolRelayer.decodeErrorName("\"" + Web3jAnchorRelayer.selector("BadConfig()") + "\"")).isEqualTo("BadConfig");
        assertThat(Web3jPoolRelayer.decodeErrorName("{\"message\":\"x\",\"data\":\"" + Web3jAnchorRelayer.selector("AlreadyBet(address,uint16)") + "00".repeat(64) + "\"}"))
                .isEqualTo("AlreadyBet");
        assertThat(Web3jPoolRelayer.decodeErrorName(null)).isEqualTo("Unknown(no data)");
    }

    @Test
    @DisplayName("인자 검증 — 커밋 길이 · 인덱스 범위 · 배열 길이 불일치 · 빈 배열 · 금액 0 은 인코딩 전에 IllegalArgumentException")
    void argument_validation() {
        assertThatThrownBy(() -> Web3jPoolRelayer.placeBetFunction(1, A, 0, BigInteger.ONE, new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.placeBetFunction(1, A, 65536, BigInteger.ONE, COMMIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.placeBetFunction(1, A, 0, BigInteger.ZERO, COMMIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.placeBetFunction(-1, A, 0, BigInteger.ONE, COMMIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.placeBetFunction(1, "0xabc", 0, BigInteger.ONE, COMMIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.openEventFunction(1, 0, BigInteger.ONE, BigInteger.TWO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.revealFunction(1, List.of(0L), List.of(Direction.UP, Direction.DOWN), List.of("0x" + "00".repeat(32))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.revealFunction(1, List.of(0L), List.of(Direction.UP), List.of("00".repeat(32))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.revealFunction(1, List.of(), List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.settleFunction(1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Web3jPoolRelayer.getBetFunction(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
