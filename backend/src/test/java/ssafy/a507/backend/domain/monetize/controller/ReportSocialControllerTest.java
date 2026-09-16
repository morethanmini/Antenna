package ssafy.a507.backend.domain.monetize.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ANT-REPORT-SOCIAL — 리포트 좋아요 · 댓글 · 댓글 좋아요의 AC 검증.
 *
 * <p>요청마다 {@code user(...)} 로 인증 주체를 붙인다. 주체의 이름이 곧 users.id 다
 * ({@code SecurityContextCurrentUserProvider}) — {@code ReportControllerTest} 와 같은 방식이다.
 *
 * <p>여기서 제일 중요한 것은 <b>잠금이 반응까지 막는가</b>이다. 열람은 200 + {@code locked:true}
 * 인데 반응은 403 이라 두 규칙이 다르고, 잠금 판정이 {@code ReportService} 와
 * {@code ReportSocialService} 두 곳에서 쓰이므로 한쪽만 고쳐지면 화면에 드러나지 않는 구멍이 된다.
 * 리포트 본체 · 그 리포트의 댓글 양쪽을 다 본다 — 댓글 좋아요가 리포트 잠금을 우회하면 소용없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("리포트 좋아요 · 댓글")
class ReportSocialControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager em;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long authorId;
    private Long readerId;

    @BeforeEach
    void setUp() {
        authorId = insertUser("발행자");
        readerId = insertUser("독자");
        flush();
    }

    @Nested
    @DisplayName("좋아요")
    class Likes {

        @Test
        @DisplayName("전체 공개 리포트에 누르면 201 과 갱신된 likeCount 가 온다")
        void 좋아요_성공() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.likeCount").value(1));
        }

        @Test
        @DisplayName("두 번 누르면 409 DUPLICATE_LIKE")
        void 좋아요_중복() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated());
            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_LIKE"));
        }

        @Test
        @DisplayName("사람이 둘이면 서로 막지 않는다 — UQ 는 (report, user) 짝이다")
        void 좋아요_다른_사람() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(jsonPath("$.likeCount").value(1));
            mockMvc.perform(post(likeUrl(reportId)).with(user(str(authorId))).with(csrf()))
                    .andExpect(jsonPath("$.likeCount").value(2));
        }

        @Test
        @DisplayName("좋아요를 누르면 작성자에게 REPORT_LIKE 알림 — 취소했다 다시 눌러도 (리포트, 사람)당 한 번")
        void 좋아요_알림() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated());
            mockMvc.perform(delete(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated());
            flush();

            assertThat(countNotifications(authorId, "REPORT_LIKE")).isEqualTo(1);
            assertThat(notificationLink(authorId, "REPORT_LIKE")).isEqualTo("/reports/" + reportId);
        }

        @Test
        @DisplayName("내 채널 반응 알림을 끈 작성자에게는 좋아요 알림이 가지 않는다(ANT-NOTIFY-02)")
        void 좋아요_알림_설정_끔() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            insertSetting(authorId, true, true, false, true);
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated());
            flush();

            assertThat(countNotifications(authorId, "REPORT_LIKE")).isZero();
        }

        @Test
        @DisplayName("내 리포트에 내가 누른 좋아요는 알리지 않는다")
        void 좋아요_본인_알림_없음() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(authorId))).with(csrf()))
                    .andExpect(status().isCreated());
            flush();

            assertThat(countNotifications(authorId, "REPORT_LIKE")).isZero();
        }

        @Test
        @DisplayName("취소는 멱등 — 누른 적 없어도 204")
        void 좋아요_취소_멱등() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(delete(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated());
            mockMvc.perform(delete(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isNoContent());
            // 취소했으니 같은 사람이 다시 누를 수 있어야 한다 — 행이 남아 있으면 409 가 난다.
            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.likeCount").value(1));
        }

        @Test
        @DisplayName("없는 리포트는 404 REPORT_NOT_FOUND")
        void 좋아요_없는_리포트() throws Exception {
            mockMvc.perform(post(likeUrl(987_654_321L)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("댓글")
    class Comments {

        @Test
        @DisplayName("댓글이 달리면 작성자에게 REPORT_COMMENT 알림 — 내가 단 댓글은 알리지 않는다")
        void 댓글_알림() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(post(commentsUrl(reportId))
                            .with(user(str(readerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"잘 읽었습니다"}
                                    """))
                    .andExpect(status().isCreated());
            mockMvc.perform(post(commentsUrl(reportId))
                            .with(user(str(authorId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"감사합니다"}
                                    """))
                    .andExpect(status().isCreated());
            flush();

            assertThat(countNotifications(authorId, "REPORT_COMMENT")).isEqualTo(1);
            assertThat(notificationLink(authorId, "REPORT_COMMENT")).isEqualTo("/reports/" + reportId);
        }

        @Test
        @DisplayName("작성하면 201 과 id, 목록에 실린다")
        void 댓글_작성() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(post(commentsUrl(reportId))
                            .with(user(str(readerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"잘 읽었습니다"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber());

            mockMvc.perform(get(commentsUrl(reportId)).with(user(str(readerId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].body").value("잘 읽었습니다"))
                    .andExpect(jsonPath("$.items[0].author.nickname").value("독자"))
                    .andExpect(jsonPath("$.items[0].likeCount").value(0))
                    .andExpect(jsonPath("$.items[0].liked").value(false))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }

        @Test
        @DisplayName("빈 본문은 400 INVALID_REQUEST · field 는 body")
        void 댓글_빈_본문() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();

            mockMvc.perform(post(commentsUrl(reportId))
                            .with(user(str(readerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"  "}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.field").value("body"));
        }

        @Test
        @DisplayName("오래된 순이고 커서로 다음 페이지가 이어진다 — 앞쪽을 다시 주지 않는다")
        void 댓글_커서() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();
            for (int i = 1; i <= 3; i++) {
                insertComment(reportId, readerId, "댓글" + i, "VISIBLE");
            }
            flush();

            String first = mockMvc.perform(
                            get(commentsUrl(reportId)).param("size", "2").with(user(str(readerId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].body").value("댓글1"))
                    .andExpect(jsonPath("$.items[1].body").value("댓글2"))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            String cursor = json(first).get("nextCursor").asText();
            mockMvc.perform(get(commentsUrl(reportId))
                            .param("size", "2")
                            .param("cursor", cursor)
                            .with(user(str(readerId))))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].body").value("댓글3"))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }

        @Test
        @DisplayName("BLOCKED 댓글은 목록에서 빠진다")
        void 댓글_블라인드() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();
            insertComment(reportId, readerId, "보이는 댓글", "VISIBLE");
            insertComment(reportId, readerId, "가려진 댓글", "BLOCKED");
            flush();

            mockMvc.perform(get(commentsUrl(reportId)).with(user(str(readerId))))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].body").value("보이는 댓글"));
        }

        @Test
        @DisplayName("없는 리포트의 댓글 목록은 404 REPORT_NOT_FOUND")
        void 댓글_없는_리포트() throws Exception {
            mockMvc.perform(get(commentsUrl(987_654_321L)).with(user(str(readerId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("댓글 좋아요")
    class CommentLikes {

        @Test
        @DisplayName("누르면 201 과 갱신된 수, 목록의 liked 가 요청자 기준으로 채워진다")
        void 댓글_좋아요() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();
            Long commentId = insertComment(reportId, authorId, "원 댓글", "VISIBLE");
            flush();

            mockMvc.perform(post(commentLikeUrl(commentId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.likeCount").value(1));

            // 누른 사람에게는 liked=true
            mockMvc.perform(get(commentsUrl(reportId)).with(user(str(readerId))))
                    .andExpect(jsonPath("$.items[0].likeCount").value(1))
                    .andExpect(jsonPath("$.items[0].liked").value(true));
            // 누르지 않은 사람에게는 수만 보이고 liked=false
            mockMvc.perform(get(commentsUrl(reportId)).with(user(str(authorId))))
                    .andExpect(jsonPath("$.items[0].likeCount").value(1))
                    .andExpect(jsonPath("$.items[0].liked").value(false));
        }

        @Test
        @DisplayName("두 번 누르면 409, 취소 뒤에는 다시 눌린다")
        void 댓글_좋아요_중복과_취소() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();
            Long commentId = insertComment(reportId, authorId, "원 댓글", "VISIBLE");
            flush();

            mockMvc.perform(post(commentLikeUrl(commentId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated());
            mockMvc.perform(post(commentLikeUrl(commentId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DUPLICATE_LIKE"));
            mockMvc.perform(delete(commentLikeUrl(commentId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post(commentLikeUrl(commentId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.likeCount").value(1));
        }

        @Test
        @DisplayName("BLOCKED 댓글은 404 COMMENT_NOT_FOUND — 좋아요로 존재를 확인할 수 없다")
        void 댓글_좋아요_블라인드() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();
            Long commentId = insertComment(reportId, authorId, "가려진 댓글", "BLOCKED");
            flush();

            mockMvc.perform(post(commentLikeUrl(commentId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("없는 댓글은 404 COMMENT_NOT_FOUND")
        void 댓글_좋아요_없는_댓글() throws Exception {
            mockMvc.perform(
                            post(commentLikeUrl(987_654_321L)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("구독자 전용 리포트 — 읽기와 반응의 규칙이 다르다")
    class Locked {

        @Test
        @DisplayName("미구독자의 좋아요·댓글은 403 REPORT_FORBIDDEN")
        void 미구독자는_반응_불가() throws Exception {
            Long reportId = insertReport(authorId, "구독자 전용", false);
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REPORT_FORBIDDEN"));

            mockMvc.perform(post(commentsUrl(reportId))
                            .with(user(str(readerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"본문도 못 봤는데"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REPORT_FORBIDDEN"));
        }

        @Test
        @DisplayName("미구독자도 댓글 목록은 읽는다 — 쓰기(403)와 다르다")
        void 미구독자도_댓글은_읽는다() throws Exception {
            Long reportId = insertReport(authorId, "구독자 전용", false);
            flush();
            insertComment(reportId, authorId, "작성자 댓글", "VISIBLE");
            flush();

            mockMvc.perform(get(commentsUrl(reportId)).with(user(str(readerId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1));
        }

        @Test
        @DisplayName("ACTIVE 구독자는 좋아요·댓글이 된다")
        void 구독자는_반응_가능() throws Exception {
            Long reportId = insertReport(authorId, "구독자 전용", false);
            insertSubscription(readerId, authorId, "ACTIVE");
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.likeCount").value(1));

            mockMvc.perform(post(commentsUrl(reportId))
                            .with(user(str(readerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"구독자입니다"}
                                    """))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("만료된 구독은 미구독과 같다 — 403")
        void 만료_구독은_미구독() throws Exception {
            Long reportId = insertReport(authorId, "구독자 전용", false);
            insertSubscription(readerId, authorId, "EXPIRED");
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REPORT_FORBIDDEN"));
        }

        @Test
        @DisplayName("작성자 본인은 자기 구독자 전용 리포트에 반응할 수 있다")
        void 작성자는_반응_가능() throws Exception {
            Long reportId = insertReport(authorId, "구독자 전용", false);
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(authorId))).with(csrf()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("잠긴 리포트의 댓글에도 좋아요를 누를 수 없다 — 리포트 규칙을 우회하는 길이 생기면 안 된다")
        void 잠긴_리포트의_댓글_좋아요() throws Exception {
            Long reportId = insertReport(authorId, "구독자 전용", false);
            flush();
            Long commentId = insertComment(reportId, authorId, "작성자 댓글", "VISIBLE");
            flush();

            mockMvc.perform(post(commentLikeUrl(commentId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("REPORT_FORBIDDEN"));
        }
    }

    @Nested
    @DisplayName("리포트 피드·상세에 실리는 통계")
    class Counts {

        @Test
        @DisplayName("상세에 likeCount · liked · commentCount 가 온다")
        void 상세_통계() throws Exception {
            Long reportId = insertReport(authorId, "공개 리포트", true);
            flush();
            insertComment(reportId, authorId, "댓글", "VISIBLE");
            insertComment(reportId, authorId, "가려진 댓글", "BLOCKED");
            flush();

            mockMvc.perform(post(likeUrl(reportId)).with(user(str(readerId))).with(csrf()))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/reports/" + reportId).with(user(str(readerId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likeCount").value(1))
                    .andExpect(jsonPath("$.liked").value(true))
                    // BLOCKED 는 목록에서 빠지므로 개수에도 넣지 않는다.
                    .andExpect(jsonPath("$.commentCount").value(1));
        }

        @Test
        @DisplayName("피드에도 실린다. 잠긴 리포트도 통계는 공개다 — 반응이 많다는 사실이 구독 유인이다")
        void 피드_통계() throws Exception {
            Long reportId = insertReport(authorId, "구독자 전용", false);
            flush();
            insertComment(reportId, authorId, "댓글", "VISIBLE");
            flush();
            // 작성자 본인은 잠기지 않으므로 좋아요를 남길 수 있다(자기 구독은 CHECK 가 막는다).
            mockMvc.perform(post(likeUrl(reportId)).with(user(str(authorId))).with(csrf()))
                    .andExpect(status().isCreated());

            // 미구독자에게는 locked=true 인데 통계는 그대로 온다.
            mockMvc.perform(get("/api/v1/reports").with(user(str(readerId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.id == " + reportId + ")].locked")
                            .value(true))
                    .andExpect(jsonPath("$.items[?(@.id == " + reportId + ")].likeCount")
                            .value(1))
                    .andExpect(jsonPath("$.items[?(@.id == " + reportId + ")].liked")
                            .value(false))
                    .andExpect(jsonPath("$.items[?(@.id == " + reportId + ")].commentCount")
                            .value(1));
        }

        @Test
        @DisplayName("반응이 없는 리포트는 0 이다 — 집계에 없는 행을 빠뜨리지 않는다")
        void 통계_기본값() throws Exception {
            Long reportId = insertReport(authorId, "조용한 리포트", true);
            flush();

            mockMvc.perform(get("/api/v1/reports/" + reportId).with(user(str(readerId))))
                    .andExpect(jsonPath("$.likeCount").value(0))
                    .andExpect(jsonPath("$.liked").value(false))
                    .andExpect(jsonPath("$.commentCount").value(0));
        }
    }

    // ── 도우미 ──────────────────────────────────────────────

    private static String likeUrl(Long reportId) {
        return "/api/v1/reports/" + reportId + "/like";
    }

    private static String commentsUrl(Long reportId) {
        return "/api/v1/reports/" + reportId + "/comments";
    }

    /** 커뮤니티의 {@code /comments/{id}/like} 와 경로를 나눈 이유는 컨트롤러 주석에 적어 두었다. */
    private static String commentLikeUrl(Long commentId) {
        return "/api/v1/report-comments/" + commentId + "/like";
    }

    /** 알림 설정 행. 없으면 전부 on 이라, 끈 경우만 심는다. */
    private void insertSetting(Long userId, boolean verdict, boolean season, boolean social, boolean channel) {
        em.createNativeQuery("""
                        INSERT INTO user_settings (user_id, notify_verdict, notify_season, notify_social, notify_channel, chart_view, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'BEGINNER', CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, verdict)
                .setParameter(3, season)
                .setParameter(4, social)
                .setParameter(5, channel)
                .executeUpdate();
    }

    private long countNotifications(Long userId, String type) {
        return ((Number) em.createNativeQuery(
                                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?")
                        .setParameter(1, userId)
                        .setParameter(2, type)
                        .getSingleResult())
                .longValue();
    }

    private String notificationLink(Long userId, String type) {
        return (String) em.createNativeQuery(
                        "SELECT link_path FROM notifications WHERE user_id = ? AND type = ?")
                .setParameter(1, userId)
                .setParameter(2, type)
                .getSingleResult();
    }

    private static String str(Long userId) {
        return String.valueOf(userId);
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private void flush() {
        em.flush();
        em.clear();
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

    private Long insertReport(Long userId, String title, boolean isPublic) {
        em.createNativeQuery("""
                        INSERT INTO reports (user_id, title, body, is_public, view_count, created_at)
                        VALUES (?, ?, '본문', ?, 0, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, title)
                .setParameter(3, isPublic)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM reports WHERE title = ?")
                        .setParameter(1, title)
                        .getSingleResult())
                .longValue();
    }

    /** BLOCKED 댓글은 API 로 만들 수 없다(관리자 기능이 별도 스토리다) — 행을 직접 넣는다. */
    private Long insertComment(Long reportId, Long userId, String body, String status) {
        em.createNativeQuery("""
                        INSERT INTO report_comments (report_id, user_id, body, status, created_at)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, reportId)
                .setParameter(2, userId)
                .setParameter(3, body)
                .setParameter(4, status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM report_comments WHERE body = ?")
                        .setParameter(1, body)
                        .getSingleResult())
                .longValue();
    }

    /** 구독은 ANT-MONETIZE 담당 엔티티다. NOT NULL 컬럼만 채운 최소 행을 넣는다. */
    private void insertSubscription(Long subscriberId, Long publisherId, String status) {
        em.createNativeQuery("""
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, auto_renew, created_at, updated_at)
                        VALUES (?, ?, 0, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriberId)
                .setParameter(2, publisherId)
                .setParameter(3, status)
                .executeUpdate();
    }
}
