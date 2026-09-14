package ssafy.a507.backend.domain.prediction.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * ANT-PRED-06 — GET /predictions/me/portfolio (화면 C-04)의 AC 검증.
 *
 * <p>응답은 여덟 필드뿐이다(설계서 §4 C-04). 섹터별 적중률·누적 수익률 같은 것은 두지 않는다.
 *
 * <p>등록 API 대신 네이티브 INSERT 로 행을 만든다 — MyPredictionControllerTest 와 같은 방식이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PortfolioControllerTest {

    private static final String URL = "/api/v1/predictions/me/portfolio";
    private static final String SAMSUNG = "005930";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private Long userId;
    private Long otherId;

    @BeforeEach
    void setUp() {
        userId = insertUser("예측가");
        otherId = insertUser("남의계정");
        insertStock(SAMSUNG, "삼성전자");
    }

    @Test
    @DisplayName("예측이 한 건도 없으면 0 이고 적중률·평균 오차는 null 이다 — 404 가 아니다")
    void 아직_아무것도_없으면_빈_성적표() throws Exception {
        mockMvc.perform(get(URL).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.hit").value(0))
                .andExpect(jsonPath("$.miss").value(0))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.hitRate").doesNotExist())
                .andExpect(jsonPath("$.avgError").doesNotExist())
                .andExpect(jsonPath("$.anchored").value(0))
                .andExpect(jsonPath("$.unanchored").value(0));
    }

    @Test
    @DisplayName("BASE·OPEN 은 대기로 묶이고 적중률은 판정된 것만으로 센다")
    void 상태_분포와_적중률() throws Exception {
        insertPrediction(userId, "BASE", null, null);
        insertPrediction(userId, "OPEN", LocalDate.now().plusDays(5), null);
        insertPrediction(userId, "HIT", LocalDate.now().minusDays(1), "1.000");
        insertPrediction(userId, "HIT", LocalDate.now().minusDays(2), "3.000");
        insertPrediction(userId, "MISS", LocalDate.now().minusDays(3), "8.000");

        mockMvc.perform(get(URL).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.hit").value(2))
                .andExpect(jsonPath("$.miss").value(1))
                .andExpect(jsonPath("$.pending").value(2))
                // 2 / (2+1) — 대기 둘은 분모에 들어가지 않는다
                .andExpect(jsonPath("$.hitRate").value(66.67))
                // (1 + 3 + 8) / 3
                .andExpect(jsonPath("$.avgError").value(4.0));
    }

    @Test
    @DisplayName("판정이 하나도 없으면 적중률·평균 오차는 0 이 아니라 null 이다")
    void 판정_전에는_적중률이_null() throws Exception {
        insertPrediction(userId, "BASE", null, null);
        insertPrediction(userId, "OPEN", LocalDate.now().plusDays(5), null);

        mockMvc.perform(get(URL).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.hitRate").doesNotExist())
                .andExpect(jsonPath("$.avgError").doesNotExist());
    }

    @Test
    @DisplayName("체인에 올라간 배치에 든 것만 anchored 다 — 커밋이 없거나 배치가 아직 안 박혔으면 unanchored")
    void 온체인_기록_상태() throws Exception {
        Long confirmed = insertPrediction(userId, "OPEN", LocalDate.now().plusDays(7), null);
        Long waitingBatch = insertPrediction(userId, "OPEN", LocalDate.now().plusDays(7), null);
        insertPrediction(userId, "BASE", null, null); // 커밋 자체가 없다

        insertCommit(confirmed, insertBatch("CONFIRMED", "aa"), "0x" + "bb".repeat(32));
        insertCommit(waitingBatch, insertBatch("PENDING", "dd"), "0x" + "cc".repeat(32));

        mockMvc.perform(get(URL).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.anchored").value(1))
                .andExpect(jsonPath("$.unanchored").value(2));
    }

    @Test
    @DisplayName("남의 예측은 내 성적표에 섞이지 않는다")
    void 남의_예측은_안_센다() throws Exception {
        insertPrediction(userId, "HIT", LocalDate.now().minusDays(1), "1.000");
        insertPrediction(otherId, "HIT", LocalDate.now().minusDays(1), "9.000");
        insertPrediction(otherId, "MISS", LocalDate.now().minusDays(1), "9.000");

        mockMvc.perform(get(URL).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.hit").value(1))
                .andExpect(jsonPath("$.hitRate").value(100.0))
                .andExpect(jsonPath("$.avgError").value(1.0));
    }

    // ── helpers ──────────────────────────────────────────────

    private Long insertPrediction(Long ownerId, String status, LocalDate settleDate, String errorRate) {
        em.createNativeQuery("""
                        INSERT INTO predictions
                          (user_id, track, stock_code, direction, target_price, horizon,
                           status, settle_date, error_rate, created_at, updated_at)
                        VALUES (?, 'REAL', ?, 'UP', 70000, 7, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, ownerId)
                .setParameter(2, SAMSUNG)
                .setParameter(3, status)
                .setParameter(4, settleDate)
                .setParameter(5, errorRate == null ? null : new java.math.BigDecimal(errorRate))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT max(id) FROM predictions").getSingleResult())
                .longValue();
    }

    /** 머클루트는 유일해야 한다(체인 v3 가 루트를 칸의 키로 쓴다) — 배치마다 다른 바이트로 채운다. */
    private Long insertBatch(String batchStatus, String rootByte) {
        em.createNativeQuery("""
                        INSERT INTO anchor_batches
                          (business_date, merkle_root, commit_count, status, contract_address, chain_id,
                           attempts, created_at)
                        VALUES (CURRENT_DATE, ?, 1, ?, ?, 31337, 1, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, "0x" + rootByte.repeat(32))
                .setParameter(2, batchStatus)
                .setParameter(3, "0x07f8cfe2bc6174d62be8226e5e8699ffbf0d6d6a")
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT max(id) FROM anchor_batches").getSingleResult())
                .longValue();
    }

    private void insertCommit(Long predictionId, Long batchId, String commitHash) {
        em.createNativeQuery("""
                        INSERT INTO prediction_commits (prediction_id, commit_hash, salt, anchor_batch_id)
                        VALUES (?, ?, ?, ?)
                        """)
                .setParameter(1, predictionId)
                .setParameter(2, commitHash)
                .setParameter(3, "0123456789abcdef".repeat(4))
                .setParameter(4, batchId)
                .executeUpdate();
    }

    private void insertStock(String code, String name) {
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES (?, ?, true)")
                .setParameter(1, code)
                .setParameter(2, name)
                .executeUpdate();
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
}
