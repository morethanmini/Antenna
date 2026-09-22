package ssafy.a507.backend.domain.community.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 신고 처리 · 블라인드 — ANT-ADMIN-01 의 AC 검증.
 *
 * SecurityConfig 가 없어 기본 체인이 살아 있다. 요청마다 user(...) 로 인증 주체를,
 * csrf() 로 토큰을 붙인다. 인증 주체의 이름이 곧 users.id 다.
 *
 * 관리자 권한은 서비스 안에서 role 을 읽으므로, 픽스처에서 role='ADMIN' 인 행을 따로 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminAbuseReportControllerTest {

    private static final String REPORTS = "/api/v1/admin/abuse-reports";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager em;

    private Long adminId;
    private Long reporterId;
    private Long authorId;
    private Long postId;
    private Long commentId;
    private Long postReportId;
    private Long commentReportId;
    private Long userReportId;

    @BeforeEach
    void setUp() {
        adminId = insertUser("운영자", "ADMIN");
        reporterId = insertUser("신고자", "USER");
        authorId = insertUser("작성자", "USER");
        postId = insertFeedPost(authorId, "문제되는 글");
        commentId = insertPostComment(postId, authorId, "문제되는 댓글");
        postReportId = insertReport(reporterId, "targetPost", postId);
        commentReportId = insertReport(reporterId, "targetComment", commentId);
        userReportId = insertReport(reporterId, "targetUser", authorId);
        flush();
    }

    // ── 권한 ────────────────────────────────────────────────

    @Test
    @DisplayName("일반 회원이 신고 목록을 부르면 403 ADMIN_ONLY")
    void 일반회원_목록조회는_403() throws Exception {
        mockMvc.perform(get(REPORTS).with(user(String.valueOf(reporterId))).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
    }

    @Test
    @DisplayName("일반 회원이 신고를 처리하려 해도 403 ADMIN_ONLY")
    void 일반회원_처리도_403() throws Exception {
        mockMvc.perform(patch(REPORTS + "/" + postReportId)
                        .with(user(String.valueOf(reporterId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"ACCEPT\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401 — 기본 Security 체인이 막는다")
    void 미인증은_401() throws Exception {
        mockMvc.perform(get(REPORTS).with(csrf())).andExpect(status().isUnauthorized());
    }

    // ── 목록 ────────────────────────────────────────────────

    @Test
    @DisplayName("관리자는 PENDING 신고 목록을 본다 — 대상 본문까지 함께 온다")
    void 목록_조회_성공() throws Exception {
        mockMvc.perform(get(REPORTS).param("status", "PENDING")
                        .with(user(String.valueOf(adminId))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                // id desc 라 마지막에 넣은 회원 신고가 맨 앞이다
                .andExpect(jsonPath("$.items[0].targetType").value("USER"))
                .andExpect(jsonPath("$.items[2].targetType").value("POST"))
                .andExpect(jsonPath("$.items[2].targetBody").value("문제되는 글"))
                .andExpect(jsonPath("$.items[2].targetBlocked").value(false))
                .andExpect(jsonPath("$.items[2].reporterNickname").value("신고자"));
    }

    @Test
    @DisplayName("status 를 빼면 처리된 것까지 전부 나온다")
    void 목록_전체_조회() throws Exception {
        처리(postReportId, "REJECT");

        mockMvc.perform(get(REPORTS).with(user(String.valueOf(adminId))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));

        mockMvc.perform(get(REPORTS).param("status", "PENDING")
                        .with(user(String.valueOf(adminId))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    // ── 처리 ────────────────────────────────────────────────

    @Test
    @DisplayName("글 신고를 ACCEPT 하면 글이 BLOCKED 로 내려가고 행은 남는다")
    void 글_신고_수락() throws Exception {
        처리(postReportId, "ACCEPT");
        flush();

        assertThat(신고상태(postReportId)).isEqualTo("ACCEPTED");
        assertThat(처리자(postReportId)).isEqualTo(adminId);
        assertThat(처리시각_있나(postReportId)).isTrue();
        assertThat(글상태(postId)).isEqualTo("BLOCKED");
        assertThat(글_행_수(postId)).isEqualTo(1); // 행 삭제 없음
        // 신고자는 결과를, 작성자는 가려진 사실을 안다
        assertThat(알림수(reporterId, "REPORT_HANDLED")).isEqualTo(1);
        assertThat(알림수(authorId, "CONTENT_BLOCKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("댓글 신고를 ACCEPT 하면 댓글이 BLOCKED 로 내려간다")
    void 댓글_신고_수락() throws Exception {
        처리(commentReportId, "ACCEPT");
        flush();

        assertThat(댓글상태(commentId)).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("REJECT 하면 신고만 닫히고 글은 그대로 보인다")
    void 신고_기각() throws Exception {
        처리(postReportId, "REJECT");
        flush();

        assertThat(신고상태(postReportId)).isEqualTo("REJECTED");
        assertThat(처리자(postReportId)).isEqualTo(adminId);
        assertThat(글상태(postId)).isEqualTo("VISIBLE");
        // 기각은 신고자만 안다 — 작성자는 아무 일도 없었다
        assertThat(알림수(reporterId, "REPORT_HANDLED")).isEqualTo(1);
        assertThat(알림수(authorId, "CONTENT_BLOCKED")).isEqualTo(0);
        assertThat(알림본문(reporterId, "REPORT_HANDLED")).startsWith("신고하신 글은 ");
    }

    @Test
    @DisplayName("리포트 신고를 기각하면 '리포트는' — 받침 없는 말에는 는")
    void 리포트_기각_조사() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "멀쩡한 리포트");
        Long abuseId = insertReport(reporterId, "targetReport", reportId);
        flush();

        처리(abuseId, "REJECT");
        flush();

        assertThat(알림본문(reporterId, "REPORT_HANDLED")).startsWith("신고하신 리포트는 ");
    }

    @Test
    @DisplayName("회원 신고를 ACCEPT 해도 계정은 ACTIVE 그대로 — 차단은 별도 API 다")
    void 회원_신고_수락은_계정을_막지_않는다() throws Exception {
        처리(userReportId, "ACCEPT");
        flush();

        assertThat(신고상태(userReportId)).isEqualTo("ACCEPTED");
        assertThat(회원상태(authorId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("이미 처리한 신고를 또 처리하면 409 REPORT_ALREADY_HANDLED")
    void 중복_처리는_409() throws Exception {
        처리(postReportId, "ACCEPT");

        mockMvc.perform(patch(REPORTS + "/" + postReportId)
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"REJECT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_ALREADY_HANDLED"));
    }

    @Test
    @DisplayName("없는 신고를 처리하면 404 TARGET_NOT_FOUND")
    void 없는_신고는_404() throws Exception {
        mockMvc.perform(patch(REPORTS + "/999999")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"ACCEPT\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_NOT_FOUND"));
    }

    @Test
    @DisplayName("action 이 없으면 400 INVALID_REQUEST")
    void action_누락은_400() throws Exception {
        mockMvc.perform(patch(REPORTS + "/" + postReportId)
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("action"));
    }

    // ── 직접 블라인드 ────────────────────────────────────────

    @Test
    @DisplayName("신고를 거치지 않고 글을 가렸다가 되돌릴 수 있다")
    void 글_블라인드_토글() throws Exception {
        상태변경("/api/v1/admin/posts/" + postId + "/status", "BLOCKED");
        flush();
        assertThat(글상태(postId)).isEqualTo("BLOCKED");

        상태변경("/api/v1/admin/posts/" + postId + "/status", "VISIBLE");
        flush();
        assertThat(글상태(postId)).isEqualTo("VISIBLE");
    }

    @Test
    @DisplayName("댓글도 같은 방식으로 가린다")
    void 댓글_블라인드() throws Exception {
        상태변경("/api/v1/admin/comments/" + commentId + "/status", "BLOCKED");
        flush();
        assertThat(댓글상태(commentId)).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("없는 글을 가리려 하면 404 POST_NOT_FOUND")
    void 없는_글_블라인드는_404() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/posts/999999/status")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"BLOCKED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    }

    // ── 리포트 · 리포트 댓글 (ANT-FE-ABUSE-REPORT) ─────────────

    @Test
    @DisplayName("리포트 신고를 ACCEPT 하면 리포트가 BLOCKED 로 내려가고 행은 남는다")
    void 리포트_신고_수락() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "문제되는 리포트");
        Long abuseId = insertReport(reporterId, "targetReport", reportId);
        flush();

        처리(abuseId, "ACCEPT");
        flush();

        assertThat(신고상태(abuseId)).isEqualTo("ACCEPTED");
        assertThat(리포트상태(reportId)).isEqualTo("BLOCKED");
        assertThat(리포트_행_수(reportId)).isEqualTo(1); // 봉인·앵커된 행이라 지우지 않는다
    }

    @Test
    @DisplayName("리포트 댓글 신고를 ACCEPT 하면 그 댓글만 BLOCKED 로 내려간다")
    void 리포트댓글_신고_수락() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "리포트");
        Long reportCommentId = insertReportComment(reportId, authorId, "문제되는 리포트 댓글");
        Long abuseId = insertReport(reporterId, "targetReportComment", reportCommentId);
        flush();

        처리(abuseId, "ACCEPT");
        flush();

        assertThat(리포트댓글상태(reportCommentId)).isEqualTo("BLOCKED");
        assertThat(리포트상태(reportId)).isEqualTo("VISIBLE"); // 댓글 신고로 리포트까지 가리지 않는다
    }

    @Test
    @DisplayName("리포트 신고를 REJECT 하면 리포트는 그대로 보인다")
    void 리포트_신고_기각() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "멀쩡한 리포트");
        Long abuseId = insertReport(reporterId, "targetReport", reportId);
        flush();

        처리(abuseId, "REJECT");
        flush();

        assertThat(리포트상태(reportId)).isEqualTo("VISIBLE");
    }

    @Test
    @DisplayName("목록에 리포트·리포트 댓글 신고가 제목·본문과 리포트 id 를 달고 나온다")
    void 리포트_신고_목록() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "문제되는 리포트");
        Long reportCommentId = insertReportComment(reportId, authorId, "문제되는 리포트 댓글");
        insertReport(reporterId, "targetReport", reportId);
        insertReport(reporterId, "targetReportComment", reportCommentId);
        flush();

        mockMvc.perform(get(REPORTS).param("status", "PENDING")
                        .with(user(String.valueOf(adminId))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                // id desc — 마지막에 넣은 리포트 댓글 신고가 맨 앞
                .andExpect(jsonPath("$.items[0].targetType").value("REPORT_COMMENT"))
                .andExpect(jsonPath("$.items[0].targetBody").value("문제되는 리포트 댓글"))
                .andExpect(jsonPath("$.items[0].targetReportId").value(reportId))
                .andExpect(jsonPath("$.items[0].targetBlocked").value(false))
                .andExpect(jsonPath("$.items[1].targetType").value("REPORT"))
                .andExpect(jsonPath("$.items[1].targetBody").value("문제되는 리포트"))
                .andExpect(jsonPath("$.items[1].targetReportId").value(reportId));
    }

    @Test
    @DisplayName("관리자는 리포트를 직접 가리고 복구할 수 있다 — 신고 승인으로 가려진 것을 되돌리는 길")
    void 리포트_블라인드_토글() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "가렸다 되돌릴 리포트");
        flush();

        상태변경("/api/v1/admin/reports/" + reportId + "/status", "BLOCKED");
        flush();
        assertThat(리포트상태(reportId)).isEqualTo("BLOCKED");

        상태변경("/api/v1/admin/reports/" + reportId + "/status", "VISIBLE");
        flush();
        assertThat(리포트상태(reportId)).isEqualTo("VISIBLE");
    }

    @Test
    @DisplayName("리포트 댓글 블라인드는 report_comments 를 바꾼다 — 같은 id 의 커뮤니티 댓글은 그대로다")
    void 리포트댓글_블라인드는_표를_섞지_않는다() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "리포트");
        Long reportCommentId = insertReportComment(reportId, authorId, "리포트 댓글");
        flush();

        상태변경("/api/v1/admin/report-comments/" + reportCommentId + "/status", "BLOCKED");
        flush();

        assertThat(리포트댓글상태(reportCommentId)).isEqualTo("BLOCKED");
        assertThat(댓글상태(commentId)).isEqualTo("VISIBLE");
    }

    @Test
    @DisplayName("일반 회원은 리포트를 가릴 수 없다 — 403 ADMIN_ONLY")
    void 일반회원_리포트_블라인드는_403() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "리포트");
        flush();

        mockMvc.perform(patch("/api/v1/admin/reports/" + reportId + "/status")
                        .with(user(String.valueOf(reporterId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"BLOCKED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
    }

    @Test
    @DisplayName("없는 리포트를 가리려 하면 404 REPORT_NOT_FOUND")
    void 없는_리포트_블라인드는_404() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/reports/999999/status")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"BLOCKED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 사람이 같은 리포트를 다시 신고하면 두 번째 신고도 처리할 수 있다")
    void 재신고도_처리된다() throws Exception {
        Long reportId = insertMonetizeReport(authorId, "두 번 신고될 리포트");
        flush();

        mockMvc.perform(post("/api/v1/abuse-reports")
                        .with(user(String.valueOf(reporterId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"REPORT\", \"targetId\": %d, \"reason\": \"SPAM\"}".formatted(reportId)))
                .andExpect(status().isCreated());
        처리(최근신고(), "REJECT");
        flush();

        mockMvc.perform(post("/api/v1/abuse-reports")
                        .with(user(String.valueOf(reporterId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"REPORT\", \"targetId\": %d, \"reason\": \"ABUSE\"}".formatted(reportId)))
                .andExpect(status().isCreated());
        처리(최근신고(), "REJECT"); // 옛 제약이면 여기서 (신고자, 대상, REJECTED) 중복 — 실제 PG 는 PendingUniqueTest
    }

    // ── 호출 도우미 ──────────────────────────────────────────

    private void 처리(Long reportId, String action) throws Exception {
        mockMvc.perform(patch(REPORTS + "/" + reportId)
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"%s\"}".formatted(action)))
                .andExpect(status().isNoContent());
    }

    private void 상태변경(String url, String status) throws Exception {
        mockMvc.perform(patch(url)
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"%s\"}".formatted(status)))
                .andExpect(status().isNoContent());
    }

    // ── 픽스처 ──────────────────────────────────────────────
    // User 는 ANT-AUTH 담당 엔티티라 생성 팩터리를 추가하지 않고 네이티브 INSERT 로 넣는다.

    private Long insertUser(String nickname, String role) {
        em.createNativeQuery("""
                INSERT INTO users (nickname, role, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).setParameter(1, nickname).setParameter(2, role).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = ?")
                .setParameter(1, nickname).getSingleResult()).longValue();
    }

    private Long insertFeedPost(Long userId, String body) {
        em.createNativeQuery("""
                INSERT INTO feed_posts (user_id, body, status, created_at)
                VALUES (?, ?, 'VISIBLE', CURRENT_TIMESTAMP)
                """).setParameter(1, userId).setParameter(2, body).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM feed_posts WHERE body = ?")
                .setParameter(1, body).getSingleResult()).longValue();
    }

    private Long insertPostComment(Long postId, Long userId, String body) {
        em.createNativeQuery("""
                INSERT INTO post_comments (post_id, user_id, body, status, created_at)
                VALUES (?, ?, ?, 'VISIBLE', CURRENT_TIMESTAMP)
                """).setParameter(1, postId).setParameter(2, userId)
                .setParameter(3, body).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM post_comments WHERE body = ?")
                .setParameter(1, body).getSingleResult()).longValue();
    }

    /** 대상 칸 하나만 채운 PENDING 신고. {@code field} 는 대상 칼럼(target_*_id) 중 하나의 뜻이다. */
    private Long insertReport(Long reporterId, String field, Long targetId) {
        String column = switch (field) {
            case "targetPost" -> "target_post_id";
            case "targetComment" -> "target_comment_id";
            case "targetReport" -> "target_report_id";
            case "targetReportComment" -> "target_report_comment_id";
            default -> "target_user_id";
        };
        String detail = "신고-" + column + "-" + targetId;
        em.createNativeQuery("""
                INSERT INTO abuse_reports (reporter_id, %s, reason, detail, status, created_at)
                VALUES (?, ?, 'SPAM', ?, 'PENDING', CURRENT_TIMESTAMP)
                """.formatted(column))
                .setParameter(1, reporterId).setParameter(2, targetId)
                .setParameter(3, detail).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM abuse_reports WHERE detail = ?")
                .setParameter(1, detail).getSingleResult()).longValue();
    }

    private String 신고상태(Long id) {
        return (String) em.createNativeQuery("SELECT status FROM abuse_reports WHERE id = ?")
                .setParameter(1, id).getSingleResult();
    }

    private Long 처리자(Long id) {
        return ((Number) em.createNativeQuery("SELECT handled_by FROM abuse_reports WHERE id = ?")
                .setParameter(1, id).getSingleResult()).longValue();
    }

    private boolean 처리시각_있나(Long id) {
        return em.createNativeQuery("SELECT handled_at FROM abuse_reports WHERE id = ?")
                .setParameter(1, id).getSingleResult() != null;
    }

    private String 알림본문(Long userId, String type) {
        return (String) em.createNativeQuery(
                        "SELECT body FROM notifications WHERE user_id = ? AND type = ? ORDER BY id DESC LIMIT 1")
                .setParameter(1, userId).setParameter(2, type).getSingleResult();
    }

    private long 알림수(Long userId, String type) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?")
                .setParameter(1, userId).setParameter(2, type).getSingleResult()).longValue();
    }

    private String 글상태(Long id) {
        return (String) em.createNativeQuery("SELECT status FROM feed_posts WHERE id = ?")
                .setParameter(1, id).getSingleResult();
    }

    private int 글_행_수(Long id) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM feed_posts WHERE id = ?")
                .setParameter(1, id).getSingleResult()).intValue();
    }

    private String 댓글상태(Long id) {
        return (String) em.createNativeQuery("SELECT status FROM post_comments WHERE id = ?")
                .setParameter(1, id).getSingleResult();
    }

    private Long insertMonetizeReport(Long userId, String title) {
        em.createNativeQuery("""
                INSERT INTO reports (user_id, title, body, is_public, view_count, created_at)
                VALUES (?, ?, '본문', true, 0, CURRENT_TIMESTAMP)
                """).setParameter(1, userId).setParameter(2, title).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM reports WHERE title = ?")
                .setParameter(1, title).getSingleResult()).longValue();
    }

    private Long insertReportComment(Long reportId, Long userId, String body) {
        em.createNativeQuery("""
                INSERT INTO report_comments (report_id, user_id, body, status, created_at)
                VALUES (?, ?, ?, 'VISIBLE', CURRENT_TIMESTAMP)
                """).setParameter(1, reportId).setParameter(2, userId)
                .setParameter(3, body).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM report_comments WHERE body = ?")
                .setParameter(1, body).getSingleResult()).longValue();
    }

    private Long 최근신고() {
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM abuse_reports").getSingleResult()).longValue();
    }

    private String 리포트상태(Long id) {
        return (String) em.createNativeQuery("SELECT status FROM reports WHERE id = ?")
                .setParameter(1, id).getSingleResult();
    }

    private int 리포트_행_수(Long id) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM reports WHERE id = ?")
                .setParameter(1, id).getSingleResult()).intValue();
    }

    private String 리포트댓글상태(Long id) {
        return (String) em.createNativeQuery("SELECT status FROM report_comments WHERE id = ?")
                .setParameter(1, id).getSingleResult();
    }

    private String 회원상태(Long id) {
        return (String) em.createNativeQuery("SELECT status FROM users WHERE id = ?")
                .setParameter(1, id).getSingleResult();
    }

    /** MockMvc 요청이 같은 트랜잭션에 참여하므로 flush 만 하면 픽스처가 보인다. */
    private void flush() {
        em.flush();
        em.clear();
    }
}
