package ssafy.a507.backend.domain.monetize.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * ANT-TOKEN-05 — 내 구독 목록 · 자동 갱신 해지(E-03).
 *
 * <p>가장 중요한 것은 <b>해지가 구독을 끊지 않는다</b>는 것이다. 만료일까지 ACTIVE 그대로여야
 * 하고, 꺼지는 것은 다음 주기 결제뿐이다. 여기가 틀리면 사용자는 돈을 낸 기간을 잃는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MySubscriptionControllerTest {

    private static final String MINE = "/api/v1/subscriptions/me";
    private static final String RENEWAL = "/api/v1/subscriptions/{id}/renewal";

    @Autowired MockMvc mockMvc;

    @Autowired EntityManager em;

    private Long subscriberId;
    private Long publisherId;
    private Long strangerId;

    @BeforeEach
    void setUp() {
        subscriberId = insertUser("구독자");
        publisherId = insertUser("채널주인");
        strangerId = insertUser("남");
    }

    // ── 목록 ─────────────────────────────────────────────

    @Test
    @DisplayName("구독이 없으면 빈 배열이다 — null 이면 화면이 가드를 따로 써야 한다")
    void 없으면_빈_배열() throws Exception {
        mockMvc.perform(get(MINE).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("응답 이름은 화면(api/subscriptions.ts)과 같다 — renewEnabled 다. auto_renew 가 아니다")
    void 응답_이름이_화면과_같다() throws Exception {
        insertSubscription(subscriberId, publisherId, "7000", "ACTIVE", days(10), true);

        mockMvc.perform(get(MINE).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].publisher.userId").value(publisherId))
                .andExpect(jsonPath("$.items[0].publisher.nickname").value("채널주인"))
                .andExpect(jsonPath("$.items[0].fee").value("7000"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].expiresAt").exists())
                .andExpect(jsonPath("$.items[0].renewEnabled").value(true))
                .andExpect(jsonPath("$.items[0].autoRenew").doesNotExist());
    }

    @Test
    @DisplayName("체인에 오른 구독은 txHash 를 내리고 확정 전(PENDING)은 null 이다 — 내 결제가 체인에 있음을 해시로 보여 준다(ANT-TOKEN-10)")
    void txHash_는_확정된_구독에만() throws Exception {
        String tx = "0x" + "ab".repeat(32);
        insertSubscription(subscriberId, publisherId, "7000", "ACTIVE", days(10), true, tx);
        insertSubscription(subscriberId, strangerId, "2000", "PENDING", null, true, null);

        mockMvc.perform(get(MINE).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                // 최신순 — PENDING 이 먼저 온다
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].txHash").value((Object) null))
                .andExpect(jsonPath("$.items[1].txHash").value(tx));
    }

    @Test
    @DisplayName("만료분도 내린다 — 화면이 만료됨 줄에 재구독 안내를 그린다")
    void 만료분도_내린다() throws Exception {
        insertSubscription(subscriberId, publisherId, "1000", "EXPIRED", days(-40), true);
        insertSubscription(subscriberId, strangerId, "2000", "ACTIVE", days(10), true);

        mockMvc.perform(get(MINE).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                // 최신순이다 — 나중에 만든 행이 먼저 온다
                .andExpect(jsonPath("$.items[0].fee").value("2000"))
                .andExpect(jsonPath("$.items[1].status").value("EXPIRED"));
    }

    @Test
    @DisplayName("남의 구독은 내 목록에 없다")
    void 남의_구독은_안_보인다() throws Exception {
        insertSubscription(strangerId, publisherId, "1000", "ACTIVE", days(10), true);

        mockMvc.perform(get(MINE).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    // ── 갱신 해지 ────────────────────────────────────────

    @Test
    @DisplayName("해지해도 구독은 ACTIVE 그대로다 — 꺼지는 것은 다음 주기 결제뿐이다")
    void 해지해도_기간은_남는다() throws Exception {
        Long id = insertSubscription(subscriberId, publisherId, "1000", "ACTIVE", days(10), true);

        mockMvc.perform(delete(RENEWAL, id).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isNoContent());

        em.flush();
        em.clear();
        assertThat(autoRenewOf(id)).isFalse();
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(canceledAtOf(id)).isNotNull();
    }

    @Test
    @DisplayName("두 번 해지해도 204 이고 해지 시각은 처음 것이 남는다")
    void 두_번_해지해도_같다() throws Exception {
        Long id = insertSubscription(subscriberId, publisherId, "1000", "ACTIVE", days(10), true);

        mockMvc.perform(delete(RENEWAL, id).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();
        Object first = canceledAtOf(id);

        mockMvc.perform(delete(RENEWAL, id).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isNoContent());
        em.flush();
        em.clear();
        assertThat(canceledAtOf(id)).isEqualTo(first);
    }

    @Test
    @DisplayName("남의 구독 해지는 404 다 — 403 을 주면 그 id 의 구독이 있다는 것이 새 나간다")
    void 남의_구독은_404() throws Exception {
        Long id = insertSubscription(strangerId, publisherId, "1000", "ACTIVE", days(10), true);

        mockMvc.perform(delete(RENEWAL, id).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));

        em.flush();
        em.clear();
        assertThat(autoRenewOf(id)).isTrue();
    }

    @Test
    @DisplayName("없는 구독도 같은 404 다")
    void 없는_구독은_404() throws Exception {
        mockMvc.perform(delete(RENEWAL, 999_999L).with(user(String.valueOf(subscriberId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));
    }

    // ── fixtures ─────────────────────────────────────────

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS);
    }

    private boolean autoRenewOf(Long id) {
        return (Boolean) em.createNativeQuery("SELECT auto_renew FROM subscriptions WHERE id = ?")
                .setParameter(1, id)
                .getSingleResult();
    }

    private String statusOf(Long id) {
        return (String) em.createNativeQuery("SELECT status FROM subscriptions WHERE id = ?")
                .setParameter(1, id)
                .getSingleResult();
    }

    /** 드라이버마다 시각 타입이 달라(Timestamp · OffsetDateTime …) 값만 비교한다 */
    private Object canceledAtOf(Long id) {
        return em.createNativeQuery("SELECT canceled_at FROM subscriptions WHERE id = ?")
                .setParameter(1, id)
                .getSingleResult();
    }

    private Long insertSubscription(
            Long subscriber, Long publisher, String fee, String status, Instant expiresAt, boolean autoRenew) {
        return insertSubscription(subscriber, publisher, fee, status, expiresAt, autoRenew, null);
    }

    /** @param txHash 인덱서가 ACTIVE 로 올리며 붙이는 결제 tx. 확정 전(PENDING)이면 null */
    private Long insertSubscription(
            Long subscriber, Long publisher, String fee, String status, Instant expiresAt, boolean autoRenew,
            String txHash) {
        em.createNativeQuery("""
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, started_at, expires_at, auto_renew, tx_hash,
                           created_at, updated_at)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriber)
                .setParameter(2, publisher)
                .setParameter(3, new BigDecimal(fee))
                .setParameter(4, status)
                .setParameter(5, expiresAt)
                .setParameter(6, autoRenew)
                .setParameter(7, txHash)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT max(id) FROM subscriptions").getSingleResult())
                .longValue();
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
