package ssafy.a507.backend.domain.monetize.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import ssafy.a507.backend.common.security.SignatureNonceStore;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;
import ssafy.a507.backend.domain.chain.service.AvailableBalanceService;
import ssafy.a507.backend.domain.chain.service.FakeTokenRelayer;
import ssafy.a507.backend.domain.monetize.dto.AdCreateRequest;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.repository.AdBannerRepository;
import ssafy.a507.backend.domain.upload.entity.UploadFile;
import ssafy.a507.backend.support.TestNonceStoreConfig;
import ssafy.a507.backend.support.WalletSignatures;

/**
 * POST /api/v1/ads · DELETE /api/v1/ads/{id} · GET /api/v1/ads/active — ANT-COMMUNITY-05 의 AC 검증.
 *
 * <p><b>승인제(ANT-ADMIN-03)</b> — 신청은 접수만 한다. 소각 tx 는 관리자 승인({@code AdminAdControllerTest})에서 나간다.
 * 잔액 기준은 원장이 아니라 <b>가용잔액</b>(체인 잔액 − PENDING 배팅·구독·광고, ANT-TOKEN-11)이라 잔액은
 * {@link FakeTokenRelayer#withBalance} 로 심는다.
 *
 * <p>Redis 는 띄우지 않는다 — 멱등 저장소는 맵으로, nonce 저장소는 인메모리 구현으로 대신한다. 체인은 {@link FakeTokenRelayer}.
 *
 * <p><b>팀 컨벤션인 {@code @Transactional} 롤백을 쓰지 않는다</b>(ANT-CHAIN-12). 신청이 소각 전송을 부르는데, 그 전송은
 * "활성 트랜잭션 안에서는 거부" 가 계약이다({@code TokenOperationService}). 테스트 트랜잭션 안에서 부르면 그 계약이 먼저
 * 터진다. 대신 픽스처는 {@link TransactionTemplate} 으로 커밋하고, {@code @AfterEach} 가 이 테스트가 만든 사용자와
 * 그 사용자에 걸린 작업 · 배너 · 원장 · 파일을 지운다. H2 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestNonceStoreConfig.class, FakeTokenRelayer.Config.class})
@DisplayName("스폰서드 광고 API")
class AdControllerTest {

    private static final String URL = "/api/v1/ads";
    private static final long CHAIN_ID = 31337L;
    private static final String PRIVATE_KEY =
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
    private static final String DUMMY_SIGNATURE = "0x" + "0".repeat(130);
    /** 단가(테스트 yaml app.token.amounts.ad-per-day)가 1,000 ANT/일이라 30일치(30,000)를 덮는 잔액. ANT 는 decimals 0 이다. */
    private static final BigInteger RICH = BigInteger.valueOf(30_000);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired SignatureNonceStore nonceStore;
    @Autowired FakeTokenRelayer relayer;
    @Autowired AdBannerRepository banners;
    @Autowired OperationRepository operations;
    @Autowired AvailableBalanceService availableBalance;

    @MockitoBean StringRedisTemplate redis;

    private final Map<String, String> redisStore = new HashMap<>();
    /** {@code @AfterEach} 가 지울 사용자. 이 사용자에 걸린 행을 전부 같이 지운다. */
    private final List<Long> userIds = new ArrayList<>();

    private Credentials credentials;
    private User advertiser;
    private String imageFileId;

    @BeforeEach
    void setUp() {
        stubRedis();
        relayer.reset();
        // 잔액은 체인에서 읽는다(가용잔액). 넉넉히 심고, 모자란 경우만 테스트가 덮어쓴다.
        relayer.withBalance(RICH);
        credentials = Credentials.create(PRIVATE_KEY);
        advertiser = insertUser("광고주", credentials.getAddress());
        imageFileId = insertUploadFile(advertiser, UploadFile.Purpose.AD);
    }

    @AfterEach
    void tearDown() {
        // FK 순서: 작업 · 배너 · 원장 · 파일 → 사용자.
        tx.executeWithoutResult(s -> {
            for (String delete : List.of(
                    "DELETE FROM operations WHERE user_id IN (:ids)",
                    "DELETE FROM ad_banners WHERE advertiser_id IN (:ids)",
                    "DELETE FROM subscriptions WHERE subscriber_id IN (:ids) OR publisher_id IN (:ids)",
                    "DELETE FROM token_ledger WHERE user_id IN (:ids)",
                    "DELETE FROM upload_files WHERE user_id IN (:ids)",
                    "DELETE FROM users WHERE id IN (:ids)")) {
                em.createNativeQuery(delete).setParameter("ids", userIds).executeUpdate();
            }
        });
        userIds.clear();
    }

    @Test
    @DisplayName("신청하면 201 과 adId 를 받는다 — operationId 는 없다. 돈은 승인 때 나간다(ANT-ADMIN-03)")
    void 신청_성공() throws Exception {
        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adId").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.operationId").doesNotExist());
    }

    @Test
    @DisplayName("신청은 소각 tx 를 보내지 않는다 — 작업도 만들지 않는다. 배너만 PENDING 으로 남는다(ANT-ADMIN-03)")
    void 신청은_소각_tx_를_보내지_않는다() throws Exception {
        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-no-burn"))
                .andExpect(status().isCreated());

        assertThat(relayer.calls()).isEmpty();
        assertThat(operationsOf(advertiser)).isEmpty();
        assertThat(bannersOf(advertiser)).singleElement().satisfies(b -> {
            assertThat(b.getStatus()).isEqualTo(AdBanner.Status.PENDING);
            assertThat(b.getPriceWei()).isEqualTo(BigInteger.valueOf(7_000)); // 1,000 ANT/일 × 7일
        });
    }

    @Test
    @DisplayName("체인이 꺼져 있으면 503 — 배너도 작업도 만들지 않고 nonce 도 태우지 않는다")
    void 체인이_꺼져_있으면_503() throws Exception {
        relayer.disable();

        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-off"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAIN_UNAVAILABLE"));

        assertThat(bannersOf(advertiser)).isEmpty();
        assertThat(operationsOf(advertiser)).isEmpty();
        assertThat(relayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("접수된 배너는 확정 전이라 노출 목록에 들어가지 않는다")
    void 접수만으로는_노출되지_않는다() throws Exception {
        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-pending"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(URL + "/active").with(user(String.valueOf(advertiser.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("같은 기간에 이미 자리가 찼으면 409 AD_SLOT_SOLD_OUT")
    void 슬롯_마감() throws Exception {
        insertBanner(AdBanner.Status.ACTIVE, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));

        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-slot"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_SLOT_SOLD_OUT"));
    }

    @Test
    @DisplayName("확정을 기다리는 신청도 자리를 차지한다 — 확정되는 순간 배너가 둘이 되면 안 된다")
    void 최근_PENDING_은_슬롯을_막는다() throws Exception {
        insertBanner(AdBanner.Status.PENDING, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));

        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-pending-blocks"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_SLOT_SOLD_OUT"));
    }

    @Test
    @DisplayName("오래된 PENDING 도 자리를 잡는다 — 승인 대기는 유예 창 없이 기간 내내 자리를 잡는다(ANT-ADMIN-03)")
    void 오래된_PENDING_도_슬롯을_막는다() throws Exception {
        AdBanner waiting =
                insertBanner(
                        AdBanner.Status.PENDING, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        backdate(waiting, Instant.now().minus(1, ChronoUnit.HOURS));

        // 승인제 전에는 유예 창(pending-grace-minutes)이 지나 자리를 놓은 것으로 봤다. 이제 자리만 붙잡는 신청은
        // 관리자가 반려하거나 신청자가 취소한다.
        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-waiting"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_SLOT_SOLD_OUT"));
    }

    @Test
    @DisplayName("견적가를 배너에 박제한다 — 인덱서가 소각 tx 와 맞출 기대 금액이 필요하다")
    void 게재료를_박제한다() throws Exception {

        String adId = jsonValue(
                mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-price"))
                        .andReturn(),
                "adId");

        AdBanner saved = banners.findById(Long.valueOf(adId)).orElseThrow();
        assertThat(saved.getPriceWei()).isEqualTo(BigInteger.valueOf(7_000)); // 1,000 ANT/일 × 7일
    }

    @Test
    @DisplayName("이미 끝난 광고는 슬롯을 막지 않는다 — 겹침 판정이 기간 양쪽을 본다")
    void 지난_광고는_슬롯을_막지_않는다() throws Exception {
        Instant now = Instant.now();
        insertBanner(
                AdBanner.Status.ACTIVE, now.minus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));

        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-past"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("링크가 컬럼 길이를 넘으면 400 — INSERT 가 터져 500 이 되게 두지 않는다")
    void 너무_긴_링크는_400() throws Exception {
        String tooLong = "https://ad.example.com/" + "a".repeat(500);

        mockMvc.perform(create(imageFileId, tooLong, 7, "key-longurl"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("linkUrl"));
    }

    @Test
    @DisplayName("가용잔액이 게재료보다 적으면 409 INSUFFICIENT_BALANCE — 기준은 원장이 아니라 체인이다(ANT-TOKEN-11)")
    void 잔액_부족() throws Exception {
        relayer.withBalance(BigInteger.ONE);

        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-poor"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertThat(relayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("남이 올린 이미지의 fileId 로는 등록할 수 없다 — 404 UPLOAD_FILE_NOT_FOUND")
    void 남의_이미지는_거부() throws Exception {
        User other = insertUser("남", null);
        String othersFile = insertUploadFile(other, UploadFile.Purpose.AD);

        mockMvc.perform(create(othersFile, "https://ad.example.com", 7, "key-other"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UPLOAD_FILE_NOT_FOUND"));
    }

    @Test
    @DisplayName("배너 용도로 올리지 않은 이미지는 400 — 비율 검증을 건너뛴 파일이다")
    void 다른_용도_이미지는_거부() throws Exception {
        String postImage = insertUploadFile(advertiser, UploadFile.Purpose.POST);

        mockMvc.perform(create(postImage, "https://ad.example.com", 7, "key-purpose"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("imageFileId"));
    }

    @Test
    @DisplayName("http 링크는 400 — 배너는 클릭을 유도하는 자리라 피싱 대상이 된다")
    void http_링크는_거부() throws Exception {

        mockMvc.perform(create(imageFileId, "http://ad.example.com", 7, "key-http"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("linkUrl"));
    }

    @Test
    @DisplayName("최대 기간을 넘기면 400 — 한 명이 몇 년치를 선점하지 못한다")
    void 기간_상한() throws Exception {

        mockMvc.perform(create(imageFileId, "https://ad.example.com", 365, "key-long"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("days"));
    }

    @Test
    @DisplayName("같은 Idempotency-Key 재요청은 광고를 하나만 만든다 — 게재료는 되돌릴 수 없다")
    void 멱등_재요청() throws Exception {
        String body = signedBody(imageFileId, "https://ad.example.com", 7);

        String first = jsonValue(perform(body, "key-same"), "adId");
        String second = jsonValue(perform(body, "key-same"), "adId");

        assertThat(second).isEqualTo(first);
        // 신청도 하나뿐이다 — 재요청은 저장된 응답을 돌려줄 뿐 서비스를 다시 부르지 않는다. 둘이면 자리와 홀드를 두 배로 잡는다.
        assertThat(bannersOf(advertiser)).hasSize(1);
    }

    @Test
    @DisplayName("같은 키·같은 신청이면 서명이 달라도 재생한다 — 화면은 재시도마다 nonce 를 새로 받아 다시 서명한다")
    void 멱등_재서명_재요청() throws Exception {
        /* 위 테스트는 같은 body 문자열을 두 번 보내 서명까지 같았다. 실제 화면에는 그런 재시도가 없다 —
           nonce 는 한 번 쓰면 타 버려서 다시 누르면 서명이 반드시 달라진다. 서명이 해시에 들어가 있던 동안
           이 경로는 409 IDEMPOTENCY_KEY_REUSED 였다(QA 09-21). */
        String first = jsonValue(
                mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-resign"))
                        .andExpect(status().isCreated()).andReturn(), "adId");
        String second = jsonValue(
                mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-resign"))
                        .andExpect(status().isCreated()).andReturn(), "adId");

        assertThat(second).isEqualTo(first);
        assertThat(bannersOf(advertiser)).hasSize(1);
    }

    @Test
    @DisplayName("같은 키로 다른 광고를 보내면 409 IDEMPOTENCY_KEY_REUSED — 서명을 뺀 뒤에도 내용은 본다")
    void 멱등키_다른_신청() throws Exception {
        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-other"))
                .andExpect(status().isCreated());

        mockMvc.perform(create(imageFileId, "https://ad.example.com", 3, "key-other"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(bannersOf(advertiser)).hasSize(1);
    }

    @Test
    @DisplayName("신청 → 취소 → 새 키로 같은 내용 재신청은 새 신청으로 접수된다 — 화면이 접수 뒤 키를 버린다")
    void 취소_뒤_재신청() throws Exception {
        relayer.withBalance(BigInteger.valueOf(7_000));
        String first = jsonValue(
                mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-before-cancel"))
                        .andExpect(status().isCreated()).andReturn(), "adId");
        mockMvc.perform(delete(URL + "/" + first)
                        .with(user(String.valueOf(advertiser.getId()))).with(csrf()))
                .andExpect(status().isNoContent());

        String second = jsonValue(
                mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-after-cancel"))
                        .andExpect(status().isCreated()).andReturn(), "adId");

        assertThat(second).isNotEqualTo(first);
        // 취소된 것 하나 · 심사 대기 하나. 홀드는 새 신청 몫만 잡힌다
        assertThat(bannersOf(advertiser)).extracting(AdBanner::getStatus)
                .containsExactlyInAnyOrder(AdBanner.Status.REJECTED, AdBanner.Status.PENDING);
        assertThat(availableBalance.of(advertiser)).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("노출 목록은 ACTIVE 이면서 기간 안인 배너만 담는다")
    void 활성_목록() throws Exception {
        Instant now = Instant.now();
        insertBanner(AdBanner.Status.ACTIVE, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS));
        // 끝난 광고. 상태를 ENDED 로 바꾸는 배치가 없어도 기간으로 걸러져야 한다.
        insertBanner(AdBanner.Status.ACTIVE, now.minus(10, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
        insertBanner(AdBanner.Status.PENDING, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS));

        mockMvc.perform(get(URL + "/active").with(user(String.valueOf(advertiser.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].imageUrl").isNotEmpty())
                .andExpect(jsonPath("$.items[0].linkUrl").isNotEmpty());
    }

    @Test
    @DisplayName("게재 조건은 설정값을 그대로 내린다 — 단가는 토큰 금액표, 금액 필드는 문자열이다")
    void 게재_조건() throws Exception {
        mockMvc.perform(get(URL + "/pricing").with(user(String.valueOf(advertiser.getId()))))
                .andExpect(status().isOk())
                // 테스트 yaml 의 app.token.amounts.ad-per-day = 1,000 ANT/일. ANT 는 decimals 0
                // 이라 10^18 을 곱하지 않고, 금액 필드라 문자열로 내린다. 필드 이름에 wei 가 없다(ANT-TOKEN-08).
                .andExpect(jsonPath("$.pricePerDay").value("1000"))
                .andExpect(jsonPath("$.pricePerDayWei").doesNotExist())
                // AdCreateRequest 의 @Min(1) 과 같은 값이어야 한다.
                .andExpect(jsonPath("$.minDays").value(1))
                .andExpect(jsonPath("$.maxDays").value(30))
                .andExpect(jsonPath("$.slotCount").value(1));
    }

    @Test
    @DisplayName("게재 조건도 로그인해야 받는다 — 401")
    void 게재_조건_미인증은_401() throws Exception {
        mockMvc.perform(get(URL + "/pricing")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void 미인증은_401() throws Exception {
        mockMvc.perform(get(URL + "/active")).andExpect(status().isUnauthorized());
    }

    // ── 가용잔액 홀드 (ANT-TOKEN-11) ──────────────────────────

    @Test
    @DisplayName("구독 PENDING 이 광고 가용잔액을 묶는다 — 전송 중인 구독료로 광고를 낼 수 없다")
    void 구독_PENDING_이_광고_잔액을_묶는다() throws Exception {
        // 체인 잔액 30,000 인데 확정 대기 구독이 25,000 을 잡고 있다 → 가용 5,000 < 게재료 7,000
        User publisher = insertUser("예측가", null);
        insertPendingSubscription(advertiser, publisher, BigInteger.valueOf(25_000));

        mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-held"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertThat(bannersOf(advertiser)).isEmpty();
    }

    @Test
    @DisplayName("신청하면 그 게재료만큼 가용잔액이 묶이고, 취소하면 풀린다")
    void 신청은_홀드_취소는_해제() throws Exception {
        relayer.withBalance(BigInteger.valueOf(7_000));

        String adId = jsonValue(
                mockMvc.perform(create(imageFileId, "https://ad.example.com", 7, "key-hold")).andReturn(), "adId");
        assertThat(availableBalance.of(advertiser)).isEqualTo(BigInteger.ZERO);

        mockMvc.perform(delete(URL + "/" + adId)
                        .with(user(String.valueOf(advertiser.getId()))).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(availableBalance.of(advertiser)).isEqualTo(BigInteger.valueOf(7_000));
        assertThat(bannersOf(advertiser)).singleElement()
                .extracting(AdBanner::getStatus).isEqualTo(AdBanner.Status.REJECTED);
        // 돈은 한 푼도 안 나갔다
        assertThat(relayer.calls()).isEmpty();
    }

    // ── 신청 취소 (ANT-ADMIN-03) ──────────────────────────────

    @Test
    @DisplayName("남의 신청은 취소할 수 없다 — 403 AD_FORBIDDEN")
    void 남의_신청_취소는_403() throws Exception {
        AdBanner mine = insertBanner(AdBanner.Status.PENDING, Instant.now(), Instant.now().plus(7, ChronoUnit.DAYS));
        User other = insertUser("남", null);

        mockMvc.perform(delete(URL + "/" + mine.getId())
                        .with(user(String.valueOf(other.getId()))).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AD_FORBIDDEN"));
    }

    @Test
    @DisplayName("이미 처리된 신청은 취소할 수 없다 — 409 AD_ALREADY_HANDLED")
    void 처리된_신청_취소는_409() throws Exception {
        AdBanner rejected =
                insertBanner(AdBanner.Status.REJECTED, Instant.now(), Instant.now().plus(7, ChronoUnit.DAYS));

        mockMvc.perform(delete(URL + "/" + rejected.getId())
                        .with(user(String.valueOf(advertiser.getId()))).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_ALREADY_HANDLED"));
    }

    @Test
    @DisplayName("없는 신청 취소는 404 AD_NOT_FOUND")
    void 없는_신청_취소는_404() throws Exception {
        mockMvc.perform(delete(URL + "/999999")
                        .with(user(String.valueOf(advertiser.getId()))).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AD_NOT_FOUND"));
    }

    // ── 픽스처 ──────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
            String fileId, String linkUrl, int days, String key) {
        return post(URL)
                .with(user(String.valueOf(advertiser.getId()))).with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(signedBody(fileId, linkUrl, days));
    }

    private MvcResult perform(String body, String key) throws Exception {
        return mockMvc.perform(post(URL)
                        .with(user(String.valueOf(advertiser.getId()))).with(csrf())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** 서버와 같은 규칙으로 payload 를 조립해 서명한다. 프론트가 해야 할 일과 같다. */
    private String signedBody(String fileId, String linkUrl, int days) {
        String nonce = nonceStore.issue(advertiser.getId(), SignatureScope.AD);
        String payload =
                new AdCreateRequest(fileId, linkUrl, days, DUMMY_SIGNATURE)
                        .signingPayload(nonce, CHAIN_ID);
        return """
                {"imageFileId":"%s","linkUrl":"%s","days":%d,"signature":"%s"}
                """
                .formatted(fileId, linkUrl, days, WalletSignatures.sign(payload, credentials));
    }

    private String jsonValue(MvcResult result, String field) throws Exception {
        return String.valueOf(
                (Object) com.jayway.jsonpath.JsonPath.read(
                        result.getResponse().getContentAsString(), "$." + field));
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

    private String insertUploadFile(User owner, UploadFile.Purpose purpose) {
        return tx.execute(s -> {
            UploadFile file = UploadFile.create(em.getReference(User.class, owner.getId()), purpose, "image/png", 400, 100, 1024);
            file.locateAt("/api/v1/uploads/" + file.getId());
            em.persist(file);
            return file.getId();
        });
    }

    private AdBanner insertBanner(AdBanner.Status status, Instant startsAt, Instant endsAt) {
        return tx.execute(s -> {
            AdBanner banner =
                    AdBanner.request(
                            em.getReference(User.class, advertiser.getId()),
                            "/api/v1/uploads/x",
                            "https://ad.example.com",
                            startsAt,
                            endsAt,
                            BigInteger.valueOf(7));
            if (status == AdBanner.Status.ACTIVE) {
                banner.activate("0x" + "1".repeat(64));
            } else if (status == AdBanner.Status.REJECTED) {
                banner.reject();
            }
            em.persist(banner);
            return banner;
        });
    }

    /** 확정 대기 구독. 가용잔액이 이 fee 를 뺀다. */
    private void insertPendingSubscription(User subscriber, User publisher, BigInteger fee) {
        tx.executeWithoutResult(s -> em.persist(Subscription.request(
                em.getReference(User.class, subscriber.getId()),
                em.getReference(User.class, publisher.getId()),
                fee)));
    }

    private List<AdBanner> bannersOf(User owner) {
        return tx.execute(s -> em.createQuery(
                        "select b from AdBanner b where b.advertiser.id = :id", AdBanner.class)
                .setParameter("id", owner.getId())
                .getResultList());
    }

    private List<Operation> operationsOf(User owner) {
        return tx.execute(s -> em.createQuery(
                        "select o from Operation o where o.user.id = :id", Operation.class)
                .setParameter("id", owner.getId())
                .getResultList());
    }

    /** @CreationTimestamp 라 코드로는 못 바꾼다. 유예 창이 지난 신청을 만들려면 되돌려야 한다. */
    private void backdate(AdBanner banner, Instant createdAt) {
        tx.executeWithoutResult(s -> em.createNativeQuery("UPDATE ad_banners SET created_at = ? WHERE id = ?")
                .setParameter(1, java.sql.Timestamp.from(createdAt))
                .setParameter(2, banner.getId())
                .executeUpdate());
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
