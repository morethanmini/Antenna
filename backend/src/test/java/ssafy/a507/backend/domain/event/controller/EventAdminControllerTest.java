package ssafy.a507.backend.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * POST /api/v1/admin/events · PUT …/base-price — ANT-EVENT-04 (작업명세 v1.2 §3-6·7 · §4).
 *
 * <p>여기서 고정하는 것은 셋이다 — <b>관리자만</b> 부를 수 있을 것(매처가 아니라 서비스 안 role 비교,
 * §0.2 ④) · 기준가가 있으면 복사하고 없으면 null 로 두어 응답에 보일 것 · R1 은 온체인 전송 없이
 * 작업(PENDING)까지만 만들 것(§0.2 ②).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("배팅 이벤트 관리자 API")
class EventAdminControllerTest {

    private static final String CREATE = "/api/v1/admin/events";
    private static final String BASE_PRICE = "/api/v1/admin/events/{eventId}/stocks/{idx}/base-price";
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 17);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private Long admin;
    private Long member;

    @BeforeEach
    void setUp() {
        admin = insertUser("관리자", "ADMIN");
        member = insertUser("일반회원", "USER");
        insertStock("005930", "삼성전자");
        insertStock("000660", "SK하이닉스");
        // 삼성만 기준일 종가가 있다 — 배치가 한 종목을 놓친 상황을 그대로 만든다.
        insertQuote("005930", BASE_DATE, "91000.00");
    }

    @Test
    @DisplayName("관리자가 만들면 202. 기준가는 있으면 복사하고 없으면 null 로 응답에 보인다")
    void 생성_성공() throws Exception {
        mockMvc.perform(create(admin, body(2)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").isNumber())
                .andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.stocks[0].idx").value(0))
                .andExpect(jsonPath("$.stocks[0].stockCode").value("005930"))
                .andExpect(jsonPath("$.stocks[0].basePrice").value(91000.00))
                .andExpect(jsonPath("$.stocks[1].stockCode").value("000660"))
                // 기준가가 없는 종목은 null 이다 — 0 으로 채우면 승패가 거짓으로 갈린다
                .andExpect(jsonPath("$.stocks[1].basePrice").doesNotExist());
    }

    @Test
    @DisplayName("생성 직후 상태는 SCHEDULED 다 — 기준가가 덜 찼으니 크론이 열지 않는다")
    void 생성_직후는_SCHEDULED() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());

        assertThat(scalar("SELECT status FROM prediction_events ORDER BY id DESC LIMIT 1"))
                .isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("R1 은 온체인 전송을 하지 않는다 — EVENT_ADMIN 작업이 PENDING 이고 tx 는 비어 있다")
    void R1_은_전송하지_않는다() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());

        assertThat(scalar("SELECT kind FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo("EVENT_ADMIN");
        assertThat(scalar("SELECT resource_type FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo("EVENT");
        assertThat(scalar("SELECT status FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo("PENDING");
        assertThat(scalar("SELECT tx_hash FROM operations ORDER BY created_at DESC LIMIT 1")).isNull();
    }

    @Test
    @DisplayName("일반 회원은 403 EVENT_ADMIN_ONLY — 권한은 서비스 안에서 본다")
    void 일반회원은_403() throws Exception {
        mockMvc.perform(create(member, body(2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ADMIN_ONLY"));

        assertThat(count("SELECT count(*) FROM prediction_events")).isZero();
    }

    @Test
    @DisplayName("종목이 겹치면 400 — 같은 종목에 두 칸을 주면 배팅이 어느 칸인지 갈린다")
    void 종목_중복은_400() throws Exception {
        String dup = """
                {"title":"대회","opensAt":"2026-09-18T13:30:00+09:00","sealsAt":"2026-09-19T11:59:00+09:00",
                 "baseDate":"2026-09-17","settleDate":"2026-09-21",
                 "minStake":500,"maxStake":2000,"minStocks":2,
                 "stockCodes":["005930","005930"]}
                """;
        mockMvc.perform(create(admin, dup))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("stockCodes"));
    }

    @Test
    @DisplayName("종목 수가 최소 참가 종목 수보다 적으면 400 — 아무도 참가 조건을 못 채운다")
    void 종목이_minStocks_보다_적으면_400() throws Exception {
        String few = """
                {"title":"대회","opensAt":"2026-09-18T13:30:00+09:00","sealsAt":"2026-09-19T11:59:00+09:00",
                 "baseDate":"2026-09-17","settleDate":"2026-09-21",
                 "minStake":500,"maxStake":2000,"minStocks":5,
                 "stockCodes":["005930","000660"]}
                """;
        mockMvc.perform(create(admin, few))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("stockCodes"));
    }

    @Test
    @DisplayName("접수 마감이 시작보다 빠르면 400")
    void 일정_역순은_400() throws Exception {
        String bad = """
                {"title":"대회","opensAt":"2026-09-19T13:30:00+09:00","sealsAt":"2026-09-18T11:59:00+09:00",
                 "baseDate":"2026-09-17","settleDate":"2026-09-21",
                 "minStake":500,"maxStake":2000,"minStocks":2,
                 "stockCodes":["005930","000660"]}
                """;
        mockMvc.perform(create(admin, bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("sealsAt"));
    }

    @Test
    @DisplayName("없는 종목은 400 STOCK_NOT_FOUND")
    void 없는_종목은_400() throws Exception {
        String ghost = """
                {"title":"대회","opensAt":"2026-09-18T13:30:00+09:00","sealsAt":"2026-09-19T11:59:00+09:00",
                 "baseDate":"2026-09-17","settleDate":"2026-09-21",
                 "minStake":500,"maxStake":2000,"minStocks":2,
                 "stockCodes":["005930","999999"]}
                """;
        mockMvc.perform(create(admin, ghost))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }

    @Test
    @DisplayName("기준가 수동 입력 — 배치가 놓친 종목을 관리자가 채운다")
    void 기준가_수동_입력() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());
        long eventId = count("SELECT max(id) FROM prediction_events");

        mockMvc.perform(put(BASE_PRICE, eventId, 1)
                        .with(user(String.valueOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basePrice\":210000.00}"))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT count(*) FROM prediction_event_stocks WHERE base_price IS NULL")).isZero();
    }

    @Test
    @DisplayName("기준가 입력도 관리자만 — 일반 회원은 403")
    void 기준가_입력은_관리자만() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());
        long eventId = count("SELECT max(id) FROM prediction_events");

        mockMvc.perform(put(BASE_PRICE, eventId, 1)
                        .with(user(String.valueOf(member))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basePrice\":210000.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ADMIN_ONLY"));
    }

    @Test
    @DisplayName("이벤트에 없는 idx 는 400 EVENT_STOCK_NOT_INCLUDED")
    void 없는_idx_는_400() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());
        long eventId = count("SELECT max(id) FROM prediction_events");

        mockMvc.perform(put(BASE_PRICE, eventId, 9)
                        .with(user(String.valueOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"basePrice\":210000.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_STOCK_NOT_INCLUDED"));
    }

    // ── fixtures ─────────────────────────────────────────

    private MockHttpServletRequestBuilder create(Long userId, String body) {
        return post(CREATE)
                .with(user(String.valueOf(userId))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String body(int minStocks) {
        return """
                {"title":"9월 3주 업앤다운",
                 "opensAt":"2026-09-18T13:30:00+09:00","sealsAt":"2026-09-19T11:59:00+09:00",
                 "baseDate":"2026-09-17","settleDate":"2026-09-21",
                 "minStake":500,"maxStake":2000,"minStocks":%d,
                 "stockCodes":["005930","000660"]}
                """.formatted(minStocks);
    }

    private String scalar(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return v == null ? null : String.valueOf(v);
    }

    private long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private void insertQuote(String code, LocalDate tradeDate, String close) {
        em.createNativeQuery("""
                        INSERT INTO daily_quotes (stock_code, trade_date, close, collected_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, code)
                .setParameter(2, tradeDate)
                .setParameter(3, new BigDecimal(close))
                .executeUpdate();
    }

    private void insertStock(String code, String name) {
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES (?, ?, true)")
                .setParameter(1, code)
                .setParameter(2, name)
                .executeUpdate();
    }

    private Long insertUser(String nickname, String role) {
        em.createNativeQuery("""
                        INSERT INTO users (nickname, role, status, created_at, updated_at)
                        VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, nickname)
                .setParameter(2, role)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = ?")
                        .setParameter(1, nickname)
                        .getSingleResult())
                .longValue();
    }
}
