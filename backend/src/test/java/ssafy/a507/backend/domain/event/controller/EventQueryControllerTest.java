package ssafy.a507.backend.domain.event.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /api/v1/events · /events/{id} — ANT-EVENT-04 (작업명세 v1.2 §3-1·2 · §4).
 *
 * <p>여기서 고정하는 것은 넷이다 — <b>남의 방향·목표가가 응답에 없을 것</b> · 배당률 계산과 0 풀의
 * null · PENDING 을 풀에서 뺄 것 · 정산 전 {@code paid}·{@code net}·{@code result} 가 null 일 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("배팅 이벤트 조회 API")
class EventQueryControllerTest {

    private static final String LIST = "/api/v1/events";
    private static final String HOME = "/api/v1/events/{eventId}";
    private static final String SAMSUNG = "005930";
    private static final String HYNIX = "000660";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private Long me;
    private Long other;
    private Long eventId;
    private Long samsungStockId;

    @BeforeEach
    void setUp() {
        me = insertUser("나");
        other = insertUser("남");
        insertStock(SAMSUNG, "삼성전자");
        insertStock(HYNIX, "SK하이닉스");
        eventId = insertEvent("OPEN");
        samsungStockId = insertEventStock(eventId, (short) 0, SAMSUNG, "91000.00");
        insertEventStock(eventId, (short) 1, HYNIX, null);
    }

    @Test
    @DisplayName("목록은 일정·단계만 준다 — 종목·풀은 홈 몫이다")
    void 목록() throws Exception {
        mockMvc.perform(get(LIST).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventId").value(eventId))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].phase").value("ENTRY"))
                .andExpect(jsonPath("$.items[0].stocks").doesNotExist());
    }

    @Test
    @DisplayName("홈은 종목 카드를 idx 순으로 준다. 기준가가 없는 종목은 null 이고 0 이 아니다")
    void 홈_카드() throws Exception {
        mockMvc.perform(get(HOME, eventId).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.stockCount").value(2))
                .andExpect(jsonPath("$.stocks[0].idx").value(0))
                .andExpect(jsonPath("$.stocks[0].stockCode").value(SAMSUNG))
                .andExpect(jsonPath("$.stocks[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.stocks[0].basePrice").value(91000.00))
                .andExpect(jsonPath("$.stocks[1].idx").value(1))
                .andExpect(jsonPath("$.stocks[1].basePrice").value(nullValue()));
    }

    @Test
    @DisplayName("남의 방향·목표가는 응답에 없다 — 집계만 나간다(§1 비공개 규칙)")
    void 남의_배팅은_집계만() throws Exception {
        bet(other, SAMSUNG, "UP", "93000", 1000, "ACTIVE");

        mockMvc.perform(get(HOME, eventId).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                // 집계는 보인다
                .andExpect(jsonPath("$.stocks[0].pool.up").value(1000))
                .andExpect(jsonPath("$.stocks[0].pool.upCount").value(1))
                // 남의 것이라 mine 은 비어 있다
                .andExpect(jsonPath("$.stocks[0].mine").value(nullValue()));
    }

    @Test
    @DisplayName("내 배팅은 방향·목표가까지 보인다")
    void 내_배팅은_다_보인다() throws Exception {
        bet(me, SAMSUNG, "DOWN", "88000", 500, "ACTIVE");

        mockMvc.perform(get(HOME, eventId).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].mine.direction").value("DOWN"))
                .andExpect(jsonPath("$.stocks[0].mine.targetPrice").value(88000))
                .andExpect(jsonPath("$.stocks[0].mine.stake").value(500))
                .andExpect(jsonPath("$.stocks[0].mine.betStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("배당률 = 전체 풀 / 그 방향 풀. 한쪽이 0 이면 그쪽은 null 이다 — 0 배당은 거짓이다")
    void 배당률() throws Exception {
        bet(other, SAMSUNG, "UP", "93000", 3000, "ACTIVE");
        bet(me, SAMSUNG, "DOWN", "88000", 1000, "ACTIVE");

        mockMvc.perform(get(HOME, eventId).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].pool.up").value(3000))
                .andExpect(jsonPath("$.stocks[0].pool.down").value(1000))
                // 4000/3000 = 1.33 · 4000/1000 = 4.00
                .andExpect(jsonPath("$.stocks[0].pool.oddsUp").value(1.33))
                .andExpect(jsonPath("$.stocks[0].pool.oddsDown").value(4.00))
                // 아무도 안 건 종목은 양쪽 다 null
                .andExpect(jsonPath("$.stocks[1].pool.up").value(0))
                .andExpect(jsonPath("$.stocks[1].pool.oddsUp").value(nullValue()))
                .andExpect(jsonPath("$.stocks[1].pool.oddsDown").value(nullValue()));
    }

    @Test
    @DisplayName("PENDING 배팅은 풀에서 뺀다 — 체인에 안 올라간 돈이 배당률을 흔들면 안 된다")
    void PENDING_은_풀에서_뺀다() throws Exception {
        bet(other, SAMSUNG, "UP", "93000", 3000, "PENDING");
        bet(me, SAMSUNG, "DOWN", "88000", 1000, "ACTIVE");

        mockMvc.perform(get(HOME, eventId).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].pool.up").value(0))
                .andExpect(jsonPath("$.stocks[0].pool.upCount").value(0))
                .andExpect(jsonPath("$.stocks[0].pool.down").value(1000));
    }

    @Test
    @DisplayName("FAILED 배팅도 풀에서 뺀다 — 못 올라간 돈이다")
    void FAILED_도_풀에서_뺀다() throws Exception {
        bet(other, SAMSUNG, "UP", "93000", 3000, "FAILED");

        mockMvc.perform(get(HOME, eventId).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].pool.up").value(0));
    }

    @Test
    @DisplayName("내 참가 요약 — 건 수·합계·남은 종목·자격. 정산 전 paid·net·rank 는 null 이다")
    void 내_참가_요약() throws Exception {
        bet(me, SAMSUNG, "UP", "93000", 500, "ACTIVE");

        mockMvc.perform(get(HOME, eventId).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.me.stockCount").value(1))
                .andExpect(jsonPath("$.me.staked").value(500))
                // minStocks 가 5 인데 1건이라 아직 순위 밖이다
                .andExpect(jsonPath("$.me.qualified").value(false))
                // 이벤트 종목 2개 중 1개를 걸었다
                .andExpect(jsonPath("$.me.remainingStocks").value(1))
                .andExpect(jsonPath("$.me.paid").value(nullValue()))
                .andExpect(jsonPath("$.me.net").value(nullValue()))
                .andExpect(jsonPath("$.me.rank").value(nullValue()));
    }

    @Test
    @DisplayName("정산 전에는 종목 result 가 null 이다 — 0 으로 내리면 '졌다'로 읽힌다")
    void 정산_전_result_는_null() throws Exception {
        mockMvc.perform(get(HOME, eventId).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].result").value(nullValue()));
    }

    @Test
    @DisplayName("status 여섯 갈래를 화면용 phase 넷으로 접는다 — SEALED·REVEALED·RESOLVED 는 같은 그림이다")
    void phase_로_접는다() throws Exception {
        Long revealed = insertEvent("REVEALED");
        mockMvc.perform(get(HOME, revealed).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVEALED"))
                .andExpect(jsonPath("$.phase").value("SEALED"));
    }

    @Test
    @DisplayName("없는 이벤트는 404 EVENT_NOT_FOUND")
    void 없는_이벤트는_404() throws Exception {
        mockMvc.perform(get(HOME, 999_999L).with(user(String.valueOf(me))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    // ── fixtures ─────────────────────────────────────────

    /** 예측 + 배팅 한 쌍. 이벤트 예측은 predictions 에 컬럼을 더하지 않고 1:1 테이블로 잇는다(§2.3). */
    private void bet(Long userId, String stockCode, String direction, String targetPrice, long stake, String betStatus) {
        em.createNativeQuery("""
                        INSERT INTO predictions
                          (user_id, track, stock_code, direction, target_price, horizon, status,
                           base_date, settle_date, created_at, updated_at)
                        VALUES (?, 'REAL', ?, ?, ?, 4, 'BASE', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, stockCode)
                .setParameter(3, direction)
                .setParameter(4, new BigDecimal(targetPrice))
                .setParameter(5, LocalDate.now().minusDays(1))
                .setParameter(6, LocalDate.now().plusDays(3))
                .executeUpdate();
        long predictionId =
                ((Number) em.createNativeQuery("SELECT max(id) FROM predictions").getSingleResult()).longValue();
        long stockId =
                ((Number) em.createNativeQuery(
                                "SELECT id FROM prediction_event_stocks WHERE event_id = ? AND stock_code = ?")
                        .setParameter(1, eventId)
                        .setParameter(2, stockCode)
                        .getSingleResult())
                        .longValue();
        em.createNativeQuery("""
                        INSERT INTO prediction_event_bets
                          (prediction_id, event_id, event_stock_id, user_id, stake, event_commit,
                           bet_status, attempts, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, predictionId)
                .setParameter(2, eventId)
                .setParameter(3, stockId)
                .setParameter(4, userId)
                .setParameter(5, BigDecimal.valueOf(stake))
                .setParameter(6, "0x" + "1".repeat(64))
                .setParameter(7, betStatus)
                .executeUpdate();
    }

    private Long insertEvent(String status) {
        em.createNativeQuery("""
                        INSERT INTO prediction_events
                          (title, status, opens_at, seals_at, base_date, settle_date,
                           min_stake, max_stake, min_stocks, created_at)
                        VALUES ('9월 3주 업앤다운', ?, ?, ?, ?, ?, 500, 2000, 5, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, status)
                .setParameter(2, Instant.now().minus(1, ChronoUnit.HOURS))
                .setParameter(3, Instant.now().plus(1, ChronoUnit.DAYS))
                .setParameter(4, LocalDate.now().minusDays(1))
                .setParameter(5, LocalDate.now().plusDays(3))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT max(id) FROM prediction_events").getSingleResult())
                .longValue();
    }

    private Long insertEventStock(Long eventId, short idx, String stockCode, String basePrice) {
        em.createNativeQuery("""
                        INSERT INTO prediction_event_stocks (event_id, idx, stock_code, base_price)
                        VALUES (?, ?, ?, ?)
                        """)
                .setParameter(1, eventId)
                .setParameter(2, idx)
                .setParameter(3, stockCode)
                .setParameter(4, basePrice == null ? null : new BigDecimal(basePrice))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT max(id) FROM prediction_event_stocks").getSingleResult())
                .longValue();
    }

    private void insertStock(String code, String name) {
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES (?, ?, true)")
                .setParameter(1, code)
                .setParameter(2, name)
                .executeUpdate();
    }

    private Long insertUser(String nickname) {
        em.createNativeQuery("""
                        INSERT INTO users (nickname, role, status, created_at, updated_at)
                        VALUES (?, 'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, nickname)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = ?")
                        .setParameter(1, nickname)
                        .getSingleResult())
                .longValue();
    }
}
