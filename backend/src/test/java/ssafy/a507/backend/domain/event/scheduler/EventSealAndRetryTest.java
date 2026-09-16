package ssafy.a507.backend.domain.event.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.service.FakeTokenRelayer;
import ssafy.a507.backend.domain.event.relay.ScriptedPoolRelayer;
import ssafy.a507.backend.domain.event.service.EventAdminService;
import ssafy.a507.backend.domain.event.service.EventEntryService;

/**
 * 봉인 크론 · 실패한 배팅 재전송 크론 — ANT-EVENT-05 (작업명세 v1.2 §5 · §0.3).
 *
 * <p>여기서 고정하는 것은 셋이다.
 *
 * <ol>
 *   <li><b>봉인은 상태를 쓰지 않는다</b> — tx 만 보내고 SEALED 전이는 인덱서 몫이다. 미리 적으면
 *       전송이 실패했을 때 체인은 열려 있는데 서비스만 닫힌다
 *   <li><b>재전송은 새 작업으로 한다</b> — 옛 작업 id 를 두면 인덱서가 그 FAILED 를 보고 배팅을 다시
 *       FAILED 로 돌려 무한 재전송이 된다
 *   <li><b>봉인 시각이 지난 이벤트는 재전송하지 않는다</b> — 컨트랙트가 revert 한다
 * </ol>
 *
 * <p>{@code @Transactional} 롤백을 쓰지 않는다 — 전송이 "활성 트랜잭션 안에서는 거부" 가 계약이다.
 */
@SpringBootTest
@Import({ScriptedPoolRelayer.Config.class, FakeTokenRelayer.Config.class})
@DisplayName("이벤트 봉인 · 재전송 크론")
class EventSealAndRetryTest {

    private static final String CODE = "005930";

    @Autowired EventClockScheduler scheduler;
    @Autowired EventAdminService eventAdminService;
    @Autowired EventEntryService eventEntryService;
    @Autowired ScriptedPoolRelayer poolRelayer;
    @Autowired FakeTokenRelayer tokenRelayer;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    private final List<Long> userIds = new ArrayList<>();
    private Long adminId;
    private Long bettorId;

    @BeforeEach
    void setUp() {
        poolRelayer.reset();
        tokenRelayer.reset();
        // 재전송은 보내기 전에 체인 잔액을 다시 본다 — 넉넉히 채워 둔다.
        tokenRelayer.withBalance(BigInteger.valueOf(1_000_000));
        adminId = insertUser("봉인관리자", "ADMIN", "0x1111111111111111111111111111111111111111");
        bettorId = insertUser("봉인배팅러", "USER", "0x2222222222222222222222222222222222222222");
        insertStock();
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM prediction_event_bets WHERE user_id IN (:ids)")
                    .setParameter("ids", userIds)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM prediction_event_stocks WHERE stock_code = :code")
                    .setParameter("code", CODE)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM prediction_events").executeUpdate();
            em.createNativeQuery("DELETE FROM operations WHERE user_id IN (:ids)")
                    .setParameter("ids", userIds)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM predictions WHERE user_id IN (:ids)")
                    .setParameter("ids", userIds)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)")
                    .setParameter("ids", userIds)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM stocks WHERE code = :code")
                    .setParameter("code", CODE)
                    .executeUpdate();
        });
        userIds.clear();
    }

    // ── 봉인 ─────────────────────────────────────────────

    @Test
    @DisplayName("봉인 크론이 seals_at 지난 OPEN 이벤트에 seal 을 보낸다 — 상태는 그대로 OPEN 이다")
    void 봉인_크론() {
        long id = openEvent(Instant.now().minus(1, ChronoUnit.MINUTES));

        scheduler.sealDueEvents();

        assertThat(poolRelayer.callsOf("seal")).hasSize(1);
        assertThat(poolRelayer.callsOf("seal").get(0).eventId()).isEqualTo(id);
        // SEALED 전이는 인덱서가 EventSealed 로그를 보고 한다(§0.3)
        assertThat(scalar("SELECT status FROM prediction_events WHERE id = " + id)).isEqualTo("OPEN");
        assertThat(scalar("SELECT kind FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo("EVENT_ADMIN");
        assertThat(scalar("SELECT tx_hash FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isNotNull();
    }

    @Test
    @DisplayName("seals_at 이 아직이면 봉인하지 않는다")
    void 시각_전이면_봉인_안_함() {
        openEvent(Instant.now().plus(1, ChronoUnit.HOURS));

        scheduler.sealDueEvents();

        assertThat(poolRelayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("이미 seal tx 가 나갔으면 다시 보내지 않는다 — 두 번 보내면 컨트랙트가 revert 한다")
    void 이미_보냈으면_안_보냄() {
        long id = openEvent(Instant.now().minus(1, ChronoUnit.MINUTES));
        update("UPDATE prediction_events SET seal_tx_hash = '0xsealed' WHERE id = " + id);

        scheduler.sealDueEvents();

        assertThat(poolRelayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("관리자가 직접 봉인할 수 있다 — 크론이 못 돌았을 때의 길")
    void 수동_봉인() {
        long id = openEvent(Instant.now().plus(1, ChronoUnit.HOURS));

        eventAdminService.seal(adminId, id);

        assertThat(poolRelayer.callsOf("seal")).hasSize(1);
    }

    @Test
    @DisplayName("일반 회원은 봉인할 수 없다 — 전송도 안 나간다")
    void 일반회원은_봉인_불가() {
        long id = openEvent(Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> eventAdminService.seal(bettorId, id))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("관리자");
        assertThat(poolRelayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("OPEN 이 아닌 이벤트는 봉인하지 않는다")
    void OPEN_아니면_409() {
        long id = openEvent(Instant.now().plus(1, ChronoUnit.HOURS));
        update("UPDATE prediction_events SET status = 'SEALED' WHERE id = " + id);

        assertThatThrownBy(() -> eventAdminService.seal(adminId, id))
                .isInstanceOf(BusinessException.class);
        assertThat(poolRelayer.calls()).isEmpty();
    }

    // ── 재전송 ───────────────────────────────────────────

    @Test
    @DisplayName("FAILED 배팅을 새 작업으로 다시 보낸다 — 옛 작업 id 를 두면 인덱서가 다시 FAILED 로 돌린다")
    void 재전송은_새_작업으로() {
        long id = openEvent(Instant.now().plus(1, ChronoUnit.HOURS));
        long predictionId = failedBet(id);
        String oldOperationId = scalar("SELECT operation_id FROM prediction_event_bets");

        int sent = eventEntryService.retryFailedBets();

        assertThat(sent).isEqualTo(1);
        assertThat(poolRelayer.callsOf("placeBet")).hasSize(1);
        assertThat(scalar("SELECT bet_status FROM prediction_event_bets")).isEqualTo("PENDING");
        assertThat(scalar("SELECT operation_id FROM prediction_event_bets")).isNotEqualTo(oldOperationId);
        // 몇 번째 시도인지 남는다 — 운영이 "몇 번을 시도했나" 를 이 값으로 본다
        assertThat(scalar("SELECT attempts FROM prediction_event_bets")).isEqualTo("2");
        assertThat(scalar("SELECT resource_id FROM operations WHERE kind = 'EVENT_BET'"
                        + " ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo(String.valueOf(predictionId));
    }

    @Test
    @DisplayName("봉인 시각이 지난 이벤트의 배팅은 재전송하지 않는다 — 컨트랙트가 revert 한다")
    void 마감_뒤에는_재전송_안_함() {
        long id = openEvent(Instant.now().minus(1, ChronoUnit.MINUTES));
        failedBet(id);

        assertThat(eventEntryService.retryFailedBets()).isZero();
        assertThat(poolRelayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("잔액이 배팅금보다 적으면 보내지 않는다 — 작업 행만 쌓고 어차피 실패한다")
    void 잔액_부족이면_보류() {
        long id = openEvent(Instant.now().plus(1, ChronoUnit.HOURS));
        failedBet(id);
        tokenRelayer.withBalance(BigInteger.valueOf(999)); // 배팅금 1000

        assertThat(eventEntryService.retryFailedBets()).isZero();

        assertThat(poolRelayer.calls()).isEmpty();
        // 상태도 작업 id 도 그대로다 — 건드리지 않았다
        assertThat(scalar("SELECT bet_status FROM prediction_event_bets")).isEqualTo("FAILED");
        assertThat(scalar("SELECT operation_id FROM prediction_event_bets")).isEqualTo("op-failed");
        assertThat(scalar("SELECT attempts FROM prediction_event_bets")).isEqualTo("1");
    }

    @Test
    @DisplayName("충전하면 다음 회차에 나간다 — 횟수 상한 대신 잔액을 보는 이유다")
    void 충전하면_다음_회차에_나간다() {
        long id = openEvent(Instant.now().plus(1, ChronoUnit.HOURS));
        failedBet(id);
        tokenRelayer.withBalance(BigInteger.valueOf(999));
        assertThat(eventEntryService.retryFailedBets()).isZero();

        tokenRelayer.withBalance(BigInteger.valueOf(5_000));

        assertThat(eventEntryService.retryFailedBets()).isEqualTo(1);
        assertThat(poolRelayer.callsOf("placeBet")).hasSize(1);
        assertThat(scalar("SELECT bet_status FROM prediction_event_bets")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("체인이 꺼져 있으면 그 회차를 건너뛴다 — 크론이 터지면 뒤에 오는 사람도 잃는다")
    void 체인_장애면_건너뛴다() {
        long id = openEvent(Instant.now().plus(1, ChronoUnit.HOURS));
        failedBet(id);
        tokenRelayer.disable();

        assertThat(eventEntryService.retryFailedBets()).isZero();

        assertThat(poolRelayer.calls()).isEmpty();
        assertThat(scalar("SELECT bet_status FROM prediction_event_bets")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("PENDING 인 배팅은 건드리지 않는다 — 아직 전송 중이다")
    void PENDING_은_그대로() {
        long id = openEvent(Instant.now().plus(1, ChronoUnit.HOURS));
        failedBet(id);
        update("UPDATE prediction_event_bets SET bet_status = 'PENDING'");

        assertThat(eventEntryService.retryFailedBets()).isZero();
    }

    // ── fixtures ─────────────────────────────────────────

    /** OPEN 이벤트 하나 + 종목 한 칸. */
    private long openEvent(Instant sealsAt) {
        return tx.execute(s -> {
            em.createNativeQuery("""
                            INSERT INTO prediction_events
                              (title, status, opens_at, seals_at, base_date, settle_date,
                               min_stake, max_stake, min_stocks, open_tx_hash, created_at)
                            VALUES ('대회', 'OPEN', ?, ?, ?, ?, 500, 2000, 1, '0xopen', CURRENT_TIMESTAMP)
                            """)
                    .setParameter(1, Instant.now().minus(1, ChronoUnit.HOURS))
                    .setParameter(2, sealsAt)
                    .setParameter(3, LocalDate.now())
                    .setParameter(4, LocalDate.now().plusDays(4))
                    .executeUpdate();
            long id = ((Number) em.createNativeQuery("SELECT max(id) FROM prediction_events")
                            .getSingleResult())
                    .longValue();
            em.createNativeQuery("""
                            INSERT INTO prediction_event_stocks (event_id, idx, stock_code, base_price)
                            VALUES (?, 0, ?, ?)
                            """)
                    .setParameter(1, id)
                    .setParameter(2, CODE)
                    .setParameter(3, new BigDecimal("10000.00"))
                    .executeUpdate();
            return id;
        });
    }

    /**
     * 전송이 실패해 FAILED 로 닫힌 배팅 한 건. 인덱서 ⑤ 가 하는 일(작업 FAILED → 배팅 FAILED)을 그대로
     * 만든다 — 재시도 크론이 실제로 집는 모양이 이것이다.
     */
    private long failedBet(long eventId) {
        return tx.execute(s -> {
            em.createNativeQuery("""
                            INSERT INTO predictions
                              (user_id, track, stock_code, direction, target_price, ref_close, horizon,
                               base_date, settle_date, status, created_at, updated_at)
                            VALUES (?, 'REAL', ?, 'UP', 11000.00, 10000.00, 4, ?, ?, 'BASE',
                                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """)
                    .setParameter(1, bettorId)
                    .setParameter(2, CODE)
                    .setParameter(3, LocalDate.now())
                    .setParameter(4, LocalDate.now().plusDays(4))
                    .executeUpdate();
            long predictionId =
                    ((Number) em.createNativeQuery("SELECT max(id) FROM predictions").getSingleResult())
                            .longValue();

            em.createNativeQuery("""
                            INSERT INTO operations
                              (id, user_id, kind, resource_type, resource_id, status, created_at)
                            VALUES ('op-failed', ?, 'EVENT_BET', 'PREDICTION', ?, 'FAILED',
                                    CURRENT_TIMESTAMP)
                            """)
                    .setParameter(1, bettorId)
                    .setParameter(2, predictionId)
                    .executeUpdate();

            long stockId =
                    ((Number) em.createNativeQuery(
                                            "SELECT id FROM prediction_event_stocks WHERE event_id = ?")
                                    .setParameter(1, eventId)
                                    .getSingleResult())
                            .longValue();
            em.createNativeQuery("""
                            INSERT INTO prediction_event_bets
                              (prediction_id, event_id, event_stock_id, user_id, stake, event_commit,
                               bet_status, operation_id, attempts, created_at)
                            VALUES (?, ?, ?, ?, ?, '0xcommit', 'FAILED', 'op-failed', 1, CURRENT_TIMESTAMP)
                            """)
                    .setParameter(1, predictionId)
                    .setParameter(2, eventId)
                    .setParameter(3, stockId)
                    .setParameter(4, bettorId)
                    .setParameter(5, BigInteger.valueOf(1000))
                    .executeUpdate();
            return predictionId;
        });
    }

    private void insertStock() {
        tx.executeWithoutResult(s -> em.createNativeQuery(
                        "INSERT INTO stocks (code, name, listed) VALUES (?, '삼성전자', true)")
                .setParameter(1, CODE)
                .executeUpdate());
    }

    private Long insertUser(String nickname, String role, String wallet) {
        Long id = tx.execute(s -> {
            User u = User.create();
            u.changeNickname(nickname);
            u.linkWallet(wallet);
            em.persist(u);
            em.flush();
            em.createNativeQuery("UPDATE users SET role = ? WHERE id = ?")
                    .setParameter(1, role)
                    .setParameter(2, u.getId())
                    .executeUpdate();
            return u.getId();
        });
        userIds.add(id);
        return id;
    }

    private String scalar(String sql) {
        Object v = tx.execute(s -> em.createNativeQuery(sql).getSingleResult());
        return v == null ? null : String.valueOf(v);
    }

    private void update(String sql) {
        tx.executeWithoutResult(s -> em.createNativeQuery(sql).executeUpdate());
    }
}
