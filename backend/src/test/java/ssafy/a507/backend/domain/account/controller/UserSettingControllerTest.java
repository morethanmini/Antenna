package ssafy.a507.backend.domain.account.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * ANT-NOTIFY-02 — 환경 설정 조회·변경. 알림을 실제로 거르는지는 발송 지점 테스트가 본다
 * (리포트 발행·좋아요·구독 확정·판정).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("환경 설정 API")
class UserSettingControllerTest {

    private static final String URL = "/api/v1/users/me/settings";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private Long me;

    @BeforeEach
    void setUp() {
        em.createNativeQuery("""
                        INSERT INTO users (nickname, role, status, created_at, updated_at)
                        VALUES ('나', 'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .executeUpdate();
        me = ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = '나'").getSingleResult()).longValue();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("행이 없으면 전부 기본값 — 알림 넷 on · BEGINNER. 행을 만들지는 않는다")
    void 기본값() throws Exception {
        mockMvc.perform(get(URL).with(user(String.valueOf(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifyVerdict").value(true))
                .andExpect(jsonPath("$.notifySeason").value(true))
                .andExpect(jsonPath("$.notifySocial").value(true))
                .andExpect(jsonPath("$.notifyChannel").value(true))
                .andExpect(jsonPath("$.chartView").value("BEGINNER"));
    }

    @Test
    @DisplayName("PUT 은 행을 만들거나 덮어쓰고 갱신값을 돌려준다 — 다음 GET 이 같은 값이다")
    void 변경() throws Exception {
        mockMvc.perform(put(URL).with(user(String.valueOf(me))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"notifyVerdict":false,"notifySeason":true,"notifySocial":false,"notifyChannel":true,"chartView":"ADVANCED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifyVerdict").value(false))
                .andExpect(jsonPath("$.notifySocial").value(false))
                .andExpect(jsonPath("$.chartView").value("ADVANCED"));
        em.flush();
        em.clear();

        mockMvc.perform(get(URL).with(user(String.valueOf(me))))
                .andExpect(jsonPath("$.notifyVerdict").value(false))
                .andExpect(jsonPath("$.notifySeason").value(true))
                .andExpect(jsonPath("$.notifySocial").value(false))
                .andExpect(jsonPath("$.notifyChannel").value(true))
                .andExpect(jsonPath("$.chartView").value("ADVANCED"));

        // 두 번째 PUT 은 같은 행을 덮어쓴다
        mockMvc.perform(put(URL).with(user(String.valueOf(me))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"notifyVerdict":true,"notifySeason":false,"notifySocial":true,"notifyChannel":false,"chartView":"BEGINNER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifySeason").value(false))
                .andExpect(jsonPath("$.notifyChannel").value(false));
    }

    @Test
    @DisplayName("값이 빠지면 400 INVALID_REQUEST — 일부만 보내 나머지를 기본값으로 되돌리지 않는다")
    void 변경_누락() throws Exception {
        mockMvc.perform(put(URL).with(user(String.valueOf(me))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"notifyVerdict":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
