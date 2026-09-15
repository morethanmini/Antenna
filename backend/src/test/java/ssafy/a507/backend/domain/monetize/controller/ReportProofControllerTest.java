package ssafy.a507.backend.domain.monetize.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
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
import org.web3j.crypto.Credentials;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.common.security.SignatureNonceStore;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.domain.monetize.dto.ReportCreateRequest;
import ssafy.a507.backend.domain.monetize.report.ReportSeal;
import ssafy.a507.backend.domain.prediction.commit.CommitHashes;
import ssafy.a507.backend.support.TestNonceStoreConfig;
import ssafy.a507.backend.support.WalletSignatures;

/**
 * ANT-CHAIN-17 — 리포트 검산 재료(GET /reports/{id}/proof).
 *
 * <p>이 화면의 존재 이유가 "서버 말을 믿지 않는다" 라서, 테스트도 <b>서버가 준 값으로 실제로 다시 접어</b>
 * 대조한다. 응답 필드가 채워졌는지만 보면 규격이 바뀌어도 통과한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestNonceStoreConfig.class)
@DisplayName("리포트 검산")
class ReportProofControllerTest {

    private static final String PRIVATE_KEY =
            "0x4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318";
    private static final String DUMMY_SIGNATURE = "0x" + "11".repeat(65);

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

    private final Map<String, String> redisStore = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Credentials wallet;
    private Long authorId;
    private Long viewerId;

    @BeforeEach
    void setUp() {
        stubRedis();
        wallet = Credentials.create(PRIVATE_KEY);
        authorId = insertUser("발행자");
        viewerId = insertUser("열람자");
        linkWallet(authorId, wallet.getAddress());
        flush();
    }

    @Nested
    @DisplayName("① 봉인 대조 재료")
    class Seal {

        @Test
        @DisplayName("서버가 준 salt 로 본문을 다시 접으면 bodyHash 가 나오고, 그것으로 reportHash 가 나온다")
        void 두_겹이_재현된다() throws Exception {
            String body = "본문 1줄\n2줄\n3줄";
            long reportId = publish("재현 리포트", body, true);

            JsonNode proof = proofOf(reportId, authorId);

            String salt = proof.get("salt").asText();
            String bodyHash = proof.get("bodyHash").asText();
            String reportHash = proof.get("reportHash").asText();

            // ①-1 안쪽 — keccak256(body ‖ salt)
            assertThat(CommitHashes.noteHash(body, salt)).isEqualTo(bodyHash);

            // ①-2 바깥 — 봉인 문자열을 응답 값만으로 다시 조립한다(본문 없이도 된다)
            String canonical = ReportSeal.HEADER
                    + "\nauthorId=" + proof.get("authorId").asLong()
                    + "\ntitle=" + proof.get("title").asText()
                    + "\nvisibility=" + proof.get("visibility").asBoolean()
                    + "\nbodyHash=" + bodyHash;
            assertThat(CommitHashes.keccak256Hex(canonical)).isEqualTo(reportHash);
        }

        @Test
        @DisplayName("공개 리포트의 salt 는 남에게도 온다 — 본문을 이미 다 주므로 숨겨서 지킬 것이 없다")
        void 공개_리포트_salt_는_전원() throws Exception {
            long reportId = publish("공개 리포트", "본문", true);

            assertThat(proofOf(reportId, viewerId).get("salt").asText()).isNotBlank();
        }

        @Test
        @DisplayName("구독자 전용 리포트의 salt 는 미구독자에게 오지 않는다 — 키가 아예 빠진다")
        void 전용_리포트_salt_는_구독자만() throws Exception {
            long reportId = publish("구독자 전용", "본문", false);

            JsonNode proof = proofOf(reportId, viewerId);
            assertThat(proof.has("salt")).isFalse();
            // 바깥 해시는 그대로 온다 — 비구독자도 "수정되지 않았다" 는 검증할 수 있어야 한다
            assertThat(proof.get("bodyHash").asText()).isNotBlank();
            assertThat(proof.get("reportHash").asText()).isNotBlank();
        }

        @Test
        @DisplayName("ACTIVE 구독자에게는 전용 리포트의 salt 도 온다")
        void 구독자는_전용_salt_를_받는다() throws Exception {
            long reportId = publish("구독자 전용2", "본문", false);
            insertSubscription(viewerId, authorId);
            flush();

            assertThat(proofOf(reportId, viewerId).get("salt").asText()).isNotBlank();
        }

        @Test
        @DisplayName("작성자 본인은 자기 전용 리포트의 salt 를 받는다")
        void 작성자는_받는다() throws Exception {
            long reportId = publish("내 전용 리포트", "본문", false);

            assertThat(proofOf(reportId, authorId).get("salt").asText()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("③ 작성자 대조 재료")
    class Signature {

        @Test
        @DisplayName("서명·서명자·nonce 가 함께 오고, 그 셋으로 payload 를 다시 조립할 수 있다")
        void 서명_재료가_완전하다() throws Exception {
            String title = "서명 재료";
            String body = "본문";
            long reportId = publish(title, body, true);

            JsonNode proof = proofOf(reportId, viewerId);

            assertThat(proof.get("signature").asText()).matches("^0x[0-9a-fA-F]{130}$");
            assertThat(proof.get("signerAddress").asText())
                    .isEqualTo(wallet.getAddress().toLowerCase(Locale.ROOT));
            assertThat(proof.get("signNonce").asText()).isNotBlank();
            assertThat(proof.get("chainId").asLong()).isEqualTo(signatureGuard.chainId());

            /* nonce 가 있어야 payload 를 다시 만들 수 있다 — 브라우저가 서명에서 주소를 복원해
               signerAddress 와 대조하는 것이 ③이고, 서버가 준 주소를 그대로 믿는 것과 다르다.
               여기서는 같은 재료로 조립한 payload 가 발행 때 서명한 것과 같은지까지 본다. */
            String rebuilt = new ReportCreateRequest(title, body, true, DUMMY_SIGNATURE)
                    .signingPayload(proof.get("signNonce").asText(), proof.get("chainId").asLong());
            assertThat(WalletSignatures.sign(rebuilt, wallet))
                    .isEqualTo(proof.get("signature").asText());
        }

        @Test
        @DisplayName("서명 이전에 발행된 리포트는 서명 키가 아예 없다 — 실패가 아니라 그 기능 이전 글이다")
        void 서명_이전_발행분() throws Exception {
            long reportId = insertSealedReportWithoutSignature("옛 리포트");
            flush();

            JsonNode proof = proofOf(reportId, viewerId);
            assertThat(proof.has("signature")).isFalse();
            assertThat(proof.has("signerAddress")).isFalse();
            assertThat(proof.has("signNonce")).isFalse();
            // 봉인은 있으니 ①·②는 돈다
            assertThat(proof.get("reportHash").asText()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("② 앵커 · 없는 것들")
    class Anchor {

        @Test
        @DisplayName("아직 배치에 안 묶였으면 anchor 는 없고 상태가 WAITING 이다 — 실패가 아니다")
        void 앵커_대기() throws Exception {
            long reportId = publish("대기 리포트", "본문", true);

            JsonNode proof = proofOf(reportId, viewerId);
            assertThat(proof.has("anchor")).isFalse();
            assertThat(proof.get("anchorStatus").asText()).isEqualTo("WAITING");
        }

        @Test
        @DisplayName("봉인 이전에 발행된 리포트는 404 REPORT_NOT_SEALED — REPORT_NOT_FOUND 와 다른 code 다")
        void 봉인_안된_리포트() throws Exception {
            long reportId = insertUnsealedReport("봉인 없음");
            flush();

            mockMvc.perform(get(proofUrl(reportId)).with(user(String.valueOf(viewerId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REPORT_NOT_SEALED"))
                    .andExpect(jsonPath("$.field").value("reportId"));
        }

        @Test
        @DisplayName("없는 리포트는 404 REPORT_NOT_FOUND")
        void 없는_리포트() throws Exception {
            mockMvc.perform(get(proofUrl(987_654_321L)).with(user(String.valueOf(viewerId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        }

        @Test
        @DisplayName("미인증은 401")
        void 미인증() throws Exception {
            mockMvc.perform(get(proofUrl(1L))).andExpect(status().isUnauthorized());
        }
    }

    // ── 도우미 ──────────────────────────────────────────────

    private static String proofUrl(long reportId) {
        return "/api/v1/reports/" + reportId + "/proof";
    }

    private JsonNode proofOf(long reportId, Long viewerId) throws Exception {
        String body = mockMvc.perform(get(proofUrl(reportId)).with(user(String.valueOf(viewerId))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    /** 실제 발행 경로로 만든다 — 봉인·서명이 서비스가 하는 그대로 들어가야 검산 재료가 진짜가 된다. */
    private long publish(String title, String body, boolean visibility) throws Exception {
        ReportCreateRequest unsigned = new ReportCreateRequest(title, body, visibility, DUMMY_SIGNATURE);
        String nonce = nonceStore.issue(authorId, SignatureScope.REPORT_PUBLISH);
        String signature =
                WalletSignatures.sign(unsigned.signingPayload(nonce, signatureGuard.chainId()), wallet);

        String json = "{\"title\":\"" + title
                + "\",\"body\":\"" + body.replace("\n", "\\n")
                + "\",\"visibility\":" + visibility
                + ",\"signature\":\"" + signature + "\"}";

        String response = mockMvc.perform(post("/api/v1/reports")
                        .with(user(String.valueOf(authorId)))
                        .with(csrf())
                        .header("Idempotency-Key", "proof-" + title)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        flush();
        return objectMapper.readTree(response).get("id").asLong();
    }

    /** Redis 대신 맵. 멱등키가 get·set(TTL) 두 연산만 쓴다. */
    private void stubRedis() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        BDDMockito.given(redis.opsForValue()).willReturn(ops);
        BDDMockito.given(ops.get(ArgumentMatchers.anyString()))
                .willAnswer(call -> redisStore.get(call.getArgument(0, String.class)));
        BDDMockito.willAnswer(call -> {
                    redisStore.put(call.getArgument(0), call.getArgument(1));
                    return null;
                })
                .given(ops)
                .set(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(Duration.class));
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

    private void linkWallet(Long userId, String address) {
        em.createNativeQuery("UPDATE users SET wallet_address = ? WHERE id = ?")
                .setParameter(1, address.toLowerCase(Locale.ROOT))
                .setParameter(2, userId)
                .executeUpdate();
    }

    private void insertSubscription(Long subscriberId, Long publisherId) {
        em.createNativeQuery("""
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, auto_renew, created_at, updated_at)
                        VALUES (?, ?, 0, 'ACTIVE', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriberId)
                .setParameter(2, publisherId)
                .executeUpdate();
    }

    /** 봉인 이전 발행분 — body_hash 가 NULL 이다. API 로는 만들 수 없어 행을 직접 넣는다. */
    private long insertUnsealedReport(String title) {
        em.createNativeQuery("""
                        INSERT INTO reports (user_id, title, body, is_public, view_count, created_at)
                        VALUES (?, ?, '본문', true, 0, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, authorId)
                .setParameter(2, title)
                .executeUpdate();
        return reportIdOf(title);
    }

    /** 봉인은 됐는데 서명이 없는 리포트 — ANT-CHAIN-16 이전 발행분이다. */
    private long insertSealedReportWithoutSignature(String title) {
        /* 같은 두 글자의 반복은 CommitHashes 가 거부한다(난수가 아닌 salt 를 거르는 검사).
           실제 발행분과 같은 모양이어야 하므로 아무 64 hex 를 쓴다. */
        String salt = "9f2c74a1b03e5d6817ca49fb20de3705c8a16b94ef3d27015ab6c8309f4e21d7";
        String bodyHash = CommitHashes.noteHash("본문", salt);
        em.createNativeQuery("""
                        INSERT INTO reports
                          (user_id, title, body, is_public, view_count, created_at, salt, body_hash, report_hash)
                        VALUES (?, ?, '본문', true, 0, CURRENT_TIMESTAMP, ?, ?, ?)
                        """)
                .setParameter(1, authorId)
                .setParameter(2, title)
                .setParameter(3, salt)
                .setParameter(4, bodyHash)
                .setParameter(5, CommitHashes.keccak256Hex(
                        ReportSeal.HEADER
                                + "\nauthorId=" + authorId
                                + "\ntitle=" + title
                                + "\nvisibility=true"
                                + "\nbodyHash=" + bodyHash))
                .executeUpdate();
        return reportIdOf(title);
    }

    private long reportIdOf(String title) {
        return ((Number) em.createNativeQuery("SELECT id FROM reports WHERE title = ?")
                        .setParameter(1, title)
                        .getSingleResult())
                .longValue();
    }
}
