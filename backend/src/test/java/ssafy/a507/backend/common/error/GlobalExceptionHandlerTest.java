package ssafy.a507.backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프레임워크가 던지는 예외가 마지막 그물(500)로 새지 않는지 (ANT-INFRA-06).
 *
 * <p>도메인 오류는 각 컨트롤러 테스트가 본다. 여기서 보는 것은 <b>컨트롤러까지 오지 못한 요청</b>이다 —
 * 그쪽은 어느 도메인 테스트도 다루지 않아 셋이 오래 새고 있었다: 없는 경로 · 잘못된 메서드 ·
 * 잘못된 {@code Content-Type}.
 *
 * <p><b>이 클래스는 표면 전체를 훑는다.</b> 하나가 터질 때마다 그것만 고치면 다음 것이 또 샌다 —
 * 실제로 없는 경로를 고치고 나서야 405·415 가 드러났다. 클라이언트가 틀릴 수 있는 방식을 한자리에
 * 모아 두고 전부 400·404·405·406·415 로 끝나는지 본다. 여기에 500 이 하나라도 있으면 "서버 잘못"
 * 이라고 말하는 것인데 실제로는 클라이언트 잘못이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("공통 오류 응답")
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    /* 상태 코드만 보면 놓치는 것이 있어서 로그도 같이 본다.
       406 이 실제로 그랬다 — 응답은 406 이라 멀쩡해 보였는데 마지막 그물이 잡아 ERROR 스택을 남기고
       있었다. 이 작업의 이유 절반이 "로그가 더러워진다" 라, 상태 코드만 고정하면 반만 지키는 셈이다. */
    private ListAppender<ILoggingEvent> logs;
    private Logger handlerLogger;

    @BeforeEach
    void captureLogs() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        handlerLogger.detachAppender(logs);
    }

    /** 클라이언트 잘못은 ERROR 로 남기지 않는다 — 진짜 서버 오류가 묻힌다. */
    private void assertNoErrorLogged() {
        assertThat(logs.list)
                .extracting(ILoggingEvent::getLevel)
                .as("클라이언트 잘못인데 ERROR 를 남기면 안 된다")
                .doesNotContain(Level.ERROR);
    }

    @Nested
    @DisplayName("컨트롤러까지 오지 못한 요청 — 캐치올로 새면 안 된다")
    class BeforeController {

        @Test
        @DisplayName("매핑되지 않은 경로는 404 NOT_FOUND")
        void 없는_경로() throws Exception {
            // 실제로 500 이 나가던 경로다(정상 경로는 /api/v1/predictions/me/portfolio).
            mockMvc.perform(get("/api/v1/me/portfolio").with(user("1")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));

            mockMvc.perform(get("/api/v1/nonexistent/deep/path").with(user("1")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            assertNoErrorLogged();
        }

        @Test
        @DisplayName("경로는 있는데 메서드가 다르면 405 METHOD_NOT_ALLOWED — 404 와 code 를 나눈다")
        void 잘못된_메서드() throws Exception {
            mockMvc.perform(delete("/api/v1/predictions/1").with(user("1")))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
            assertNoErrorLogged();
        }

        @Test
        @DisplayName("Content-Type 이 JSON 이 아니면 415 UNSUPPORTED_MEDIA_TYPE")
        void 잘못된_컨텐츠타입() throws Exception {
            mockMvc.perform(post("/api/v1/reports")
                            .with(user("1"))
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("아무 글"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
            assertNoErrorLogged();
        }

        @Test
        @DisplayName("Accept 를 만족 못 하면 406 — 본문은 비운다(클라이언트가 JSON 을 안 받겠다고 했다)")
        void 수용불가_타입() throws Exception {
            mockMvc.perform(get("/api/v1/predictions/me").with(user("1")).accept(MediaType.APPLICATION_XML))
                    .andExpect(status().isNotAcceptable());
            /* 응답은 핸들러가 없어도 406 이었다 — 마지막 그물이 500 본문을 쓰려다 같은 이유로 실패해서다.
               상태 코드만 보면 통과하므로 여기서는 로그가 진짜 증거다. */
            assertNoErrorLogged();
        }
    }

    @Nested
    @DisplayName("본문·파라미터가 틀린 요청 — 전부 400 INVALID_REQUEST")
    class BadInput {

        @Test
        @DisplayName("깨진 JSON")
        void 깨진_json() throws Exception {
            mockMvc.perform(post("/api/v1/reports")
                            .with(user("1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("본문 없는 POST")
        void 본문_없음() throws Exception {
            mockMvc.perform(post("/api/v1/reports").with(user("1")).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        @Test
        @DisplayName("경로 변수 타입 불일치 — 어느 자리인지 field 로 알린다")
        void 경로변수_타입() throws Exception {
            mockMvc.perform(get("/api/v1/predictions/abc").with(user("1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.field").value("predictionId"));
        }

        @Test
        @DisplayName("필수 파라미터 누락")
        void 파라미터_누락() throws Exception {
            mockMvc.perform(get("/api/v1/rankings").with(user("1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.field").value("track"));
        }

        @Test
        @DisplayName("multipart 필수 파트 누락")
        void 파트_누락() throws Exception {
            mockMvc.perform(multipart("/api/v1/uploads").with(user("1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Nested
    @DisplayName("고치면서 깨뜨리지 않아야 하는 것")
    class Unchanged {

        @Test
        @DisplayName("도메인 404 는 그대로다 — 공통 NOT_FOUND 가 덮어쓰지 않는다")
        void 도메인_404() throws Exception {
            mockMvc.perform(get("/api/v1/predictions/{id}", 987_654_321L).with(user("1")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PREDICTION_NOT_FOUND"));
        }

        @Test
        @DisplayName("인증 없이 없는 경로를 부르면 401 이 먼저다 — 보안 필터가 경로 존재 여부보다 앞선다")
        void 미인증이_먼저() throws Exception {
            mockMvc.perform(get("/api/v1/me/portfolio")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("정상 경로는 200 — 핸들러가 늘어도 성공 경로에 영향이 없다")
        void 정상_경로() throws Exception {
            mockMvc.perform(get("/api/v1/predictions/me/portfolio").with(user("1")))
                    .andExpect(status().isOk());
        }
    }
}
