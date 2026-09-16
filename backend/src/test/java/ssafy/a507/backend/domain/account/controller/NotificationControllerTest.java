package ssafy.a507.backend.domain.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림함 — 목록·읽음·전체 읽음·삭제. 발송 쪽은 각 도메인 테스트가 본다(리포트 발행·댓글·좋아요·예측·구독·판정).
 *
 * <p>남의 알림은 404 다. 403 으로 나누면 "그 id 가 있다" 를 알려 주는 셈이라 없는 것과 같게 감춘다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("알림함 API")
class NotificationControllerTest {

    private static final String URL = "/api/v1/notifications";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private Long me;
    private Long other;

    @BeforeEach
    void setUp() {
        me = insertUser("나");
        other = insertUser("남");
        flush();
    }

    @Test
    @DisplayName("목록은 내 것만 최신순이고 unreadCount 는 read_at 이 비어 있는 수다")
    void 목록() throws Exception {
        Long first = insert(me, "VERDICT", "판정", "/predictions/1", false);
        Long second = insert(me, "REPORT_LIKE", "좋아요", "/reports/2", true);
        insert(other, "VERDICT", "남의 것", "/predictions/3", false);
        flush();

        mockMvc.perform(get(URL).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[0].type").value("REPORT_LIKE"))
                .andExpect(jsonPath("$.items[0].readAt").isNotEmpty())
                .andExpect(jsonPath("$.items[1].id").value(first))
                .andExpect(jsonPath("$.items[1].title").value("판정"))
                .andExpect(jsonPath("$.items[1].linkPath").value("/predictions/1"))
                .andExpect(jsonPath("$.items[1].readAt").isEmpty())
                .andExpect(jsonPath("$.items[1].createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("한 건 읽음 — read_at 이 찍히고 두 번 읽어도 204")
    void 읽음() throws Exception {
        Long id = insert(me, "VERDICT", "판정", "/predictions/1", false);
        flush();

        mockMvc.perform(patch(URL + "/" + id + "/read").with(user(String.valueOf(me))).with(csrf()))
                .andExpect(status().isNoContent());
        flush();
        assertThat(readAt(id)).isNotNull();

        mockMvc.perform(patch(URL + "/" + id + "/read").with(user(String.valueOf(me))).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("남의 알림을 읽음 처리하면 404 NOTIFICATION_NOT_FOUND")
    void 읽음_남의_것() throws Exception {
        Long id = insert(other, "VERDICT", "남의 것", "/predictions/1", false);
        flush();

        mockMvc.perform(patch(URL + "/" + id + "/read").with(user(String.valueOf(me))).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        flush();
        assertThat(readAt(id)).isNull();
    }

    @Test
    @DisplayName("전체 읽음 — 내 것만 전부 read_at 이 찍힌다")
    void 전체_읽음() throws Exception {
        Long a = insert(me, "VERDICT", "판정", "/predictions/1", false);
        Long b = insert(me, "SUBSCRIBE", "구독", "/channels/1", false);
        Long theirs = insert(other, "VERDICT", "남의 것", "/predictions/3", false);
        flush();

        mockMvc.perform(patch(URL + "/read-all").with(user(String.valueOf(me))).with(csrf()))
                .andExpect(status().isNoContent());
        flush();

        assertThat(readAt(a)).isNotNull();
        assertThat(readAt(b)).isNotNull();
        assertThat(readAt(theirs)).isNull();
        mockMvc.perform(get(URL).with(user(String.valueOf(me))))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    @DisplayName("삭제 — 목록에서 사라지고, 남의 것은 404")
    void 삭제() throws Exception {
        Long mine = insert(me, "VERDICT", "판정", "/predictions/1", false);
        Long theirs = insert(other, "VERDICT", "남의 것", "/predictions/3", false);
        flush();

        mockMvc.perform(delete(URL + "/" + mine).with(user(String.valueOf(me))).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(URL + "/" + theirs).with(user(String.valueOf(me))).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        flush();

        assertThat(count(mine)).isZero();
        assertThat(count(theirs)).isEqualTo(1);
    }

    @Test
    @DisplayName("모두 삭제 — 내 것만 전부 사라지고, 없어도 204")
    void 모두_삭제() throws Exception {
        Long a = insert(me, "VERDICT", "판정", "/predictions/1", false);
        Long b = insert(me, "SUBSCRIBE", "구독", "/channels/1", true);
        Long theirs = insert(other, "VERDICT", "남의 것", "/predictions/3", false);
        flush();

        mockMvc.perform(delete(URL).with(user(String.valueOf(me))).with(csrf()))
                .andExpect(status().isNoContent());
        flush();

        assertThat(count(a)).isZero();
        assertThat(count(b)).isZero();
        assertThat(count(theirs)).isEqualTo(1);
        mockMvc.perform(delete(URL).with(user(String.valueOf(me))).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ── 재료 ──────────────────────────────────────────────

    private Long insert(Long userId, String type, String title, String linkPath, boolean read) {
        em.createNativeQuery("""
                        INSERT INTO notifications (user_id, type, title, body, link_path, read_at, created_at)
                        VALUES (?, ?, ?, '본문', ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, type)
                .setParameter(3, title)
                .setParameter(4, linkPath)
                .setParameter(5, read ? java.sql.Timestamp.from(java.time.Instant.now()) : null)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT max(id) FROM notifications").getSingleResult()).longValue();
    }

    private Object readAt(Long id) {
        return em.createNativeQuery("SELECT read_at FROM notifications WHERE id = ?")
                .setParameter(1, id)
                .getSingleResult();
    }

    private long count(Long id) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM notifications WHERE id = ?")
                        .setParameter(1, id)
                        .getSingleResult())
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

    private void flush() {
        em.flush();
        em.clear();
    }
}
