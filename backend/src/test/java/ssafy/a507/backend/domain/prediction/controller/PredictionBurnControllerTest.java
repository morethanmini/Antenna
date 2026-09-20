package ssafy.a507.backend.domain.prediction.controller;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.common.security.SignatureNonceStore;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;
import ssafy.a507.backend.domain.chain.service.FakeTokenRelayer;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.prediction.dto.PredictionCreateRequest;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.support.TestNonceStoreConfig;
import ssafy.a507.backend.support.WalletSignatures;
import tools.jackson.databind.json.JsonMapper;

/**
 * ANT-PRED-10 — 슬롯 초과 소각 202 경로. 체인은 {@link FakeTokenRelayer}.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다 — 소각 전송은 트랜잭션 밖에서만 되고(TokenOperationService 계약),
 * 테스트 트랜잭션 안이면 {@code IllegalStateException} 이다. 픽스처는 {@link TransactionTemplate} 으로 커밋하고
 * {@code @AfterEach} 가 지운다(AdControllerTest 와 같다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestNonceStoreConfig.class, FakeTokenRelayer.Config.class})
@DisplayName("예측 등록 — 슬롯 초과 소각(202)")
class PredictionBurnControllerTest {

    private static final String URL = "/api/v1/predictions";
    private static final String PRIVATE_KEY = "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
    private static final String STOCK = "999910";
    private static final String NOTE_SALT = "0123456789abcdef".repeat(4);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final BigInteger SLOT_OVER = BigInteger.valueOf(500);
    private static final BigInteger RICH = BigInteger.valueOf(30_000);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired SignatureNonceStore nonceStore;
    @Autowired SignatureGuard signatureGuard;
    @Autowired FakeTokenRelayer relayer;
    @Autowired OperationRepository operations;

    @MockitoBean StringRedisTemplate redis;
    private final Map<String, String> redisStore = new HashMap<>();

    private Credentials wallet;
    private long userId;

    @BeforeEach
    void setUp() {
        stubRedis();
        relayer.reset();
        wallet = Credentials.create(PRIVATE_KEY);
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("INSERT INTO users (wallet_address, role, status, created_at, updated_at)"
                            + " VALUES (?, 'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
                    .setParameter(1, wallet.getAddress().toLowerCase()).executeUpdate();
            userId = ((Number) em.createNativeQuery("SELECT id FROM users WHERE wallet_address = ?")
                    .setParameter(1, wallet.getAddress().toLowerCase()).getSingleResult()).longValue();
            em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES (?, '소각테스트', true)")
                    .setParameter(1, STOCK).executeUpdate();
            em.createNativeQuery("INSERT INTO daily_quotes (stock_code, trade_date, close, collected_at)"
                            + " VALUES (?, ?, 80000, CURRENT_TIMESTAMP)")
                    .setParameter(1, STOCK).setParameter(2, LocalDate.now(KST).minusDays(1)).executeUpdate();
        });
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            for (String sql : List.of(
                    "DELETE FROM notifications WHERE user_id = :u",
                    "DELETE FROM operations WHERE user_id = :u",
                    "DELETE FROM ad_banners WHERE advertiser_id = :u",
                    "DELETE FROM prediction_commits WHERE prediction_id IN (SELECT id FROM predictions WHERE user_id = :u)",
                    "DELETE FROM prediction_evidences WHERE prediction_id IN (SELECT id FROM predictions WHERE user_id = :u)",
                    "DELETE FROM prediction_notes WHERE prediction_id IN (SELECT id FROM predictions WHERE user_id = :u)",
                    "DELETE FROM predictions WHERE user_id = :u",
                    "DELETE FROM token_ledger WHERE user_id = :u",
                    "DELETE FROM users WHERE id = :u")) {
                em.createNativeQuery(sql).setParameter("u", userId).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM daily_quotes WHERE stock_code = ?").setParameter(1, STOCK).executeUpdate();
            em.createNativeQuery("DELETE FROM stocks WHERE code = ?").setParameter(1, STOCK).executeUpdate();
        });
    }

    @Test
    @DisplayName("무료 3건 뒤 4번째는 202 — 예측은 저장되고 소각 tx(500 ANT · SLOT_OVER)가 나가며 작업은 PENDING")
    void 슬롯_초과_202() throws Exception {
        credit(RICH);
        useFreeSlots();

        ResultActions accepted = perform(signed(request("85000")), UUID.randomUUID().toString())
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/operations/")))
                .andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.predictionId").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"));
        String operationId = json(accepted, "operationId");
        long predictionId = Long.parseLong(json(accepted, "predictionId"));

        assertThat(relayer.calls()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("burn");
            assertThat(c.from()).isEqualToIgnoringCase(wallet.getAddress());
            assertThat(c.amount()).isEqualTo(SLOT_OVER);
            assertThat(c.reason()).isEqualTo(TokenReason.SLOT_OVER);
        });
        Operation op = operations.findById(operationId).orElseThrow();
        assertThat(op.getKind()).isEqualTo(Operation.Kind.PREDICTION_BURN);
        assertThat(op.getResourceType()).isEqualTo(Operation.ResourceType.PREDICTION);
        assertThat(op.getResourceId()).isEqualTo(predictionId);
        assertThat(op.getStatus()).isEqualTo(Operation.Status.PENDING);
        assertThat(op.getTxHash()).startsWith("0x");
        assertThat(count("SELECT count(*) FROM predictions WHERE user_id = " + userId)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM prediction_commits WHERE prediction_id = " + predictionId)).isEqualTo(1);

        // 유료 건은 무료 슬롯 수에 안 센다 — 다음 것도 소각이다
        mockMvc.perform(get(URL + "/slots").with(user(String.valueOf(userId))))
                .andExpect(jsonPath("$.used").value(3))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.overCost").value("500"));
        mockMvc.perform(get("/api/v1/operations/" + operationId).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("PREDICTION_BURN"))
                .andExpect(jsonPath("$.resource.type").value("PREDICTION"))
                .andExpect(jsonPath("$.resource.id").value(predictionId));
    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 다시 보내면 같은 202 — 소각 tx 는 한 번만 나간다")
    void 슬롯_초과_멱등() throws Exception {
        credit(RICH);
        useFreeSlots();
        PredictionCreateRequest req = signed(request("85000"));
        String key = UUID.randomUUID().toString();

        String first = json(perform(req, key).andExpect(status().isAccepted()), "operationId");
        String again = json(perform(req, key).andExpect(status().isAccepted()), "operationId");

        assertThat(again).isEqualTo(first);
        assertThat(relayer.calls()).hasSize(1);
    }

    @Test
    @DisplayName("가용잔액이 소각액보다 적으면 409 INSUFFICIENT_BALANCE — 서명 앞에서 끊겨 nonce 가 남고 예측도 없다")
    void 가용잔액_부족() throws Exception {
        credit(BigInteger.valueOf(499));
        useFreeSlots();

        perform(signed(request("85000")), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(count("SELECT count(*) FROM predictions WHERE user_id = " + userId)).isEqualTo(3);
        assertThat(relayer.calls()).isEmpty();
        assertThat(nonceStore.consume(userId, SignatureScope.PREDICTION)).isNotNull();
    }

    @Test
    @DisplayName("광고 심사로 묶인 돈으로는 소각이 안 된다 — 체인에 10,000 이 있어도 PENDING 광고 9,800 을 빼면 200 이다")
    void 광고_심사로_묶인_돈() throws Exception {
        credit(BigInteger.valueOf(10_000));
        useFreeSlots();
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> em.persist(AdBanner.request(
                em.getReference(User.class, userId), "/x", "https://a.example.com",
                now, now.plus(7, ChronoUnit.DAYS), BigInteger.valueOf(9_800))));

        perform(signed(request("85000")), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(count("SELECT count(*) FROM predictions WHERE user_id = " + userId)).isEqualTo(3);
        assertThat(relayer.calls()).isEmpty();
        assertThat(nonceStore.consume(userId, SignatureScope.PREDICTION)).isNotNull();
    }

    @Test
    @DisplayName("원장은 더 이상 보지 않는다 — 원장에 30,000 이 찍혀 있어도 체인 잔액이 0 이면 409")
    void 원장이_아니라_체인을_본다() throws Exception {
        // 인덱서가 늦어 원장이 앞서 있는 상황. 전에는 이 검사를 통과하고 서명까지 받은 뒤 체인 시뮬레이션에서 막혔다.
        tx.executeWithoutResult(s -> em.createNativeQuery(
                        "INSERT INTO token_ledger (user_id, delta, reason, created_at) VALUES (?, ?, 'TEST', CURRENT_TIMESTAMP)")
                .setParameter(1, userId).setParameter(2, RICH).executeUpdate());
        useFreeSlots();

        perform(signed(request("85000")), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(relayer.calls()).isEmpty();
        assertThat(nonceStore.consume(userId, SignatureScope.PREDICTION)).isNotNull();
    }

    @Test
    @DisplayName("지갑이 없는 채로 슬롯을 넘기면 400 WALLET_NOT_LINKED — 전에는 원장이 0 이라 '잔액 부족' 으로 나갔다")
    void 지갑_미연동() throws Exception {
        credit(RICH);
        useFreeSlots();
        tx.executeWithoutResult(s -> em.createNativeQuery("UPDATE users SET wallet_address = NULL WHERE id = ?")
                .setParameter(1, userId).executeUpdate());

        perform(signed(request("85000")), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_LINKED"));

        assertThat(relayer.calls()).isEmpty();
        assertThat(nonceStore.consume(userId, SignatureScope.PREDICTION)).isNotNull();
    }

    @Test
    @DisplayName("체인 시뮬레이션이 잔액 부족으로 거부하면 409 — 예측은 만들어지지 않고(append-only 라 못 지운다) 작업만 FAILED")
    void 체인_잔액_부족이면_예측이_없다() throws Exception {
        credit(RICH);
        useFreeSlots();
        relayer.thenInsufficientBalance();

        perform(signed(request("85000")), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(count("SELECT count(*) FROM predictions WHERE user_id = " + userId)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM prediction_commits c JOIN predictions p ON p.id = c.prediction_id WHERE p.user_id = " + userId)).isEqualTo(3);
        assertThat(operations.findAll()).filteredOn(o -> o.getUser().getId().equals(userId)).singleElement()
                .satisfies(o -> {
                    assertThat(o.getStatus()).isEqualTo(Operation.Status.FAILED);
                    assertThat(o.getResourceId()).isNull();
                    assertThat(o.getResourceType()).isNull();
                });
        // nonce 는 접수에서 태워졌다 — 다시 서명해야 한다(구독·광고와 같다)
        assertThat(nonceStore.consume(userId, SignatureScope.PREDICTION)).isNull();
    }

    @Test
    @DisplayName("체인이 꺼져 있으면 503 CHAIN_UNAVAILABLE — 아무것도 쓰지 않고 nonce 도 남는다")
    void 체인_꺼짐() throws Exception {
        credit(RICH);
        useFreeSlots();
        relayer.disable();

        perform(signed(request("85000")), UUID.randomUUID().toString())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAIN_UNAVAILABLE"));

        assertThat(count("SELECT count(*) FROM predictions WHERE user_id = " + userId)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM operations WHERE user_id = " + userId)).isZero();
        assertThat(nonceStore.consume(userId, SignatureScope.PREDICTION)).isNotNull();
    }

    // ── 픽스처 ───────────────────────────────────────────────────

    private void useFreeSlots() throws Exception {
        for (int i = 0; i < 3; i++) {
            perform(signed(request("8100" + i)), UUID.randomUUID().toString()).andExpect(status().isCreated());
        }
        assertThat(relayer.calls()).isEmpty();
    }

    private PredictionCreateRequest request(String price) {
        return new PredictionCreateRequest(
                STOCK, Prediction.Direction.UP, new BigDecimal(price), (short) 7,
                "실적 기대", NOTE_SALT, List.of(), "0x" + "00".repeat(65));
    }

    private PredictionCreateRequest signed(PredictionCreateRequest r) {
        String nonce = nonceStore.issue(userId, SignatureScope.PREDICTION);
        String signature = WalletSignatures.sign(r.signingPayload(nonce, signatureGuard.chainId()), wallet);
        return new PredictionCreateRequest(
                r.stockCode(), r.direction(), r.targetPrice(), r.horizon(), r.note(), r.noteSalt(),
                r.evidencePointIds(), signature);
    }

    private ResultActions perform(PredictionCreateRequest req, String idempotencyKey) throws Exception {
        return mockMvc.perform(post(URL)
                .with(user(String.valueOf(userId)))
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(req)));
    }

    private static String json(ResultActions actions, String field) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get(field).asString();
    }

    /** 체인 잔액을 심는다. 소각 판단은 가용잔액(체인 잔액 − PENDING 홀드)을 본다(ANT-TOKEN-13) — 원장이 아니다. */
    private void credit(BigInteger amount) {
        relayer.withBalance(amount);
    }

    private long count(String sql) {
        return tx.execute(s -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    /** Redis 대신 맵 — 멱등 저장소가 쓰는 get·set(TTL) 만. */
    private void stubRedis() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(ops);
        given(ops.get(anyString())).willAnswer(call -> redisStore.get(call.getArgument(0, String.class)));
        willAnswer(call -> { redisStore.put(call.getArgument(0), call.getArgument(1)); return null; })
                .given(ops).set(anyString(), anyString(), any(Duration.class));
    }
}
