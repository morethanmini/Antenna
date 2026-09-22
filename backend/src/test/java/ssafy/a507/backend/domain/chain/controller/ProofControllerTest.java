package ssafy.a507.backend.domain.chain.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.domain.chain.merkle.MerkleTree;
import ssafy.a507.backend.support.WalletSignatures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ANT-CHAIN-06 · ANT-CHAIN-18 — 검산 proof. H2.
 *
 * <p>예측·커밋·구독은 팩터리가 없어(PRED-01·02 몫) 네이티브 INSERT 로 심는다. 게이팅 두 겹(미판정 예측 / 근거 salt)과
 * proof 의 실제 유효성({@link MerkleTree#verify})을 본다. 체인은 부르지 않는다 — 루트 대조는 브라우저 몫이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("예측 검산 proof API")
class ProofControllerTest {

    private static final String CONTRACT = "0x07f8cfe2bc6174d62be8226e5e8699ffbf0d6d6a";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);
    private static final String SALT = "5e" + "00".repeat(31);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private long authorId;
    private long otherId;

    @BeforeEach
    void setUp() {
        authorId = insertUser();
        otherId = insertUser();
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES ('005930', '삼성전자', true)")
                .executeUpdate();
    }

    // ── 겹 ① 미판정 예측 게이팅 ──────────────────────────────────────

    @Nested
    @DisplayName("미판정(OPEN) 예측")
    class Unsettled {

        @Test
        @DisplayName("작성자 본인은 200 — 앵커 전이면 anchor 는 null, anchorStatus 는 WAITING")
        void 작성자_앵커_전() throws Exception {
            long pid = insertPrediction(authorId, "OPEN");
            insertCommit(pid, "seed", null);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.predictionId").value(pid))
                    .andExpect(jsonPath("$.commitHash").value(hashOf("seed")))
                    .andExpect(jsonPath("$.signerAddress").value("0x" + "11".repeat(20)))
                    .andExpect(jsonPath("$.payload.stockCode").value("005930"))
                    .andExpect(jsonPath("$.payload.direction").value("UP"))
                    .andExpect(jsonPath("$.payload.targetPrice").value(80000.0))
                    .andExpect(jsonPath("$.payload.horizon").value(30))
                    .andExpect(jsonPath("$.payload.noteHash").value(noteHashOf("seed")))
                    .andExpect(jsonPath("$.anchor").value((Object) null))
                    .andExpect(jsonPath("$.anchorStatus").value("WAITING"))
                    // 미판정·리빌 전이어도 작성자는 salt 를 받는다(2026-09-15) — 근거 대조를 만기까지 미룰 이유가 없다.
                    .andExpect(jsonPath("$.salt").value(SALT))
                    .andExpect(jsonPath("$.settle").value((Object) null));
        }

        @Test
        @DisplayName("구독하지 않은 남은 403 PREDICTION_FORBIDDEN — 404 로 숨기지 않는다")
        void 비구독자_403() throws Exception {
            long pid = insertPrediction(authorId, "OPEN");
            insertCommit(pid, "seed", null);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(otherId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PREDICTION_FORBIDDEN"));
        }

        @Test
        @DisplayName("유효 구독자(ACTIVE + 기간 안)는 200")
        void 유효_구독자_200() throws Exception {
            long pid = insertPrediction(authorId, "OPEN");
            insertCommit(pid, "seed", null);
            insertSubscription(otherId, authorId, "ACTIVE", Instant.now().plusSeconds(86_400));

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(otherId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commitHash").value(hashOf("seed")))
                    // 판정 전이라도 구독자는 salt 를 받는다 — 이것이 없으면 돈을 내고도 근거 대조가 만기까지 막힌다.
                    .andExpect(jsonPath("$.salt").value(SALT));
        }

        @Test
        @DisplayName("만료된 구독(ACTIVE 지만 expires_at 이 지남)은 403 — 상태가 아니라 기간이 근거다")
        void 만료_구독자_403() throws Exception {
            long pid = insertPrediction(authorId, "OPEN");
            insertCommit(pid, "seed", null);
            insertSubscription(otherId, authorId, "ACTIVE", Instant.now().minusSeconds(60));

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(otherId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("결제 대기(PENDING) 구독은 아직 열람 권한이 아니다 — 403")
        void 대기_구독자_403() throws Exception {
            long pid = insertPrediction(authorId, "OPEN");
            insertCommit(pid, "seed", null);
            insertSubscription(otherId, authorId, "PENDING", null);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(otherId))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("판정(HIT) 뒤")
    class Settled {

        @Test
        @DisplayName("구독 없는 남도 200 — settle 이 채워지고 sourceUrl 은 만기일·종목으로 조립된다")
        void 타인_200_settle() throws Exception {
            long pid = insertPrediction(authorId, "HIT");
            insertCommit(pid, "seed", null);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(otherId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.settle.status").value("HIT"))
                    .andExpect(jsonPath("$.settle.settleDate").value("2026-10-05"))
                    .andExpect(jsonPath("$.settle.settlePrice").value(82000.0))
                    .andExpect(jsonPath("$.settle.basePrice").value(79000.0))
                    .andExpect(jsonPath("$.settle.sourceUrl").value(
                            org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.containsString("basDt=20261005"),
                                    org.hamcrest.Matchers.containsString("likeSrtnCd=005930"),
                                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("serviceKey")))));
        }

        @Test
        @DisplayName("만기가 휴장이면 settleDate 도 실제 판정 거래일(settled_on) — 화면의 판정일과 대조 링크 날짜가 같아야 한다")
        void 판정일은_settled_on() throws Exception {
            // 만기 2026-10-05 · 실제 판정 거래일 2026-10-07. 화면 ④단계가 "판정일" 로 적는 값과 원본 링크의 basDt 가 같아야
            // 사용자가 그 날짜로 포털을 열었을 때 종가가 나온다 — 만기일로 열면 휴장이라 빈 응답이다.
            long pid = insertPrediction(authorId, "HIT", LocalDate.of(2026, 10, 7));
            insertCommit(pid, "seed", null);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(otherId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.settle.settleDate").value("2026-10-07"))
                    .andExpect(jsonPath("$.settle.sourceUrl").value(org.hamcrest.Matchers.containsString("basDt=20261007")));
        }

        @Test
        @DisplayName("기준가를 딴 거래일도 내린다 — basedOn. 기준일이 휴장이면 그 뒤 첫 거래일이고, 옛 행(based_on 없음)은 기준일로 물러선다(ANT-PRED-09)")
        void 기준가_거래일_basedOn() throws Exception {
            long fresh = insertPrediction(authorId, "HIT", LocalDate.of(2026, 10, 7), LocalDate.of(2026, 9, 7));
            insertCommit(fresh, "fresh", null);
            long legacy = insertPrediction(authorId, "HIT", null, null);
            insertCommit(legacy, "legacy", null);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", fresh).with(user(str(otherId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.settle.basedOn").value("2026-09-07"));
            mockMvc.perform(get("/api/v1/predictions/{id}/proof", legacy).with(user(str(otherId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.settle.basedOn").value("2026-09-05"));
        }

        @Test
        @DisplayName("리빌 전에도 작성자·구독자는 salt 를 받는다 — 받을 자격은 구독이지 시간이 아니다(2026-09-15)")
        void 리빌_전에도_구독자는_salt() throws Exception {
            long pid = insertPrediction(authorId, "HIT");
            insertCommit(pid, "b", null);
            long subscriberId = insertUser();
            insertSubscription(subscriberId, authorId, "ACTIVE", Instant.now().plusSeconds(86_400));

            // 작성자 — 리빌 전(revealedAt null)이어도 salt 가 나간다.
            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revealedAt").value((Object) null))
                    .andExpect(jsonPath("$.salt").value(SALT))
                    // noteHash 는 salt 가 아니라 공개 값 — 리빌 전에도 (볼 수 있는 사람에게는) 나간다.
                    .andExpect(jsonPath("$.payload.noteHash").value(noteHashOf("b")));

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(subscriberId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revealedAt").value((Object) null))
                    .andExpect(jsonPath("$.salt").value(SALT));

            // 경계는 그대로다 — 비구독자는 리빌 전에도 후에도 salt 가 없다.
            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(otherId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.salt").value((Object) null));
        }

        @Test
        @DisplayName("비구독자는 salt 없이 noteHash 로 ①단계를 검산한다 — salt(근거 salt)는 작성자·구독자만 받는다")
        void 리빌_후_salt_는_구독자만() throws Exception {
            long pid = insertPrediction(authorId, "HIT");
            insertCommit(pid, "a", Instant.parse("2026-10-05T04:31:00Z"));
            long subscriberId = insertUser();
            insertSubscription(subscriberId, authorId, "ACTIVE", Instant.now().plusSeconds(86_400));

            // 비구독자: 리빌 시각과 noteHash 는 받지만 salt 는 없다.
            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(otherId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revealedAt").value("2026-10-05T04:31:00Z"))
                    .andExpect(jsonPath("$.payload.noteHash").value(noteHashOf("a")))
                    .andExpect(jsonPath("$.salt").value((Object) null));
            // 작성자·구독자: salt 까지.
            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.salt").value(SALT));
            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(subscriberId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.salt").value(SALT));
        }

        @Test
        @DisplayName("응답에 noteSalt 키는 없다 — salt 하나가 그 자리다 (ANT-PRED-02, 09-09 결정)")
        void noteSalt_키_없음() throws Exception {
            long pid = insertPrediction(authorId, "HIT");
            insertCommit(pid, "a", Instant.parse("2026-10-05T04:31:00Z"));

            String body = mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            JsonNode json = JSON.readTree(body);
            assertThat(json.has("noteSalt")).isFalse();
            assertThat(json.has("salt")).isTrue();
        }
    }

    // ── 앵커 · proof ──────────────────────────────────────────────────

    @Test
    @DisplayName("확정 배치의 커밋 — merkleProof 로 접은 루트가 merkleRoot 와 같고 leafIndex 는 리프 순서상 자리다")
    void proof_가_실제로_검증된다() throws Exception {
        List<Long> pids = new ArrayList<>();
        List<String> seeds = List.of("l0", "l1", "l2", "l3", "l4");
        for (String s : seeds) {
            long pid = insertPrediction(authorId, "OPEN");
            insertCommit(pid, s, null);
            pids.add(pid);
        }
        AnchorBatch batch = confirmedBatch(seeds, pids);
        long target = pids.get(2);

        String body = mockMvc.perform(get("/api/v1/predictions/{id}/proof", target).with(user(str(authorId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchorStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.anchor.batchId").value(batch.getId()))
                .andExpect(jsonPath("$.anchor.merkleRoot").value(batch.getMerkleRoot()))
                .andExpect(jsonPath("$.anchor.leafIndex").value(2))
                .andExpect(jsonPath("$.anchor.leafCount").value(5))
                .andExpect(jsonPath("$.anchor.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.anchor.txHash").value(batch.getTxHash()))
                .andExpect(jsonPath("$.anchor.blockNumber").value(batch.getBlockNumber()))
                .andExpect(jsonPath("$.anchor.contractAddress").value(CONTRACT))
                .andExpect(jsonPath("$.anchor.chainId").value(31337))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = JSON.readTree(body);
        List<byte[]> proof = new ArrayList<>();
        json.get("anchor").get("merkleProof").forEach(n -> proof.add(Numeric.hexStringToByteArray(n.asString())));
        byte[] commitHash = Numeric.hexStringToByteArray(json.get("commitHash").asString());
        byte[] root = Numeric.hexStringToByteArray(json.get("anchor").get("merkleRoot").asString());

        assertThat(MerkleTree.verify(commitHash, proof, root)).isTrue();
        // 남의 커밋 해시로는 같은 proof 가 통하지 않는다.
        assertThat(MerkleTree.verify(Numeric.hexStringToByteArray(hashOf("l3")), proof, root)).isFalse();
    }

    @Test
    @DisplayName("배치가 아직 PENDING 이면 proof 는 내리되 anchorStatus 가 PENDING 이다 — 트리는 DB 만으로 정해진다")
    void 미확정_배치는_PENDING() throws Exception {
        long pid = insertPrediction(authorId, "OPEN");
        insertCommit(pid, "only", null);
        String root = Numeric.toHexString(MerkleTree.build(List.of(Hash.sha3("only".getBytes()))).root());
        AnchorBatch batch = AnchorBatch.open(DAY, root, 1, CONTRACT, 31337L);
        em.persist(batch);
        em.flush();
        assign(batch.getId(), pid);

        mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anchorStatus").value("PENDING"))
                .andExpect(jsonPath("$.anchor.status").value("PENDING"))
                .andExpect(jsonPath("$.anchor.merkleProof").isEmpty())
                .andExpect(jsonPath("$.anchor.txHash").value((Object) null));
    }

    @Test
    @DisplayName("리프로 접은 루트가 저장된 루트와 다르면 500 — 틀린 proof 를 내리지 않는다")
    void 루트_불일치_500() throws Exception {
        long pid = insertPrediction(authorId, "OPEN");
        insertCommit(pid, "x", null);
        AnchorBatch batch = AnchorBatch.open(DAY, hashOf("not-the-root"), 1, CONTRACT, 31337L);
        em.persist(batch);
        em.flush();
        assign(batch.getId(), pid);

        mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    // ── 기타 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("없는 예측은 404 PREDICTION_NOT_FOUND")
    void 없는_예측_404() throws Exception {
        mockMvc.perform(get("/api/v1/predictions/{id}/proof", 999_999).with(user(str(authorId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PREDICTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없이는 401")
    void 인증_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/predictions/{id}/proof", 1)).andExpect(status().isUnauthorized());
    }

    /**
     * ③ 서명자 대조 (ANT-CHAIN-18).
     *
     * <p>여기서 보는 것은 "서버가 준 {@code signerAddress} 가 맞나" 가 아니다 — 그건 서버 말을 서버 말로 확인하는
     * 일이다. <b>응답에 든 값만으로 서명 문자열을 다시 조립해 주소를 복원할 수 있는가</b>를 본다. 브라우저가 할 일을
     * 그대로 한다. 재료가 한 줄이라도 빠지면 이 테스트가 깨진다.
     */
    @Nested
    @DisplayName("서명자 대조 재료")
    class Signer {

        /** 테스트 전용 키. 어디에도 쓰이지 않는 값이다. */
        private static final String PRIVATE_KEY =
                "0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d";
        private static final String NONCE = "3f8c1d5b7e2a49065b1e7c3a9d24f8061c7b3e5a92d4608f7a3c1e5b9d268047";

        @Autowired SignatureGuard signatureGuard;

        @Test
        @DisplayName("응답 값만으로 서명 문자열을 다시 만들면 서명자 주소가 복원된다 — 서버 주소를 옮겨 적지 않는다")
        void 브라우저가_서명자를_복원한다() throws Exception {
            Credentials wallet = Credentials.create(PRIVATE_KEY);
            long pid = insertPrediction(authorId, "OPEN");
            String noteHash = noteHashOf("seed");
            String payload = signingPayload(noteHash, signatureGuard.chainId(), NONCE);
            String signature = WalletSignatures.sign(payload, wallet);
            insertSignedCommit(pid, "seed", signature, wallet.getAddress(), NONCE);

            String body = mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.signNonce").value(NONCE))
                    .andExpect(jsonPath("$.chainId").value((int) signatureGuard.chainId()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // 여기부터가 브라우저 몫 — 응답만 보고 문자열을 다시 만든다.
            JsonNode res = JSON.readTree(body);
            String rebuilt = signingPayload(
                    res.get("payload").get("noteHash").asString(),
                    res.get("chainId").asLong(),
                    res.get("signNonce").asString());
            assertThat(recover(rebuilt, res.get("signature").asString()))
                    .isEqualToIgnoringCase(res.get("signerAddress").asString());
        }

        @Test
        @DisplayName("이 기능 이전 커밋은 signNonce 가 null — 실패가 아니라 ③이 잠기는 상태다")
        void 옛_커밋은_nonce_가_없다() throws Exception {
            long pid = insertPrediction(authorId, "OPEN");
            insertCommit(pid, "seed", null);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.signNonce").value((Object) null))
                    // chainId 는 커밋과 무관하게 늘 온다 — 지금 이 서버가 선 체인이다.
                    .andExpect(jsonPath("$.chainId").value((int) signatureGuard.chainId()));
        }

        /** 서버 {@code PredictionCreateRequest.signingPayload} 와 같은 문자열이어야 한다. */
        private String signingPayload(String noteHash, long chainId, String nonce) {
            return "antenna:prediction:v1"
                    + "\nstockCode=005930"
                    + "\ndirection=UP"
                    + "\ntargetPrice=80000.00"
                    + "\nhorizon=30"
                    + "\nnoteHash=" + noteHash
                    + "\nchainId=" + chainId
                    + "\nnonce=" + nonce;
        }

        /**
         * 대회로 등록한 예측이면 서명 서식이 다르다 (ANT-CHAIN-20).
         *
         * <p>이 테스트가 없던 동안 운영에서 대회 예측 전부의 ③이 "서명자 불일치" 로 떠 있었다. 서명은 멀쩡했고,
         * 화면이 단건 서식으로 조립하고 있었다 — ECDSA 는 틀린 메시지에도 실패하지 않고 엉뚱한 주소를 뱉는다.
         * 그래서 위 단건 테스트와 <b>같은 방식으로</b> 한 번 더 본다: 응답 값만으로 event-entry 문자열을
         * 다시 만들어 주소가 복원되는가.
         */
        @Test
        @DisplayName("대회 등록 예측은 event-entry 서식으로 복원된다 — eventId·stake 가 응답에 온다")
        void 대회_예측의_서명자를_복원한다() throws Exception {
            Credentials wallet = Credentials.create(PRIVATE_KEY);
            long pid = insertPrediction(authorId, "OPEN");
            String noteHash = noteHashOf("seed");
            long eventId = insertEvent();
            insertBet(pid, eventId, authorId, 500, "005930", 0);
            String payload = eventEntryPayload(eventId, noteHash, 500, signatureGuard.chainId(), NONCE);
            String signature = WalletSignatures.sign(payload, wallet);
            insertSignedCommit(pid, "seed", signature, wallet.getAddress(), NONCE);

            String body = mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventEntry.eventId").value((int) eventId))
                    .andExpect(jsonPath("$.eventEntry.stake").value(500))
                    // 묶음이 아니라 이 커밋 혼자 — 화면이 ③을 계산해도 된다는 신호다
                    .andExpect(jsonPath("$.eventEntry.itemCount").value(1))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            JsonNode res = JSON.readTree(body);
            String rebuilt = eventEntryPayload(
                    res.get("eventEntry").get("eventId").asLong(),
                    res.get("payload").get("noteHash").asString(),
                    res.get("eventEntry").get("stake").asLong(),
                    res.get("chainId").asLong(),
                    res.get("signNonce").asString());
            assertThat(recover(rebuilt, res.get("signature").asString()))
                    .isEqualToIgnoringCase(res.get("signerAddress").asString());
        }

        @Test
        @DisplayName("단건 등록 예측이면 eventEntry 는 null — 화면이 예전 서식 그대로 조립한다")
        void 단건_예측은_eventEntry_가_없다() throws Exception {
            long pid = insertPrediction(authorId, "OPEN");
            insertCommit(pid, "seed", null);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", pid).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventEntry").value((Object) null));
        }

        @Test
        @DisplayName("한 서명에 여러 종목이 묶이면 itemCount 가 그 수다 — 화면은 이 값으로 ③을 잠근다")
        void 묶음_등록은_itemCount_가_여럿이다() throws Exception {
            long eventId = insertEvent();
            long first = insertPrediction(authorId, "OPEN");
            long second = insertPrediction(authorId, "OPEN");
            insertBet(first, eventId, authorId, 500, "005930", 0);
            insertBet(second, eventId, authorId, 1000, "000660", 1);
            // 묶음 등록은 서명 하나를 종목 수만큼 복사해 넣는다 — 같은 nonce 가 두 행에 들어간다
            insertSignedCommit(first, "seed", "0x" + "cd".repeat(65), "0x" + "22".repeat(20), NONCE);
            insertSignedCommit(second, "seed2", "0x" + "cd".repeat(65), "0x" + "22".repeat(20), NONCE);

            mockMvc.perform(get("/api/v1/predictions/{id}/proof", first).with(user(str(authorId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventEntry.itemCount").value(2));
        }

        /** 서버 {@code EventEntryService.Signed.signingPayload} 와 같은 문자열이어야 한다. 종목 한 줄짜리다. */
        private String eventEntryPayload(
                long eventId, String noteHash, long stake, long chainId, String nonce) {
            return "antenna:event-entry:v1"
                    + "\neventId=" + eventId
                    + "\n005930/UP/80000.00/" + noteHash + "/" + stake
                    + "\nchainId=" + chainId
                    + "\nnonce=" + nonce;
        }

        private String recover(String payload, String signature) throws Exception {
            byte[] raw = Numeric.hexStringToByteArray(signature);
            byte[] r = new byte[32];
            byte[] sPart = new byte[32];
            System.arraycopy(raw, 0, r, 0, 32);
            System.arraycopy(raw, 32, sPart, 0, 32);
            Sign.SignatureData data = new Sign.SignatureData(raw[64], r, sPart);
            return "0x"
                    + Keys.getAddress(
                            Sign.signedPrefixedMessageToKey(payload.getBytes(StandardCharsets.UTF_8), data));
        }
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private static String str(long id) {
        return String.valueOf(id);
    }

    private static String hashOf(String seed) {
        return Numeric.toHexString(Hash.sha3(seed.getBytes()));
    }

    /** 픽스처용 근거 해시. 실제 계산(keccak(note ‖ salt))은 CommitHashesTest 가 본다 — 여기서는 "저장된 값이 그대로 나가는가" 만. */
    private static String noteHashOf(String seed) {
        return hashOf(seed + "-note");
    }

    private long insertUser() {
        User user = User.create();
        em.persist(user);
        em.flush();
        return user.getId();
    }

    /** HIT 이면 판정 컬럼(기준가·만기·종가·오차)까지 채운다 — PRED-03 이 채울 값의 모양이다. settled_on 은 비운다(옛 행). */
    private long insertPrediction(long userId, String status) {
        return insertPrediction(userId, status, null);
    }

    /** @param settledOn 실제로 종가를 딴 거래일(settled_on). 만기(settle_date=2026-10-05)와 다를 수 있다 — ANT-PRED-04 */
    private long insertPrediction(long userId, String status, LocalDate settledOn) {
        return insertPrediction(userId, status, settledOn, null);
    }

    /** @param basedOn 기준가를 딴 거래일(based_on). 기준일(base_date=2026-09-05)이 휴장이면 다를 수 있다 — ANT-PRED-09 */
    private long insertPrediction(long userId, String status, LocalDate settledOn, LocalDate basedOn) {
        boolean settled = status.equals("HIT") || status.equals("MISS");
        em.createNativeQuery(
                        """
                        INSERT INTO predictions
                          (user_id, track, stock_code, direction, target_price, ref_close, horizon, status,
                           base_date, base_price, settle_date, settle_price, error_rate, settled_on, based_on,
                           created_at, updated_at)
                        VALUES (?, 'REAL', '005930', 'UP', 80000.00, 79500.00, 30, ?,
                                ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, status)
                .setParameter(3, settled ? LocalDate.of(2026, 9, 5) : null)
                .setParameter(4, settled ? 79000.00 : null)
                .setParameter(5, settled ? LocalDate.of(2026, 10, 5) : null)
                .setParameter(6, settled ? 82000.00 : null)
                .setParameter(7, settled ? 2.5 : null)
                .setParameter(8, settledOn)
                .setParameter(9, basedOn)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM predictions").getSingleResult()).longValue();
    }

    /**
     * 대회 회차 하나 (ANT-CHAIN-20). 종목 슬롯은 {@link #insertBet} 이 자기 몫을 만든다 —
     * 한 회차 안에서 슬롯은 종목마다 하나라({@code uq_event_stocks_code}) 배팅과 짝지어 만드는 편이 안 엉킨다.
     *
     * <p>값은 ③ 재료와 무관한 칸을 채우는 용도다. 서명 문자열에 들어가는 것은 {@code eventId} 하나뿐이다.
     */
    private long insertEvent() {
        em.createNativeQuery(
                        """
                        INSERT INTO prediction_events
                          (title, status, opens_at, seals_at, base_date, settle_date,
                           min_stake, max_stake, min_stocks, created_at)
                        VALUES ('리허설', 'OPEN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?,
                                100, 10000, 1, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, DAY)
                .setParameter(2, DAY.plusDays(3))
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM prediction_events").getSingleResult())
                .longValue();
    }

    /**
     * 종목 슬롯 + 배팅 행 하나. proof 는 이 배팅 행의 존재로 "대회 등록" 을, {@code stake} 로 서명 줄의
     * 마지막 칸을 안다.
     *
     * @param stockCode 슬롯을 딸 종목. 한 회차에 같은 종목은 한 번뿐이라 배팅을 둘 심을 때는 달라야 한다
     */
    private void insertBet(long pid, long eventId, long userId, long stake, String stockCode, int idx) {
        em.createNativeQuery("MERGE INTO stocks (code, name, listed) KEY(code) VALUES (?, ?, true)")
                .setParameter(1, stockCode)
                .setParameter(2, stockCode)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO prediction_event_stocks (event_id, idx, stock_code) VALUES (?, ?, ?)")
                .setParameter(1, eventId)
                .setParameter(2, (short) idx)
                .setParameter(3, stockCode)
                .executeUpdate();
        em.flush();
        long stockId =
                ((Number) em.createNativeQuery("SELECT MAX(id) FROM prediction_event_stocks").getSingleResult())
                        .longValue();
        em.createNativeQuery(
                        """
                        INSERT INTO prediction_event_bets
                          (prediction_id, event_id, event_stock_id, user_id, stake, event_commit,
                           bet_status, attempts, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, pid)
                .setParameter(2, eventId)
                .setParameter(3, stockId)
                .setParameter(4, userId)
                .setParameter(5, stake)
                .setParameter(6, hashOf("bet" + pid))
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private void insertCommit(long pid, String seed, Instant revealedAt) {
        em.createNativeQuery(
                        """
                        INSERT INTO prediction_commits
                          (prediction_id, commit_hash, note_hash, salt, signature, signer_address, revealed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .setParameter(1, pid)
                .setParameter(2, hashOf(seed))
                .setParameter(3, noteHashOf(seed))
                .setParameter(4, SALT)
                .setParameter(5, "0x" + "ab".repeat(65))
                .setParameter(6, "0x" + "11".repeat(20))
                .setParameter(7, revealedAt)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    /** 실제 서명·nonce 가 든 커밋. {@link #insertCommit} 과 달리 ③ 재료가 진짜 값이다. */
    private void insertSignedCommit(long pid, String seed, String signature, String signer, String nonce) {
        em.createNativeQuery(
                        """
                        INSERT INTO prediction_commits
                          (prediction_id, commit_hash, note_hash, salt, signature, signer_address, sign_nonce)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .setParameter(1, pid)
                .setParameter(2, hashOf(seed))
                .setParameter(3, noteHashOf(seed))
                .setParameter(4, SALT)
                .setParameter(5, signature)
                .setParameter(6, signer)
                .setParameter(7, nonce)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private void insertSubscription(long subscriberId, long publisherId, String status, Instant expiresAt) {
        em.createNativeQuery(
                        """
                        INSERT INTO subscriptions
                          (subscriber_id, publisher_id, fee, status, started_at, expires_at, auto_renew, created_at, updated_at)
                        VALUES (?, ?, 1000, ?, CURRENT_TIMESTAMP, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, subscriberId)
                .setParameter(2, publisherId)
                .setParameter(3, status)
                .setParameter(4, expiresAt)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private void assign(long batchId, long pid) {
        em.createNativeQuery("UPDATE prediction_commits SET anchor_batch_id = ? WHERE prediction_id = ?")
                .setParameter(1, batchId)
                .setParameter(2, pid)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    /** seeds 순서 = pids 순서 = 리프 순서(prediction id 오름차순)로 루트를 만들고 CONFIRMED 배치에 소속시킨다. */
    private AnchorBatch confirmedBatch(List<String> seeds, List<Long> pids) {
        List<byte[]> leaves = seeds.stream().map(s -> Hash.sha3(s.getBytes())).toList();
        String root = Numeric.toHexString(MerkleTree.build(leaves).root());
        AnchorBatch batch = AnchorBatch.open(DAY, root, seeds.size(), CONTRACT, 31337L);
        batch.markSending();
        batch.markConfirmed("0x" + "cd".repeat(32), 11_187_694L, Instant.parse("2026-09-05T15:05:10Z"));
        em.persist(batch);
        em.flush();
        for (long pid : pids) {
            assign(batch.getId(), pid);
        }
        return batch;
    }
}
