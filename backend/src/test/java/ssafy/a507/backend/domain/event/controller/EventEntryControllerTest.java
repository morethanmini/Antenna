package ssafy.a507.backend.domain.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import ssafy.a507.backend.common.security.SignatureNonceStore;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.service.FakeTokenRelayer;
import ssafy.a507.backend.domain.event.relay.FakePoolRelayer;
import ssafy.a507.backend.support.TestNonceStoreConfig;
import ssafy.a507.backend.support.WalletSignatures;

/**
 * POST /api/v1/events/{eventId}/entries — ANT-EVENT-05 (작업명세 v1.2 §3-3 · §4 · §6).
 *
 * <p>여기서 고정하는 것은 다섯이다.
 *
 * <ol>
 *   <li><b>첫 참가는 5종목 이상</b> — 이 규칙 때문에 묶음 API 가 존재한다
 *   <li><b>슬롯을 쓰지 않는다</b> — 이벤트 참가로 무료 예측 3건이 닳으면 안 된다
 *   <li><b>날짜는 이벤트가 준다</b> — 기존 판정 배치가 코드 한 줄 안 바꾸고 이벤트 예측을 판정한다
 *   <li><b>행을 먼저 커밋하고 전송한다</b>(§0.3) — 돈이 풀로 갔는데 배팅 행이 없으면 인덱서가 주인을 못 찾는다
 *   <li><b>전송 실패는 202 를 무너뜨리지 않는다</b> — 실패한 배팅은 재시도 크론이 봉인 전까지 다시 보낸다
 * </ol>
 *
 * <p>{@code @Transactional} 롤백을 쓰지 않는 이유 — 전송이 "활성 트랜잭션 안에서는 거부" 가 계약이라
 * 테스트 트랜잭션 안에서 부르면 그 계약이 먼저 터진다({@code SubscriptionControllerTest} 와 같다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestNonceStoreConfig.class, FakeTokenRelayer.Config.class, FakePoolRelayer.Config.class})
@DisplayName("배팅 이벤트 묶음 등록 API")
class EventEntryControllerTest {

    private static final long CHAIN_ID = 31337L;
    private static final String BETTOR_KEY =
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
    private static final String SALT = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final List<String> CODES =
            List.of("005930", "000660", "035720", "005380", "051910", "068270");
    /** 이벤트에 들어 있지 않은 종목 — "이벤트 밖 종목" 검사용. */
    private static final String OUTSIDER = "373220";
    private static final BigDecimal REF_CLOSE = new BigDecimal("10000.00");
    private static final BigInteger RICH = BigInteger.valueOf(1_000_000);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired SignatureNonceStore nonceStore;
    @Autowired FakeTokenRelayer tokenRelayer;
    @Autowired FakePoolRelayer poolRelayer;

    @MockitoBean StringRedisTemplate redis;

    private final Map<String, String> redisStore = new HashMap<>();
    private final List<Long> userIds = new ArrayList<>();

    private Credentials credentials;
    private User bettor;
    private Long eventId;

    @BeforeEach
    void setUp() {
        stubRedis();
        tokenRelayer.reset();
        tokenRelayer.withBalance(RICH);
        poolRelayer.reset();
        credentials = Credentials.create(BETTOR_KEY);
        bettor = insertUser("배팅러", credentials.getAddress());
        deleteStocks();
        insertStocks();
        eventId = insertEvent(Instant.now().plus(1, ChronoUnit.DAYS));
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM prediction_event_bets WHERE user_id IN (:ids)")
                    .setParameter("ids", userIds)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM prediction_event_stocks").executeUpdate();
            em.createNativeQuery("DELETE FROM prediction_events").executeUpdate();
            for (String delete : List.of(
                    "DELETE FROM operations WHERE user_id IN (:ids)",
                    "DELETE FROM prediction_commits WHERE prediction_id IN"
                            + " (SELECT id FROM predictions WHERE user_id IN (:ids))",
                    "DELETE FROM prediction_notes WHERE prediction_id IN"
                            + " (SELECT id FROM predictions WHERE user_id IN (:ids))",
                    "DELETE FROM prediction_evidences WHERE prediction_id IN"
                            + " (SELECT id FROM predictions WHERE user_id IN (:ids))",
                    "DELETE FROM predictions WHERE user_id IN (:ids)",
                    "DELETE FROM users WHERE id IN (:ids)")) {
                em.createNativeQuery(delete).setParameter("ids", userIds).executeUpdate();
            }
        });
        deleteStocks();
        userIds.clear();
    }

    // ── 성공 경로 ────────────────────────────────────────

    @Test
    @DisplayName("5종목을 서명 한 번으로 등록하면 202 — 종목마다 예측 id·커밋·작업 id 를 돌려준다")
    void 묶음_등록_성공() throws Exception {
        mockMvc.perform(entries(items(5), "key-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.entries.length()").value(5))
                .andExpect(jsonPath("$.entries[0].stockCode").value("005930"))
                .andExpect(jsonPath("$.entries[0].predictionId").isNumber())
                .andExpect(jsonPath("$.entries[0].commitHash").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].operationId").isNotEmpty())
                .andExpect(jsonPath("$.me.stockCount").value(5))
                .andExpect(jsonPath("$.me.staked").value(5000))
                .andExpect(jsonPath("$.me.qualified").value(true))
                // 이벤트 종목이 6개고 5개를 걸었다
                .andExpect(jsonPath("$.me.remainingStocks").value(1))
                // 정산 전이라 null 이다 — 0 으로 내리면 "본전" 으로 읽힌다
                .andExpect(jsonPath("$.me.paid").doesNotExist());
    }

    @Test
    @DisplayName("배팅은 PENDING 으로, 작업은 EVENT_BET 으로 저장된다 — 확정은 인덱서 몫이다")
    void 저장_모양() throws Exception {
        mockMvc.perform(entries(items(5), "key-1")).andExpect(status().isAccepted());

        assertThat(count("SELECT count(*) FROM prediction_event_bets WHERE bet_status = 'PENDING'"))
                .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM operations WHERE kind = 'EVENT_BET'")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM operations WHERE resource_type = 'PREDICTION'"))
                .isEqualTo(5);
        // 배팅 행은 예측과 1:1 이라 근거·커밋도 함께 생긴다 — 앵커 배치가 평소처럼 집어 간다
        assertThat(count("SELECT count(*) FROM prediction_commits")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM prediction_notes")).isEqualTo(5);
    }

    @Test
    @DisplayName("예측의 기준일·만기일·기간을 이벤트가 준다 — 기존 판정 배치가 그대로 판정한다")
    void 날짜는_이벤트_값() throws Exception {
        mockMvc.perform(entries(items(5), "key-1")).andExpect(status().isAccepted());

        assertThat(scalar("SELECT DISTINCT base_date FROM predictions")).isEqualTo(baseDate().toString());
        assertThat(scalar("SELECT DISTINCT settle_date FROM predictions"))
                .isEqualTo(baseDate().plusDays(4).toString());
        // 목→월 = 4 캘린더일. 상수로 박지 않고 이벤트의 두 날짜에서 뽑는다
        assertThat(scalar("SELECT DISTINCT horizon FROM predictions")).isEqualTo("4");
        assertThat(scalar("SELECT DISTINCT track FROM predictions")).isEqualTo("REAL");
    }

    @Test
    @DisplayName("무료 예측 슬롯을 쓰지 않는다 — 이벤트 참가로 하루 3건이 닳으면 안 된다")
    void 슬롯은_그대로() throws Exception {
        mockMvc.perform(entries(items(5), "key-1")).andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/predictions/slots").with(user(String.valueOf(bettor.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(0))
                .andExpect(jsonPath("$.remaining").value(3));
    }

    @Test
    @DisplayName("placeBet 이 종목마다 나가고, 보낸 커밋이 DB 의 event_commit 과 같다")
    void 전송_확인() throws Exception {
        mockMvc.perform(entries(items(5), "key-1")).andExpect(status().isAccepted());

        List<FakePoolRelayer.Call> sent = poolRelayer.callsOf("placeBet");
        assertThat(sent).hasSize(5);
        assertThat(sent.get(0).wallet()).isEqualToIgnoringCase(credentials.getAddress());
        assertThat(sent.get(0).stockIdx()).isZero();
        assertThat(sent.get(0).stake()).isEqualTo(BigInteger.valueOf(1000));
        assertThat(sent.get(0).commitHex())
                .isEqualTo(scalar(
                        "SELECT event_commit FROM prediction_event_bets b"
                                + " JOIN prediction_event_stocks s ON s.id = b.event_stock_id"
                                + " WHERE s.idx = 0"));
        // 전송이 끝나면 작업에 tx 가 붙는다. 배팅 상태는 그대로 PENDING 이다 — 확정은 인덱서가 한다
        assertThat(count("SELECT count(*) FROM operations WHERE tx_hash IS NOT NULL")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM prediction_event_bets WHERE bet_status = 'PENDING'"))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("이미 5종목을 걸었으면 한 종목만 더해도 된다 — 최소 종목 수는 첫 제출에만 건다")
    void 두_번째부터는_한_종목() throws Exception {
        mockMvc.perform(entries(items(5), "key-1")).andExpect(status().isAccepted());

        mockMvc.perform(entries(itemsFrom(5, 1), "key-2"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.me.stockCount").value(6))
                .andExpect(jsonPath("$.me.remainingStocks").value(0));
    }

    // ── 거절 ─────────────────────────────────────────────

    @Test
    @DisplayName("첫 제출이 4종목이면 400 EVENT_MIN_STOCKS — 하나도 저장하지 않는다")
    void 첫_제출_4종목은_400() throws Exception {
        mockMvc.perform(entries(items(4), "key-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_MIN_STOCKS"))
                .andExpect(jsonPath("$.field").value("items"));

        assertThat(count("SELECT count(*) FROM predictions")).isZero();
        assertThat(poolRelayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("같은 종목을 두 요청에 걸면 409 EVENT_STOCK_ALREADY_BET")
    void 같은_종목_두_번은_409() throws Exception {
        mockMvc.perform(entries(items(5), "key-1")).andExpect(status().isAccepted());

        mockMvc.perform(entries(items(5), "key-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_STOCK_ALREADY_BET"));
    }

    @Test
    @DisplayName("한 요청 안에 같은 종목이 두 줄이면 400 — 그러면 '이미 건 종목' 검사를 둘 다 빠져나간다")
    void 요청_안_중복은_400() throws Exception {
        String dup = body(List.of(item("005930", 1000), item("005930", 1000),
                item("000660", 1000), item("035720", 1000), item("005380", 1000)));
        mockMvc.perform(entries(dup, "key-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("items"));
    }

    @Test
    @DisplayName("이벤트에 없는 종목은 400 EVENT_STOCK_NOT_INCLUDED")
    void 이벤트_밖_종목은_400() throws Exception {
        List<String> five = new ArrayList<>(itemList(4));
        five.add(item(OUTSIDER, 1000));
        mockMvc.perform(entries(body(five), "key-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_STOCK_NOT_INCLUDED"));
    }

    @Test
    @DisplayName("배팅 금액이 범위 밖이면 400 EVENT_STAKE_OUT_OF_RANGE — 범위는 이벤트 행이 정한다")
    void 금액_범위_밖은_400() throws Exception {
        List<String> five = new ArrayList<>(itemList(4));
        five.add(item("051910", 499));
        mockMvc.perform(entries(body(five), "key-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_STAKE_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.field").value("stake"));
    }

    @Test
    @DisplayName("체인 잔액이 모자라면 409 — 기준은 원장이 아니라 체인이다(인덱서 지연)")
    void 잔액_부족은_409() throws Exception {
        tokenRelayer.withBalance(BigInteger.valueOf(4_999));

        mockMvc.perform(entries(items(5), "key-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_INSUFFICIENT_BALANCE"));

        assertThat(count("SELECT count(*) FROM predictions")).isZero();
    }

    @Test
    @DisplayName("접수 마감 시각이 지나면 409 EVENT_SEALED — 상태가 아직 OPEN 이어도 막는다")
    void 마감_뒤는_409() throws Exception {
        // 봉인 tx 가 확정되기 전까지 status 는 OPEN 그대로다. 그 틈을 시각으로 막는다.
        update("UPDATE prediction_events SET seals_at = ? WHERE id = ?",
                Instant.now().minus(1, ChronoUnit.MINUTES), eventId);

        mockMvc.perform(entries(items(5), "key-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_SEALED"));
    }

    @Test
    @DisplayName("아직 SCHEDULED 면 409 EVENT_NOT_OPEN")
    void 열리기_전은_409() throws Exception {
        update("UPDATE prediction_events SET status = 'SCHEDULED' WHERE id = ?", eventId);

        mockMvc.perform(entries(items(5), "key-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_OPEN"));
    }

    @Test
    @DisplayName("없는 이벤트는 404")
    void 없는_이벤트는_404() throws Exception {
        mockMvc.perform(post("/api/v1/events/{eventId}/entries", 999_999L)
                        .with(user(String.valueOf(bettor.getId()))).with(csrf())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(items(5)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("서명이 다른 항목으로 만들어졌으면 401 — 화면에서 본 것과 다른 값으로 결제되지 않는다")
    void 서명_불일치는_401() throws Exception {
        // 서명은 1000 원짜리 다섯 줄로 만들고 본문만 2000 으로 바꾼다.
        String signature = signatureFor(itemList(5));
        List<String> tampered = itemList(5).stream()
                .map(line -> line.replace("\"stake\":1000", "\"stake\":2000"))
                .toList();
        String tamperedBody = """
                {"items":[%s],"signature":"%s"}
                """.formatted(String.join(",", tampered), signature);

        mockMvc.perform(entries(tamperedBody, "key-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGNER_MISMATCH"));

        assertThat(count("SELECT count(*) FROM predictions")).isZero();
    }

    @Test
    @DisplayName("멱등키가 없으면 400 — 다시 누르면 같은 돈이 두 번 나간다")
    void 멱등키_없으면_400() throws Exception {
        mockMvc.perform(post("/api/v1/events/{eventId}/entries", eventId)
                        .with(user(String.valueOf(bettor.getId()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(items(5)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("같은 멱등키·같은 본문이면 접수를 되풀이하지 않고 같은 202 를 준다")
    void 멱등_재요청() throws Exception {
        String body = items(5);
        mockMvc.perform(entries(body, "key-1")).andExpect(status().isAccepted());

        mockMvc.perform(entries(body, "key-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.entries.length()").value(5));

        assertThat(count("SELECT count(*) FROM predictions")).isEqualTo(5);
        assertThat(poolRelayer.callsOf("placeBet")).hasSize(5);
    }

    // ── 전송 실패 ────────────────────────────────────────

    @Test
    @DisplayName("체인이 꺼져 있어도 202 — 작업은 PENDING 으로 두고 인덱서가 SEND_LOST 로 닫는다")
    void 체인_장애면_PENDING_유지() throws Exception {
        poolRelayer.disable();

        mockMvc.perform(entries(items(5), "key-1")).andExpect(status().isAccepted());

        assertThat(count("SELECT count(*) FROM prediction_event_bets WHERE bet_status = 'PENDING'"))
                .isEqualTo(5);
        assertThat(count("SELECT count(*) FROM operations WHERE status = 'PENDING'")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM operations WHERE tx_hash IS NOT NULL")).isZero();
    }

    @Test
    @DisplayName("전송 시뮬레이션에서 잔액이 모자라면 그 작업만 FAILED — 나머지 종목은 계속 나간다")
    void 전송_잔액부족은_그_건만_FAILED() throws Exception {
        poolRelayer.thenSent().thenInsufficientBalance();

        mockMvc.perform(entries(items(5), "key-1")).andExpect(status().isAccepted());

        assertThat(count("SELECT count(*) FROM operations WHERE status = 'FAILED'")).isEqualTo(1);
        assertThat(scalar("SELECT error_code FROM operations WHERE status = 'FAILED'"))
                .isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(poolRelayer.callsOf("placeBet")).hasSize(5);
    }

    // ── fixtures ─────────────────────────────────────────

    private MockHttpServletRequestBuilder entries(String body, String key) {
        return post("/api/v1/events/{eventId}/entries", eventId)
                .with(user(String.valueOf(bettor.getId()))).with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** 앞에서 {@code n} 종목. 전부 UP · 목표가 11000 · 1000 ANT. */
    private String items(int n) {
        return body(itemList(n));
    }

    private String itemsFrom(int from, int n) {
        List<String> list = new ArrayList<>();
        for (int i = from; i < from + n; i++) {
            list.add(item(CODES.get(i), 1000));
        }
        return body(list);
    }

    private List<String> itemList(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(item(CODES.get(i), 1000));
        }
        return list;
    }

    private String item(String code, int stake) {
        return """
                {"stockCode":"%s","direction":"UP","targetPrice":11000,"stake":%d,\
                "note":"오를 것 같다","noteSalt":"%s","evidencePointIds":[]}\
                """.formatted(code, stake, SALT);
    }

    /** 본문 + 그 본문으로 만든 서명. 서명은 항목마다 한 줄이고 순서는 요청 순서다(§6). */
    private String body(List<String> items) {
        return """
                {"items":[%s],"signature":"%s"}
                """.formatted(String.join(",", items), signatureFor(items));
    }

    /**
     * §6 페이로드를 <b>테스트가 직접 조립한다</b>. 서비스의 조립기를 불러 쓰면 양쪽이 같이 틀려도
     * 초록불이 뜬다 — 프론트가 문자열을 손으로 만드는 것과 같은 조건이어야 한다.
     */
    private String signatureFor(List<String> items) {
        String nonce = nonceStore.issue(bettor.getId(), SignatureScope.EVENT_ENTRY);
        StringBuilder payload = new StringBuilder("antenna:event-entry:v1\neventId=").append(eventId);
        for (String item : items) {
            String code = between(item, "\"stockCode\":\"", "\"");
            String stake = between(item, "\"stake\":", ",");
            payload.append("\n")
                    .append(code)
                    .append("/UP/11000.00/")
                    .append(noteHash())
                    .append("/")
                    .append(stake);
        }
        payload.append("\nchainId=").append(CHAIN_ID).append("\nnonce=").append(nonce);
        return WalletSignatures.sign(payload.toString(), credentials);
    }

    private static String noteHash() {
        return ssafy.a507.backend.domain.prediction.commit.CommitHashes.noteHash("오를 것 같다", SALT);
    }

    private static String between(String source, String from, String to) {
        int start = source.indexOf(from) + from.length();
        return source.substring(start, source.indexOf(to, start));
    }

    /** 기준일은 오늘이다 — {@code latestClose} 가 오늘 종가를 보고 방향 모순을 판정한다. */
    private static LocalDate baseDate() {
        return LocalDate.now();
    }

    private Long insertEvent(Instant sealsAt) {
        return tx.execute(s -> {
            em.createNativeQuery("""
                            INSERT INTO prediction_events
                              (title, status, opens_at, seals_at, base_date, settle_date,
                               min_stake, max_stake, min_stocks, open_tx_hash, created_at)
                            VALUES ('9월 3주 업앤다운', 'OPEN', ?, ?, ?, ?, 500, 2000, 5,
                                    '0xopen', CURRENT_TIMESTAMP)
                            """)
                    .setParameter(1, Instant.now().minus(1, ChronoUnit.HOURS))
                    .setParameter(2, sealsAt)
                    .setParameter(3, baseDate())
                    .setParameter(4, baseDate().plusDays(4))
                    .executeUpdate();
            Long id = ((Number) em.createNativeQuery("SELECT max(id) FROM prediction_events")
                            .getSingleResult())
                    .longValue();
            short idx = 0;
            for (String code : CODES) {
                em.createNativeQuery("""
                                INSERT INTO prediction_event_stocks (event_id, idx, stock_code, base_price)
                                VALUES (?, ?, ?, ?)
                                """)
                        .setParameter(1, id)
                        .setParameter(2, idx)
                        .setParameter(3, code)
                        .setParameter(4, REF_CLOSE)
                        .executeUpdate();
                idx++;
            }
            return id;
        });
    }

    private void insertStocks() {
        tx.executeWithoutResult(s -> {
            for (String code : CODES) {
                em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES (?, ?, true)")
                        .setParameter(1, code)
                        .setParameter(2, code)
                        .executeUpdate();
                em.createNativeQuery("""
                                INSERT INTO daily_quotes (stock_code, trade_date, close, collected_at)
                                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                                """)
                        .setParameter(1, code)
                        .setParameter(2, baseDate())
                        .setParameter(3, REF_CLOSE)
                        .executeUpdate();
            }
            em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES (?, 'LG엔솔', true)")
                    .setParameter(1, OUTSIDER)
                    .executeUpdate();
        });
    }

    /**
     * 이 테스트가 넣은 종목·시세만 지운다. H2 는 컨텍스트끼리 공유되므로({@code jdbc:h2:mem:antenna})
     * 표를 통째로 비우면 같은 컨텍스트의 다른 테스트가 넣어 둔 행까지 날아간다.
     */
    private void deleteStocks() {
        List<String> all = new ArrayList<>(CODES);
        all.add(OUTSIDER);
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM daily_quotes WHERE stock_code IN (:codes)")
                    .setParameter("codes", all)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM stocks WHERE code IN (:codes)")
                    .setParameter("codes", all)
                    .executeUpdate();
        });
    }

    private User insertUser(String nickname, String wallet) {
        User user = tx.execute(s -> {
            User u = User.create();
            u.changeNickname(nickname);
            u.linkWallet(wallet);
            em.persist(u);
            return u;
        });
        userIds.add(user.getId());
        return user;
    }

    private String scalar(String sql) {
        Object v = tx.execute(s -> em.createNativeQuery(sql).getSingleResult());
        return v == null ? null : String.valueOf(v);
    }

    private long count(String sql) {
        return ((Number) tx.execute(s -> em.createNativeQuery(sql).getSingleResult())).longValue();
    }

    private void update(String sql, Object... params) {
        tx.executeWithoutResult(s -> {
            var query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            query.executeUpdate();
        });
    }

    private void stubRedis() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(ops);
        given(ops.get(anyString())).willAnswer(call -> redisStore.get(call.getArgument(0, String.class)));
        willAnswer(call -> {
                    redisStore.put(call.getArgument(0), call.getArgument(1));
                    return null;
                })
                .given(ops)
                .set(anyString(), anyString(), any(Duration.class));
    }
}
