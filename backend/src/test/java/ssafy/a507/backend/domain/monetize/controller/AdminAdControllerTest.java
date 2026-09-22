package ssafy.a507.backend.domain.monetize.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.service.FakeTokenRelayer;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.monetize.repository.AdBannerRepository;

/**
 * GET·PATCH /api/v1/admin/ads — ANT-ADMIN-03 광고 승인제. <b>돈이 나가는 자리</b>의 검증이다.
 *
 * <p>지켜야 할 것: 승인에서만 소각이 나갈 것 · 반려·실패에서는 한 푼도 안 나갈 것 · 실패해도 배너가 승인 대기로
 * 남아 다시 누를 수 있을 것 · 자기 홀드를 자기가 막지 않을 것 · 게재 기간이 승인 시점부터일 것.
 *
 * <p>{@link AdControllerTest} 와 같은 이유로 {@code @Transactional} 롤백을 쓰지 않는다 — 승인이 소각 전송을 부르는데
 * 그 전송은 "활성 트랜잭션 안에서는 거부" 가 계약이다. 픽스처는 {@link TransactionTemplate} 으로 커밋하고
 * {@code @AfterEach} 가 지운다. 체인은 {@link FakeTokenRelayer}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeTokenRelayer.Config.class)
@DisplayName("관리자 광고 승인 API")
class AdminAdControllerTest {

    private static final String URL = "/api/v1/admin/ads";
    private static final String WALLET = "0x" + "ab".repeat(20);
    /** 7일치 게재료. 테스트 yaml 단가 1,000 ANT/일 — 픽스처가 직접 박제하는 값이라 단가와 무관하게 7,000 이다. */
    private static final BigInteger PRICE = BigInteger.valueOf(7_000);
    private static final BigInteger RICH = BigInteger.valueOf(30_000);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired FakeTokenRelayer relayer;
    @Autowired AdBannerRepository banners;

    private final List<Long> userIds = new ArrayList<>();

    private Long adminId;
    private User advertiser;

    @BeforeEach
    void setUp() {
        relayer.reset();
        relayer.withBalance(RICH);
        adminId = insertAdmin("운영자");
        advertiser = insertUser("광고주", WALLET);
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(s -> {
            for (String delete : List.of(
                    "DELETE FROM operations WHERE user_id IN (:ids)",
                    "DELETE FROM notifications WHERE user_id IN (:ids)",
                    "DELETE FROM ad_banners WHERE advertiser_id IN (:ids)",
                    "DELETE FROM users WHERE id IN (:ids)")) {
                em.createNativeQuery(delete).setParameter("ids", userIds).executeUpdate();
            }
        });
        userIds.clear();
    }

    // ── 권한 ────────────────────────────────────────────────

    @Test
    @DisplayName("일반 회원이 부르면 403 ADMIN_ONLY — 목록도 처리도")
    void 일반회원은_403() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);

        mockMvc.perform(get(URL).with(user(String.valueOf(advertiser.getId()))).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(advertiser.getId()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
        assertThat(relayer.calls()).isEmpty();
    }

    // ── 목록 ────────────────────────────────────────────────

    @Test
    @DisplayName("목록에 신청자의 가용잔액이 실린다 — 운영자가 누르기 전에 돈이 있는지 본다")
    void 목록에_가용잔액() throws Exception {
        insertPending(advertiser, PRICE, 7);

        mockMvc.perform(get(URL).param("status", "PENDING").with(user(String.valueOf(adminId))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].advertiserNickname").value("광고주"))
                .andExpect(jsonPath("$.items[0].days").value(7))
                .andExpect(jsonPath("$.items[0].price").value("7000"))
                // 체인 30,000 − 이 광고 홀드 7,000
                .andExpect(jsonPath("$.items[0].advertiserAvailable").value("23000"))
                .andExpect(jsonPath("$.items[0].burnPending").value(false));
    }

    // ── 승인 ────────────────────────────────────────────────

    @Test
    @DisplayName("승인하면 게재료 소각 tx 가 그때 나간다 — 광고주 지갑에서 AD_PAY 로, 작업이 생기고, 기간은 승인 시점부터")
    void 승인하면_소각_tx_가_나간다() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);
        // 사흘 전에 신청한 것으로 되돌린다. 승인 시점에 기간이 다시 잡혀야 한다.
        Instant applied = Instant.now().minus(3, ChronoUnit.DAYS);
        setPeriod(ad, applied, applied.plus(7, ChronoUnit.DAYS));

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").isNotEmpty())
                .andExpect(jsonPath("$.adId").value(ad.getId()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(relayer.calls()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("burn");
            assertThat(c.from()).isEqualToIgnoringCase(WALLET);
            assertThat(c.amount()).isEqualTo(PRICE);
            assertThat(c.reason()).isEqualTo(TokenReason.AD_PAY);
        });

        AdBanner saved = banners.findById(ad.getId()).orElseThrow();
        // 확정(ACTIVE)은 인덱서 몫 — 승인만으로는 PENDING 이다
        assertThat(saved.getStatus()).isEqualTo(AdBanner.Status.PENDING);
        // 기간은 사흘 전이 아니라 지금부터 7일
        assertThat(Duration.between(saved.getStartsAt(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(saved.days()).isEqualTo(7);

        List<Operation> ops = operationsOf(advertiser);
        assertThat(ops).singleElement().satisfies(op -> {
            assertThat(op.getKind()).isEqualTo(Operation.Kind.AD);
            assertThat(op.getResourceId()).isEqualTo(ad.getId());
            assertThat(op.getStatus()).isEqualTo(Operation.Status.PENDING);
            assertThat(op.getTxHash()).startsWith("0x");
        });

        // 목록은 "소각 대기" 로 표시한다 — 심사 대기와 구분돼야 두 번 누르지 않는다
        mockMvc.perform(get(URL).with(user(String.valueOf(adminId))).with(csrf()))
                .andExpect(jsonPath("$.items[0].burnPending").value(true));
    }

    @Test
    @DisplayName("자기 홀드는 빼고 본다 — 잔액이 딱 게재료만큼이어도 승인된다")
    void 자기_홀드는_빼고_본다() throws Exception {
        relayer.withBalance(PRICE); // 가용잔액은 0 이지만 그중 7,000 이 이 광고 자신의 홀드다
        AdBanner ad = insertPending(advertiser, PRICE, 7);

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isAccepted());
        assertThat(relayer.calls()).hasSize(1);
    }

    @Test
    @DisplayName("승인 시점에 잔액이 모자라면 409 — 배너는 PENDING 으로 남고 한 푼도 안 나간다")
    void 잔액_부족은_409_PENDING_유지() throws Exception {
        relayer.withBalance(PRICE.subtract(BigInteger.ONE));
        AdBanner ad = insertPending(advertiser, PRICE, 7);

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(banners.findById(ad.getId()).orElseThrow().getStatus()).isEqualTo(AdBanner.Status.PENDING);
        assertThat(relayer.calls()).isEmpty();
        assertThat(operationsOf(advertiser)).isEmpty();
    }

    @Test
    @DisplayName("소각 전송이 실패하면 409·작업 FAILED — 배너는 PENDING 으로 남아 다시 승인할 수 있다")
    void 전송_실패는_PENDING_유지_재승인_가능() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);
        relayer.thenInsufficientBalance(); // 체인 시뮬레이션이 거부하는 경우(가용잔액과 체인이 어긋난 순간)

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        assertThat(banners.findById(ad.getId()).orElseThrow().getStatus()).isEqualTo(AdBanner.Status.PENDING);
        assertThat(operationsOf(advertiser)).singleElement()
                .extracting(Operation::getStatus).isEqualTo(Operation.Status.FAILED);

        // 다시 누르면 새 작업으로 다시 보낸다. 사용자가 다시 신청하고 다시 서명할 필요가 없다.
        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isAccepted());
        assertThat(operationsOf(advertiser)).hasSize(2);
        assertThat(relayer.calls()).hasSize(2);
    }

    @Test
    @DisplayName("체인이 꺼져 있으면 503 — 작업도 안 만들고 기간도 안 건드린다")
    void 체인이_꺼져_있으면_503() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);
        Instant before = banners.findById(ad.getId()).orElseThrow().getStartsAt();
        relayer.disable();

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAIN_UNAVAILABLE"));

        assertThat(operationsOf(advertiser)).isEmpty();
        assertThat(banners.findById(ad.getId()).orElseThrow().getStartsAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("승인 시점에 자리가 찼으면 409 AD_SLOT_SOLD_OUT — 기간이 밀려 다른 광고와 겹칠 수 있다")
    void 승인_시_슬롯_재검사() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);
        User other = insertUser("다른광고주", "0x" + "cd".repeat(20));
        insertActive(other, Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(30, ChronoUnit.DAYS));

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_SLOT_SOLD_OUT"));

        assertThat(banners.findById(ad.getId()).orElseThrow().getStatus()).isEqualTo(AdBanner.Status.PENDING);
        assertThat(relayer.calls()).isEmpty();
    }

    // ── 반려 · 중복 처리 ────────────────────────────────────

    @Test
    @DisplayName("반려하면 204 · REJECTED — 소각 tx 는 0회. 반려는 돈을 안 태운다")
    void 반려는_소각_0회() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);

        mockMvc.perform(handle(ad.getId(), "REJECT").with(user(String.valueOf(adminId))))
                .andExpect(status().isNoContent());

        assertThat(banners.findById(ad.getId()).orElseThrow().getStatus()).isEqualTo(AdBanner.Status.REJECTED);
        assertThat(relayer.calls()).isEmpty();
        assertThat(operationsOf(advertiser)).isEmpty();
        assertThat(알림수(advertiser, "AD_REJECTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("승인하면 신청자에게 AD_APPROVED 알림이 간다 — 전송이 나간 뒤에")
    void 승인하면_알림() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isAccepted());

        assertThat(알림수(advertiser, "AD_APPROVED")).isEqualTo(1);
    }

    @Test
    @DisplayName("소각이 나가는 중이면 다시 승인도 반려도 409 — 두 번 눌러도 두 번 태우지 않는다")
    void 소각_중에는_중복_처리_409() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);
        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isAccepted());

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_ALREADY_HANDLED"));
        mockMvc.perform(handle(ad.getId(), "REJECT").with(user(String.valueOf(adminId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_ALREADY_HANDLED"));

        assertThat(relayer.calls()).hasSize(1);
    }

    @Test
    @DisplayName("반려한 뒤 승인하면 409 AD_ALREADY_HANDLED")
    void 반려_뒤_승인은_409() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);
        mockMvc.perform(handle(ad.getId(), "REJECT").with(user(String.valueOf(adminId))))
                .andExpect(status().isNoContent());

        mockMvc.perform(handle(ad.getId(), "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_ALREADY_HANDLED"));
        assertThat(relayer.calls()).isEmpty();
    }

    @Test
    @DisplayName("없는 광고는 404 AD_NOT_FOUND")
    void 없는_광고는_404() throws Exception {
        mockMvc.perform(handle(999_999L, "APPROVE").with(user(String.valueOf(adminId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AD_NOT_FOUND"));
    }

    @Test
    @DisplayName("action 이 없으면 400 INVALID_REQUEST")
    void action_누락은_400() throws Exception {
        AdBanner ad = insertPending(advertiser, PRICE, 7);

        mockMvc.perform(patch(URL + "/" + ad.getId())
                        .with(user(String.valueOf(adminId))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("action"));
    }

    // ── 도우미 ──────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder handle(Long adId, String action) {
        return patch(URL + "/" + adId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\": \"%s\"}".formatted(action));
    }

    // ── 픽스처 ──────────────────────────────────────────────

    private Long insertAdmin(String nickname) {
        Long id = tx.execute(s -> {
            em.createNativeQuery("""
                    INSERT INTO users (nickname, role, status, created_at, updated_at)
                    VALUES (?, 'ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """).setParameter(1, nickname).executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM users WHERE nickname = ?")
                    .setParameter(1, nickname).getSingleResult()).longValue();
        });
        userIds.add(id);
        return id;
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

    private AdBanner insertPending(User owner, BigInteger price, int days) {
        Instant now = Instant.now();
        return tx.execute(s -> {
            AdBanner banner = AdBanner.request(
                    em.getReference(User.class, owner.getId()),
                    "/api/v1/uploads/x", "https://ad.example.com",
                    now, now.plus(days, ChronoUnit.DAYS), price);
            em.persist(banner);
            return banner;
        });
    }

    private void insertActive(User owner, Instant startsAt, Instant endsAt) {
        tx.executeWithoutResult(s -> {
            AdBanner banner = AdBanner.request(
                    em.getReference(User.class, owner.getId()),
                    "/api/v1/uploads/y", "https://other.example.com",
                    startsAt, endsAt, PRICE);
            banner.activate("0x" + "1".repeat(64));
            em.persist(banner);
        });
    }

    /** 신청 시각을 되돌린다 — 승인이 기간을 다시 잡는지 보려면 옛 기간이 필요하다. */
    private void setPeriod(AdBanner banner, Instant startsAt, Instant endsAt) {
        tx.executeWithoutResult(s -> em.createNativeQuery(
                        "UPDATE ad_banners SET starts_at = ?, ends_at = ? WHERE id = ?")
                .setParameter(1, java.sql.Timestamp.from(startsAt))
                .setParameter(2, java.sql.Timestamp.from(endsAt))
                .setParameter(3, banner.getId())
                .executeUpdate());
    }

    private long 알림수(User owner, String type) {
        return tx.execute(s -> ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?")
                .setParameter(1, owner.getId()).setParameter(2, type).getSingleResult()).longValue());
    }

    private List<Operation> operationsOf(User owner) {
        return tx.execute(s -> em.createQuery(
                        "select o from Operation o where o.user.id = :id order by o.createdAt", Operation.class)
                .setParameter("id", owner.getId())
                .getResultList());
    }
}
