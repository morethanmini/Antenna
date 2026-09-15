package ssafy.a507.backend.domain.monetize.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ANT-TOKEN-03 — "따라 샀다면" 백테스트(E-02).
 *
 * <p>여기서 고정하는 것은 계산 규칙 넷이다 — DOWN 은 부호를 뒤집는다 · 합치는 법은 균등 배분 ·
 * 표본은 최근 3개월의 판정된 REAL 예측뿐 · 표본이 없으면 0% 가 아니라 "없음"이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BacktestControllerTest {

    private static final String PATH = "/api/v1/channels/{userId}/backtest";
    private static final String SAMSUNG = "005930";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager em;

    private Long ownerId;
    private Long viewerId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("예측가");
        viewerId = insertUser("구경꾼");
        insertStock();
    }

    @Test
    @DisplayName("표본이 없으면 sampleCount 0 이고 벤치마크도 null 이다 — 0% 를 그리면 '손실 없음'으로 읽힌다")
    void 표본이_없으면_결과가_없다() throws Exception {
        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(0))
                .andExpect(jsonPath("$.returnRate").value(0))
                .andExpect(jsonPath("$.benchmarkRate").doesNotExist())
                .andExpect(jsonPath("$.months").value(3))
                .andExpect(jsonPath("$.principal").value(10000000));
    }

    @Test
    @DisplayName("UP 예측은 오른 만큼이 수익이다")
    void UP_은_상승분이_수익() throws Exception {
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(10));

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(1))
                .andExpect(jsonPath("$.returnRate").value(10.00));
    }

    @Test
    @DisplayName("DOWN 예측은 부호를 뒤집는다 — 하락을 맞히면 번 것이다. 안 뒤집으면 하락 전문 예측가가 늘 손해로 나온다")
    void DOWN_은_부호를_뒤집는다() throws Exception {
        insertSettled("DOWN", "HIT", "10000", "9000", daysAgo(10));

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(1))
                .andExpect(jsonPath("$.returnRate").value(10.00));
    }

    @Test
    @DisplayName("DOWN 을 틀리면 손실이다 — 내린다고 했는데 올랐다")
    void DOWN_을_틀리면_손실() throws Exception {
        insertSettled("DOWN", "MISS", "10000", "11000", daysAgo(10));

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnRate").value(-10.00));
    }

    @Test
    @DisplayName("여러 건은 균등 배분 — 누적 수익률은 각 건의 평균이다")
    void 균등_배분해_평균을_낸다() throws Exception {
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(10));   // +10%
        insertSettled("UP", "MISS", "10000", "9000", daysAgo(9));    // -10%
        insertSettled("DOWN", "HIT", "10000", "8000", daysAgo(8));   // +20%

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(3))
                .andExpect(jsonPath("$.returnRate").value(6.67));
    }

    @Test
    @DisplayName("판정 전(BASE·OPEN) 예측은 표본이 아니다 — 아직 결과가 없다")
    void 판정_전은_표본이_아니다() throws Exception {
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(10));
        insertPending("BASE");
        insertPending("OPEN");

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(1));
    }

    @Test
    @DisplayName("3개월보다 오래된 판정은 빠진다 — 기간이 고정이다")
    void 기간_밖은_빠진다() throws Exception {
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(10));
        insertSettled("UP", "HIT", "10000", "20000", daysAgo(200));

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(1))
                .andExpect(jsonPath("$.returnRate").value(10.00));
    }

    @Test
    @DisplayName("KOSPI 가 있으면 같은 구간의 지수 수익률을 함께 낸다 — 비교 기준이 없으면 숫자를 읽을 수 없다")
    void 벤치마크는_같은_구간의_KOSPI() throws Exception {
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(30));
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(10));
        insertIndex(daysAgo(30), "2000.0000");
        insertIndex(daysAgo(10), "2100.0000");

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.benchmarkRate").value(5.00));
    }

    @Test
    @DisplayName("지수가 없으면 벤치마크는 null 이다 — 0 으로 내리면 '코스피는 제자리였다'는 거짓이 된다")
    void 지수가_없으면_벤치마크는_null() throws Exception {
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(30));
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(10));

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(2))
                .andExpect(jsonPath("$.benchmarkRate").doesNotExist());
    }

    @Test
    @DisplayName("하루 안에 다 판정됐으면 구간이 없어 벤치마크도 없다")
    void 구간이_없으면_벤치마크도_없다() throws Exception {
        insertSettled("UP", "HIT", "10000", "11000", daysAgo(10));
        insertIndex(daysAgo(10), "2000.0000");

        mockMvc.perform(get(PATH, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleCount").value(1))
                .andExpect(jsonPath("$.benchmarkRate").doesNotExist());
    }

    @Test
    @DisplayName("없는 회원은 404 USER_NOT_FOUND")
    void 없는_회원은_404() throws Exception {
        mockMvc.perform(get(PATH, 999_999L).with(user(String.valueOf(viewerId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ── fixtures ─────────────────────────────────────────

    private static LocalDate daysAgo(int days) {
        return LocalDate.now().minusDays(days);
    }

    private void insertSettled(
            String direction, String status, String basePrice, String settlePrice, LocalDate settledOn) {
        em.createNativeQuery("""
                        INSERT INTO predictions
                          (user_id, track, stock_code, direction, target_price, horizon, status,
                           base_price, settle_price, settled_on, settle_date, created_at, updated_at)
                        VALUES (?, 'REAL', ?, ?, 70000, 7, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, ownerId)
                .setParameter(2, SAMSUNG)
                .setParameter(3, direction)
                .setParameter(4, status)
                .setParameter(5, new BigDecimal(basePrice))
                .setParameter(6, new BigDecimal(settlePrice))
                .setParameter(7, settledOn)
                .setParameter(8, settledOn)
                .executeUpdate();
    }

    private void insertPending(String status) {
        em.createNativeQuery("""
                        INSERT INTO predictions
                          (user_id, track, stock_code, direction, target_price, horizon, status,
                           created_at, updated_at)
                        VALUES (?, 'REAL', ?, 'UP', 70000, 7, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, ownerId)
                .setParameter(2, SAMSUNG)
                .setParameter(3, status)
                .executeUpdate();
    }

    private void insertIndex(LocalDate tradeDate, String close) {
        em.createNativeQuery("INSERT INTO index_quotes (index_code, trade_date, close) VALUES ('KOSPI', ?, ?)")
                .setParameter(1, tradeDate)
                .setParameter(2, new BigDecimal(close))
                .executeUpdate();
    }

    private void insertStock() {
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES (?, ?, true)")
                .setParameter(1, SAMSUNG)
                .setParameter(2, "삼성전자")
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
