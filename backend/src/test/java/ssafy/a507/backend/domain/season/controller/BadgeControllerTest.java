package ssafy.a507.backend.domain.season.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.ai.AiClient;

/**
 * GET /users/me/badges 와 종료 시 배지 부여의 AC 검증(ANT-SEASON-09).
 *
 * <p>시세는 두 시즌 모두 D+1 70,000 · D+2 80,000 이다. 예수금 30,000,000 으로 400주를 사서
 * 다음 날 전부 팔면 34,000,000 — 수익률 13.333% 라 PROFIT_ACHIEVED 하나만 걸린다. 매도가 한 번뿐이라
 * 연승은 표본 미달이고, 보유가 하루라 장기 투자자도 아니며, 고점을 깬 적이 없어 낙폭도 0 이다.
 *
 * <p>AI 복기는 목이다 — finish 가 복기 경로를 타야 결과가 저장된다(SeasonProgressControllerTest 와 같다).
 */
@SpringBootTest(properties = "app.ai.api-key=test-key")
@AutoConfigureMockMvc
@Transactional
class BadgeControllerTest {

    private static final String URL = "/api/v1/users/me/badges";
    private static final String KEY = "Idempotency-Key";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AiClient aiClient;
    private final Map<String, String> redisStore = new HashMap<>();

    private String me;
    private Long seasonId;
    private Long tickerA;

    @BeforeEach
    void setUp() {
        stubRedis();
        given(aiClient.complete(anyString(), anyString())).willReturn("복기");
        me = String.valueOf(insertUser("나"));
        insertStock("A00010");
        seasonId = insertSeason("첫 시즌");
        tickerA = insertTicker(seasonId, "종목A");
        insertPrice(tickerA, 1, 70000);
        insertPrice(tickerA, 2, 80000);
        insertParticipant(seasonId, Long.valueOf(me));
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("아직 딴 배지가 없으면 빈 목록이다 — 잠긴 배지를 내려 주지 않는다")
    void 배지가_없으면_빈_목록() throws Exception {
        mockMvc.perform(get(URL).with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("수익률 10% 이상으로 끝내면 수익 달성 배지가 그 시즌과 함께 남는다")
    void 종료하면_조건을_채운_배지를_준다() throws Exception {
        playProfitableRun(seasonId, tickerA);

        mockMvc.perform(get(URL).with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].badgeCode").value("PROFIT_ACHIEVED"))
                .andExpect(jsonPath("$.items[0].seasonId").value(seasonId))
                .andExpect(jsonPath("$.items[0].pinned").value(false))
                .andExpect(jsonPath("$.items[0].createdAt").isString());
    }

    @Test
    @DisplayName("조건을 못 채우면 아무것도 주지 않는다 — 사기만 하고 끝낸 회차")
    void 조건을_못_채우면_안_준다() throws Exception {
        order(seasonId, "k-1", tickerA, "BUY", 100).andExpect(status().isCreated());
        advance(seasonId, 1).andExpect(status().isOk());
        mockMvc.perform(post(finishUrl(seasonId)).with(user(me))).andExpect(status().isOk());

        mockMvc.perform(get(URL).with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("다른 시즌에서 또 달성해도 배지는 늘지 않는다 — 계정당 평생 1회")
    void 같은_배지를_두_번_주지_않는다() throws Exception {
        playProfitableRun(seasonId, tickerA);

        Long second = insertSeason("둘째 시즌");
        Long tickerB = insertTicker(second, "종목B");
        insertPrice(tickerB, 1, 70000);
        insertPrice(tickerB, 2, 80000);
        insertParticipant(second, Long.valueOf(me));
        em.flush();
        em.clear();
        playProfitableRun(second, tickerB);

        mockMvc.perform(get(URL).with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].seasonId").value(seasonId));
        assertThat(badgeRows()).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────

    /** 400주를 70,000 에 사서 다음 날 80,000 에 전부 판다 — 매매 비용을 빼고 수익률 13.143%. */
    private void playProfitableRun(Long season, Long ticker) throws Exception {
        order(season, "b-" + season, ticker, "BUY", 400).andExpect(status().isCreated());
        advance(season, 1).andExpect(status().isOk());
        order(season, "s-" + season, ticker, "SELL", 400).andExpect(status().isCreated());
        mockMvc.perform(post(finishUrl(season)).with(user(me))).andExpect(status().isOk());
    }

    private String finishUrl(Long season) {
        return "/api/v1/seasons/" + season + "/finish";
    }

    private ResultActions advance(Long season, int expectedDay) throws Exception {
        return mockMvc.perform(post("/api/v1/seasons/" + season + "/advance").with(user(me))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedDay\":" + expectedDay + "}"));
    }

    private ResultActions order(Long season, String key, Long tickerId, String side, int qty)
            throws Exception {
        return mockMvc.perform(post("/api/v1/seasons/" + season + "/orders")
                .with(user(me)).header(KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tickerId\":" + tickerId + ",\"side\":\"" + side + "\",\"qty\":" + qty + "}"));
    }

    private int badgeRows() {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM user_badges").getSingleResult())
                .intValue();
    }

    private void stubRedis() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(ops);
        given(ops.get(anyString())).willAnswer(call -> redisStore.get(call.getArgument(0, String.class)));
        willAnswer(call -> {
                    redisStore.put(call.getArgument(0), call.getArgument(1));
                    return null;
                })
                .given(ops)
                .set(anyString(), anyString(), any(Duration.class));
    }

    private Long insertUser(String nickname) {
        em.createNativeQuery(
                        """
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

    /** 게임일 2일짜리 연습 시즌. 하루 진행하면 마지막 날이라 바로 종료할 수 있다. */
    private Long insertSeason(String title) {
        em.createNativeQuery(
                        """
                        INSERT INTO seasons
                          (mode, status, title, theme, base_date, length_days, initial_cash, seed, current_day)
                        VALUES ('PRACTICE', 'RUNNING', ?, '전기·전자', ?, 2, 30000000, 1, 0)
                        """)
                .setParameter(1, title)
                .setParameter(2, LocalDate.of(2021, 3, 2))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM seasons WHERE title = ?")
                        .setParameter(1, title)
                        .getSingleResult())
                .longValue();
    }

    private void insertStock(String code) {
        em.createNativeQuery(
                        "INSERT INTO stocks (code, name, market, listed) VALUES (?, ?, 'KOSPI', true)")
                .setParameter(1, code)
                .setParameter(2, "이름" + code)
                .executeUpdate();
    }

    private Long insertTicker(Long season, String displayName) {
        em.createNativeQuery(
                        """
                        INSERT INTO season_tickers (season_id, display_name, real_stock_code, sector)
                        VALUES (?, ?, 'A00010', '전기·전자')
                        """)
                .setParameter(1, season)
                .setParameter(2, displayName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                                "SELECT id FROM season_tickers WHERE season_id = ? AND display_name = ?")
                        .setParameter(1, season)
                        .setParameter(2, displayName)
                        .getSingleResult())
                .longValue();
    }

    private void insertPrice(Long tickerId, int gameDay, int close) {
        em.createNativeQuery(
                        """
                        INSERT INTO season_prices (ticker_id, game_day, open, high, low, close, volume)
                        VALUES (?, ?, ?, ?, ?, ?, 1000)
                        """)
                .setParameter(1, tickerId)
                .setParameter(2, gameDay)
                .setParameter(3, new BigDecimal(close))
                .setParameter(4, new BigDecimal(close + 10))
                .setParameter(5, new BigDecimal(close - 10))
                .setParameter(6, new BigDecimal(close))
                .executeUpdate();
    }

    private void insertParticipant(Long season, Long userId) {
        em.createNativeQuery(
                        """
                        INSERT INTO season_participants
                          (season_id, user_id, attempt_no, cash, current_day, status, created_at)
                        VALUES (?, ?, 1, 30000000, 1, 'ONGOING', CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, season)
                .setParameter(2, userId)
                .executeUpdate();
    }
}
