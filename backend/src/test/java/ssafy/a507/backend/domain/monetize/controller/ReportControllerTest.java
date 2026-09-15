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
import org.web3j.crypto.Credentials;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
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
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.common.security.SignatureNonceStore;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.domain.monetize.dto.ReportCreateRequest;
import ssafy.a507.backend.support.TestNonceStoreConfig;
import ssafy.a507.backend.support.WalletSignatures;

/**
 * ANT-COMMUNITY-01 — 리포트 발행·열람의 AC 검증.
 *
 * <p>요청마다 {@code user(...)} 로 인증 주체를 붙인다. 주체의 이름이 곧 users.id 다
 * ({@code SecurityContextCurrentUserProvider}).
 *
 * <p>Redis 는 띄우지 않는다 — 이 프로젝트 테스트는 컨테이너 없이 도는 것이 전제다.
 * StringRedisTemplate 을 맵 하나로 대신해 멱등성 동작까지 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// nonce 는 Redis 가 아니라 인메모리로. Lettuce 는 지연 연결이라 컨텍스트는 뜨고 연산에서 터진다.
@Import(TestNonceStoreConfig.class)
class ReportControllerTest {

    private static final String URL = "/api/v1/reports";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EntityManager em;

    @Autowired
    SignatureNonceStore nonceStore;

    @Autowired
    SignatureGuard signatureGuard;

    @MockitoBean
    StringRedisTemplate redis;

    /* 발행이 지갑 서명을 받는다(ANT-CHAIN-16). 지갑 역할은 web3j Credentials 가 한다 —
       PredictionControllerTest 와 같은 방식이고 키도 같은 것을 쓴다. */
    private static final String PRIVATE_KEY =
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";

    /** 둘째 지갑. {@code users.wallet_address} 가 UQ 라 두 계정이 같은 주소를 쓸 수 없다. */
    private static final String OTHER_PRIVATE_KEY =
            "0x8d5366123cb560bb606379f90a0bfd4769eecc0557f1b362dcae9012b548b1e5";

    /** 형식만 맞는 가짜 서명. @Valid 가 서명 검증보다 먼저 도는 400 테스트에 쓴다. */
    private static final String DUMMY_SIGNATURE = "0x" + "11".repeat(65);

    private Credentials wallet;

    private final Map<String, String> redisStore = new HashMap<>();

    private Long authorId;
    private Long viewerId;

    @BeforeEach
    void setUp() {
        stubRedis();
        wallet = Credentials.create(PRIVATE_KEY);
        authorId = insertUser("발행자");
        viewerId = insertUser("열람자");
        // 서명자 주소와 대조할 연동 지갑. 없으면 발행이 WALLET_NOT_LINKED 로 막힌다.
        linkWallet(authorId, wallet.getAddress());
        flush();
    }

    /** Redis 대신 맵. get·set(TTL) 두 연산만 쓰므로 그 둘만 흉내낸다. */
    private void stubRedis() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(ops);
        given(ops.get(anyString()))
                .willAnswer(call -> redisStore.get(call.getArgument(0, String.class)));
        willAnswer(call -> {
                    redisStore.put(call.getArgument(0), call.getArgument(1));
                    return null;
                })
                .given(ops)
                .set(anyString(), anyString(), any(Duration.class));
    }

    // ── POST /reports ───────────────────────────────────────

    @Test
    @DisplayName("리포트를 발행하면 201 과 id 를 받는다")
    void 발행_성공() throws Exception {
        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody("반도체 리포트", "본문", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    @DisplayName("같은 Idempotency-Key 재요청은 리포트를 하나만 만든다")
    void 발행_멱등() throws Exception {
        /* 멱등 재요청은 같은 본문이어야 한다 — 서명까지 같은 문자열을 두 번 보낸다.
           nonce 는 첫 요청에서 소비되지만 두 번째는 컨트롤러가 최초 결과를 돌려주므로
           서비스가 다시 불리지 않는다(서명 검증도 안 탄다). 그것까지 이 테스트가 고정한다. */
        String body = signedBody("멱등 리포트", "본문", true);

        String first = mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-same")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String second = mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-same")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(countReports("멱등 리포트")).isEqualTo(1);
    }

    @Test
    @DisplayName("Idempotency-Key 가 없으면 400 IDEMPOTENCY_KEY_REQUIRED")
    void 발행_키_누락() throws Exception {
        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("제목", "본문", "true", DUMMY_SIGNATURE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("visibility 를 빼면 400 — 공개 여부를 기본값으로 정하지 않는다")
    void 발행_공개여부_누락() throws Exception {
        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"body\":\"본문\",\"signature\":\"" + DUMMY_SIGNATURE + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("visibility"));
    }

    @Test
    @DisplayName("제목이 100자를 넘으면 400")
    void 발행_제목_초과() throws Exception {
        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("가".repeat(101), "본문", "true", DUMMY_SIGNATURE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("title"));
    }

    @Test
    @DisplayName("발행하면 ACTIVE 구독자에게 REPORT_PUBLISHED 알림이 쌓인다")
    void 발행_알림() throws Exception {
        insertSubscription(viewerId, authorId, "ACTIVE");
        flush();

        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-noti")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody("알림 리포트", "본문", false)))
                .andExpect(status().isCreated());
        flush();

        assertThat(countNotifications(viewerId, "REPORT_PUBLISHED")).isEqualTo(1);
    }

    @Test
    @DisplayName("PENDING 구독자에게는 발행 알림을 보내지 않는다 — 아직 열람 권한이 아니다")
    void 발행_알림_PENDING_제외() throws Exception {
        insertSubscription(viewerId, authorId, "PENDING");
        flush();

        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-noti-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody("알림 리포트2", "본문", false)))
                .andExpect(status().isCreated());
        flush();

        assertThat(countNotifications(viewerId, "REPORT_PUBLISHED")).isZero();
    }

    // ── 발행 서명 (ANT-CHAIN-16) ─────────────────────────────

    @Test
    @DisplayName("서명한 발행은 signature · signer_address 가 함께 저장된다 — 작성자 증명은 이 칸이 한다")
    void 발행_서명_저장() throws Exception {
        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-sig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody("서명 리포트", "본문", true)))
                .andExpect(status().isCreated());
        flush();

        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT signature, signer_address FROM reports WHERE title = '서명 리포트'")
                .getSingleResult();
        assertThat((String) row[0]).matches("^0x[0-9a-fA-F]{130}$");
        // 저장·비교 형식은 소문자 0x + 40자 하나뿐이다(SignatureGuard).
        assertThat((String) row[1]).isEqualTo(wallet.getAddress().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    @DisplayName("봉인 규격은 그대로다 — 서명이 reportHash 재료가 아니라서 옛 리포트도 같은 식으로 검증된다")
    void 발행_봉인은_그대로() throws Exception {
        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody("봉인 리포트", "본문", true)))
                .andExpect(status().isCreated());
        flush();

        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT body_hash, report_hash, salt FROM reports WHERE title = '봉인 리포트'")
                .getSingleResult();
        assertThat((String) row[0]).matches("^0x[0-9a-f]{64}$");
        assertThat((String) row[1]).matches("^0x[0-9a-f]{64}$");
        assertThat((String) row[2]).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("남의 지갑으로 서명하면 401 SIGNER_MISMATCH — 발행되지 않는다")
    void 발행_서명자_불일치() throws Exception {
        Credentials other = Credentials.create(OTHER_PRIVATE_KEY);

        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-mismatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(authorId, other, "남의 서명", "본문", true)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIGNER_MISMATCH"));
        flush();

        // 검증이 저장보다 먼저다 — 실패한 발행이 행으로 남으면 지울 API 가 없다.
        assertThat(countReports("남의 서명")).isZero();
    }

    @Test
    @DisplayName("지갑 미연동 회원은 400 WALLET_NOT_LINKED — nonce 를 태우기 전에 거른다")
    void 발행_지갑_미연동() throws Exception {
        String nonce = nonceStore.issue(viewerId, SignatureScope.REPORT_PUBLISH);
        ReportCreateRequest unsigned =
                new ReportCreateRequest("지갑없음", "본문", true, DUMMY_SIGNATURE);
        String signature = WalletSignatures.sign(
                unsigned.signingPayload(nonce, signatureGuard.chainId()), wallet);

        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(viewerId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-nowallet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("지갑없음", "본문", "true", signature)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_LINKED"));

        // 성공할 수 없는 요청에 nonce 를 쓰지 않는다 — 그대로 남아 있어야 한다.
        assertThat(nonceStore.consume(viewerId, SignatureScope.REPORT_PUBLISH)).isEqualTo(nonce);
    }

    @Test
    @DisplayName("nonce 없이 보내면 400 NONCE_NOT_FOUND — 같은 서명을 두 번 쓸 수 없다")
    void 발행_nonce_없음() throws Exception {
        nonceStore.consume(authorId, SignatureScope.REPORT_PUBLISH); // 남은 것이 있어도 비운다
        ReportCreateRequest unsigned =
                new ReportCreateRequest("nonce 없음", "본문", true, DUMMY_SIGNATURE);
        String signature = WalletSignatures.sign(
                unsigned.signingPayload("stale", signatureGuard.chainId()), wallet);

        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-nononce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("nonce 없음", "본문", "true", signature)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NONCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("서명이 없으면 400 — 형식 검증이 서명 검증보다 먼저다")
    void 발행_서명_누락() throws Exception {
        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-nosig")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"서명없음","body":"본문","visibility":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("signature"));
    }

    // ── GET /reports (통합 피드) ──────────────────────────────

    @Test
    @DisplayName("통합 피드는 최신순이고 제목·작성자·열람 수를 공개한다")
    void 피드_최신순() throws Exception {
        insertReport(authorId, "먼저 쓴 것", false, 0);
        insertReport(authorId, "나중에 쓴 것", true, 7);
        flush();

        mockMvc.perform(get(URL).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("나중에 쓴 것"))
                .andExpect(jsonPath("$.items[0].author.nickname").value("발행자"))
                .andExpect(jsonPath("$.items[0].viewCount").value(7))
                .andExpect(jsonPath("$.items[1].title").value("먼저 쓴 것"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("비공개 리포트는 미구독자에게 locked=true 로 내려간다 — 제목은 그대로 공개")
    void 피드_잠금() throws Exception {
        insertReport(authorId, "구독자 전용", false, 0);
        flush();

        mockMvc.perform(get(URL).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].locked").value(true))
                .andExpect(jsonPath("$.items[0].title").value("구독자 전용"));
    }

    @Test
    @DisplayName("ACTIVE 구독자에게는 비공개 리포트도 잠기지 않는다")
    void 피드_구독자는_열린다() throws Exception {
        insertReport(authorId, "구독자 전용", false, 0);
        insertSubscription(viewerId, authorId, "ACTIVE");
        flush();

        mockMvc.perform(get(URL).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].locked").value(false));
    }

    @Test
    @DisplayName("본인 리포트는 비공개여도 잠기지 않는다")
    void 피드_본인은_열린다() throws Exception {
        insertReport(authorId, "내 비공개", false, 0);
        flush();

        mockMvc.perform(get(URL).with(user(String.valueOf(authorId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].locked").value(false));
    }

    @Test
    @DisplayName("scope=SUBSCRIBED 는 구독 중인 채널의 리포트만 돌려준다")
    void 피드_구독_탭() throws Exception {
        Long other = insertUser("남");
        insertReport(authorId, "구독한 채널 글", true, 0);
        insertReport(other, "안 구독한 채널 글", true, 0);
        insertSubscription(viewerId, authorId, "ACTIVE");
        flush();

        mockMvc.perform(get(URL).param("scope", "SUBSCRIBED").with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("구독한 채널 글"));
    }

    @Test
    @DisplayName("sort=POPULAR 는 열람 수 내림차순이고 커서가 (viewCount:id) 복합값이다")
    void 피드_인기순_커서() throws Exception {
        insertReport(authorId, "1등", true, 30);
        insertReport(authorId, "2등", true, 20);
        insertReport(authorId, "3등", true, 10);
        flush();

        String cursor = mockMvc.perform(get(URL)
                        .param("sort", "POPULAR")
                        .param("size", "2")
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("1등"))
                .andExpect(jsonPath("$.items[1].title").value("2등"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

        assertThat(cursor).contains(":");

        mockMvc.perform(get(URL)
                        .param("sort", "POPULAR")
                        .param("cursor", cursor)
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("3등"));
    }

    @Test
    @DisplayName("최신순 커서는 다음 페이지를 겹치지 않게 이어준다")
    void 피드_최신순_커서() throws Exception {
        insertReport(authorId, "첫째", true, 0);
        insertReport(authorId, "둘째", true, 0);
        insertReport(authorId, "셋째", true, 0);
        flush();

        String cursor = mockMvc.perform(get(URL)
                        .param("size", "2")
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("셋째"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get(URL).param("cursor", cursor).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("첫째"));
    }

    @Test
    @DisplayName("sector 필터는 아직 구현되지 않아 400 으로 거절한다 — 조용히 무시하지 않는다")
    void 피드_sector_거절() throws Exception {
        mockMvc.perform(get(URL).param("sector", "반도체").with(user(String.valueOf(viewerId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("sector"));
    }

    @Test
    @DisplayName("어휘에 없는 sort 는 500 이 아니라 400 이다")
    void 피드_잘못된_sort() throws Exception {
        mockMvc.perform(get(URL).param("sort", "HOT").with(user(String.valueOf(viewerId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("sort"));
    }

    @Test
    @DisplayName("숫자가 아닌 커서는 400 이다")
    void 피드_잘못된_커서() throws Exception {
        mockMvc.perform(get(URL).param("cursor", "abc").with(user(String.valueOf(viewerId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("cursor"));
    }

    @Test
    @DisplayName("인기순 커서의 열람 수가 int 범위를 넘으면 400 — 조용히 감싸 다른 페이지를 주지 않는다")
    void 피드_인기순_커서_범위초과() throws Exception {
        mockMvc.perform(get(URL)
                        .param("sort", "POPULAR")
                        .param("cursor", "4294967296:5")
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("cursor"));
    }

    // ── GET /reports/{id} ───────────────────────────────────

    @Test
    @DisplayName("공개 리포트는 전문이 내려간다")
    void 열람_전문() throws Exception {
        Long reportId = insertReportWithBody(authorId, "공개 리포트", true, "1줄\n2줄\n3줄\n4줄");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.body").value("1줄\n2줄\n3줄\n4줄"))
                .andExpect(jsonPath("$.preview").doesNotExist());
    }

    @Test
    @DisplayName("비공개 리포트를 미구독자가 열면 앞 3줄만 내려간다 — AC 핵심")
    void 열람_미리보기_잠금() throws Exception {
        Long reportId = insertReportWithBody(authorId, "구독자 전용", false, "1줄\n2줄\n3줄\n4줄\n5줄");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.preview").value("1줄\n2줄\n3줄"))
                .andExpect(jsonPath("$.body").doesNotExist())
                // 제목은 항상 공개다 — 구독 유인이다
                .andExpect(jsonPath("$.title").value("구독자 전용"));
    }

    @Test
    @DisplayName("개행 없는 본문의 미리보기는 300자에서 끊는다 — 1줄=전문이 되는 구멍을 막는다")
    void 열람_미리보기_글자수_상한() throws Exception {
        Long reportId = insertReportWithBody(authorId, "한 줄 리포트", false, "가".repeat(1000));
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                // JsonPath 의 length() 는 문자열에 쓰면 null 이라 값 자체로 단정한다
                .andExpect(jsonPath("$.preview").value("가".repeat(300)));
    }

    @Test
    @DisplayName("CRLF 본문의 미리보기에 \\r 이 남지 않는다")
    void 열람_미리보기_CRLF() throws Exception {
        Long reportId = insertReportWithBody(authorId, "CRLF 리포트", false, "1줄\r\n2줄\r\n3줄\r\n4줄");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value("1줄\n2줄\n3줄"));
    }

    @Test
    @DisplayName("ACTIVE 구독자는 비공개 리포트 전문을 본다")
    void 열람_구독자_전문() throws Exception {
        Long reportId = insertReportWithBody(authorId, "구독자 전용", false, "1줄\n2줄\n3줄\n4줄");
        insertSubscription(viewerId, authorId, "ACTIVE");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.body").value("1줄\n2줄\n3줄\n4줄"));
    }

    @Test
    @DisplayName("EXPIRED 구독은 열람 권한이 아니다")
    void 열람_만료구독_잠금() throws Exception {
        Long reportId = insertReportWithBody(authorId, "구독자 전용", false, "1줄\n2줄\n3줄\n4줄");
        insertSubscription(viewerId, authorId, "EXPIRED");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));
    }

    @Test
    @DisplayName("열람하면 viewCount 가 1 늘고 응답에도 반영된다")
    void 열람_카운트_증가() throws Exception {
        Long reportId = insertReport(authorId, "카운트", true, 5);
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(6));
        flush();

        assertThat(viewCountOf(reportId)).isEqualTo(6);
    }

    @Test
    @DisplayName("작성자 본인의 열람은 세지 않는다 — 인기순 정렬 키를 자기 새로고침으로 올릴 수 없다")
    void 열람_본인은_카운트_제외() throws Exception {
        Long reportId = insertReport(authorId, "카운트", true, 5);
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(authorId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewCount").value(5));
        flush();

        assertThat(viewCountOf(reportId)).isEqualTo(5);
    }

    @Test
    @DisplayName("없는 리포트는 404 REPORT_NOT_FOUND")
    void 열람_없음() throws Exception {
        mockMvc.perform(get(URL + "/999999").with(user(String.valueOf(viewerId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
    }

    // ── GET /channels/{userId}/reports ───────────────────────

    @Test
    @DisplayName("채널 목록은 미구독자에게도 제목·발행일·공개 여부를 준다")
    void 채널목록_미구독자_공개() throws Exception {
        insertReport(authorId, "비공개 글", false, 3);
        flush();

        mockMvc.perform(get("/api/v1/channels/" + authorId + "/reports")
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("비공개 글"))
                .andExpect(jsonPath("$.items[0].visibility").value(false))
                .andExpect(jsonPath("$.items[0].locked").value(true))
                .andExpect(jsonPath("$.items[0].publishedAt").exists());
    }

    @Test
    @DisplayName("채널 목록은 그 채널의 리포트만 담는다")
    void 채널목록_채널_한정() throws Exception {
        Long other = insertUser("다른 발행자");
        insertReport(authorId, "내 채널 글", true, 0);
        insertReport(other, "남의 채널 글", true, 0);
        flush();

        mockMvc.perform(get("/api/v1/channels/" + authorId + "/reports")
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("내 채널 글"));
    }

    @Test
    @DisplayName("구독자에게는 visibility=false 라도 locked=false 다 — 두 값은 다른 것을 말한다")
    void 채널목록_구독자() throws Exception {
        insertReport(authorId, "비공개 글", false, 0);
        insertSubscription(viewerId, authorId, "ACTIVE");
        flush();

        mockMvc.perform(get("/api/v1/channels/" + authorId + "/reports")
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].visibility").value(false))
                .andExpect(jsonPath("$.items[0].locked").value(false));
    }

    @Test
    @DisplayName("없는 채널은 404 USER_NOT_FOUND — 빈 목록으로 속이지 않는다")
    void 채널목록_없는_유저() throws Exception {
        mockMvc.perform(get("/api/v1/channels/999999/reports")
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ── 파라미터 타입 오류는 500 이 아니라 400 ────────────────

    @Test
    @DisplayName("size 에 문자가 오면 500 이 아니라 400 이다")
    void 파라미터_타입오류_size() throws Exception {
        mockMvc.perform(get(URL).param("size", "abc").with(user(String.valueOf(viewerId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("size"));
    }

    @Test
    @DisplayName("경로 변수에 문자가 오면 500 이 아니라 400 이다")
    void 파라미터_타입오류_경로변수() throws Exception {
        mockMvc.perform(get(URL + "/abc").with(user(String.valueOf(viewerId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("reportId"));
    }

    @Test
    @DisplayName("채널 목록의 cursor 에 문자가 오면 500 이 아니라 400 이다")
    void 파라미터_타입오류_채널_커서() throws Exception {
        mockMvc.perform(get("/api/v1/channels/" + authorId + "/reports")
                        .param("cursor", "abc")
                        .with(user(String.valueOf(viewerId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("cursor"));
    }

    // ── 만료된 구독 · 닉네임 없음 · 이모지 경계 ─────────────────

    @Test
    @DisplayName("기간이 지난 ACTIVE 구독은 본문을 열어주지 않는다 — 만료 배치가 없어도 정확하다")
    void 만료된_구독은_잠긴다() throws Exception {
        Long reportId = insertReportWithBody(authorId, "구독자 전용", false, "1줄\n2줄\n3줄\n4줄");
        insertSubscriptionExpiring(viewerId, authorId, "ACTIVE", Duration.ofDays(-1));
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));
    }

    @Test
    @DisplayName("기간이 남은 ACTIVE 구독은 그대로 열어준다")
    void 기간_남은_구독은_열린다() throws Exception {
        Long reportId = insertReportWithBody(authorId, "구독자 전용", false, "1줄\n2줄\n3줄\n4줄");
        insertSubscriptionExpiring(viewerId, authorId, "ACTIVE", Duration.ofDays(30));
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    @DisplayName("만료된 구독자는 scope=SUBSCRIBED 목록에도 안 걸린다")
    void 만료된_구독은_구독탭에서_빠진다() throws Exception {
        insertReport(authorId, "구독한 채널 글", true, 0);
        insertSubscriptionExpiring(viewerId, authorId, "ACTIVE", Duration.ofDays(-1));
        flush();

        mockMvc.perform(get(URL).param("scope", "SUBSCRIBED").with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("만료된 구독자에게는 발행 알림을 보내지 않는다")
    void 만료된_구독자는_알림_제외() throws Exception {
        insertSubscriptionExpiring(viewerId, authorId, "ACTIVE", Duration.ofDays(-1));
        flush();

        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody("만료 알림 리포트", "본문", false)))
                .andExpect(status().isCreated());
        flush();

        assertThat(countNotifications(viewerId, "REPORT_PUBLISHED")).isZero();
    }

    @Test
    @DisplayName("닉네임이 없는 발행자의 알림 본문에 null 이 찍히지 않는다")
    void 닉네임_없는_발행자_알림() throws Exception {
        Long noNameId = insertUserWithoutNickname();
        insertSubscription(viewerId, noNameId, "ACTIVE");
        // 발행자가 authorId 가 아니라 이 사람이다. 지갑은 UQ 라 둘째 키를 쓴다.
        Credentials other = Credentials.create(OTHER_PRIVATE_KEY);
        linkWallet(noNameId, other.getAddress());
        flush();

        mockMvc.perform(post(URL)
                        .with(user(String.valueOf(noNameId)))
                        .with(csrf())
                        .header("Idempotency-Key", "key-nonick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signedBody(noNameId, other, "닉없음 리포트", "본문", false)))
                .andExpect(status().isCreated());
        flush();

        assertThat(notificationBody(viewerId)).isEqualTo("닉없음 리포트");
    }

    @Test
    @DisplayName("미리보기 300자 경계가 이모지를 반으로 쪼개지 않는다")
    void 미리보기_이모지_경계() throws Exception {
        // 299자 + 이모지(2칸) → 300번째 칸이 이모지의 앞쪽 절반이다
        Long reportId = insertReportWithBody(authorId, "이모지 리포트", false, "가".repeat(299) + "😀");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                // 반쪼가리를 남기지 않고 한 칸 물러서 299자로 끝낸다
                .andExpect(jsonPath("$.preview").value("가".repeat(299)));
    }

    // ── 픽스처 ──────────────────────────────────────────────

    // ── 서명 (ANT-CHAIN-16) ─────────────────────────────────

    /** {@link #signedBody(Long, String, String, boolean)} 의 발행자 기본값 = authorId. */
    private String signedBody(String title, String body, boolean visibility) {
        return signedBody(authorId, title, body, visibility);
    }

    /**
     * nonce 를 발급받아 DTO 의 {@code signingPayload} 로 서명한 요청 본문 — 프론트가 할 일과 같다.
     *
     * <p>DTO 를 한 번 만들어 payload 를 뽑는 이유는 서명 문자열 조립 규칙이 한 곳이어야 해서다.
     * 테스트가 payload 를 따로 적으면 규칙이 갈려도 테스트만 통과한다.
     */
    private String signedBody(Long userId, String title, String body, boolean visibility) {
        return signedBody(userId, wallet, title, body, visibility);
    }

    private String signedBody(
            Long userId, Credentials signer, String title, String body, boolean visibility) {
        ReportCreateRequest unsigned =
                new ReportCreateRequest(title, body, visibility, DUMMY_SIGNATURE);
        String nonce = nonceStore.issue(userId, SignatureScope.REPORT_PUBLISH);
        String signature = WalletSignatures.sign(
                unsigned.signingPayload(nonce, signatureGuard.chainId()), signer);
        return json(title, body, String.valueOf(visibility), signature);
    }

    /** 원시 JSON. visibility 를 문자열로 받아 누락·잘못된 값까지 적을 수 있게 둔다. */
    private static String json(String title, String body, String visibility, String signature) {
        return "{\"title\":\"" + title
                + "\",\"body\":\"" + body
                + "\",\"visibility\":" + visibility
                + ",\"signature\":\"" + signature + "\"}";
    }

    /** 서명자 주소와 대조할 연동 지갑. SignatureGuard 는 소문자로 눕힌 값을 기대한다. */
    private void linkWallet(Long userId, String address) {
        em.createNativeQuery("UPDATE users SET wallet_address = ? WHERE id = ?")
                .setParameter(1, address.toLowerCase(java.util.Locale.ROOT))
                .setParameter(2, userId)
                .executeUpdate();
    }

    private void flush() {
        em.flush();
        em.clear();
    }

    private Long insertUser(String nickname) {
        em.createNativeQuery("""
                        INSERT INTO users (nickname, role, status, created_at, updated_at)
                        VALUES (?, 'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, nickname)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = ?")
                        .setParameter(1, nickname)
                        .getSingleResult())
                .longValue();
    }

    private Long insertReport(Long userId, String title, boolean isPublic, int viewCount) {
        return insertReportWithBody(userId, title, isPublic, "본문", viewCount);
    }

    private Long insertReportWithBody(Long userId, String title, boolean isPublic, String body) {
        return insertReportWithBody(userId, title, isPublic, body, 0);
    }

    private Long insertReportWithBody(
            Long userId, String title, boolean isPublic, String body, int viewCount) {
        em.createNativeQuery("""
                        INSERT INTO reports (user_id, title, body, is_public, view_count, created_at)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, title)
                .setParameter(3, body)
                .setParameter(4, isPublic)
                .setParameter(5, viewCount)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM reports WHERE title = ?")
                        .setParameter(1, title)
                        .getSingleResult())
                .longValue();
    }

    /** 구독은 ANT-MONETIZE 담당 엔티티다. NOT NULL 컬럼만 채운 최소 행을 넣는다. */
    private void insertSubscription(Long subscriberId, Long publisherId, String status) {
        em.createNativeQuery("""
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, auto_renew, created_at, updated_at)
                        VALUES (?, ?, 0, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriberId)
                .setParameter(2, publisherId)
                .setParameter(3, status)
                .executeUpdate();
    }

    /**
     * 기간이 붙은 구독.
     *
     * <p>만료 시각을 SQL 의 INTERVAL 로 만들지 않고 Java 에서 계산해 바인딩한다 — H2 는
     * 단위 없는 {@code CAST(? AS INTERVAL)} 을 받지 않고, 방언별 표현을 테스트가 알 이유도 없다.
     */
    private void insertSubscriptionExpiring(
            Long subscriberId, Long publisherId, String status, Duration expiresIn) {
        em.createNativeQuery("""
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, auto_renew,
                           started_at, expires_at, created_at, updated_at)
                        VALUES (?, ?, 0, ?, false, CURRENT_TIMESTAMP, ?,
                                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriberId)
                .setParameter(2, publisherId)
                .setParameter(3, status)
                .setParameter(4, Instant.now().plus(expiresIn))
                .executeUpdate();
    }

    /** 온보딩 미완료 회원 — {@code nickname} 이 NULL 이다. */
    private Long insertUserWithoutNickname() {
        em.createNativeQuery("""
                        INSERT INTO users (role, status, created_at, updated_at)
                        VALUES ('USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                                "SELECT max(id) FROM users WHERE nickname IS NULL")
                        .getSingleResult())
                .longValue();
    }

    private String notificationBody(Long userId) {
        return (String) em.createNativeQuery("""
                        SELECT body FROM notifications WHERE user_id = ? AND type = 'REPORT_PUBLISHED'
                        """)
                .setParameter(1, userId)
                .getSingleResult();
    }

    private int viewCountOf(Long reportId) {
        return ((Number) em.createNativeQuery("SELECT view_count FROM reports WHERE id = ?")
                        .setParameter(1, reportId)
                        .getSingleResult())
                .intValue();
    }

    private long countReports(String title) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM reports WHERE title = ?")
                        .setParameter(1, title)
                        .getSingleResult())
                .longValue();
    }

    private long countNotifications(Long userId, String type) {
        return ((Number) em.createNativeQuery("""
                                SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?
                                """)
                        .setParameter(1, userId)
                        .setParameter(2, type)
                        .getSingleResult())
                .longValue();
    }

    /* ── 마크다운 본문 (ANT-FE-REPORT-MD) ─────────────────────────
       본문이 마크다운이 되면서 잠긴 리포트의 앞 3줄에 문법이 새어 나가지 않아야 한다.
       구독을 권하는 자리라 "## 제목" 이나 이미지 주소가 뜨면 읽히지도 않는다.
       저장·봉인은 원문 그대로다 — 벗기는 것은 이 응답 필드뿐이다. */

    @Test
    @DisplayName("미리보기에서 마크다운 문법을 벗긴다 — 제목·강조·목록")
    void 열람_미리보기_마크다운_벗김() throws Exception {
        Long reportId = insertReportWithBody(
                authorId, "마크다운 리포트", false,
                "## 반도체 업황\n**공급 과잉**이 끝났다\n- 재고 감소\n- 가격 반등");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.preview").value("반도체 업황\n공급 과잉이 끝났다\n재고 감소"));
    }

    @Test
    @DisplayName("이미지 줄은 미리보기 세 줄을 먹지 않는다")
    void 열람_미리보기_이미지_제외() throws Exception {
        Long reportId = insertReportWithBody(
                authorId, "그림 있는 리포트", false,
                "![차트](/api/v1/uploads/abc-123)\n1줄\n2줄\n3줄\n4줄");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value("1줄\n2줄\n3줄"));
    }

    @Test
    @DisplayName("문단 사이 빈 줄도 미리보기 세 줄을 먹지 않는다")
    void 열람_미리보기_빈줄_제외() throws Exception {
        Long reportId = insertReportWithBody(
                authorId, "문단 리포트", false, "1줄\n\n2줄\n\n3줄\n\n4줄");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value("1줄\n2줄\n3줄"));
    }

    @Test
    @DisplayName("링크는 글자만 남고 주소는 미리보기에 안 나온다")
    void 열람_미리보기_링크_주소_제거() throws Exception {
        Long reportId = insertReportWithBody(
                authorId, "링크 리포트", false, "[공시 원문](https://dart.fss.or.kr/x)을 봤다");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview").value("공시 원문을 봤다"));
    }

    @Test
    @DisplayName("본문이 그림뿐이면 미리보기 키가 아예 없다 — 빈 칸을 그리게 두지 않는다")
    void 열람_미리보기_그림뿐() throws Exception {
        Long reportId = insertReportWithBody(
                authorId, "그림만", false, "![](/api/v1/uploads/a)\n\n![](/api/v1/uploads/b)");
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.preview").doesNotExist());
    }

    @Test
    @DisplayName("구독자에게는 본문이 마크다운 원문 그대로 간다 — 벗기는 것은 미리보기뿐")
    void 열람_전문은_원문_그대로() throws Exception {
        String body = "## 제목\n**굵게**\n![](/api/v1/uploads/a)";
        Long reportId = insertReportWithBody(authorId, "원문 확인", true, body);
        flush();

        mockMvc.perform(get(URL + "/" + reportId).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.body").value(body));
    }

}
