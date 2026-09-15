package ssafy.a507.backend.domain.account.controller;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.upload.entity.UploadFile;

/**
 * PATCH /users/me — 프로필 수정 (ANT-AUTH-05, 명세 §회원).
 *
 * <p>여기서 지키려는 것은 셋이다. 보내지 않은 필드를 건드리지 않을 것, 배열은 전체 교체일 것,
 * https 가 아닌 주소를 저장하지 않을 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("프로필 수정 API")
class ProfileUpdateTest {

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser("예측가");
    }

    private Long insertUser(String nickname) {
        User user = User.create();
        user.changeNickname(nickname);
        em.persist(user);
        em.flush();
        return user.getId();
    }

    /** 업로드 한 건을 만든다. 실제 POST /uploads 를 거치지 않고 행만 넣는다 */
    private String insertUpload(Long ownerId, UploadFile.Purpose purpose) {
        User owner = em.find(User.class, ownerId);
        UploadFile file = UploadFile.create(owner, purpose, "image/png", 400, 400, 1024);
        file.locateAt("/api/v1/uploads/" + file.getId());
        em.persist(file);
        em.flush();
        return file.getId();
    }

    private ResultActions patchMe(String body) throws Exception {
        return mockMvc.perform(
                patch("/api/v1/users/me")
                        .with(user(String.valueOf(userId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    @DisplayName("소개·관심 섹터·외부 채널을 저장하고 그대로 돌려준다")
    void savesWholeProfile() throws Exception {
        patchMe(
                        """
                        {"introduce":"반도체만 봅니다",
                         "interests":["반도체","2차전지"],
                         "channels":[{"platform":"BLOG","url":"https://example.com/blog"}]}
                        """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.introduce").value("반도체만 봅니다"))
                .andExpect(jsonPath("$.interests[0]").value("반도체"))
                .andExpect(jsonPath("$.interests[1]").value("2차전지"))
                .andExpect(jsonPath("$.channels[0].platform").value("BLOG"))
                .andExpect(jsonPath("$.channels[0].url").value("https://example.com/blog"));

        // 같은 트랜잭션의 1차 캐시가 아니라 실제로 읽어 오는 경로로도 확인한다
        mockMvc.perform(get("/api/v1/users/me").with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(2));
    }

    @Test
    @DisplayName("보내지 않은 필드는 건드리지 않는다 — 온보딩은 닉네임만 보낸다")
    void leavesAbsentFieldsAlone() throws Exception {
        patchMe("""
                {"introduce":"그대로 남아야 한다","interests":["반도체"]}
                """)
                .andExpect(status().isOk());

        patchMe("""
                {"nickname":"새이름"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새이름"))
                .andExpect(jsonPath("$.introduce").value("그대로 남아야 한다"))
                .andExpect(jsonPath("$.interests.length()").value(1));
    }

    @Test
    @DisplayName("배열은 전체 교체다 — 빈 배열이면 비운다")
    void replacesArraysWholesale() throws Exception {
        patchMe("""
                {"interests":["반도체","2차전지","자동차"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(3));

        patchMe("""
                {"interests":["바이오"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(1))
                .andExpect(jsonPath("$.interests[0]").value("바이오"));

        patchMe("""
                {"interests":[]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(0));
    }

    @Test
    @DisplayName("같은 값이 두 번 와도 UQ 에 걸리지 않고 한 번만 담긴다")
    void foldsDuplicates() throws Exception {
        patchMe("""
                {"interests":["반도체","반도체"],
                 "channels":[{"platform":"BLOG","url":"https://a.com"},
                             {"platform":"X","url":"https://a.com"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests.length()").value(1))
                .andExpect(jsonPath("$.channels.length()").value(1));
    }

    @Test
    @DisplayName("http 주소는 저장하지 않는다 — 피싱 통로를 막는다")
    void rejectsNonHttpsChannel() throws Exception {
        patchMe("""
                {"channels":[{"platform":"BLOG","url":"http://example.com"}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("javascript: 주소도 막는다")
    void rejectsJavascriptUrl() throws Exception {
        patchMe("""
                {"channels":[{"platform":"ETC","url":"javascript:alert(1)"}]}
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("소개 200자를 넘기면 거절한다")
    void rejectsTooLongIntroduce() throws Exception {
        patchMe("{\"introduce\":\"" + "가".repeat(201) + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("남이 쓰는 닉네임이면 409 이고, 같은 요청의 소개도 저장되지 않는다")
    void rejectsDuplicateNicknameAtomically() throws Exception {
        insertUser("이미있음");

        patchMe("""
                {"nickname":"이미있음","introduce":"이 값은 남으면 안 된다"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));

        mockMvc.perform(get("/api/v1/users/me").with(user(String.valueOf(userId))))
                .andExpect(jsonPath("$.nickname").value("예측가"))
                .andExpect(jsonPath("$.introduce").doesNotExist());
    }

    @Test
    @DisplayName("소개를 빈 문자열로 보내면 지운 것으로 본다")
    void blankIntroduceClears() throws Exception {
        patchMe("""
                {"introduce":"한때 적었던 소개"}
                """)
                .andExpect(status().isOk());

        patchMe("""
                {"introduce":"   "}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.introduce").doesNotExist());
    }

    @Test
    @DisplayName("내가 올린 PROFILE 사진을 걸면 주소를 돌려준다")
    void setsAvatar() throws Exception {
        String fileId = insertUpload(userId, UploadFile.Purpose.PROFILE);

        patchMe("{\"avatarFileId\":\"" + fileId + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("/api/v1/uploads/" + fileId));
    }

    @Test
    @DisplayName("빈 문자열이면 사진을 지운다 — 기본 그림으로 돌아간다")
    void clearsAvatar() throws Exception {
        patchMe("{\"avatarFileId\":\"" + insertUpload(userId, UploadFile.Purpose.PROFILE) + "\"}")
                .andExpect(status().isOk());

        patchMe("""
                {"avatarFileId":""}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());
    }

    @Test
    @DisplayName("남이 올린 파일은 걸 수 없다 — id 를 알아내도 소용없어야 한다")
    void rejectsOthersUpload() throws Exception {
        Long other = insertUser("남의계정");
        String fileId = insertUpload(other, UploadFile.Purpose.PROFILE);

        patchMe("{\"avatarFileId\":\"" + fileId + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("용도가 다른 파일은 걸 수 없다 — 광고 배너가 프로필 자리에 들어가면 안 된다")
    void rejectsWrongPurpose() throws Exception {
        String fileId = insertUpload(userId, UploadFile.Purpose.AD);

        patchMe("{\"avatarFileId\":\"" + fileId + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는 파일 id 는 400 이다")
    void rejectsUnknownFile() throws Exception {
        patchMe("""
                {"avatarFileId":"00000000-0000-0000-0000-000000000000"}
                """)
                .andExpect(status().isBadRequest());
    }
}

