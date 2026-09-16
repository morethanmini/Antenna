package ssafy.a507.backend.domain.event.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.domain.event.relay.ScriptedPoolRelayer;

/**
 * POST /api/v1/admin/events · PUT …/base-price · POST …/open — ANT-EVENT-04 (작업명세 v1.2 §3-6·7).
 *
 * <p>여기서 고정하는 것은 넷이다 — <b>관리자만</b> 부를 수 있을 것(매처가 아니라 서비스 안 role 비교,
 * §0.2 ④) · 기준가가 있으면 복사하고 없으면 null 로 두어 응답에 보일 것 · <b>생성이 온체인
 * {@code openEvent} 를 보낼 것</b> · 그 전송이 실패해도 이벤트 행은 남고 다시 보낼 수 있을 것.
 *
 * <p>{@code @Transactional} 롤백을 쓰지 않는다 — 전송이 "활성 트랜잭션 안에서는 거부" 가 계약이라
 * 테스트 트랜잭션 안에서 부르면 그 계약이 먼저 터진다({@code EventEntryControllerTest} 와 같다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ScriptedPoolRelayer.Config.class)
@DisplayName("배팅 이벤트 관리자 API")
class EventAdminControllerTest {

    private static final String CREATE = "/api/v1/admin/events";
    private static final String BASE_PRICE = "/api/v1/admin/events/{eventId}/stocks/{idx}/base-price";
    private static final String OPEN = "/api/v1/admin/events/{eventId}/open";
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 17);
    private static final List<String> CODES = List.of("005930", "000660");

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired ScriptedPoolRelayer poolRelayer;

    private final List<Long> userIds = new ArrayList<>();
    private Long admin;
    private Long member;

    @BeforeEach
    void setUp() {
        poolRelayer.reset();
        admin = insertUser("이벤트관리자", "ADMIN");
        member = insertUser("이벤트일반회원", "USER");
        deleteStocks();
        insertStock("005930", "삼성전자");
        insertStock("000660", "SK하이닉스");
        // 삼성만 기준일 종가가 있다 — 배치가 한 종목을 놓친 상황을 그대로 만든다.
        insertQuote("005930", BASE_DATE, "91000.00");
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM prediction_event_stocks").executeUpdate();
            em.createNativeQuery("DELETE FROM prediction_events").executeUpdate();
            em.createNativeQuery("DELETE FROM operations WHERE user_id IN (:ids)")
                    .setParameter("ids", userIds)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)")
                    .setParameter("ids", userIds)
                    .executeUpdate();
        });
        deleteStocks();
        userIds.clear();
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
                .andExpect(jsonPath("$.stocks[1].basePrice").value(nullValue()));
    }

    @Test
    @DisplayName("slug 를 주면 목록·홈 응답에 그대로 나온다 — 프론트가 어느 회차인지 아는 유일한 길")
    void slug() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());
        long eventId = count("SELECT max(id) FROM prediction_events");

        mockMvc.perform(get("/api/v1/events").with(user(String.valueOf(member))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].slug").value("scalping"));
        mockMvc.perform(get("/api/v1/events/{id}", eventId).with(user(String.valueOf(member))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("scalping"));
    }

    @Test
    @DisplayName("slug 는 없어도 된다 — 운영 점검용 회차는 화면 주소가 필요 없다")
    void slug_없어도_된다() throws Exception {
        String noSlug = body(2).replaceFirst("\\s*\"slug\":\"scalping\",", "");
        mockMvc.perform(create(admin, noSlug)).andExpect(status().isAccepted());

        assertThat(scalar("SELECT slug FROM prediction_events ORDER BY id DESC LIMIT 1")).isNull();
    }

    @Test
    @DisplayName("같은 slug 가 둘이면 막는다 — 화면이 어느 회차를 열지 갈린다")
    void slug_중복은_거부() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());

        mockMvc.perform(create(admin, body(2))).andExpect(status().is5xxServerError());

        assertThat(count("SELECT count(*) FROM prediction_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 직후 상태는 SCHEDULED 다 — 기준가가 덜 찼으니 크론이 열지 않는다")
    void 생성_직후는_SCHEDULED() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());

        assertThat(scalar("SELECT status FROM prediction_events ORDER BY id DESC LIMIT 1"))
                .isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("생성하면 온체인 openEvent 가 나간다 — 안 보내면 open_tx_hash 가 영영 비어 크론이 못 연다")
    void 생성이_openEvent_를_보낸다() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());

        List<ScriptedPoolRelayer.Call> sent = poolRelayer.callsOf("openEvent");
        assertThat(sent).hasSize(1);
        // 종목 수와 배팅 범위가 컨트랙트에도 같이 간다 — 이벤트 행의 값이 단일 진실이다
        assertThat(sent.get(0).stockIdx()).isEqualTo(2);
        assertThat(sent.get(0).stake()).isEqualTo(java.math.BigInteger.valueOf(2000));

        assertThat(scalar("SELECT kind FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo("EVENT_ADMIN");
        assertThat(scalar("SELECT resource_type FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo("EVENT");
        assertThat(scalar("SELECT tx_hash FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isNotNull();
        // 상태는 그대로 PENDING 이다 — SUCCEEDED 는 인덱서가 EventOpened 를 보고 닫는다
        assertThat(scalar("SELECT status FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("체인이 꺼져 있어도 이벤트는 만들어진다 — 작업은 PENDING 이고 tx 는 비어 있다")
    void 체인_장애면_행만_남는다() throws Exception {
        poolRelayer.disable();

        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());

        assertThat(count("SELECT count(*) FROM prediction_events")).isEqualTo(1);
        assertThat(scalar("SELECT tx_hash FROM operations ORDER BY created_at DESC LIMIT 1")).isNull();
        assertThat(scalar("SELECT status FROM operations ORDER BY created_at DESC LIMIT 1"))
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("전송이 못 나갔으면 같은 이벤트에 다시 보낸다 — 다시 만들면 종목 idx 가 어긋난다")
    void openEvent_재전송() throws Exception {
        poolRelayer.disable();
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());
        long eventId = count("SELECT max(id) FROM prediction_events");

        poolRelayer.reset();
        mockMvc.perform(post(OPEN, eventId).with(user(String.valueOf(admin))).with(csrf()))
                .andExpect(status().isAccepted());

        assertThat(poolRelayer.callsOf("openEvent")).hasSize(1);
        assertThat(count("SELECT count(*) FROM prediction_events")).isEqualTo(1);
        // 작업이 하나 더 생긴다 — 옛 작업은 인덱서가 SEND_LOST 로 닫는다
        assertThat(count("SELECT count(*) FROM operations WHERE kind = 'EVENT_ADMIN'")).isEqualTo(2);
    }

    @Test
    @DisplayName("이미 열린 이벤트는 다시 보내지 않는다 — 컨트랙트가 BadState 로 거부한다")
    void 이미_열렸으면_재전송_거부() throws Exception {
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());
        long eventId = count("SELECT max(id) FROM prediction_events");
        // 인덱서가 EventOpened 를 보고 하는 일
        update("UPDATE prediction_events SET open_tx_hash = '0xopened' WHERE id = " + eventId);
        poolRelayer.reset();

        mockMvc.perform(post(OPEN, eventId).with(user(String.valueOf(admin))).with(csrf()))
                .andExpect(status().isConflict());

        assertThat(poolRelayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("재전송도 관리자만 — 일반 회원은 403 이고 전송도 안 나간다")
    void 재전송은_관리자만() throws Exception {
        poolRelayer.disable();
        mockMvc.perform(create(admin, body(2))).andExpect(status().isAccepted());
        long eventId = count("SELECT max(id) FROM prediction_events");
        poolRelayer.reset();

        mockMvc.perform(post(OPEN, eventId).with(user(String.valueOf(member))).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ADMIN_ONLY"));

        assertThat(poolRelayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("일반 회원은 403 EVENT_ADMIN_ONLY — 권한은 서비스 안에서 본다")
    void 일반회원은_403() throws Exception {
        mockMvc.perform(create(member, body(2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EVENT_ADMIN_ONLY"));

        assertThat(count("SELECT count(*) FROM prediction_events")).isZero();
        assertThat(poolRelayer.calls()).isEmpty();
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
                {"slug":"scalping",
                 "title":"9월 3주 업앤다운",
                 "opensAt":"2026-09-18T13:30:00+09:00","sealsAt":"2026-09-19T11:59:00+09:00",
                 "baseDate":"2026-09-17","settleDate":"2026-09-21",
                 "minStake":500,"maxStake":2000,"minStocks":%d,
                 "stockCodes":["005930","000660"]}
                """.formatted(minStocks);
    }

    private String scalar(String sql) {
        Object v = tx.execute(s -> em.createNativeQuery(sql).getSingleResult());
        return v == null ? null : String.valueOf(v);
    }

    private long count(String sql) {
        return ((Number) tx.execute(s -> em.createNativeQuery(sql).getSingleResult())).longValue();
    }

    private void update(String sql) {
        tx.executeWithoutResult(s -> em.createNativeQuery(sql).executeUpdate());
    }

    /** 이 테스트가 넣은 종목·시세만 지운다 — H2 는 컨텍스트끼리 공유된다. */
    private void deleteStocks() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM daily_quotes WHERE stock_code IN (:codes)")
                    .setParameter("codes", CODES)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM stocks WHERE code IN (:codes)")
                    .setParameter("codes", CODES)
                    .executeUpdate();
        });
    }

    private void insertQuote(String code, LocalDate tradeDate, String close) {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                        INSERT INTO daily_quotes (stock_code, trade_date, close, collected_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, code)
                .setParameter(2, tradeDate)
                .setParameter(3, new BigDecimal(close))
                .executeUpdate());
    }

    private void insertStock(String code, String name) {
        tx.executeWithoutResult(s -> em.createNativeQuery(
                        "INSERT INTO stocks (code, name, listed) VALUES (?, ?, true)")
                .setParameter(1, code)
                .setParameter(2, name)
                .executeUpdate());
    }

    private Long insertUser(String nickname, String role) {
        Long id = tx.execute(s -> {
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
        });
        userIds.add(id);
        return id;
    }
}
