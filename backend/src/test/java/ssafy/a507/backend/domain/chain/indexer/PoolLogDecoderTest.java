package ssafy.a507.backend.domain.chain.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

/**
 * ANT-EVENT-02 — Hardhat 에서 뜬 <b>실제</b> PredictionEventPool 로그를 그대로 디코딩한다.
 *
 * <p>픽스처는 2026-09-16 Hardhat 인프로세스 체인에서 전 흐름(openEvent → 배팅 4건 → seal → reveal 3건 → revealDone →
 * resolveStock ×3 → resolveDone → settle → sweep)을 돌린 뒤 {@code eth_getLogs} 로 받은 원본이다(topics·data 를 손대지 않았다).
 * SSAFY 운영 체인 로그가 아닌 이유: 풀은 아직 운영에 배포 전이다 — 컴파일러·ABI 가 같으니 바이트 배치는 같다.
 */
@DisplayName("PredictionEventPool 로그 디코딩")
class PoolLogDecoderTest {

    static final String POOL = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512";
    static final String EVENT_9001 = "0x0000000000000000000000000000000000000000000000000000000000002329";
    static final String ALICE = "0x90f79bf6eb2c4f870365e785982e1f101e93b906";
    static final String ALICE_TOPIC = "0x00000000000000000000000090f79bf6eb2c4f870365e785982e1f101e93b906";
    static final String BOB_TOPIC = "0x00000000000000000000000015d34aaf54267db7d7c367839aaf71a00a2c6a65";
    static final String W0 = "0x0000000000000000000000000000000000000000000000000000000000000000";
    static final String W1 = "0x0000000000000000000000000000000000000000000000000000000000000001";
    static final String W3 = "0x0000000000000000000000000000000000000000000000000000000000000003";

    static final String TOPIC_EVENT_OPENED = "0x43e2d0c9e8255824a3422dd49bfe8630e128bbcd2baefcd2b42c4c2acdaaf3c9";
    static final String TOPIC_BET_PLACED = "0xe9591c7edd69983f26af0985c44f54441ee447411452ea6d43dcb107416a6be6";
    static final String TOPIC_EVENT_SEALED = "0x6eff68962593098f3e526a5eaa8be5000496be3228259c27839ae111772db331";
    static final String TOPIC_BET_REVEALED = "0xcde65cbd73b8cffcf9d78ed3a8690f96f523471b4a8b90a5a6a77b8d1d1078b2";
    static final String TOPIC_EVENT_REVEALED = "0xe751784a304a4726e250e6a4997e56e6ae502830c98af62c4fcaca33c83b5490";
    static final String TOPIC_STOCK_RESOLVED = "0xc0d80cfc384eafa21bcb3190a033e1cc45a0904ed04f4a1366f38e981adb3b07";
    static final String TOPIC_EVENT_RESOLVED = "0x18de1fad0fc334b362957087aa445a3adbc7a3a64642680431e0f396dcf034b8";
    static final String TOPIC_BET_SETTLED = "0x9851065d84abd773c3c2d1e93b16fd9378349da3d33a839b0ad819474b4327d9";
    static final String TOPIC_BET_REFUNDED = "0xd188cab2dae53a906e4d6a3dd9d89e50421798b85e0a780990e82e7292280209";
    static final String TOPIC_EVENT_SETTLED = "0xded5d01b60819460ae7ebcd2d6ff94175503d3c14b78dd61052cf3daa30c312c";
    static final String TOPIC_ROLE_GRANTED = "0x2f8788117e7eff1d82e926ec794901d17c78024a50270940304540a733656f0d";

    static final String OPEN_TX = "0x1a1f77d819e156b892b8960125cdb6c8e74b37c6d9592233ad1520eceacd6ebd";
    static final String OPEN_BLOCK_HASH = "0x46d2600d1f696afa93fb07cd9fccad3961f9164656bd3fa685553bfaa13c3be6";
    static final String PLACE_TX = "0xa8d4b84a0a03b1751981d6b2d05b1d456c461cad7a21a06429c19b643d11dac8";
    static final String PLACE_BLOCK_HASH = "0xc2e5c646c6433ea84911dcf7d0c288e63dadfe7759b1461499df231d990abb59";
    static final String SEAL_TX = "0x25dae2a42d7deeba2e647f22812b22708b9e4d27e6c2dced702fafaf26938247";
    static final String SEAL_BLOCK_HASH = "0x03512c03d25fce3370d15a1a46b4c3e88c1b0bf8b87da925d21ca98824e8bf21";
    static final String REVEAL_TX = "0x03f1ed5ec97519d77f4264e67463a47545dacc060c4e37740e9113138b517843";
    static final String REVEAL_BLOCK_HASH = "0xa1083d169b33fef40044f154bedb7c0f8744747b2b7b04e99404c4f8026f7d07";
    static final String REVEAL_DONE_TX = "0x58ad9e44e522bff6cca0673735eda205de0a44638b7386aaa734fe26d8d1b470";
    static final String REVEAL_DONE_BLOCK_HASH = "0xd770172d5040227976beda11b54aa7a3b6ee76c8e47871232591c86266d91634";
    static final String RESOLVE_TX = "0xb63cd2615bb153a6cd856f8fcbf0a3bdc1f593a88320d14ab1b5bc69389f6118";
    static final String RESOLVE_BLOCK_HASH = "0xb8b5447ec52f8411ce8e9e3100c247b18835e9ee4270d2005fbd20c327751875";
    static final String RESOLVE_DONE_TX = "0xe153b30ffbb68bb19169057a983d1cd41443537a7ef990ae0eb12ae2752346fe";
    static final String RESOLVE_DONE_BLOCK_HASH = "0xccc4fb66d6ed23b14176be4909b8d675c4f831b9bd04f28789e829978984450b";
    static final String SETTLE_TX = "0x8aecc897a7b22ea792840abb13aef95c305d800d9ca619ab8179e7d352498783";
    static final String SETTLE_BLOCK_HASH = "0xe3a03f46029ffb4ea8ba4bcbe10e7587cea3e789ceeae22f80383029a383dff5";
    static final String SWEEP_TX = "0x9f139c2016f5e7196b80a1e99244edd45b0c49ad568cdd500393c89d05056122";
    static final String SWEEP_BLOCK_HASH = "0x2ec5ba0280ed3b14e54a85b78aa95a8dbcd580d9e6964b17e337f51800f913e5";

    static Log log(String tx, String blockHash, long block, int idx, List<String> topics, String data) {
        return new Log(
                false,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(idx)),
                "0x0",
                tx,
                blockHash,
                Numeric.toHexStringWithPrefix(BigInteger.valueOf(block)),
                POOL, // 노드가 준 그대로(체크섬 대문자) — 디코더의 소문자 정규화도 같이 본다
                data,
                "mined",
                topics);
    }

    @Test
    @DisplayName("이벤트 시그니처 topic0 열 — 전부 실로그의 값과 같다")
    void topic0_고정() {
        assertThat(PoolLogDecoder.TOPIC_EVENT_OPENED).isEqualTo(TOPIC_EVENT_OPENED);
        assertThat(PoolLogDecoder.TOPIC_BET_PLACED).isEqualTo(TOPIC_BET_PLACED);
        assertThat(PoolLogDecoder.TOPIC_EVENT_SEALED).isEqualTo(TOPIC_EVENT_SEALED);
        assertThat(PoolLogDecoder.TOPIC_BET_REVEALED).isEqualTo(TOPIC_BET_REVEALED);
        assertThat(PoolLogDecoder.TOPIC_EVENT_REVEALED).isEqualTo(TOPIC_EVENT_REVEALED);
        assertThat(PoolLogDecoder.TOPIC_STOCK_RESOLVED).isEqualTo(TOPIC_STOCK_RESOLVED);
        assertThat(PoolLogDecoder.TOPIC_EVENT_RESOLVED).isEqualTo(TOPIC_EVENT_RESOLVED);
        assertThat(PoolLogDecoder.TOPIC_BET_SETTLED).isEqualTo(TOPIC_BET_SETTLED);
        assertThat(PoolLogDecoder.TOPIC_BET_REFUNDED).isEqualTo(TOPIC_BET_REFUNDED);
        assertThat(PoolLogDecoder.TOPIC_EVENT_SETTLED).isEqualTo(TOPIC_EVENT_SETTLED);
        assertThat(PoolLogDecoder.TOPICS).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("EventOpened 실로그 — eventId 9001 · 종목 3 · 500~2000, 주소·해시는 소문자")
    void event_opened() {
        PoolLog l =
                PoolLogDecoder.decode(
                                log(OPEN_TX, OPEN_BLOCK_HASH, 7, 0, List.of(TOPIC_EVENT_OPENED, EVENT_9001),
                                        "0x0000000000000000000000000000000000000000000000000000000000000003"
                                                + "00000000000000000000000000000000000000000000000000000000000001f4"
                                                + "00000000000000000000000000000000000000000000000000000000000007d0"))
                        .orElseThrow();

        assertThat(l.kind()).isEqualTo(PoolLog.Kind.EVENT_OPENED);
        assertThat(l.eventId()).isEqualTo(9001);
        assertThat(l.stockCount()).isEqualTo(3);
        assertThat(l.minStake()).isEqualTo(BigInteger.valueOf(500));
        assertThat(l.maxStake()).isEqualTo(BigInteger.valueOf(2000));
        assertThat(l.txHash()).isEqualTo(OPEN_TX);
        assertThat(l.logIndex()).isZero();
        assertThat(l.blockNumber()).isEqualTo(7);
        assertThat(l.blockHash()).isEqualTo(OPEN_BLOCK_HASH);
        assertThat(l.contractAddress()).isEqualTo(POOL.toLowerCase());
        assertThat(l.betId()).isNull();
        assertThat(l.user()).isNull();
    }

    @Test
    @DisplayName("BetPlaced 실로그 — betId 0 · alice · 종목 0 · 1000 ANT · commit(EventCommitsTest 벡터와 같은 값)")
    void bet_placed() {
        PoolLog l =
                PoolLogDecoder.decode(
                                log(PLACE_TX, PLACE_BLOCK_HASH, 8, 1, List.of(TOPIC_BET_PLACED, EVENT_9001, W0, ALICE_TOPIC),
                                        "0x0000000000000000000000000000000000000000000000000000000000000000"
                                                + "00000000000000000000000000000000000000000000000000000000000003e8"
                                                + "489bf1900ebee098f75749f1b8fa8c1b9d92778e54683f5ee7713d9d12a40f52"))
                        .orElseThrow();

        assertThat(l.kind()).isEqualTo(PoolLog.Kind.BET_PLACED);
        assertThat(l.eventId()).isEqualTo(9001);
        assertThat(l.betId()).isZero();
        assertThat(l.user()).isEqualTo(ALICE);
        assertThat(l.stockIdx()).isZero();
        assertThat(l.amount()).isEqualTo(BigInteger.valueOf(1000));
        assertThat(l.commitHex()).isEqualTo("0x489bf1900ebee098f75749f1b8fa8c1b9d92778e54683f5ee7713d9d12a40f52");
        assertThat(l.logIndex()).isEqualTo(1); // 같은 tx 의 ERC-20 Transfer 가 0번이라 풀 로그는 1번
        assertThat(l.side()).isNull();
    }

    @Test
    @DisplayName("EventSealed · EventRevealed · EventResolved 실로그 — eventId 만")
    void event_only_logs() {
        PoolLog sealed = PoolLogDecoder.decode(log(SEAL_TX, SEAL_BLOCK_HASH, 12, 0, List.of(TOPIC_EVENT_SEALED, EVENT_9001), "0x")).orElseThrow();
        assertThat(sealed.kind()).isEqualTo(PoolLog.Kind.EVENT_SEALED);
        assertThat(sealed.eventId()).isEqualTo(9001);

        PoolLog revealed = PoolLogDecoder.decode(log(REVEAL_DONE_TX, REVEAL_DONE_BLOCK_HASH, 14, 0, List.of(TOPIC_EVENT_REVEALED, EVENT_9001), "0x")).orElseThrow();
        assertThat(revealed.kind()).isEqualTo(PoolLog.Kind.EVENT_REVEALED);

        PoolLog resolved = PoolLogDecoder.decode(log(RESOLVE_DONE_TX, RESOLVE_DONE_BLOCK_HASH, 18, 0, List.of(TOPIC_EVENT_RESOLVED, EVENT_9001), "0x")).orElseThrow();
        assertThat(resolved.kind()).isEqualTo(PoolLog.Kind.EVENT_RESOLVED);
        assertThat(resolved.blockNumber()).isEqualTo(18);
    }

    @Test
    @DisplayName("BetRevealed 실로그 — betId 1 · side 1(DOWN)")
    void bet_revealed() {
        PoolLog l = PoolLogDecoder.decode(log(REVEAL_TX, REVEAL_BLOCK_HASH, 13, 1, List.of(TOPIC_BET_REVEALED, EVENT_9001, W1), W1)).orElseThrow();

        assertThat(l.kind()).isEqualTo(PoolLog.Kind.BET_REVEALED);
        assertThat(l.betId()).isEqualTo(1);
        assertThat(l.side()).isEqualTo(1);
        assertThat(l.user()).isNull();
    }

    @Test
    @DisplayName("StockResolved 실로그 — 종목 1 · 기준가 = 종가 21,000,000(×100) · winner 2(TIE)")
    void stock_resolved() {
        PoolLog l =
                PoolLogDecoder.decode(
                                log(RESOLVE_TX, RESOLVE_BLOCK_HASH, 16, 0, List.of(TOPIC_STOCK_RESOLVED, EVENT_9001, W1),
                                        "0x0000000000000000000000000000000000000000000000000000000001406f40"
                                                + "0000000000000000000000000000000000000000000000000000000001406f40"
                                                + "0000000000000000000000000000000000000000000000000000000000000002"))
                        .orElseThrow();

        assertThat(l.kind()).isEqualTo(PoolLog.Kind.STOCK_RESOLVED);
        assertThat(l.stockIdx()).isEqualTo(1);
        assertThat(l.basePrice()).isEqualTo(BigInteger.valueOf(21_000_000));
        assertThat(l.closePrice()).isEqualTo(BigInteger.valueOf(21_000_000));
        assertThat(l.winner()).isEqualTo(2);
    }

    @Test
    @DisplayName("BetSettled 실로그 둘 — alice 1500(이긴 쪽) · bob 0(진 쪽). 같은 tx 안에서 logIndex 1·2")
    void bet_settled() {
        PoolLog win =
                PoolLogDecoder.decode(
                                log(SETTLE_TX, SETTLE_BLOCK_HASH, 19, 1, List.of(TOPIC_BET_SETTLED, EVENT_9001, W0, ALICE_TOPIC),
                                        "0x00000000000000000000000000000000000000000000000000000000000005dc"))
                        .orElseThrow();
        assertThat(win.kind()).isEqualTo(PoolLog.Kind.BET_SETTLED);
        assertThat(win.betId()).isZero();
        assertThat(win.user()).isEqualTo(ALICE);
        assertThat(win.amount()).isEqualTo(BigInteger.valueOf(1500));

        PoolLog lose =
                PoolLogDecoder.decode(log(SETTLE_TX, SETTLE_BLOCK_HASH, 19, 2, List.of(TOPIC_BET_SETTLED, EVENT_9001, W1, BOB_TOPIC), W0))
                        .orElseThrow();
        assertThat(lose.betId()).isEqualTo(1);
        assertThat(lose.amount()).isZero();
        assertThat(lose.logIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("BetRefunded 실로그 — betId 3(미리빌) · alice · 2000 원금")
    void bet_refunded() {
        PoolLog l =
                PoolLogDecoder.decode(
                                log(SETTLE_TX, SETTLE_BLOCK_HASH, 19, 6, List.of(TOPIC_BET_REFUNDED, EVENT_9001, W3, ALICE_TOPIC),
                                        "0x00000000000000000000000000000000000000000000000000000000000007d0"))
                        .orElseThrow();

        assertThat(l.kind()).isEqualTo(PoolLog.Kind.BET_REFUNDED);
        assertThat(l.betId()).isEqualTo(3);
        assertThat(l.user()).isEqualTo(ALICE);
        assertThat(l.amount()).isEqualTo(BigInteger.valueOf(2000));
    }

    @Test
    @DisplayName("EventSettled 실로그 — dust 0")
    void event_settled() {
        PoolLog l = PoolLogDecoder.decode(log(SWEEP_TX, SWEEP_BLOCK_HASH, 20, 0, List.of(TOPIC_EVENT_SETTLED, EVENT_9001), W0)).orElseThrow();

        assertThat(l.kind()).isEqualTo(PoolLog.Kind.EVENT_SETTLED);
        assertThat(l.amount()).isZero();
    }

    @Test
    @DisplayName("모르는 topic0(RoleGranted)은 empty — 필터를 믿지 않고 한 번 더 거른다")
    void unknown_topic() {
        Log granted =
                log("0x" + "9b".repeat(32), "0x" + "18".repeat(32), 2, 0,
                        List.of(TOPIC_ROLE_GRANTED, W0, ALICE_TOPIC, ALICE_TOPIC), "0x");
        assertThat(PoolLogDecoder.decode(granted)).isEmpty();
    }

    @Test
    @DisplayName("eventId 가 bigint 를 넘는 로그(수동 tx)는 empty — DB 에 못 넣는다")
    void event_id_overflow() {
        Log huge =
                log(SEAL_TX, SEAL_BLOCK_HASH, 12, 0,
                        List.of(TOPIC_EVENT_SEALED, "0x" + "ff".repeat(32)), "0x");
        assertThat(PoolLogDecoder.decode(huge)).isEmpty();
    }
}
