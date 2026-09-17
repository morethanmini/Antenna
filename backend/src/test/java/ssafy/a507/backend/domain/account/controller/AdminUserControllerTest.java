package ssafy.a507.backend.domain.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 회원 제재 — ANT-ADMIN-01.
 *
 * 상태 이름은 지라 AC 의 SUSPENDED 가 아니라 코드의 BANNED 다(User.Status).
 * AuthService 가 이미 BANNED 로 로그인을 막고 있어 그쪽에 맞췄다 — 명세서도 정정했다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserControllerTest {

    private static final String URL = "/api/v1/admin/users";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager em;

    private Long adminId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        adminId = insertUser("운영자", "ADMIN");
        memberId = insertUser("말썽꾼", "USER");
        flush();
    }

    @Test
    @DisplayName("일반 회원이 부르면 403 ADMIN_ONLY")
    void 일반회원은_403() throws Exception {
        mockMvc.perform(patch(URL + "/" + adminId + "/status")
                        .with(user(String.valueOf(memberId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BANNED", "사유")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
    }

    @Test
    @DisplayName("관리자가 회원을 차단하면 status 가 BANNED 가 된다")
    void 회원_차단() throws Exception {
        mockMvc.perform(patch(URL + "/" + memberId + "/status")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BANNED", "반복 광고")))
                .andExpect(status().isNoContent());
        flush();

        assertThat(회원상태(memberId)).isEqualTo("BANNED");
    }

    @Test
    @DisplayName("차단을 되돌려 ACTIVE 로 올릴 수 있다")
    void 차단_해제() throws Exception {
        상태변경(memberId, "BANNED");
        flush();
        상태변경(memberId, "ACTIVE");
        flush();

        assertThat(회원상태(memberId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("관리자가 자기 계정을 차단하려 하면 409 ADMIN_SELF_DEMOTE — 되돌릴 길이 없어서 막는다")
    void 자기자신_차단은_409() throws Exception {
        mockMvc.perform(patch(URL + "/" + adminId + "/status")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BANNED", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SELF_DEMOTE"));
        flush();

        assertThat(회원상태(adminId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("없는 회원이면 404 USER_NOT_FOUND")
    void 없는_회원은_404() throws Exception {
        mockMvc.perform(patch(URL + "/999999/status")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BANNED", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("status 가 없으면 400 INVALID_REQUEST")
    void status_누락은_400() throws Exception {
        mockMvc.perform(patch(URL + "/" + memberId + "/status")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("status"));
    }

    @Test
    @DisplayName("enum 에 없는 SUSPENDED 를 보내면 400 — 이 프로젝트의 제재 상태는 BANNED 다")
    void SUSPENDED_는_400() throws Exception {
        mockMvc.perform(patch(URL + "/" + memberId + "/status")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("SUSPENDED", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("닉네임 일부로 회원을 찾는다 — 대소문자를 가리지 않는다")
    void 회원_검색() throws Exception {
        insertUser("Ant운영", "USER");
        flush();

        mockMvc.perform(get(URL).param("q", "ant")
                        .with(user(String.valueOf(adminId))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].nickname").value("Ant운영"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("q 가 없으면 최근 가입 순으로 전부 나온다")
    void 검색어_없으면_전체() throws Exception {
        mockMvc.perform(get(URL).with(user(String.valueOf(adminId))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].nickname").value("말썽꾼"));
    }

    // ── 도우미 ──────────────────────────────────────────────

    private void 상태변경(Long userId, String status) throws Exception {
        mockMvc.perform(patch(URL + "/" + userId + "/status")
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(status, null)))
                .andExpect(status().isNoContent());
    }

    private String body(String status, String reason) {
        String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
        return """
                { "status": "%s", "reason": %s }
                """.formatted(status, reasonJson);
    }

    private Long insertUser(String nickname, String role) {
        em.createNativeQuery("""
                INSERT INTO users (nickname, role, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """).setParameter(1, nickname).setParameter(2, role).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = ?")
                .setParameter(1, nickname).getSingleResult()).longValue();
    }

    private String 회원상태(Long id) {
        return (String) em.createNativeQuery("SELECT status FROM users WHERE id = ?")
                .setParameter(1, id).getSingleResult();
    }

    private void flush() {
        em.flush();
        em.clear();
    }
}
