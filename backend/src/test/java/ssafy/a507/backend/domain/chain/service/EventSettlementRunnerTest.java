package ssafy.a507.backend.domain.chain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.indexer.FakeChainLogSource;
import ssafy.a507.backend.domain.chain.relay.PoolRelayer;
import ssafy.a507.backend.domain.event.entity.EventSettlementRun;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.repository.EventSettlementRunRepository;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * ANT-EVENT-03 — 정산 러너. 체인은 {@link FakePoolRelayer}(컨트랙트 상태 머신 사본)와 {@link FakeChainLogSource}(receipt)가 대신한다. H2 다.
 *
 * <p>시나리오는 EVENT-02 픽스처와 같다 — 종목 0: alice UP 1000 · bob DOWN 500 → 종가 > 기준가 → alice 1500 · bob 0.
 * 종목 1: carol UP 700 → 보합 → 환불. 종목 2: dave DOWN 2000 인데 커밋의 noteHash 가 없다 → 리빌 못 함 → 환불.
 * 인덱서는 안 돈다 — 러너가 DB 배팅 상태를 쓰지 않는 것(체인만 진실)도 검증 대상이다.
 */
@SpringBootTest(
        properties = {
            "app.chain.rpc-url=wss://fake.invalid",
            "app.chain.event-pool.address=0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512",
            "app.chain.event-pool.settlement.chunk-size=2",
            "app.chain.event-pool.settlement.receipt-timeout-seconds=1",
            "app.chain.event-pool.settlement.retry-count=2",
            "app.chain.event-pool.settlement.retry-delay-seconds=0"
        })
@Import({FakeChainLogSource.Config.class, FakePoolRelayer.Config.class})
@Transactional
@DisplayName("정산 러너")
class EventSettlementRunnerTest {

    static final String WALLET_A = "0x00000000000000000000000000000000000000aa";
    static final String WALLET_B = "0x00000000000000000000000000000000000000bb";
    static final String WALLET_C = "0x00000000000000000000000000000000000000cc";
    static final String WALLET_D = "0x00000000000000000000000000000000000000dd";
    static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 17);
    static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 21);

    @Autowired EntityManager em;
    @Autowired EventSettlementRunner runner;
    @Autowired FakePoolRelayer pool;
    @Autowired FakeChainLogSource source;
    @Autowired EventSettlementRunRepository runs;

    private final Instant now = Instant.now();
    private PredictionEvent event;

    @BeforeEach
    void setUp() {
        source.reset();
        pool.reset();
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES ('005930', '삼성전자', true), ('000660', 'SK하이닉스', true), ('373220', 'LG에너지솔루션', true)")
                .executeUpdate();
    }

    /** 표준 픽스처: 봉인된 이벤트 · 종목 3 · 종가 있음 · 배팅 4건(체인·DB 둘 다) · 예측 전부 판정됨. */
    private void standard() {
        event = event(PredictionEvent.Status.SEALED);
        long id = event.getId();
        PredictionEventStock s0 = stock(event, 0, "005930", "91000.00");
        PredictionEventStock s1 = stock(event, 1, "000660", "210000.00");
        PredictionEventStock s2 = stock(event, 2, "373220", "500.00");
        quote("005930", "91800.00");
        quote("000660", "210000.00");
        quote("373220", "490.00");
        pool.state(id).status = PoolRelayer.Status.SEALED;
        pool.state(id).stockCount = 3;
        User alice = user(WALLET_A);
        User bob = user(WALLET_B);
        User carol = user(WALLET_C);
        User dave = user(WALLET_D);
        bet(alice, s0, Prediction.Direction.UP, 1000, true, true);
        bet(bob, s0, Prediction.Direction.DOWN, 500, false, true);
        bet(carol, s1, Prediction.Direction.UP, 700, false, true);
        bet(dave, s2, Prediction.Direction.DOWN, 2000, true, false); // noteHash 없음
        em.flush();
    }

    @Test
    @DisplayName("전체 흐름 — reveal 2묶음 → revealDone → resolve ×3 → resolveDone → settle 2묶음 → sweep. 배분·환불이 컨트랙트 식대로")
    void 전체_흐름() {
        standard();
        long id = event.getId();

        EventSettlementRunner.RunReport report = runner.run(id);

        assertThat(report.alreadySettled()).isFalse();
        assertThat(report.revealed()).isEqualTo(3); // dave 는 재료가 없어 안 밝힘
        assertThat(report.resolved()).isEqualTo(3);
        assertThat(report.settled()).isEqualTo(4);
        assertThat(pool.sent())
                .containsExactly("reveal", "reveal", "revealDone", "resolveStock", "resolveStock", "resolveStock", "resolveDone", "settle", "settle", "sweep");
        assertThat(pool.state(id).status).isEqualTo(PoolRelayer.Status.SETTLED);
        assertThat(pool.bet(id, 0).payout()).isEqualTo(BigInteger.valueOf(1500)); // alice: 1000 × 1500 / 1000
        assertThat(pool.bet(id, 1).payout()).isZero(); // bob 진 쪽
        assertThat(pool.bet(id, 2).payout()).isEqualTo(BigInteger.valueOf(700)); // carol 보합 환불
        assertThat(pool.bet(id, 3).payout()).isEqualTo(BigInteger.valueOf(2000)); // dave 미리빌 환불
        assertThat(pool.totalPayout(id)).isEqualTo(BigInteger.valueOf(4200)); // = Σstake 4200 − 잔돈 0
        assertThat(pool.result(id, 0).winner()).isEqualTo(PredictionEventStock.Winner.UP);
        assertThat(pool.result(id, 1).winner()).isEqualTo(PredictionEventStock.Winner.TIE);
        assertThat(pool.result(id, 0).basePrice()).isEqualTo(BigInteger.valueOf(9_100_000)); // ×100

        EventSettlementRun run = runs.findById(report.runId()).orElseThrow();
        assertThat(run.getPhase()).isEqualTo(EventSettlementRun.Phase.DONE);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(runner.state(id)).map(EventSettlementRun::getId).contains(report.runId());
        assertThat(runner.isRunning(id)).isFalse();
    }

    @Test
    @DisplayName("다시 누르면 — 체인이 SETTLED 라 tx 없이 끝난다. DB 가 SETTLED 면 체인도 안 묻는다")
    void 재실행_멱등() {
        standard();
        long id = event.getId();
        runner.run(id);
        int sentBefore = pool.sent().size();

        // DB 는 아직 SEALED(인덱서가 안 돈다) → 체인 상태로 건너뛴다
        EventSettlementRunner.RunReport second = runner.run(id);
        assertThat(second.alreadySettled()).isFalse();
        assertThat(pool.sent()).hasSize(sentBefore); // 아무것도 안 보냈다
        assertThat(runs.findById(second.runId()).orElseThrow().getPhase()).isEqualTo(EventSettlementRun.Phase.DONE);

        event.markSettled();
        em.flush();
        EventSettlementRunner.RunReport third = runner.run(id);
        assertThat(third.alreadySettled()).isTrue();
        assertThat(pool.sent()).hasSize(sentBefore);
    }

    @Test
    @DisplayName("정산 단계에서 RPC 장애가 재시도 횟수를 넘기면 멈추고(FAILED · CHAIN_UNAVAILABLE), 다시 누르면 판정까지는 건너뛰고 정산부터 잇는다")
    void 중간_실패_뒤_재개() {
        standard();
        long id = event.getId();
        // reveal 2 + revealDone + resolve 3 + resolveDone = 7 번 성공, 8번째(settle)부터 장애 2회 = retry-count
        pool.unavailableNext(0);
        // 장애를 settle 시점에 걸려면 앞 7건이 지난 뒤 켜야 한다 — 두 단계로 나눠 돌린다.
        // 1) 판정까지만 돌리기: sweep 직전에 멈추게 settle 에서 장애를 2회(=retry-count) 주입
        pool.unavailableNextAfter(7, 2);

        assertThatThrownBy(() -> runner.run(id))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAIN_UNAVAILABLE);
        assertThat(pool.state(id).status).isEqualTo(PoolRelayer.Status.RESOLVED);
        EventSettlementRun failed = runs.findLatest(id).orElseThrow();
        assertThat(failed.getPhase()).isEqualTo(EventSettlementRun.Phase.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("CHAIN_UNAVAILABLE");
        int sentBefore = pool.sent().size();

        EventSettlementRunner.RunReport report = runner.run(id);

        assertThat(report.revealed()).isZero();
        assertThat(report.resolved()).isZero();
        assertThat(report.settled()).isEqualTo(4);
        assertThat(pool.sent().subList(sentBefore, pool.sent().size())).containsExactly("settle", "settle", "sweep");
        assertThat(pool.state(id).status).isEqualTo(PoolRelayer.Status.SETTLED);
        assertThat(runs.findById(report.runId()).orElseThrow().getPhase()).isEqualTo(EventSettlementRun.Phase.DONE);
    }

    @Test
    @DisplayName("인덱서가 늦어 DB 는 ACTIVE 인데 체인은 이미 밝힌 배팅 — 조회로 걸러 묶음에 안 넣는다(AlreadyRevealed 방지)")
    void 체인_조회로_거른다() {
        standard();
        long id = event.getId();
        // 체인에는 alice(0) 가 이미 리빌돼 있다고 심는다
        pool.state(id).bets.get(0).revealed = true;

        EventSettlementRunner.RunReport report = runner.run(id);

        assertThat(report.revealed()).isEqualTo(2); // bob · carol 만
        assertThat(pool.state(id).status).isEqualTo(PoolRelayer.Status.SETTLED);
    }

    @Test
    @DisplayName("전제 미충족 — 종가 없음 · 예측 미판정 · DB 미봉인 · 체인 미봉인 각각 EVENT_NOT_READY 이고 tx 는 없다")
    void 전제_미충족() {
        standard();
        long id = event.getId();

        em.createNativeQuery("DELETE FROM daily_quotes WHERE stock_code = '373220'").executeUpdate();
        assertNotReady(id, "종가 없음");
        quote("373220", "490.00");

        Prediction open = em.createQuery("select p from Prediction p where p.user.walletAddress = :w", Prediction.class).setParameter("w", WALLET_B).getSingleResult();
        em.createNativeQuery("UPDATE predictions SET status = 'OPEN' WHERE id = :id").setParameter("id", open.getId()).executeUpdate();
        em.clear();
        assertNotReady(id, "판정(HIT/MISS)이 안 끝난");
        em.createNativeQuery("UPDATE predictions SET status = 'MISS' WHERE id = :id").setParameter("id", open.getId()).executeUpdate();
        em.clear();

        em.createNativeQuery("UPDATE prediction_events SET status = 'OPEN' WHERE id = :id").setParameter("id", id).executeUpdate();
        em.clear();
        assertNotReady(id, "봉인되지 않았다");
        em.createNativeQuery("UPDATE prediction_events SET status = 'SEALED' WHERE id = :id").setParameter("id", id).executeUpdate();
        em.clear();

        pool.state(id).status = PoolRelayer.Status.OPEN;
        assertNotReady(id, "봉인(seal)이 먼저");
        assertThat(pool.sent()).isEmpty();
    }

    @Test
    @DisplayName("receipt 가 제때 안 오면 EVENT_SETTLE_TIMEOUT 으로 멈추고, 다시 누르면 그 tx 가 이미 반영된 상태에서 이어 간다")
    void timeout_뒤_재개() {
        standard();
        long id = event.getId();
        pool.noReceiptNext(); // 첫 reveal 묶음의 receipt 가 안 온다(채굴은 됐다)

        assertThatThrownBy(() -> runner.run(id))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_SETTLE_TIMEOUT);
        assertThat(runs.findLatest(id).orElseThrow().getErrorCode()).isEqualTo("EVENT_SETTLE_TIMEOUT");

        EventSettlementRunner.RunReport report = runner.run(id);
        assertThat(report.revealed()).isEqualTo(1); // 첫 묶음(2건)은 체인에 이미 밝혀져 있어 남은 1건만
        assertThat(pool.state(id).status).isEqualTo(PoolRelayer.Status.SETTLED);
    }

    @Test
    @DisplayName("채굴에서 revert(receipt 0)면 EVENT_SETTLE_TX_REVERTED 로 멈춘다")
    void revert_receipt() {
        standard();
        long id = event.getId();
        pool.revertReceiptNext();

        assertThatThrownBy(() -> runner.run(id))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_SETTLE_TX_REVERTED);
        assertThat(runs.findLatest(id).orElseThrow().getPhase()).isEqualTo(EventSettlementRun.Phase.FAILED);
    }

    @Test
    @DisplayName("끝을 못 본 옛 실행 행(서버 크래시)은 다음 실행이 INTERRUPTED 로 닫고 새로 시작한다")
    void 중단된_실행_정리() {
        standard();
        long id = event.getId();
        EventSettlementRun stale = EventSettlementRun.start(event);
        stale.progress(EventSettlementRun.Phase.REVEALING, 1, 3);
        em.persist(stale);
        em.flush();

        EventSettlementRunner.RunReport report = runner.run(id);

        em.flush();
        em.refresh(stale);
        assertThat(stale.getPhase()).isEqualTo(EventSettlementRun.Phase.FAILED);
        assertThat(stale.getErrorCode()).isEqualTo(EventSettlementRun.INTERRUPTED);
        assertThat(report.runId()).isNotEqualTo(stale.getId());
        assertThat(runs.findLatest(id).orElseThrow().getPhase()).isEqualTo(EventSettlementRun.Phase.DONE);
    }

    @Test
    @DisplayName("릴레이어가 꺼져 있으면 CHAIN_UNAVAILABLE — 실행 행도 안 만든다")
    void 릴레이어_꺼짐() {
        standard();
        pool.enabled(false);
        assertThatThrownBy(() -> runner.run(event.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAIN_UNAVAILABLE);
        assertThat(runs.findLatest(event.getId())).isEmpty();
    }

    /** 예외는 코드만 실어 나른다(BusinessException 규칙). 무엇이 빠졌는지는 readiness() 로 본다 — REST 상태 API 도 같은 걸 읽는다. */
    private void assertNotReady(long id, String fragment) {
        assertThatThrownBy(() -> runner.run(id))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_NOT_READY);
        assertThat(runner.readiness(id).reasons()).anySatisfy(r -> assertThat(r).contains(fragment));
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private User user(String wallet) {
        User u = User.create();
        u.linkWallet(wallet);
        em.persist(u);
        return u;
    }

    private PredictionEvent event(PredictionEvent.Status status) {
        PredictionEvent e =
                PredictionEvent.create(
                        "정산 테스트", now.minus(3, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS), BASE_DATE, SETTLE_DATE,
                        BigInteger.valueOf(500), BigInteger.valueOf(2000), (short) 1);
        if (status == PredictionEvent.Status.SEALED) {
            e.markSealed("0x" + "5e".repeat(32));
        }
        em.persist(e);
        em.flush();
        return e;
    }

    private PredictionEventStock stock(PredictionEvent event, int idx, String code, String basePrice) {
        PredictionEventStock s = PredictionEventStock.of(event, (short) idx, em.find(Stock.class, code), new BigDecimal(basePrice));
        em.persist(s);
        return s;
    }

    private void quote(String code, String close) {
        em.createNativeQuery("INSERT INTO daily_quotes (stock_code, trade_date, close, collected_at) VALUES (:c, :d, :p, CURRENT_TIMESTAMP)")
                .setParameter("c", code)
                .setParameter("d", SETTLE_DATE)
                .setParameter("p", new BigDecimal(close))
                .executeUpdate();
    }

    /**
     * 예측(판정 완료) + 커밋(noteHash 유무) + 배팅(ACTIVE, 온체인 betId) + 가짜 풀의 배팅. 온체인 betId 는 가짜 풀이 심은 순서다.
     */
    private PredictionEventBet bet(User user, PredictionEventStock stock, Prediction.Direction dir, long stake, boolean hit, boolean withNoteHash) {
        Prediction p =
                Prediction.register(
                        user, stock.getStock(), dir, new BigDecimal("93000.00"), new BigDecimal("91000.00"), (short) 4, BASE_DATE, SETTLE_DATE);
        p.fixBasePrice(stock.getBasePrice());
        p.settle(new BigDecimal("91800.00"), SETTLE_DATE, hit, new BigDecimal("1.234"));
        em.persist(p);
        em.flush();
        em.createNativeQuery("INSERT INTO prediction_commits (prediction_id, commit_hash, salt, note_hash) VALUES (:id, :h, :s, :n)")
                .setParameter("id", p.getId())
                .setParameter("h", "0x" + String.format("%064x", p.getId()))
                .setParameter("s", "ab".repeat(32))
                .setParameter("n", withNoteHash ? "0x" + "cd".repeat(32) : null)
                .executeUpdate();
        Operation op = Operation.accept(user, Operation.Kind.EVENT_BET, Operation.ResourceType.PREDICTION, p.getId());
        em.persist(op);
        long betId = pool.seedBet(event.getId(), user.getWalletAddress(), stock.getIdx(), BigInteger.valueOf(stake));
        PredictionEventBet b = PredictionEventBet.place(p, event, stock, BigInteger.valueOf(stake), "0x" + "48".repeat(32), op.getId());
        b.markPlaced(betId, "0x" + String.format("%064x", 1000 + betId));
        em.persist(b);
        return b;
    }
}
