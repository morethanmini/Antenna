package ssafy.a507.backend.domain.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import ssafy.a507.backend.common.security.SignatureNonceStore;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.domain.account.dto.WalletLinkRequest;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.service.FakeTokenRelayer;
import ssafy.a507.backend.domain.chain.service.SignupBonusService;
import ssafy.a507.backend.support.TestNonceStoreConfig;
import ssafy.a507.backend.support.WalletSignatures;

/**
 * 지갑 연동의 가입 보너스 mint (ANT-TOKEN-07) — 연동 훅과 재시도 배치. 체인은 {@link FakeTokenRelayer}. H2 다.
 *
 * <p>{@link WalletControllerTest} 와 달리 {@code @Transactional} 롤백을 쓰지 않는다. 연동이 트랜잭션 밖에서 mint 를 보내는데
 * 그 전송은 활성 트랜잭션 안이면 거부된다({@code TokenOperationService} 계약). 픽스처는 {@link TransactionTemplate} 으로
 * 커밋하고 {@code @AfterEach} 가 지운다 — {@code AdControllerTest} 와 같은 방식이다.
 *
 * <p>애노테이션 구성({@code @Import} · {@code @MockitoBean})을 {@code AdControllerTest} 와 똑같이 둔다. 스프링 테스트 컨텍스트
 * 캐시를 같이 쓰려는 것이다 — 컨텍스트가 하나 늘면 전체 빌드의 테스트 힙이 빠듯해진다(ANT-CHAIN-12 트러블슈팅).
 *
 * <p>재시도 배치는 H2 전체에서 대상을 고른다. 다른 테스트가 남긴 회원이 끼어도 흔들리지 않게, 검증은 이 테스트가 만든
 * 지갑으로 걸러서 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestNonceStoreConfig.class, FakeTokenRelayer.Config.class})
@DisplayName("가입 보너스 mint")
class WalletLinkBonusTest {

    private static final long CHAIN_ID = 31337L;
    private static final String DUMMY_SIGNATURE = "0x" + "0".repeat(130);
    /** 테스트 yaml 의 app.token.amounts.signup-bonus. ANT 는 decimals 0 이다. */
    private static final BigInteger BONUS = BigInteger.valueOf(10_000);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired SignatureNonceStore nonceStore;
    @Autowired FakeTokenRelayer relayer;
    @Autowired SignupBonusService signupBonus;

    /** 이 테스트는 Redis 를 쓰지 않는다. {@code AdControllerTest} 와 컨텍스트 캐시 키를 맞추려고 둔다. */
    @MockitoBean StringRedisTemplate redis;

    /** {@code @AfterEach} 가 지울 사용자. 이 사용자에 걸린 작업 · 원장을 같이 지운다. */
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        relayer.reset();
    }

    @AfterEach
    void tearDown() {
        // FK 순서: 작업 · 원장 → 사용자.
        tx.executeWithoutResult(s -> {
            for (String delete : List.of(
                    "DELETE FROM operations WHERE user_id IN (:ids)",
                    "DELETE FROM token_ledger WHERE user_id IN (:ids)",
                    "DELETE FROM users WHERE id IN (:ids)")) {
                em.createNativeQuery(delete).setParameter("ids", userIds).executeUpdate();
            }
        });
        userIds.clear();
    }

    // ── 연동 ──────────────────────────────────────────────

    @Test
    @DisplayName("연동하면 그 지갑으로 10,000 ANT 를 mint 한다 — 응답에 작업 번호, 작업은 PENDING + tx 해시(확정은 인덱서)")
    void 연동하면_보너스를_보낸다() throws Exception {
        Credentials key = newKey();
        Long userId = insertUser(null);

        String body = link(userId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletAddress").value(key.getAddress()))
                .andExpect(jsonPath("$.bonus.amount").value("10000"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(relayer.calls()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("mint");
            assertThat(c.to()).isEqualTo(key.getAddress());
            assertThat(c.amount()).isEqualTo(BONUS);
            assertThat(c.reason()).isEqualTo(TokenReason.SIGNUP_BONUS);
        });
        assertThat(operationsOf(userId)).singleElement().satisfies(op -> {
            assertThat(body).contains("\"operationId\":\"" + op.getId() + "\"");
            assertThat(op.getKind()).isEqualTo(Operation.Kind.SIGNUP_BONUS);
            assertThat(op.getStatus()).isEqualTo(Operation.Status.PENDING);
            assertThat(op.getTxHash()).startsWith("0x");
            assertThat(op.getResourceType()).isNull();
        });
    }

    @Test
    @DisplayName("체인이 꺼져 있어도 연동은 200 — bonus 는 null, 작업을 만들지 않는다(켜진 뒤 재시도 배치가 보낸다)")
    void 체인이_꺼져_있으면_연동만_한다() throws Exception {
        relayer.disable();
        Credentials key = newKey();
        Long userId = insertUser(null);

        link(userId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletAddress").value(key.getAddress()))
                .andExpect(jsonPath("$.bonus").isEmpty());

        assertThat(relayer.calls()).isEmpty();
        assertThat(operationsOf(userId)).isEmpty();
        assertThat(walletOf(userId)).isEqualTo(key.getAddress());
    }

    @Test
    @DisplayName("mint 전송이 실패해도 연동은 200 — 작업은 FAILED 로 남고 응답에 그 번호가 있다")
    void 전송이_실패해도_연동은_성공한다() throws Exception {
        relayer.thenUnavailable();
        Credentials key = newKey();
        Long userId = insertUser(null);

        link(userId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bonus.operationId").isNotEmpty());

        assertThat(walletOf(userId)).isEqualTo(key.getAddress());
        assertThat(operationsOf(userId)).singleElement().satisfies(op -> {
            assertThat(op.getStatus()).isEqualTo(Operation.Status.FAILED);
            assertThat(op.getErrorCode()).isEqualTo("CHAIN_UNAVAILABLE");
        });
    }

    @Test
    @DisplayName("이미 연동한 계정이 다시 연동하면 409 — 보너스 작업도 mint 도 한 번뿐이다")
    void 재연동은_보너스를_다시_주지_않는다() throws Exception {
        Long userId = insertUser(null);
        link(userId, newKey()).andExpect(status().isOk());

        link(userId, newKey())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_ALREADY_LINKED"));

        assertThat(relayer.calls()).hasSize(1);
        assertThat(operationsOf(userId)).hasSize(1);
    }

    // ── 재시도 배치 ──────────────────────────────────────────

    @Test
    @DisplayName("재시도 — 전송이 실패했던 회원에게 새 작업으로 다시 mint 한다")
    void 재시도는_실패했던_회원에게_다시_보낸다() {
        String wallet = newWallet();
        Long userId = insertUser(wallet);
        insertBonusOp(userId, Operation.Status.FAILED);

        signupBonus.retryOnce();

        assertThat(mintsTo(wallet)).singleElement().satisfies(c -> {
            assertThat(c.amount()).isEqualTo(BONUS);
            assertThat(c.reason()).isEqualTo(TokenReason.SIGNUP_BONUS);
        });
        assertThat(operationsOf(userId))
                .extracting(Operation::getStatus)
                .containsExactlyInAnyOrder(Operation.Status.FAILED, Operation.Status.PENDING);
    }

    @Test
    @DisplayName("재시도 — 체인이 꺼진 채 연동했거나 기능 배포 전에 연동한 회원(작업 없음)도 받는다")
    void 재시도는_작업이_없는_회원도_받는다() {
        String wallet = newWallet();
        Long userId = insertUser(wallet);

        signupBonus.retryOnce();

        assertThat(mintsTo(wallet)).hasSize(1);
        assertThat(operationsOf(userId))
                .singleElement()
                .extracting(Operation::getKind)
                .isEqualTo(Operation.Kind.SIGNUP_BONUS);
    }

    @Test
    @DisplayName("재시도 대상이 아니다 — 진행 중·성공 작업이 있거나, 원장에 보너스가 있거나, 차단됐거나, 지갑이 없다")
    void 재시도_대상이_아닌_회원() {
        String pending = newWallet();
        insertBonusOp(insertUser(pending), Operation.Status.PENDING);
        String succeeded = newWallet();
        insertBonusOp(insertUser(succeeded), Operation.Status.SUCCEEDED);
        // SEND_LOST 로 닫혔는데 tx 는 실제로 나가 인덱서가 원장에 쓴 경우. 작업 상태만 보면 다시 보내게 된다.
        String ledgered = newWallet();
        Long ledgeredId = insertUser(ledgered);
        insertBonusOp(ledgeredId, Operation.Status.FAILED);
        creditBonus(ledgeredId);
        String banned = newWallet();
        ban(insertUser(banned));
        Long noWallet = insertUser(null);

        signupBonus.retryOnce();

        assertThat(relayer.calls())
                .extracting(FakeTokenRelayer.Call::to)
                .doesNotContain(pending, succeeded, ledgered, banned);
        assertThat(operationsOf(ledgeredId)).hasSize(1);
        assertThat(operationsOf(noWallet)).isEmpty();
    }

    @Test
    @DisplayName("재시도 — 전송이 한 번 실패하면 그 회차를 멈춘다(RPC 장애에 FAILED 작업만 쌓지 않는다)")
    void 재시도는_첫_실패에서_멈춘다() {
        insertUser(newWallet());
        insertUser(newWallet());
        relayer.thenUnavailable();

        int sent = signupBonus.retryOnce();

        assertThat(sent).isZero();
        assertThat(relayer.calls()).hasSize(1);
    }

    @Test
    @DisplayName("재시도 — 체인이 꺼져 있으면 작업을 만들지 않는다")
    void 재시도는_체인이_꺼져_있으면_쉰다() {
        Long userId = insertUser(newWallet());
        relayer.disable();

        assertThat(signupBonus.retryOnce()).isZero();
        assertThat(relayer.calls()).isEmpty();
        assertThat(operationsOf(userId)).isEmpty();
    }

    // ── 픽스처 ──────────────────────────────────────────────

    /** 서버와 같은 규칙으로 payload 를 조립해 서명한다. 프론트가 해야 할 일과 같다. */
    private ResultActions link(Long userId, Credentials key) throws Exception {
        String nonce = nonceStore.issue(userId, SignatureScope.WALLET_LINK);
        String payload =
                new WalletLinkRequest(key.getAddress(), DUMMY_SIGNATURE).signingPayload(nonce, CHAIN_ID);
        String body = "{\"address\":\"" + key.getAddress() + "\",\"signature\":\""
                + WalletSignatures.sign(payload, key) + "\"}";
        return mockMvc.perform(post("/api/v1/wallet/link")
                .with(user(String.valueOf(userId)))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** 다른 테스트가 남긴 주소와 부딪히지 않게 매번 새 키. */
    private static Credentials newKey() throws Exception {
        return Credentials.create(Keys.createEcKeyPair());
    }

    /** 서명이 필요 없는 재시도 픽스처용 주소. 저장 형식과 같은 소문자 40자리. */
    private static String newWallet() {
        String hex = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return "0x" + hex.substring(0, 40);
    }

    private Long insertUser(String wallet) {
        User user = tx.execute(s -> {
            User u = User.create();
            u.changeNickname("보너스-" + UUID.randomUUID().toString().substring(0, 8));
            if (wallet != null) {
                u.linkWallet(wallet);
            }
            em.persist(u);
            return u;
        });
        userIds.add(user.getId());
        return user.getId();
    }

    private void insertBonusOp(Long userId, Operation.Status status) {
        tx.executeWithoutResult(s -> {
            Operation op = Operation.accept(
                    em.getReference(User.class, userId), Operation.Kind.SIGNUP_BONUS, null, null);
            switch (status) {
                case PENDING -> op.markSent("0x" + "a".repeat(64));
                case SUCCEEDED -> op.markSucceeded("0x" + "b".repeat(64));
                case FAILED -> op.markFailed("CHAIN_UNAVAILABLE", "테스트 픽스처");
            }
            em.persist(op);
        });
    }

    /** 원장은 append-only 라 엔티티 팩터리 없이 네이티브로 넣는다 — 인덱서가 Minted 를 반영한 결과와 같은 행. */
    private void creditBonus(Long userId) {
        tx.executeWithoutResult(s -> em.createNativeQuery("""
                        INSERT INTO token_ledger (user_id, delta, reason, created_at)
                        VALUES (?, ?, 'SIGNUP_BONUS', CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, BONUS)
                .executeUpdate());
    }

    /** User 에 차단 전이 메서드가 아직 없다. */
    private void ban(Long userId) {
        tx.executeWithoutResult(s -> em.createNativeQuery("UPDATE users SET status = 'BANNED' WHERE id = ?")
                .setParameter(1, userId)
                .executeUpdate());
    }

    private List<Operation> operationsOf(Long userId) {
        return tx.execute(s -> em.createQuery(
                        "select o from Operation o where o.user.id = :id", Operation.class)
                .setParameter("id", userId)
                .getResultList());
    }

    private String walletOf(Long userId) {
        return tx.execute(s -> (String) em.createNativeQuery("SELECT wallet_address FROM users WHERE id = :id")
                .setParameter("id", userId)
                .getSingleResult());
    }

    private List<FakeTokenRelayer.Call> mintsTo(String wallet) {
        return relayer.calls().stream()
                .filter(c -> "mint".equals(c.kind()) && wallet.equals(c.to()))
                .toList();
    }
}
