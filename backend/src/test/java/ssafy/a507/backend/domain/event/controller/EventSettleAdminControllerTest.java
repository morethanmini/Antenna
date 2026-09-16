package ssafy.a507.backend.domain.event.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.service.EventSettlementRunner;
import ssafy.a507.backend.domain.chain.service.EventSettlementService;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.service.EventResultService;

/**
 * POST /admin/events/{id}/settle · GET …/status — ANT-EVENT-06 (작업명세 v1.2 §3-9·10).
 *
 * <p>러너 자체는 ANT-EVENT-03 의 몫이라 여기서는 <b>REST 층의 약속</b>만 붙든다.
 *
 * <ol>
 *   <li>전제가 안 갖춰지면 <b>러너를 부르지 않는다</b> — 실행 행을 남기지 않고 409 로 끝낸다
 *   <li>전제가 맞으면 202 로 바로 돌려주고 러너는 <b>다른 스레드</b>에서 돈다 — 요청 스레드에서
 *       몇 분을 붙들면 게이트웨이가 먼저 끊는다
 *   <li>굳히기는 러너가 끝난 <b>뒤</b> REST 가 부른다 — 체인 층이 도메인 층을 모르게 둔다
 *   <li>{@code EVENT_NOT_READY} 가 못 싣는 "무엇이 빠졌나" 를 상태 API 가 {@code reasons} 로 보여준다
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("이벤트 정산 트리거 · 상태 API")
class EventSettleAdminControllerTest {

    private static final String SETTLE = "/api/v1/admin/events/{eventId}/settle";
    private static final String STATUS = "/api/v1/admin/events/{eventId}/status";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    @MockitoBean EventSettlementRunner runner;
    @MockitoBean EventResultService resultService;

    private final List<Long> userIds = new ArrayList<>();
    private Long admin;
    private Long member;
    private Long eventId;

    @BeforeEach
    void setUp() {
        admin = insertUser("정산관리자", "ADMIN");
        member = insertUser("정산일반회원", "USER");
        eventId = insertEvent();
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM prediction_events").executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)")
                    .setParameter("ids", userIds)
                    .executeUpdate();
        });
        userIds.clear();
    }

    @Test
    @DisplayName("전제가 맞으면 202 — 러너는 다른 스레드에서 돌고 끝난 뒤 순위를 굳힌다")
    void 정산_트리거() throws Exception {
        given(runner.readiness(anyLong())).willReturn(ready());
        given(runner.state(anyLong())).willReturn(java.util.Optional.empty());
        CountDownLatch done = new CountDownLatch(1);
        willAnswer(call -> {
                    done.countDown();
                    return new EventSettlementRunner.RunReport(1L, false, 3, 2, 3);
                })
                .given(runner)
                .run(eventId);

        mockMvc.perform(post(SETTLE, eventId).with(user(String.valueOf(admin))).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(eventId));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(runner).run(eventId);
        // 굳히기는 러너가 끝난 뒤 REST 가 부른다 — 러너(chain)가 event 패키지를 부르면 방향이 거꾸로다
        verify(resultService, org.mockito.Mockito.timeout(5000)).freeze(eventId);
    }

    @Test
    @DisplayName("전제가 안 갖춰지면 409 이고 러너를 아예 부르지 않는다 — 실행 행을 남기지 않는다")
    void 전제_미충족은_409() throws Exception {
        given(runner.readiness(anyLong())).willReturn(notReady("월요일 종가가 없다"));

        mockMvc.perform(post(SETTLE, eventId).with(user(String.valueOf(admin))).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_READY"));

        verify(runner, never()).run(anyLong());
    }

    @Test
    @DisplayName("일반 회원은 정산할 수 없다 — 러너도 안 돈다")
    void 일반회원은_403() throws Exception {
        mockMvc.perform(post(SETTLE, eventId).with(user(String.valueOf(member))).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ADMIN_ONLY"));

        verify(runner, never()).run(anyLong());
    }

    @Test
    @DisplayName("없는 이벤트는 404 — 러너를 부르기 전에 막는다")
    void 없는_이벤트는_404() throws Exception {
        mockMvc.perform(post(SETTLE, 999_999L).with(user(String.valueOf(admin))).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));

        verify(runner, never()).run(anyLong());
    }

    @Test
    @DisplayName("상태 API 가 '무엇이 빠졌는지' 를 문구로 보여준다 — 409 코드만으로는 알 수 없다")
    void 상태_조회() throws Exception {
        given(runner.readiness(anyLong())).willReturn(notReady("월요일 종가가 없다", "리빌 안 된 배팅 2건"));
        given(runner.state(anyLong())).willReturn(java.util.Optional.empty());

        mockMvc.perform(get(STATUS, eventId).with(user(String.valueOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.phase").value("SEALED"))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.reasons.length()").value(2))
                .andExpect(jsonPath("$.reasons[0]").value("월요일 종가가 없다"))
                // 한 번도 안 돌렸으면 run 은 없다
                .andExpect(jsonPath("$.run").value(nullValue()))
                .andExpect(jsonPath("$.bets.total").value(0))
                .andExpect(jsonPath("$.frozenResults").value(0));
    }

    @Test
    @DisplayName("상태 조회도 관리자만")
    void 상태는_관리자만() throws Exception {
        mockMvc.perform(get(STATUS, eventId).with(user(String.valueOf(member))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ADMIN_ONLY"));
    }

    // ── 픽스처 ───────────────────────────────────────────

    private EventSettlementService.Preflight ready() {
        return new EventSettlementService.Preflight(
                eventId, PredictionEvent.Status.SEALED, 2, List.of(), List.of());
    }

    private EventSettlementService.Preflight notReady(String... reasons) {
        return new EventSettlementService.Preflight(
                eventId, PredictionEvent.Status.SEALED, 2, List.of(), List.of(reasons));
    }

    /** 봉인까지 끝난 회차. 정산 트리거가 눌리는 시점의 모양이다. */
    private Long insertEvent() {
        return tx.execute(s -> {
            Instant now = Instant.now();
            em.createNativeQuery("""
                            INSERT INTO prediction_events
                              (title, status, opens_at, seals_at, base_date, settle_date,
                               min_stake, max_stake, min_stocks, open_tx_hash, seal_tx_hash, created_at)
                            VALUES ('대회', 'SEALED', ?, ?, ?, ?, 500, 2000, 2, '0xopen', '0xseal',
                                    CURRENT_TIMESTAMP)
                            """)
                    .setParameter(1, now.minus(2, ChronoUnit.DAYS))
                    .setParameter(2, now.minus(1, ChronoUnit.DAYS))
                    .setParameter(3, LocalDate.of(2026, 9, 17))
                    .setParameter(4, LocalDate.of(2026, 9, 21))
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT max(id) FROM prediction_events")
                            .getSingleResult())
                    .longValue();
        });
    }

    private Long insertUser(String nickname, String role) {
        Long id = tx.execute(s -> {
            User u = User.create();
            u.changeNickname(nickname);
            em.persist(u);
            em.flush();
            em.createNativeQuery("UPDATE users SET role = ? WHERE id = ?")
                    .setParameter(1, role)
                    .setParameter(2, u.getId())
                    .executeUpdate();
            return u.getId();
        });
        userIds.add(id);
        return id;
    }
}
