package ssafy.a507.backend.domain.monetize.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * ANT-TOKEN-05 — 내 채널 구독료(E-04).
 *
 * <p>여기서 지킬 것은 넷이다 — 안 정한 구독료를 0 으로 내리지 않을 것, 아직 시작하지 않은 행을
 * "현재가" 로 말하지 않을 것, 오타 방어 상한이 서버에서도 걸릴 것, 그리고 바꾸면 구독자에게
 * 알림이 남을 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MyChannelControllerTest {

    private static final String FEE = "/api/v1/me/channel/fee";

    @Autowired MockMvc mockMvc;

    @Autowired EntityManager em;

    private Long ownerId;
    private Long subscriberId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("채널주인");
        subscriberId = insertUser("구독자");
    }

    // ── 조회 ─────────────────────────────────────────────

    @Test
    @DisplayName("한 번도 안 정했으면 fee 는 null 이고 이력은 빈 배열이다 — 0 으로 내리면 무료 채널로 읽힌다")
    void 안_정했으면_null() throws Exception {
        mockMvc.perform(get(FEE).with(user(String.valueOf(ownerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").doesNotExist())
                .andExpect(jsonPath("$.history").isArray())
                .andExpect(jsonPath("$.history").isEmpty());
    }

    @Test
    @DisplayName("아직 시작하지 않은 행은 현재가가 아니다 — 이력에는 있지만 fee 는 null 이다")
    void 미래_발효분은_현재가가_아니다() throws Exception {
        insertFee(ownerId, "20000", Instant.now().plus(3, ChronoUnit.DAYS));

        mockMvc.perform(get(FEE).with(user(String.valueOf(ownerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").doesNotExist())
                .andExpect(jsonPath("$.history[0].fee").value("20000"));
    }

    @Test
    @DisplayName("이력은 최신순이고 현재가는 이미 시작한 행에서 온다")
    void 이력은_최신순() throws Exception {
        insertFee(ownerId, "1000", Instant.now().minus(30, ChronoUnit.DAYS));
        insertFee(ownerId, "3000", Instant.now().minus(1, ChronoUnit.DAYS));

        mockMvc.perform(get(FEE).with(user(String.valueOf(ownerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value("3000"))
                .andExpect(jsonPath("$.history[0].fee").value("3000"))
                .andExpect(jsonPath("$.history[1].fee").value("1000"));
    }

    @Test
    @DisplayName("경로에 발행자 id 가 없다 — 남의 구독료는 이 창구로 볼 수 없다")
    void 늘_내_구독료다() throws Exception {
        insertFee(ownerId, "5000", Instant.now().minus(1, ChronoUnit.DAYS));

        mockMvc.perform(get(FEE).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").doesNotExist());
    }

    // ── 변경 ─────────────────────────────────────────────

    @Test
    @DisplayName("변경하면 이력에 행이 하나 늘고 그 값이 곧 현재가다")
    void 변경하면_현재가가_된다() throws Exception {
        mockMvc.perform(putFee(ownerId, """
                        {"fee":"12000"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value("12000"))
                .andExpect(jsonPath("$.effectiveFrom").exists());

        mockMvc.perform(get(FEE).with(user(String.valueOf(ownerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value("12000"))
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    @Test
    @DisplayName("상한을 넘으면 400 이다 — 0 을 하나 더 붙인 값이 저장되면 되돌릴 수 없다")
    void 상한_초과는_400() throws Exception {
        mockMvc.perform(putFee(ownerId, """
                        {"fee":"100001"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("소수·음수·빈 값은 400 이다 — ANT 는 decimals 0 이라 소수 구독료라는 것이 없다")
    void 정수가_아니면_400() throws Exception {
        String[] bodies = {
            """
            {"fee":"1000.5"}
            """,
            """
            {"fee":"-1"}
            """,
            """
            {"fee":""}
            """,
            """
            {"fee":"abc"}
            """
        };
        for (String body : bodies) {
            mockMvc.perform(putFee(ownerId, body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("0 은 받는다 — 무료로 여는 것도 정한 값이다. 안 정한 상태와 다르다")
    void 영은_받는다() throws Exception {
        mockMvc.perform(putFee(ownerId, """
                        {"fee":"0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value("0"));
    }

    // ── 알림 ─────────────────────────────────────────────

    @Test
    @DisplayName("변경하면 구독 중인 사람에게 알림이 남는다 — 확정 대기(PENDING)도 대상이다")
    void 구독자에게_알림() throws Exception {
        Long pendingId = insertUser("확정대기자");
        insertSubscription(subscriberId, ownerId, "ACTIVE", Instant.now().plus(10, ChronoUnit.DAYS));
        insertSubscription(pendingId, ownerId, "PENDING", null);

        mockMvc.perform(putFee(ownerId, """
                        {"fee":"9000"}
                        """))
                .andExpect(status().isOk());

        em.flush();
        assertThat(notificationCount(subscriberId)).isEqualTo(1);
        assertThat(notificationCount(pendingId)).isEqualTo(1);
        assertThat(notificationCount(ownerId)).isZero();
    }

    @Test
    @DisplayName("만료된 구독자에게는 알림이 가지 않는다 — 다음 갱신이 없다")
    void 만료_구독자는_제외() throws Exception {
        insertSubscription(subscriberId, ownerId, "EXPIRED", Instant.now().minus(1, ChronoUnit.DAYS));

        mockMvc.perform(putFee(ownerId, """
                        {"fee":"9000"}
                        """))
                .andExpect(status().isOk());

        em.flush();
        assertThat(notificationCount(subscriberId)).isZero();
    }

    // ── fixtures ─────────────────────────────────────────

    private MockHttpServletRequestBuilder putFee(Long userId, String body) {
        return put(FEE)
                .with(user(String.valueOf(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private long notificationCount(Long userId) {
        return ((Number)
                        em.createNativeQuery(
                                        "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'FEE_CHANGED'")
                                .setParameter(1, userId)
                                .getSingleResult())
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

    private void insertSubscription(Long subscriber, Long publisher, String status, Instant expiresAt) {
        em.createNativeQuery("""
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, started_at, expires_at, auto_renew,
                           created_at, updated_at)
                        VALUES (?, ?, 1000, ?, CURRENT_TIMESTAMP, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriber)
                .setParameter(2, publisher)
                .setParameter(3, status)
                .setParameter(4, expiresAt)
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
