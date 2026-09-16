package ssafy.a507.backend.domain.chain.indexer;

import static org.assertj.core.api.Assertions.assertThat;

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
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.config.ChainProperties;
import ssafy.a507.backend.domain.chain.config.EventPoolProperties;
import ssafy.a507.backend.domain.chain.entity.ChainEvent;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.entity.TokenLedger;
import ssafy.a507.backend.domain.chain.repository.ChainEventRepository;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * ANT-EVENT-02 — 풀 인덱서 ③ 의 반영·따라적기·커서. 체인은 {@link FakeChainLogSource} 가 대신한다. H2 다.
 *
 * <p>토큰·앵커 주소는 비워 두 인덱서는 꺼진 채다 — 셋이 독립인 것도 검증 대상이다. 시나리오는 Hardhat 픽스처와 같은 모양
 * (종목 0: alice UP 1000 · bob DOWN 500 → UP 승 · 종목 1: carol UP 700 → TIE 환불 · 종목 2: alice DOWN 2000 → 미리빌 환불).
 */
@SpringBootTest(
        properties = {
            "app.chain.rpc-url=wss://fake.invalid",
            "app.chain.event-pool.address=0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512",
            "app.chain.indexer.from-block=100",
            "app.chain.indexer.max-block-range=50"
        })
@Import(FakeChainLogSource.Config.class)
@Transactional
@DisplayName("풀 인덱서 ③")
class PoolIndexerTest {

    static final String POOL = "0xe7f1725e7734ce288f8367e1bb143e90bb3f0512";
    static final String TOKEN = "0xe11d728b157240dcf4a8c831176d248bafd33077";
    static final String WALLET_A = "0x00000000000000000000000000000000000000aa";
    static final String WALLET_B = "0x00000000000000000000000000000000000000bb";
    static final String WALLET_C = "0x00000000000000000000000000000000000000cc";
    static final String STRANGER = "0x00000000000000000000000000000000000000ee";
    static final String COMMIT = "0x" + "48".repeat(32);
    static final BigDecimal BASE_0 = new BigDecimal("91000.00");

    @Autowired EntityManager em;
    @Autowired PoolIndexer indexer;
    @Autowired PoolIndexService service;
    @Autowired FakeChainLogSource source;
    @Autowired ChainEventRepository events;
    @Autowired OperationRepository operations;
    @Autowired ChainProperties props;

    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        source.reset();
        indexer.resetForTest();
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES ('005930', '삼성전자', true), ('000660', 'SK하이닉스', true), ('373220', 'LG에너지솔루션', true)")
                .executeUpdate();
    }

    // ── 반영 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("전체 흐름 — 열림 → 배팅 3건 → 봉인 → 리빌 → 판정 → 정산·환불 → 종료. 원장·배팅·이벤트·종목·작업이 전부 로그대로")
    void 전체_흐름() {
        User alice = user(WALLET_A);
        User bob = user(WALLET_B);
        User carol = user(WALLET_C);
        PredictionEvent event = event();
        long id = event.getId();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventStock s1 = stock(event, 1, "000660", new BigDecimal("210000.00"));
        PredictionEventStock s2 = stock(event, 2, "373220", new BigDecimal("500.00"));
        Operation openOp = op(alice, Operation.Kind.EVENT_ADMIN, Operation.ResourceType.EVENT, id, tx("open"));
        PredictionEventBet a0 = bet(alice, event, s0, Prediction.Direction.UP, 1000, tx("p-a0"));
        PredictionEventBet b0 = bet(bob, event, s0, Prediction.Direction.DOWN, 500, tx("p-b0"));
        PredictionEventBet c1 = bet(carol, event, s1, Prediction.Direction.UP, 700, tx("p-c1"));
        PredictionEventBet a2 = bet(alice, event, s2, Prediction.Direction.DOWN, 2000, tx("p-a2"));

        source.add(opened(tx("open"), 107, id, 3, 500, 2000))
                .add(placed(tx("p-a0"), 108, id, 0, WALLET_A, 0, 1000))
                .add(placed(tx("p-b0"), 109, id, 1, WALLET_B, 0, 500))
                .add(placed(tx("p-c1"), 110, id, 2, WALLET_C, 1, 700))
                .add(placed(tx("p-a2"), 111, id, 3, WALLET_A, 2, 2000))
                .add(sealed(tx("seal"), 112, id))
                .add(revealed(tx("reveal"), 113, 0, id, 0, 0))
                .add(revealed(tx("reveal"), 113, 1, id, 1, 1))
                .add(revealed(tx("reveal"), 113, 2, id, 2, 0))
                .add(eventOnly(PoolLog.Kind.EVENT_REVEALED, tx("revealDone"), 114, id))
                .add(resolved(tx("r0"), 115, id, 0, 9_100_000, 9_180_000, 0))
                .add(resolved(tx("r1"), 116, id, 1, 21_000_000, 21_000_000, 2))
                .add(resolved(tx("r2"), 117, id, 2, 50_000, 49_000, 1))
                .add(eventOnly(PoolLog.Kind.EVENT_RESOLVED, tx("resolveDone"), 118, id))
                .add(settled(tx("settle"), 119, 1, id, 0, WALLET_A, 1500))
                .add(settled(tx("settle"), 119, 2, id, 1, WALLET_B, 0))
                .add(refunded(tx("settle"), 119, 4, id, 2, WALLET_C, 700))
                .add(refunded(tx("settle"), 119, 6, id, 3, WALLET_A, 2000))
                .add(eventSettled(tx("sweep"), 120, id, 0));

        indexer.poll(now);

        // 원장 — Hardhat 픽스처의 최종 잔액과 같다: alice +500 · bob −500 · carol 0
        assertThat(ledger(alice)).extracting(TokenLedger::getDelta, TokenLedger::getReason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(BigInteger.valueOf(-1000), "EVENT_STAKE"),
                        org.assertj.core.groups.Tuple.tuple(BigInteger.valueOf(-2000), "EVENT_STAKE"),
                        org.assertj.core.groups.Tuple.tuple(BigInteger.valueOf(1500), "EVENT_PAYOUT"),
                        org.assertj.core.groups.Tuple.tuple(BigInteger.valueOf(2000), "EVENT_REFUND"));
        assertThat(ledger(bob)).singleElement().satisfies(l -> assertThat(l.getDelta()).isEqualTo(BigInteger.valueOf(-500))); // payout 0 은 원장 없음
        assertThat(ledger(carol)).extracting(TokenLedger::getDelta)
                .containsExactly(BigInteger.valueOf(-700), BigInteger.valueOf(700));

        // 배팅
        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.SETTLED);
        assertThat(reload(a0).getBetIndex()).isZero();
        assertThat(reload(a0).getPayout()).isEqualTo(BigInteger.valueOf(1500));
        assertThat(reload(a0).getTxHash()).isEqualTo(tx("p-a0"));
        assertThat(reload(a0).getRevealTxHash()).isEqualTo(tx("reveal"));
        assertThat(reload(a0).getSettleTxHash()).isEqualTo(tx("settle"));
        assertThat(reload(b0).getBetStatus()).isEqualTo(PredictionEventBet.Status.SETTLED);
        assertThat(reload(b0).getPayout()).isZero();
        assertThat(reload(c1).getBetStatus()).isEqualTo(PredictionEventBet.Status.REFUNDED);
        assertThat(reload(c1).getPayout()).isEqualTo(BigInteger.valueOf(700));
        assertThat(reload(a2).getBetStatus()).isEqualTo(PredictionEventBet.Status.REFUNDED);
        assertThat(reload(a2).getBetIndex()).isEqualTo(3);
        assertThat(reload(a2).getRevealTxHash()).isNull(); // 미리빌

        // 이벤트 · 종목
        assertThat(reload(event).getStatus()).isEqualTo(PredictionEvent.Status.SETTLED);
        assertThat(reload(event).getOpenTxHash()).isEqualTo(tx("open"));
        assertThat(reload(event).getSealTxHash()).isEqualTo(tx("seal"));
        assertThat(reload(s0).getClosePrice()).isEqualByComparingTo("91800.00");
        assertThat(reload(s0).getWinner()).isEqualTo(PredictionEventStock.Winner.UP);
        assertThat(reload(s0).getResolveTxHash()).isEqualTo(tx("r0"));
        assertThat(reload(s1).getWinner()).isEqualTo(PredictionEventStock.Winner.TIE);
        assertThat(reload(s2).getWinner()).isEqualTo(PredictionEventStock.Winner.DOWN);
        assertThat(reload(s2).getClosePrice()).isEqualByComparingTo("490.00");
        assertThat(reload(s0).getBasePrice()).isEqualByComparingTo(BASE_0); // 기준가는 안 덮는다

        // 작업 · 이벤트 원본
        assertThat(reload(openOp).getStatus()).isEqualTo(Operation.Status.SUCCEEDED);
        assertThat(operations.findByTxHash(tx("p-a0")).orElseThrow().getStatus()).isEqualTo(Operation.Status.SUCCEEDED);
        assertThat(operations.findByTxHash(tx("p-a2")).orElseThrow().getStatus()).isEqualTo(Operation.Status.SUCCEEDED);
        assertThat(events.count()).isEqualTo(19);
        assertThat(events.findAll()).allSatisfy(e -> {
            assertThat(e.isProcessed()).isTrue();
            assertThat(e.getContractAddress()).isEqualTo(POOL);
        });
        assertThat(indexer.scannedUpTo()).contains(120L);
    }

    @Test
    @DisplayName("BetPlaced 는 왔는데 배팅 행이 없다 — 회원 원장은 그래도 −stake(체인이 진실), 이벤트는 processed, 작업 없음")
    void 배팅_행_없음() {
        User a = user(WALLET_A);
        PredictionEvent event = event();
        source.add(placed(tx("orphan"), 121, event.getId(), 0, WALLET_A, 0, 1000));

        indexer.poll(now);

        assertThat(ledger(a)).singleElement().satisfies(l -> {
            assertThat(l.getDelta()).isEqualTo(BigInteger.valueOf(-1000));
            assertThat(l.getReason()).isEqualTo("EVENT_STAKE");
        });
        assertThat(events.findAll()).singleElement().satisfies(e -> assertThat(e.isProcessed()).isTrue());
    }

    @Test
    @DisplayName("회원이 아닌 지갑의 BetPlaced 는 저장·processed 만 — 원장 없음 (리허설 계정이 여기 걸린다)")
    void 회원_아닌_지갑() {
        source.add(placed(tx("stranger"), 122, 9001, 0, STRANGER, 0, 500));

        indexer.poll(now);

        assertThat(events.findAll()).singleElement().satisfies(e -> assertThat(e.isProcessed()).isTrue());
        assertThat(em.createQuery("select count(l) from TokenLedger l", Long.class).getSingleResult()).isZero();
    }

    @Test
    @DisplayName("같은 로그를 두 회차에 걸쳐 받아도 chain_events·원장은 한 행이다")
    void 멱등() {
        User a = user(WALLET_A);
        PredictionEvent event = event();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventBet a0 = bet(a, event, s0, Prediction.Direction.UP, 1000, tx("d1"));
        source.add(placed(tx("d1"), 123, event.getId(), 0, WALLET_A, 0, 1000));

        indexer.poll(now);
        indexer.resetForTest(); // 재시작 — DB 커서부터 다시 훑는다
        indexer.poll(now);

        assertThat(events.count()).isEqualTo(1);
        assertThat(ledger(a)).hasSize(1);
        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.ACTIVE);
    }

    // ── 따라적기 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("작업이 FAILED(SEND_LOST)로 닫혔는데 배팅이 PENDING 이면 회차 끝에 FAILED 로 맞춘다")
    void 작업_실패_따라적기() {
        User a = user(WALLET_A);
        PredictionEvent event = event();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventBet a0 = bet(a, event, s0, Prediction.Direction.UP, 1000, null);
        Operation op = operations.findById(a0.getOperationId()).orElseThrow();
        op.markFailed("SEND_LOST", "test");
        em.flush();
        source.head(150);

        indexer.poll(now);

        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.FAILED);
        assertThat(service.reconcileFailedBets()).isZero(); // 두 번째는 할 게 없다
    }

    @Test
    @DisplayName("FAILED 배팅에 BetPlaced 가 뒤늦게 오면 — 배팅은 ACTIVE(체인이 진실), 작업은 FAILED 그대로, 원장은 쓴다")
    void 실패_뒤_BetPlaced() {
        User a = user(WALLET_A);
        PredictionEvent event = event();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventBet a0 = bet(a, event, s0, Prediction.Direction.UP, 1000, null);
        Operation op = operations.findById(a0.getOperationId()).orElseThrow();
        op.markFailed("SEND_LOST", "test");
        a0.fail();
        em.flush();
        source.add(placed(tx("late"), 124, event.getId(), 0, WALLET_A, 0, 1000));

        indexer.poll(now);

        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.ACTIVE);
        assertThat(reload(a0).getBetIndex()).isZero();
        assertThat(reload(op).getStatus()).isEqualTo(Operation.Status.FAILED);
        assertThat(ledger(a)).singleElement().satisfies(l -> assertThat(l.getDelta()).isEqualTo(BigInteger.valueOf(-1000)));
    }

    @Test
    @DisplayName("PENDING 배팅의 작업이 아직 PENDING 이면 건드리지 않는다")
    void 아직_PENDING() {
        User a = user(WALLET_A);
        PredictionEvent event = event();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventBet a0 = bet(a, event, s0, Prediction.Direction.UP, 1000, tx("wait"));
        source.head(150);

        indexer.poll(now);

        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.PENDING);
    }

    // ── 이벤트 없는 실패 정리 (풀 작업만) ────────────────────────────────

    @Test
    @DisplayName("tx_hash 없는 EVENT_BET PENDING 은 10분 뒤 SEND_LOST 로 FAILED, 배팅도 FAILED. 그 전엔 그대로")
    void send_lost() {
        User a = user(WALLET_A);
        PredictionEvent event = event();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventBet a0 = bet(a, event, s0, Prediction.Direction.UP, 1000, null);
        Operation op = operations.findById(a0.getOperationId()).orElseThrow();

        indexer.poll(now.plus(9, ChronoUnit.MINUTES));
        assertThat(reload(op).getStatus()).isEqualTo(Operation.Status.PENDING);
        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.PENDING);

        indexer.poll(now.plus(11, ChronoUnit.MINUTES));
        assertThat(reload(op).getStatus()).isEqualTo(Operation.Status.FAILED);
        assertThat(reload(op).getErrorCode()).isEqualTo("SEND_LOST");
        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.FAILED);
        assertThat(source.receiptCalls()).isZero();
    }

    @Test
    @DisplayName("receipt status 0 → REVERTED + 배팅 FAILED. 30초 전에는 receipt 를 묻지 않는다")
    void reverted() {
        User a = user(WALLET_A);
        PredictionEvent event = event();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventBet a0 = bet(a, event, s0, Prediction.Direction.UP, 1000, tx("rv"));
        Operation op = operations.findById(a0.getOperationId()).orElseThrow();
        source.receipt(tx("rv"), false);

        indexer.poll(now.plus(10, ChronoUnit.SECONDS));
        assertThat(source.receiptCalls()).isZero();
        assertThat(reload(op).getStatus()).isEqualTo(Operation.Status.PENDING);

        indexer.poll(now.plus(31, ChronoUnit.SECONDS));
        assertThat(source.receiptCalls()).isEqualTo(1);
        assertThat(reload(op).getErrorCode()).isEqualTo("REVERTED");
        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.FAILED);
    }

    @Test
    @DisplayName("receipt 가 10분 안에 안 나오면 TX_DROPPED. EVENT_ADMIN(openEvent) 작업도 같은 규칙 — 배팅은 없으니 작업만")
    void dropped_and_admin() {
        User admin = user(WALLET_B);
        PredictionEvent event = event();
        Operation open = op(admin, Operation.Kind.EVENT_ADMIN, Operation.ResourceType.EVENT, event.getId(), tx("open-gone"));

        indexer.poll(now.plus(5, ChronoUnit.MINUTES));
        assertThat(reload(open).getStatus()).isEqualTo(Operation.Status.PENDING);

        indexer.poll(now.plus(11, ChronoUnit.MINUTES));
        assertThat(reload(open).getErrorCode()).isEqualTo("TX_DROPPED");
    }

    @Test
    @DisplayName("receipt 는 성공인데 이벤트가 아직 없으면 그대로 둔다 — 다음 회차에 BetPlaced 가 오면 SUCCEEDED·ACTIVE")
    void receipt_ok_event_late() {
        User a = user(WALLET_A);
        PredictionEvent event = event();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventBet a0 = bet(a, event, s0, Prediction.Direction.UP, 1000, tx("late-ok"));
        Operation op = operations.findById(a0.getOperationId()).orElseThrow();
        source.receipt(tx("late-ok"), true);

        indexer.poll(now.plus(11, ChronoUnit.MINUTES));
        assertThat(reload(op).getStatus()).isEqualTo(Operation.Status.PENDING);
        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.PENDING);

        source.add(placed(tx("late-ok"), 130, event.getId(), 0, WALLET_A, 0, 1000));
        indexer.poll(now.plus(12, ChronoUnit.MINUTES));
        assertThat(reload(op).getStatus()).isEqualTo(Operation.Status.SUCCEEDED);
        assertThat(reload(a0).getBetStatus()).isEqualTo(PredictionEventBet.Status.ACTIVE);
    }

    @Test
    @DisplayName("풀 작업이 아닌 PENDING(PREDICTION_BURN · SIGNUP_BONUS)은 풀 인덱서가 건드리지 않는다 — 토큰 인덱서 몫")
    void other_kinds_untouched() {
        User a = user(WALLET_A);
        Operation burn = op(a, Operation.Kind.PREDICTION_BURN, null, null, null);
        Operation bonus = op(a, Operation.Kind.SIGNUP_BONUS, null, null, tx("bonus"));
        source.receipt(tx("bonus"), false);

        indexer.poll(now.plus(30, ChronoUnit.MINUTES));

        assertThat(reload(burn).getStatus()).isEqualTo(Operation.Status.PENDING);
        assertThat(reload(bonus).getStatus()).isEqualTo(Operation.Status.PENDING);
        assertThat(source.receiptCalls()).isZero();
    }

    // ── 커서 · 활성 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("커서는 컨트랙트별 — 토큰 이벤트가 블록 300 에 있어도 풀 커서는 from-block(100)부터")
    void 커서_독립() {
        events.save(ChainEvent.record(tx("tok"), 0, TOKEN, "Minted", 300, "{\"kind\":\"MINTED\",\"amount\":\"1\",\"blockHash\":\"0x00\"}"));
        source.head(320);

        indexer.poll(now);

        assertThat(source.poolRanges().get(0).from()).isEqualTo(100);
        assertThat(source.tokenRanges()).isEmpty(); // 토큰 인덱서는 꺼져 있다
        assertThat(indexer.scannedUpTo()).contains(320L);
    }

    @Test
    @DisplayName("풀 주소가 없으면 꺼진다 — 릴레이어 키는 안 본다")
    void 주소_없으면_꺼짐() {
        PoolIndexer off = new PoolIndexer(source, service, props, new EventPoolProperties(null));
        assertThat(off.isEnabled()).isFalse();
        assertThat(indexer.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("마지막 풀 이벤트 블록의 해시가 체인과 다르면 정지 — 이후 회차는 아무것도 묻지 않는다")
    void reorg_정지() {
        source.add(sealed(tx("r1"), 140, 9001));
        indexer.poll(now);
        assertThat(indexer.isHalted()).isFalse();

        source.overrideBlockHash(140, FakeLogs.keccak("other-chain"));
        indexer.resetForTest();
        indexer.poll(now);

        assertThat(indexer.isHalted()).isTrue();
        int ranges = source.poolRanges().size();
        indexer.poll(now);
        assertThat(source.poolRanges()).hasSize(ranges);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private User user(String wallet) {
        User u = User.create();
        u.linkWallet(wallet);
        em.persist(u);
        return u;
    }

    private PredictionEvent event() {
        PredictionEvent e =
                PredictionEvent.create(
                        "리허설", now, now.plus(1, ChronoUnit.DAYS), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 21),
                        BigInteger.valueOf(500), BigInteger.valueOf(2000), (short) 5);
        em.persist(e);
        em.flush();
        return e;
    }

    private PredictionEventStock stock(PredictionEvent event, int idx, String code, BigDecimal basePrice) {
        PredictionEventStock s = PredictionEventStock.of(event, (short) idx, em.find(Stock.class, code), basePrice);
        em.persist(s);
        return s;
    }

    /** 예측 + 배팅(PENDING) + EVENT_BET 작업. txHash 가 있으면 전송된 것으로(markSent). */
    private PredictionEventBet bet(User user, PredictionEvent event, PredictionEventStock stock, Prediction.Direction dir, long stake, String txHash) {
        Prediction p =
                Prediction.register(
                        user, stock.getStock(), dir, new BigDecimal("93000.00"), new BigDecimal("91000.00"), (short) 4,
                        event.getBaseDate(), event.getSettleDate());
        em.persist(p);
        Operation op = op(user, Operation.Kind.EVENT_BET, Operation.ResourceType.PREDICTION, p.getId(), txHash);
        PredictionEventBet b = PredictionEventBet.place(p, event, stock, BigInteger.valueOf(stake), COMMIT, op.getId());
        em.persist(b);
        em.flush();
        return b;
    }

    private Operation op(User user, Operation.Kind kind, Operation.ResourceType type, Long resourceId, String txHash) {
        Operation op = Operation.accept(user, kind, type, resourceId);
        if (txHash != null) {
            op.markSent(txHash);
        }
        em.persist(op);
        em.flush();
        return op;
    }

    private <T> T reload(T entity) {
        em.flush();
        em.refresh(entity);
        return entity;
    }

    private List<TokenLedger> ledger(User user) {
        return em.createQuery("select l from TokenLedger l where l.user.id = :id order by l.id", TokenLedger.class)
                .setParameter("id", user.getId())
                .getResultList();
    }

    static String tx(String seed) {
        return FakeLogs.keccak("pool-tx-" + seed);
    }

    static PoolLog opened(String tx, long block, long eventId, int stockCount, long min, long max) {
        return new PoolLog(tx, 0, block, FakeLogs.blockHashOf(block), POOL, PoolLog.Kind.EVENT_OPENED, eventId, null, null, null, null, null, null,
                null, null, null, stockCount, BigInteger.valueOf(min), BigInteger.valueOf(max));
    }

    static PoolLog placed(String tx, long block, long eventId, long betId, String user, int idx, long stake) {
        return new PoolLog(tx, 1, block, FakeLogs.blockHashOf(block), POOL, PoolLog.Kind.BET_PLACED, eventId, betId, user, idx,
                BigInteger.valueOf(stake), COMMIT, null, null, null, null, null, null, null);
    }

    static PoolLog sealed(String tx, long block, long eventId) {
        return eventOnly(PoolLog.Kind.EVENT_SEALED, tx, block, eventId);
    }

    static PoolLog eventOnly(PoolLog.Kind kind, String tx, long block, long eventId) {
        return new PoolLog(tx, 0, block, FakeLogs.blockHashOf(block), POOL, kind, eventId, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    static PoolLog revealed(String tx, long block, int logIndex, long eventId, long betId, int side) {
        return new PoolLog(tx, logIndex, block, FakeLogs.blockHashOf(block), POOL, PoolLog.Kind.BET_REVEALED, eventId, betId, null, null, null, null,
                side, null, null, null, null, null, null);
    }

    static PoolLog resolved(String tx, long block, long eventId, int idx, long base, long close, int winner) {
        return new PoolLog(tx, 0, block, FakeLogs.blockHashOf(block), POOL, PoolLog.Kind.STOCK_RESOLVED, eventId, null, null, idx, null, null, null,
                BigInteger.valueOf(base), BigInteger.valueOf(close), winner, null, null, null);
    }

    static PoolLog settled(String tx, long block, int logIndex, long eventId, long betId, String user, long payout) {
        return new PoolLog(tx, logIndex, block, FakeLogs.blockHashOf(block), POOL, PoolLog.Kind.BET_SETTLED, eventId, betId, user, null,
                BigInteger.valueOf(payout), null, null, null, null, null, null, null, null);
    }

    static PoolLog refunded(String tx, long block, int logIndex, long eventId, long betId, String user, long stake) {
        return new PoolLog(tx, logIndex, block, FakeLogs.blockHashOf(block), POOL, PoolLog.Kind.BET_REFUNDED, eventId, betId, user, null,
                BigInteger.valueOf(stake), null, null, null, null, null, null, null, null);
    }

    static PoolLog eventSettled(String tx, long block, long eventId, long dust) {
        return new PoolLog(tx, 0, block, FakeLogs.blockHashOf(block), POOL, PoolLog.Kind.EVENT_SETTLED, eventId, null, null, null,
                BigInteger.valueOf(dust), null, null, null, null, null, null, null, null);
    }
}
