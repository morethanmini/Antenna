package ssafy.a507.backend.domain.monetize.controller;

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
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import ssafy.a507.backend.common.security.SignatureNonceStore;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;
import ssafy.a507.backend.domain.chain.service.FakeTokenRelayer;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;
import ssafy.a507.backend.support.TestNonceStoreConfig;
import ssafy.a507.backend.support.WalletSignatures;

/**
 * POST /api/v1/channels/{userId}/subscriptions — ANT-TOKEN-01.
 *
 * <p>이 엔드포인트가 없는 동안 게이팅 셋(리포트 잠금 · 예측 근거 · 증명)은 "구독자면 연다" 분기를
 * 갖고 있었지만 그 분기에 들어갈 사용자가 존재할 수 없었다. 여기서 지킬 것은 넷이다 —
 * 자기 구독·중복 구독을 막을 것 · 구독료는 <b>서버가 읽고</b> 서명으로 묶을 것 · 접수를 먼저
 * 커밋하고 전송은 트랜잭션 밖에서 할 것 · 전송이 실패하면 구독을 닫아 재시도를 막지 말 것.
 *
 * <p>{@link AdControllerTest} 와 같은 이유로 {@code @Transactional} 롤백을 쓰지 않는다 — 전송이
 * "활성 트랜잭션 안에서는 거부" 가 계약이라 테스트 트랜잭션 안에서 부르면 그 계약이 먼저 터진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestNonceStoreConfig.class, FakeTokenRelayer.Config.class})
@DisplayName("유료 구독 결제 API")
class SubscriptionControllerTest {

    private static final long CHAIN_ID = 31337L;
    private static final String SUBSCRIBER_KEY =
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
    private static final BigInteger FEE = BigInteger.valueOf(10_000);
    private static final BigInteger RICH = BigInteger.valueOf(50_000);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired SignatureNonceStore nonceStore;
    @Autowired FakeTokenRelayer relayer;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired OperationRepository operations;

    @MockitoBean StringRedisTemplate redis;

    private final Map<String, String> redisStore = new HashMap<>();
    private final List<Long> userIds = new ArrayList<>();

    private Credentials credentials;
    private User subscriber;
    private User publisher;

    @BeforeEach
    void setUp() {
        stubRedis();
        relayer.reset();
        // 잔액은 체인에서 읽는다(가용잔액, ANT-TOKEN-11). 넉넉히 심고, 모자란 경우만 테스트가 덮어쓴다.
        relayer.withBalance(RICH);
        credentials = Credentials.create(SUBSCRIBER_KEY);
        subscriber = insertUser("구독자", credentials.getAddress());
        publisher = insertUser("예측가", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8");
        setFee(publisher, FEE, Instant.now().minus(1, ChronoUnit.DAYS));
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            for (String delete : List.of(
                    "DELETE FROM operations WHERE user_id IN (:ids)",
                    "DELETE FROM reports WHERE user_id IN (:ids)",
                    "DELETE FROM subscriptions WHERE subscriber_id IN (:ids) OR publisher_id IN (:ids)",
                    "DELETE FROM publisher_fees WHERE publisher_id IN (:ids)",
                    "DELETE FROM ad_banners WHERE advertiser_id IN (:ids)",
                    "DELETE FROM token_ledger WHERE user_id IN (:ids)",
                    "DELETE FROM users WHERE id IN (:ids)")) {
                em.createNativeQuery(delete).setParameter("ids", userIds).executeUpdate();
            }
        });
        userIds.clear();
    }

    // ── 성공 경로 ────────────────────────────────────────

    @Test
    @DisplayName("구독하면 202 와 operationId · subscriptionId 를 받고 폴링 창구에서 PENDING 으로 보인다")
    void 구독_성공() throws Exception {

        MvcResult result = mockMvc.perform(subscribe("key-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.subscriptionId").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String operationId = jsonValue(result, "operationId");
        mockMvc.perform(get("/api/v1/operations/" + operationId)
                        .with(user(String.valueOf(subscriber.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SUBSCRIBE"))
                .andExpect(jsonPath("$.resource.type").value("SUBSCRIPTION"));
    }

    @Test
    @DisplayName("구독하면 subscribe tx 를 보낸다 — 구독자에서 예측가로, 70:30 분배는 컨트랙트가 한다")
    void 구독하면_subscribe_tx_를_보낸다() throws Exception {

        String operationId = jsonValue(
                mockMvc.perform(subscribe("key-tx")).andExpect(status().isAccepted()).andReturn(),
                "operationId");

        assertThat(relayer.calls()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("subscribe");
            assertThat(c.from()).isEqualToIgnoringCase(credentials.getAddress());
            assertThat(c.amount()).isEqualTo(FEE);
        });
        Operation op = operations.findById(operationId).orElseThrow();
        assertThat(op.getTxHash()).startsWith("0x");
        // 전송 뒤에도 PENDING 이다 — 확정은 인덱서 몫이다.
        assertThat(op.getStatus()).isEqualTo(Operation.Status.PENDING);
    }

    @Test
    @DisplayName("접수한 구독은 결제 시점 가격을 박제한다 — 채널이 값을 올려도 이 구독은 그대로다")
    void 결제_시점_가격을_박제한다() throws Exception {
        mockMvc.perform(subscribe("key-fee")).andExpect(status().isAccepted());

        assertThat(mine()).singleElement().satisfies(s -> {
            assertThat(s.getFee()).isEqualTo(FEE);
            assertThat(s.getStatus()).isEqualTo(Subscription.Status.PENDING);
            // 기간은 확정 시각 기준이라 접수 때는 비어 있다.
            assertThat(s.getStartedAt()).isNull();
            assertThat(s.getExpiresAt()).isNull();
            assertThat(s.isAutoRenew()).isTrue();
        });
    }

    // ── 거절 ────────────────────────────────────────────

    @Test
    @DisplayName("자기 채널은 구독할 수 없다 — 409 SELF_SUBSCRIBE")
    void 자기_구독은_409() throws Exception {
        setFee(subscriber, FEE, Instant.now().minus(1, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/channels/{userId}/subscriptions", subscriber.getId())
                        .with(user(String.valueOf(subscriber.getId()))).with(csrf())
                        .header("Idempotency-Key", "key-self")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(subscriber.getId(), FEE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_SUBSCRIBE"));

        assertThat(mine()).isEmpty();
    }

    @Test
    @DisplayName("이미 구독 중이면 409 — PENDING 도 막는다. 확정 전에 또 누르면 두 번 결제된다")
    void 중복_구독은_409() throws Exception {
        mockMvc.perform(subscribe("key-first")).andExpect(status().isAccepted());

        mockMvc.perform(subscribe("key-second"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_SUBSCRIBED"));

        assertThat(mine()).hasSize(1);
        assertThat(relayer.calls()).hasSize(1);
    }

    @Test
    @DisplayName("만료된 구독은 재구독을 막지 않는다 — 새 행이 생긴다")
    void 만료_뒤에는_재구독할_수_있다() throws Exception {
        expiredSubscription();

        mockMvc.perform(subscribe("key-again")).andExpect(status().isAccepted());

        assertThat(mine()).hasSize(2);
    }

    @Test
    @DisplayName("구독료를 안 정한 채널은 409 FEE_NOT_SET — 0 원으로 열어 주지 않는다")
    void 구독료_미설정은_409() throws Exception {
        User noFee = insertUser("가격없는채널", "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC");

        mockMvc.perform(post("/api/v1/channels/{userId}/subscriptions", noFee.getId())
                        .with(user(String.valueOf(subscriber.getId()))).with(csrf())
                        .header("Idempotency-Key", "key-nofee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(noFee.getId(), FEE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_NOT_SET"));
    }

    @Test
    @DisplayName("가용잔액이 모자라면 409 — 기준은 원장이 아니라 체인이다(ANT-TOKEN-11). 구독도 작업도 만들지 않는다")
    void 잔액_부족은_409() throws Exception {
        relayer.withBalance(BigInteger.ONE);

        mockMvc.perform(subscribe("key-poor"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(mine()).isEmpty();
        assertThat(relayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("없는 채널은 404 USER_NOT_FOUND")
    void 없는_채널은_404() throws Exception {

        mockMvc.perform(post("/api/v1/channels/{userId}/subscriptions", 999_999L)
                        .with(user(String.valueOf(subscriber.getId()))).with(csrf())
                        .header("Idempotency-Key", "key-nouser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(999_999L, FEE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ── 가용잔액 홀드 (ANT-TOKEN-11) ──────────────────────────

    @Test
    @DisplayName("광고 심사 대기가 구독 가용잔액을 묶는다 — 심사 중인 게재료로 구독할 수 없다")
    void 광고_심사_대기가_구독_잔액을_묶는다() throws Exception {
        // 체인 잔액 50,000 인데 심사 대기 광고가 45,000 을 잡고 있다 → 가용 5,000 < 구독료 10,000
        insertPendingAd(subscriber, BigInteger.valueOf(45_000));

        mockMvc.perform(subscribe("key-held"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(mine()).isEmpty();
        assertThat(relayer.calls()).isEmpty();
    }

    // ── 체인 실패 · 서명 ─────────────────────────────────

    @Test
    @DisplayName("체인 잔액이 모자라면 409 — 구독을 EXPIRED 로 닫아 재시도를 막지 않는다")
    void 체인_잔액이_모자라면_구독을_닫는다() throws Exception {
        relayer.thenInsufficientBalance();

        mockMvc.perform(subscribe("key-chain-poor"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(mine()).singleElement()
                .extracting(Subscription::getStatus).isEqualTo(Subscription.Status.EXPIRED);
        // 닫혔으니 다시 시도할 수 있다 — 안 닫으면 PENDING 이 남아 중복 검사에 걸린다.
        mockMvc.perform(subscribe("key-chain-retry")).andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("체인이 꺼져 있으면 503 — 구독도 작업도 만들지 않고 nonce 도 태우지 않는다")
    void 체인이_꺼져_있으면_503() throws Exception {
        relayer.disable();

        mockMvc.perform(subscribe("key-off"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAIN_UNAVAILABLE"));

        assertThat(mine()).isEmpty();
        assertThat(operationsOfSubscriber()).isEmpty();
        assertThat(relayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("서명이 채널이 정한 값과 다르면 거절한다 — 화면에서 본 가격과 결제되는 가격을 서명으로 묶는다")
    void 다른_fee_로_서명하면_거절() throws Exception {

        mockMvc.perform(post("/api/v1/channels/{userId}/subscriptions", publisher.getId())
                        .with(user(String.valueOf(subscriber.getId()))).with(csrf())
                        .header("Idempotency-Key", "key-wrongfee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(publisher.getId(), BigInteger.valueOf(1))))
                .andExpect(status().is4xxClientError());

        assertThat(mine()).isEmpty();
        assertThat(relayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("같은 멱등키로 서명만 새로 받아 다시 보내면 접수를 되풀이하지 않고 같은 202 를 준다")
    void 멱등키가_같으면_한_번만_접수한다() throws Exception {
        // 재시도는 본문을 다시 만든다 — nonce 가 새로 발급돼 서명이 바뀐다(SignatureGuard 가
        // 첫 시도의 nonce 를 태워서 같은 서명을 다시 보낼 길이 없다). 그래도 같은 키·같은 채널이면
        // 재생이어야 한다. 전에는 서명이 해시에 들어가 이 경우가 409 였다(2026-09-19 수정).
        String first = jsonValue(
                mockMvc.perform(send(signedBody(publisher.getId(), FEE), "key-same"))
                        .andExpect(status().isAccepted()).andReturn(),
                "operationId");
        String again = jsonValue(
                mockMvc.perform(send(signedBody(publisher.getId(), FEE), "key-same"))
                        .andExpect(status().isAccepted()).andReturn(),
                "operationId");

        assertThat(again).isEqualTo(first);
        assertThat(mine()).hasSize(1);
        // 전송도 한 번만 나간다 — 두 번 나가면 같은 채널에 두 번 결제된다.
        assertThat(relayer.calls()).hasSize(1);
    }

    @Test
    @DisplayName("같은 키로 다른 채널을 찍으면 409 IDEMPOTENCY_KEY_REUSED — 키를 돌려 쓰며 대상만 바꾸는 것을 막는다")
    void 같은_키_다른_채널은_409() throws Exception {
        User other = insertUser("다른 예측가", "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC");
        setFee(other, FEE, Instant.now().minus(1, ChronoUnit.DAYS));

        mockMvc.perform(send(publisher.getId(), signedBody(publisher.getId(), FEE), "key-reuse"))
                .andExpect(status().isAccepted());
        mockMvc.perform(send(other.getId(), signedBody(other.getId(), FEE), "key-reuse"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(mine()).hasSize(1);
        assertThat(relayer.calls()).hasSize(1);
    }

    // ── 게이팅 (이 엔드포인트의 존재 이유) ────────────────

    @Test
    @DisplayName("PENDING 동안은 근거가 안 열린다 (AC) — 결제가 체인에서 확정돼야 구독이다")
    void PENDING_동안은_잠금이_유지된다() throws Exception {
        long reportId = insertLockedReport();

        mockMvc.perform(subscribe("key-gate-pending")).andExpect(status().isAccepted());

        // 접수는 됐지만 인덱서가 확정하기 전이다. 잠금이 풀리면 결제 없이 읽는 구멍이 된다.
        assertThat(mine()).singleElement()
                .extracting(Subscription::getStatus).isEqualTo(Subscription.Status.PENDING);
        mockMvc.perform(get("/api/v1/reports/{id}", reportId)
                        .with(user(String.valueOf(subscriber.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    @DisplayName("인덱서가 ACTIVE 로 올리면 잠금이 풀린다 — 구독의 값이 실제로 생기는 지점")
    void 확정되면_근거가_열린다() throws Exception {
        long reportId = insertLockedReport();
        mockMvc.perform(subscribe("key-gate-active")).andExpect(status().isAccepted());

        // 인덱서(TokenIndexService)가 Subscribed 이벤트를 보고 하는 일과 같다.
        tx.executeWithoutResult(s -> subscriptions
                .findById(mine().get(0).getId())
                .ifPresent(sub -> sub.activate("0x" + "1".repeat(64), Instant.now())));

        mockMvc.perform(get("/api/v1/reports/{id}", reportId)
                        .with(user(String.valueOf(subscriber.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.body").isNotEmpty());
    }

    // ── fixtures ─────────────────────────────────────────

    /** 구독자 전용 리포트. 미구독자에게는 본문이 가지 않는다 */
    private long insertLockedReport() {
        return tx.execute(s -> {
            em.createNativeQuery("""
                            INSERT INTO reports (user_id, title, body, is_public, view_count, created_at)
                            VALUES (?, '구독자 전용', '본문 첫 줄\n둘째 줄\n셋째 줄\n넷째 줄', false, 0, CURRENT_TIMESTAMP)
                            """)
                    .setParameter(1, publisher.getId())
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT max(id) FROM reports").getSingleResult()).longValue();
        });
    }

    private MockHttpServletRequestBuilder subscribe(String key) {
        return send(signedBody(publisher.getId(), FEE), key);
    }

    private MockHttpServletRequestBuilder send(String body, String key) {
        return send(publisher.getId(), body, key);
    }

    private MockHttpServletRequestBuilder send(Long publisherId, String body, String key) {
        return post("/api/v1/channels/{userId}/subscriptions", publisherId)
                .with(user(String.valueOf(subscriber.getId()))).with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** 서버가 조립하는 것과 같은 문자열에 서명한다 — 다르면 복원 주소가 어긋나 거절된다. */
    private String signedBody(Long publisherId, BigInteger fee) {
        String nonce = nonceStore.issue(subscriber.getId(), SignatureScope.SUBSCRIBE);
        String payload = "antenna:subscribe:v1\n"
                + "publisherId=" + publisherId + "\n"
                + "fee=" + fee + "\n"
                + "operatorAuthorize=true\n"
                + "chainId=" + CHAIN_ID + "\n"
                + "nonce=" + nonce;
        return """
                {"signature":"%s"}
                """.formatted(WalletSignatures.sign(payload, credentials));
    }

    private List<Subscription> mine() {
        return tx.execute(s -> em.createQuery(
                        "select s from Subscription s where s.subscriber.id = :id order by s.id",
                        Subscription.class)
                .setParameter("id", subscriber.getId())
                .getResultList());
    }

    private List<Operation> operationsOfSubscriber() {
        return tx.execute(s -> em.createQuery(
                        "select o from Operation o where o.user.id = :id", Operation.class)
                .setParameter("id", subscriber.getId())
                .getResultList());
    }

    /** 만료된 이전 구독. 재구독이 막히지 않아야 한다 */
    private void expiredSubscription() {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, started_at, expires_at,
                           auto_renew, created_at, updated_at)
                        VALUES (?, ?, ?, 'EXPIRED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriber.getId())
                .setParameter(2, publisher.getId())
                .setParameter(3, FEE)
                .executeUpdate());
    }

    private void setFee(User publisher, BigInteger fee, Instant effectiveFrom) {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                        INSERT INTO publisher_fees (publisher_id, fee, effective_from, created_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, publisher.getId())
                .setParameter(2, fee)
                .setParameter(3, effectiveFrom)
                .executeUpdate());
    }

    /** 심사 대기 광고. 가용잔액이 이 게재료를 뺀다(ANT-ADMIN-03 승인제에서는 승인 전까지 돈이 안 나가고 묶인다). */
    private void insertPendingAd(User advertiser, BigInteger price) {
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> em.persist(AdBanner.request(
                em.getReference(User.class, advertiser.getId()),
                "/api/v1/uploads/x",
                "https://ad.example.com",
                now,
                now.plus(7, ChronoUnit.DAYS),
                price)));
    }

    private User insertUser(String nickname, String wallet) {
        User user = tx.execute(s -> {
            User u = User.create();
            u.changeNickname(nickname);
            if (wallet != null) {
                u.linkWallet(wallet);
            }
            em.persist(u);
            return u;
        });
        userIds.add(user.getId());
        return user;
    }

    private String jsonValue(MvcResult result, String field) throws Exception {
        return String.valueOf(
                (Object) com.jayway.jsonpath.JsonPath.read(
                        result.getResponse().getContentAsString(), "$." + field));
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
