package ssafy.a507.backend.domain.event.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * SCHEDULED → OPEN 전이 크론 — ANT-EVENT-04 (작업명세 v1.2 §4).
 *
 * <p>여는 조건 셋을 각각 하나씩 빼 보며 고정한다. 셋 중 하나라도 빠지면 열리면 안 된다 —
 * 기준가가 없으면 승패를 못 가리고, 온체인이 안 열렸으면 배팅 전송이 전부 실패한다.
 */
@SpringBootTest
@Transactional
@DisplayName("이벤트 시계 크론")
class EventClockSchedulerTest {

    private static final String SAMSUNG = "005930";
    private static final String HYNIX = "000660";

    @Autowired EventClockScheduler scheduler;
    @Autowired EntityManager em;

    @BeforeEach
    void setUp() {
        insertStock();
    }

    @Test
    @DisplayName("세 조건이 다 맞으면 연다 — opensAt 도달 · 기준가 완비 · open tx 확인")
    void 세_조건이_맞으면_연다() {
        long id = event(Instant.now().minus(1, ChronoUnit.HOURS), "0xopen");
        stock(id, "91000.00");

        scheduler.openDueEvents();

        assertThat(status(id)).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("opensAt 이 아직이면 안 연다")
    void 시각_전이면_안_연다() {
        long id = event(Instant.now().plus(1, ChronoUnit.HOURS), "0xopen");
        stock(id, "91000.00");

        scheduler.openDueEvents();

        assertThat(status(id)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("기준가가 빈 종목이 하나라도 있으면 안 연다 — 승패를 가릴 수 없다")
    void 기준가가_비면_안_연다() {
        long id = event(Instant.now().minus(1, ChronoUnit.HOURS), "0xopen");
        stock(id, "91000.00");
        stockAt(id, (short) 1, null);

        scheduler.openDueEvents();

        assertThat(status(id)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("온체인이 아직 안 열렸으면(open tx 없음) 안 연다 — 배팅 전송이 전부 실패한다")
    void 온체인이_안_열렸으면_안_연다() {
        long id = event(Instant.now().minus(1, ChronoUnit.HOURS), null);
        stock(id, "91000.00");

        scheduler.openDueEvents();

        assertThat(status(id)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("종목이 하나도 없으면 안 연다 — '기준가 없는 종목이 없다' 가 참이 되어 버린다")
    void 종목이_없으면_안_연다() {
        long id = event(Instant.now().minus(1, ChronoUnit.HOURS), "0xopen");

        scheduler.openDueEvents();

        assertThat(status(id)).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("이미 OPEN 인 이벤트는 건드리지 않는다 — 다시 열 것이 없다")
    void 이미_열린_것은_그대로() {
        long id = event(Instant.now().minus(1, ChronoUnit.HOURS), "0xopen");
        stock(id, "91000.00");
        em.createNativeQuery("UPDATE prediction_events SET status = 'SEALED' WHERE id = ?")
                .setParameter(1, id)
                .executeUpdate();

        scheduler.openDueEvents();

        assertThat(status(id)).isEqualTo("SEALED");
    }

    // ── fixtures ─────────────────────────────────────────

    private String status(long id) {
        em.flush();
        em.clear();
        return String.valueOf(
                em.createNativeQuery("SELECT status FROM prediction_events WHERE id = ?")
                        .setParameter(1, id)
                        .getSingleResult());
    }

    private long event(Instant opensAt, String openTxHash) {
        em.createNativeQuery("""
                        INSERT INTO prediction_events
                          (title, status, opens_at, seals_at, base_date, settle_date,
                           min_stake, max_stake, min_stocks, open_tx_hash, created_at)
                        VALUES ('대회', 'SCHEDULED', ?, ?, ?, ?, 500, 2000, 5, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, opensAt)
                .setParameter(2, opensAt.plus(1, ChronoUnit.DAYS))
                .setParameter(3, LocalDate.now().minusDays(1))
                .setParameter(4, LocalDate.now().plusDays(3))
                .setParameter(5, openTxHash)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT max(id) FROM prediction_events").getSingleResult())
                .longValue();
    }

    private void stock(long eventId, String basePrice) {
        stockAt(eventId, (short) 0, basePrice);
    }

    /** idx 마다 종목이 달라야 한다 — uq_event_stocks_code(event_id, stock_code) 가 같은 종목 두 칸을 막는다. */
    private void stockAt(long eventId, short idx, String basePrice) {
        em.createNativeQuery("""
                        INSERT INTO prediction_event_stocks (event_id, idx, stock_code, base_price)
                        VALUES (?, ?, ?, ?)
                        """)
                .setParameter(1, eventId)
                .setParameter(2, idx)
                .setParameter(3, idx == 0 ? SAMSUNG : HYNIX)
                .setParameter(4, basePrice == null ? null : new BigDecimal(basePrice))
                .executeUpdate();
    }

    private void insertStock() {
        insertStock(SAMSUNG, "삼성전자");
        insertStock(HYNIX, "SK하이닉스");
    }

    private void insertStock(String code, String name) {
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES (?, ?, true)")
                .setParameter(1, code)
                .setParameter(2, name)
                .executeUpdate();
    }
}
