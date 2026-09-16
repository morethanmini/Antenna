package ssafy.a507.backend.domain.chain.indexer;

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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.entity.TokenLedger;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * ANT-EVENT-02 — 실제 PostgreSQL 에서: ddl-auto 가 이벤트 테이블 셋과 UNIQUE 넷을 만드는지, enum-checks.sql 이 도는지,
 * 인덱서 전체 흐름이 H2 와 같게 나오는지. {@code CommitLedgerPostgresTest} 와 같은 Testcontainers 방식 — 도커 없으면 스킵.
 *
 * <p>H2 로는 확인이 안 되는 것만 여기서 본다: numeric(30,0)·varchar 길이의 실제 DDL, (event_id, bet_index) UNIQUE 에 NULL 이
 * 여러 개 들어가는 것(PG 는 NULL 끼리 다르다고 본다), 종목당 1건 UNIQUE 위반이 진짜로 막히는 것.
 */
@SpringBootTest(
        properties = {
            "app.chain.rpc-url=wss://fake.invalid",
            "app.chain.event-pool.address=0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512",
            "app.chain.indexer.from-block=100",
            "app.chain.indexer.max-block-range=50",
            // 부팅 SQL(enum-checks.sql)도 실제 PG 에서 돌려 본다. append-only 트리거도 같이 걸린다.
            "spring.sql.init.mode=always"
        })
@Import(FakeChainLogSource.Config.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
@DisplayName("풀 인덱서 ③ — 실제 PostgreSQL")
class PoolIndexerPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static final String WALLET_A = "0x00000000000000000000000000000000000000aa";
    static final String WALLET_B = "0x00000000000000000000000000000000000000bb";
    static final BigDecimal BASE_0 = new BigDecimal("91000.00");

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PoolIndexer indexer;
    @Autowired FakeChainLogSource source;

    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        source.reset();
        indexer.resetForTest();
        jdbc.update("INSERT INTO stocks (code, name, listed) VALUES ('005930', '삼성전자', true), ('000660', 'SK하이닉스', true) ON CONFLICT DO NOTHING");
    }

    @Test
    @DisplayName("ddl-auto 가 이벤트 테이블 넷과 UNIQUE 넷을 만들었고, bets 의 PK 는 prediction_id 다")
    void 스키마() {
        List<String> tables =
                jdbc.queryForList(
                        "select table_name from information_schema.tables where table_schema = 'public' and table_name like 'prediction_event%' order by 1",
                        String.class);
        assertThat(tables).containsExactly("prediction_event_bets", "prediction_event_results", "prediction_event_stocks", "prediction_events"); // results 는 EVENT-03

        List<String> uniques =
                jdbc.queryForList(
                        "select conname from pg_constraint where contype = 'u' and conrelid::regclass::text in ('prediction_event_bets', 'prediction_event_stocks') order by 1",
                        String.class);
        assertThat(uniques).containsExactly("uq_event_bets_index", "uq_event_bets_user_stock", "uq_event_stocks_code", "uq_event_stocks_idx");

        String pk =
                jdbc.queryForObject(
                        "select string_agg(a.attname, ',') from pg_constraint c join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey)"
                                + " where c.contype = 'p' and c.conrelid = 'prediction_event_bets'::regclass",
                        String.class);
        assertThat(pk).isEqualTo("prediction_id");

        // numeric(30,0) — 정수 ANT. 자릿수가 줄면 큰 금액에서 조용히 잘린다.
        assertThat(jdbc.queryForObject(
                        "select numeric_precision || ',' || numeric_scale from information_schema.columns where table_name = 'prediction_event_bets' and column_name = 'stake'",
                        String.class))
                .isEqualTo("30,0");
        // enum-checks.sql 이 돌았다 — 옛 resource_type CHECK 가 없어야 한다(새 DB 라 Hibernate 가 새 값으로 다시 건 것은 이름이 다르다).
        assertThat(jdbc.queryForList("select conname from pg_constraint where conname = 'operations_resource_type_check'", String.class)).isEmpty();
    }

    @Test
    @DisplayName("전체 흐름 — 배팅 2건 → 봉인 → 리빌 → 판정 → 정산 → 종료. 원장·배팅·이벤트가 H2 결과와 같다")
    void 전체_흐름() {
        User alice = user(WALLET_A);
        User bob = user(WALLET_B);
        PredictionEvent event = event();
        long id = event.getId();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventBet a0 = bet(alice, event, s0, Prediction.Direction.UP, 1000, tx("p-a0"));
        PredictionEventBet b0 = bet(bob, event, s0, Prediction.Direction.DOWN, 500, tx("p-b0"));

        source.add(PoolIndexerTest.opened(tx("open"), 107, id, 1, 500, 2000))
                .add(PoolIndexerTest.placed(tx("p-a0"), 108, id, 0, WALLET_A, 0, 1000))
                .add(PoolIndexerTest.placed(tx("p-b0"), 109, id, 1, WALLET_B, 0, 500))
                .add(PoolIndexerTest.sealed(tx("seal"), 112, id))
                .add(PoolIndexerTest.revealed(tx("reveal"), 113, 0, id, 0, 0))
                .add(PoolIndexerTest.revealed(tx("reveal"), 113, 1, id, 1, 1))
                .add(PoolIndexerTest.eventOnly(PoolLog.Kind.EVENT_REVEALED, tx("revealDone"), 114, id))
                .add(PoolIndexerTest.resolved(tx("r0"), 115, id, 0, 9_100_000, 9_180_000, 0))
                .add(PoolIndexerTest.eventOnly(PoolLog.Kind.EVENT_RESOLVED, tx("resolveDone"), 118, id))
                .add(PoolIndexerTest.settled(tx("settle"), 119, 1, id, 0, WALLET_A, 1500))
                .add(PoolIndexerTest.settled(tx("settle"), 119, 2, id, 1, WALLET_B, 0))
                .add(PoolIndexerTest.eventSettled(tx("sweep"), 120, id, 0));

        indexer.poll(now);
        em.flush();
        em.clear();

        assertThat(sum(alice)).isEqualTo(BigInteger.valueOf(500));
        assertThat(sum(bob)).isEqualTo(BigInteger.valueOf(-500));
        PredictionEventBet a = em.find(PredictionEventBet.class, a0.getPredictionId());
        assertThat(a.getBetStatus()).isEqualTo(PredictionEventBet.Status.SETTLED);
        assertThat(a.getBetIndex()).isZero();
        assertThat(a.getPayout()).isEqualTo(BigInteger.valueOf(1500));
        PredictionEventBet b = em.find(PredictionEventBet.class, b0.getPredictionId());
        assertThat(b.getPayout()).isZero();
        PredictionEvent e = em.find(PredictionEvent.class, id);
        assertThat(e.getStatus()).isEqualTo(PredictionEvent.Status.SETTLED);
        assertThat(e.getSealTxHash()).isEqualTo(tx("seal"));
        PredictionEventStock s = em.find(PredictionEventStock.class, s0.getId());
        assertThat(s.getClosePrice()).isEqualByComparingTo("91800.00");
        assertThat(s.getWinner()).isEqualTo(PredictionEventStock.Winner.UP);
        assertThat(jdbc.queryForObject("select count(*) from chain_events where processed_at is not null", Long.class)).isEqualTo(12L);
        assertThat(jdbc.queryForObject("select status from operations where tx_hash = ?", String.class, tx("p-a0"))).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("bet_index 가 NULL 인 PENDING 배팅은 여러 건 공존한다(PG 는 NULL 끼리 다르다) — 종목당 1건 UNIQUE 는 진짜로 막힌다")
    void unique_제약() {
        User alice = user(WALLET_A);
        User bob = user(WALLET_B);
        PredictionEvent event = event();
        PredictionEventStock s0 = stock(event, 0, "005930", BASE_0);
        PredictionEventStock s1 = stock(event, 1, "000660", new BigDecimal("210000.00"));
        bet(alice, event, s0, Prediction.Direction.UP, 1000, null);
        bet(bob, event, s0, Prediction.Direction.DOWN, 500, null);
        bet(alice, event, s1, Prediction.Direction.UP, 700, null); // 같은 사람 다른 종목 — 허용
        em.flush();

        assertThat(jdbc.queryForObject("select count(*) from prediction_event_bets where bet_index is null", Long.class)).isEqualTo(3L);

        // 같은 사람 같은 종목 두 번째 — 마지막에 둔다. PG 는 위반 뒤 트랜잭션을 못 쓴다.
        assertThatThrownBy(() -> {
                    bet(alice, event, s0, Prediction.Direction.DOWN, 600, null);
                    em.flush();
                })
                // em.flush() 직접 호출이라 스프링 번역(DataIntegrityViolationException) 전의 Hibernate 예외가 그대로 온다.
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class)
                .hasMessageContaining("uq_event_bets_user_stock");
    }

    // ── 픽스처 (PoolIndexerTest 와 같다) ────────────────────────────────

    private User user(String wallet) {
        User u = User.create();
        u.linkWallet(wallet);
        em.persist(u);
        return u;
    }

    private PredictionEvent event() {
        PredictionEvent e =
                PredictionEvent.create(
                        "PG 리허설", now, now.plus(1, ChronoUnit.DAYS), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 21),
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

    private PredictionEventBet bet(User user, PredictionEvent event, PredictionEventStock stock, Prediction.Direction dir, long stake, String txHash) {
        Prediction p =
                Prediction.register(
                        user, stock.getStock(), dir, new BigDecimal("93000.00"), new BigDecimal("91000.00"), (short) 4,
                        event.getBaseDate(), event.getSettleDate());
        em.persist(p);
        Operation op = Operation.accept(user, Operation.Kind.EVENT_BET, Operation.ResourceType.PREDICTION, p.getId());
        if (txHash != null) {
            op.markSent(txHash);
        }
        em.persist(op);
        PredictionEventBet b = PredictionEventBet.place(p, event, stock, BigInteger.valueOf(stake), PoolIndexerTest.COMMIT, op.getId());
        em.persist(b);
        em.flush();
        return b;
    }

    private BigInteger sum(User user) {
        return em.createQuery("select l from TokenLedger l where l.user.id = :id", TokenLedger.class)
                .setParameter("id", user.getId())
                .getResultList()
                .stream()
                .map(TokenLedger::getDelta)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    static String tx(String seed) {
        return FakeLogs.keccak("pg-pool-tx-" + seed);
    }
}
