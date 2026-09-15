package ssafy.a507.backend.domain.monetize.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
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
 * ANT-TOKEN-09 — 채널 프로필(E-02).
 *
 * <p>프로필 자체는 가리지 않는다(가리는 것은 예측·리포트 본문이다). 그래서 여기서 지킬 것은 넷이다 —
 * 표본 없는 실적을 0 으로 꾸미지 않을 것, 안 정한 구독료를 0 으로 내리지 않을 것, 내 구독 상태가
 * 보는 사람마다 다를 것, 그리고 응답 이름이 화면(api/channels.ts)과 같을 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChannelControllerTest {

    private static final String PROFILE = "/api/v1/channels/{userId}";
    private static final String SAMSUNG = "005930";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager em;

    private Long ownerId;
    private Long viewerId;
    private Long subscriberId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("채널주인");
        viewerId = insertUser("비구독자");
        subscriberId = insertUser("구독자");
        insertStock();
    }

    // ── 실적 통계 ────────────────────────────────────────

    @Test
    @DisplayName("판정 표본이 없으면 적중률·평균 오차는 null 이고 판정 수만 0 이다 — 0% 로 그리면 '한 번도 못 맞혔다'로 읽힌다")
    void 표본이_없으면_적중률은_null() throws Exception {
        insertPrediction("BASE", null);

        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.hitRate").doesNotExist())
                .andExpect(jsonPath("$.stats.avgError").doesNotExist())
                .andExpect(jsonPath("$.stats.doneCount").value(0));
    }

    @Test
    @DisplayName("적중률의 분모는 판정된 것뿐이다 — 대기 건을 분모에 넣으면 오래 걸릴수록 적중률이 떨어진다")
    void 적중률_분모는_판정분() throws Exception {
        insertPrediction("HIT", "2.00");
        insertPrediction("MISS", "6.00");
        insertPrediction("BASE", null);
        insertPrediction("OPEN", null);

        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.doneCount").value(2))
                .andExpect(jsonPath("$.stats.hitRate").value(50.00))
                .andExpect(jsonPath("$.stats.avgError").value(4.00));
    }


    @Test
    @DisplayName("모의투자(REPLAY) 예측은 채널 실적에 섞이지 않는다 — AC 가 REAL 트랙만이다")
    void 실적은_REAL_트랙만() throws Exception {
        insertPrediction("HIT", "2.00");
        insertReplayPrediction("MISS", "40.00");

        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.doneCount").value(1))
                .andExpect(jsonPath("$.stats.hitRate").value(100.00))
                .andExpect(jsonPath("$.stats.avgError").value(2.00));
    }

    // ── 구독료 ───────────────────────────────────────────

    @Test
    @DisplayName("구독료를 안 정했으면 null 이다 — 0 으로 내리면 화면이 무료 채널로 읽는다")
    void 구독료_미설정은_null() throws Exception {
        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").doesNotExist());
    }

    @Test
    @DisplayName("구독료는 발효일이 지난 것 중 가장 늦은 한 줄이다 — 미래 발효분은 아직 값이 아니다")
    void 구독료는_발효된_최신값() throws Exception {
        insertFee(ownerId, "1000", Instant.now().minus(10, ChronoUnit.DAYS));
        insertFee(ownerId, "3000", Instant.now().minus(1, ChronoUnit.DAYS));
        insertFee(ownerId, "9000", Instant.now().plus(7, ChronoUnit.DAYS));

        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value("3000"));
    }

    // ── 내 구독 상태 · isMe ──────────────────────────────

    @Test
    @DisplayName("구독한 적이 없으면 status 가 null 이다 — mySubscription 객체 자체는 늘 내려간다")
    void 미구독자는_status_가_null() throws Exception {
        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mySubscription").exists())
                .andExpect(jsonPath("$.mySubscription.status").doesNotExist())
                .andExpect(jsonPath("$.mySubscription.autoRenew").value(false))
                .andExpect(jsonPath("$.isMe").value(false));
    }

    @Test
    @DisplayName("구독자에게는 상태·만료일·자동갱신·박제된 결제가가 보인다")
    void 구독자는_내_구독이_보인다() throws Exception {
        insertSubscription(subscriberId, ownerId, "7000", "ACTIVE", Instant.now().plus(10, ChronoUnit.DAYS));

        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mySubscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mySubscription.expiresAt").exists())
                .andExpect(jsonPath("$.mySubscription.paidFee").value("7000"));
    }

    @Test
    @DisplayName("만료된 구독도 EXPIRED 로 보여준다 — 숨기면 재구독 안내를 그릴 수 없다")
    void 만료된_구독도_보인다() throws Exception {
        insertSubscription(subscriberId, ownerId, "7000", "EXPIRED", Instant.now().minus(1, ChronoUnit.DAYS));

        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mySubscription.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("한 쌍에 구독이 여러 줄 쌓이면 가장 최근 것이 내 상태다")
    void 재구독하면_최신_구독이_내_상태() throws Exception {
        insertSubscription(subscriberId, ownerId, "1000", "EXPIRED", Instant.now().minus(40, ChronoUnit.DAYS));
        insertSubscription(subscriberId, ownerId, "7000", "ACTIVE", Instant.now().plus(10, ChronoUnit.DAYS));

        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mySubscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mySubscription.paidFee").value("7000"));
    }

    @Test
    @DisplayName("내 채널이면 isMe 가 true 이고 구독 상태는 비어 있다 — 자기 자신은 구독할 수 없다")
    void 내_채널은_isMe() throws Exception {
        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(ownerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isMe").value(true))
                .andExpect(jsonPath("$.mySubscription.status").doesNotExist());
    }

    // ── 응답 이름 · 404 ──────────────────────────────────

    @Test
    @DisplayName("응답 이름은 화면(api/channels.ts)과 같다 — bio·externalLinks 다. 명세 v0.62 의 introduce·channels 가 아니다")
    void 응답_이름이_화면과_같다() throws Exception {
        em.createNativeQuery("UPDATE users SET introduce = ? WHERE id = ?")
                .setParameter(1, "소개글")
                .setParameter(2, ownerId)
                .executeUpdate();
        insertInterest(ownerId, "반도체");
        insertExternalChannel(ownerId, "YOUTUBE", "내 유튜브", "https://youtube.com/@me");

        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ownerId))
                .andExpect(jsonPath("$.nickname").value("채널주인"))
                .andExpect(jsonPath("$.bio").value("소개글"))
                .andExpect(jsonPath("$.introduce").doesNotExist())
                .andExpect(jsonPath("$.interests[0]").value("반도체"))
                .andExpect(jsonPath("$.externalLinks[0].platform").value("YOUTUBE"))
                .andExpect(jsonPath("$.externalLinks[0].label").value("내 유튜브"))
                .andExpect(jsonPath("$.channels").doesNotExist());
    }

    @Test
    @DisplayName("관심 섹터·외부 채널이 없으면 빈 배열이다 — null 이면 화면마다 가드를 따로 써야 한다")
    void 비어_있으면_빈_배열() throws Exception {
        mockMvc.perform(get(PROFILE, ownerId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests").isArray())
                .andExpect(jsonPath("$.interests").isEmpty())
                .andExpect(jsonPath("$.externalLinks").isArray())
                .andExpect(jsonPath("$.externalLinks").isEmpty());
    }

    @Test
    @DisplayName("없는 회원은 404 USER_NOT_FOUND — 빈 프로필로 속이지 않는다")
    void 없는_회원은_404() throws Exception {
        mockMvc.perform(get(PROFILE, 999_999L).with(user(String.valueOf(viewerId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ── fixtures ─────────────────────────────────────────

    private void insertPrediction(String status, String errorRate) {
        em.createNativeQuery("""
                        INSERT INTO predictions
                          (user_id, track, stock_code, direction, target_price, horizon,
                           status, error_rate, created_at, updated_at)
                        VALUES (?, 'REAL', ?, 'UP', 70000, 7, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, ownerId)
                .setParameter(2, SAMSUNG)
                .setParameter(3, status)
                .setParameter(4, errorRate == null ? null : new BigDecimal(errorRate))
                .executeUpdate();
    }


    /** REPLAY 는 stock_code 가 아니라 season_ticker_id 를 가리킨다(ck_predictions_target_matches_track) */
    private void insertReplayPrediction(String status, String errorRate) {
        Long tickerId = insertSeasonTicker();
        em.createNativeQuery("""
                        INSERT INTO predictions
                          (user_id, track, season_ticker_id, direction, target_price, horizon,
                           status, error_rate, created_at, updated_at)
                        VALUES (?, 'REPLAY', ?, 'UP', 70000, 7, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, ownerId)
                .setParameter(2, tickerId)
                .setParameter(3, status)
                .setParameter(4, new BigDecimal(errorRate))
                .executeUpdate();
    }


    private Long insertSeasonTicker() {
        em.createNativeQuery("""
                        INSERT INTO seasons
                          (mode, status, current_day, length_days, initial_cash, seed)
                        VALUES ('PRACTICE', 'RUNNING', 1, 20, 10000000, 1)
                        """)
                .executeUpdate();
        Long seasonId =
                ((Number) em.createNativeQuery("SELECT max(id) FROM seasons").getSingleResult()).longValue();
        em.createNativeQuery("""
                        INSERT INTO season_tickers (season_id, display_name, real_stock_code)
                        VALUES (?, '가나전자', ?)
                        """)
                .setParameter(1, seasonId)
                .setParameter(2, SAMSUNG)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT max(id) FROM season_tickers").getSingleResult())
                .longValue();
    }

    private void insertFee(Long publisher, String fee, Instant effectiveFrom) {
        em.createNativeQuery("""
                        INSERT INTO publisher_fees (publisher_id, fee, effective_from, created_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, publisher)
                .setParameter(2, new BigDecimal(fee))
                .setParameter(3, effectiveFrom)
                .executeUpdate();
    }

    private void insertSubscription(
            Long subscriber, Long publisher, String fee, String status, Instant expiresAt) {
        em.createNativeQuery("""
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, started_at, expires_at, auto_renew,
                           created_at, updated_at)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriber)
                .setParameter(2, publisher)
                .setParameter(3, new BigDecimal(fee))
                .setParameter(4, status)
                .setParameter(5, expiresAt)
                .executeUpdate();
    }

    private void insertInterest(Long userId, String sector) {
        em.createNativeQuery("INSERT INTO user_interests (user_id, sector, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)")
                .setParameter(1, userId)
                .setParameter(2, sector)
                .executeUpdate();
    }

    private void insertExternalChannel(Long userId, String platform, String label, String url) {
        em.createNativeQuery("""
                        INSERT INTO user_channels (user_id, platform, label, url, created_at)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, platform)
                .setParameter(3, label)
                .setParameter(4, url)
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
