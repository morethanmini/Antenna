package ssafy.a507.backend.domain.chain.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;

/**
 * ANT-CHAIN-15 — 커밋 원장이 예측 커밋과 리포트를 한 목록으로 내린다. H2.
 *
 * <p>행은 네이티브 INSERT 로 심는다(AnchorQueryControllerTest 와 같은 방식). 등록 시각을 직접 정해야 해서다 —
 * 정렬과 커서가 전부 {@code created_at} 위에 서 있는데, 엔티티로 심으면 한 트랜잭션 안의 행이 전부 같은 시각이 된다.
 *
 * <p>SecurityConfig 의 기본 체인이 살아 있어 요청마다 {@code user("<id>")} 를 붙인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("커밋 원장 — 예측 + 리포트")
class CommitQueryControllerTest {

    private static final String CONTRACT = "0x07f8cfe2bc6174d62be8226e5e8699ffbf0d6d6a";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);
    private static final Instant T0 = Instant.parse("2026-09-15T01:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private String viewer;

    @BeforeEach
    void setUp() {
        User user = User.create();
        em.persist(user);
        em.flush();
        viewer = String.valueOf(user.getId());
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES ('005930', '삼성전자', true)")
                .executeUpdate();
    }

    @Test
    @DisplayName("봉인된 것이 없으면 빈 목록")
    void 빈_목록() throws Exception {
        ledger().andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value((Object) null))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("예측과 리포트가 한 목록에 등록 시각 내림차순으로 섞이고, 줄마다 kind 가 붙는다")
    void 두_종류가_섞인다() throws Exception {
        long p1 = insertPrediction("first", T0);
        long r1 = insertReport("반도체 업황 점검", true, T0.plusSeconds(10));
        long p2 = insertPrediction("second", T0.plusSeconds(20));
        long r2 = insertReport("환율 메모", false, T0.plusSeconds(30));

        ledger().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].kind").value("REPORT"))
                .andExpect(jsonPath("$.items[0].reportId").value(r2))
                .andExpect(jsonPath("$.items[0].title").value("환율 메모"))
                .andExpect(jsonPath("$.items[0].visibility").value(false))
                .andExpect(jsonPath("$.items[1].kind").value("PREDICTION"))
                .andExpect(jsonPath("$.items[1].predictionId").value(p2))
                .andExpect(jsonPath("$.items[1].stockName").value("삼성전자"))
                .andExpect(jsonPath("$.items[2].kind").value("REPORT"))
                .andExpect(jsonPath("$.items[2].reportId").value(r1))
                .andExpect(jsonPath("$.items[2].visibility").value(true))
                .andExpect(jsonPath("$.items[3].kind").value("PREDICTION"))
                .andExpect(jsonPath("$.items[3].predictionId").value(p1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("커서로 이어 받으면 섞인 경계에서 빠지거나 겹치는 줄이 없다")
    void 커서_페이징() throws Exception {
        long p1 = insertPrediction("first", T0);
        long r1 = insertReport("리포트 하나", true, T0.plusSeconds(10));
        long p2 = insertPrediction("second", T0.plusSeconds(20));
        long r2 = insertReport("리포트 둘", true, T0.plusSeconds(30));
        long p3 = insertPrediction("third", T0.plusSeconds(40));

        List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            ResultActions res = cursor == null
                    ? mockMvc.perform(get("/api/v1/commits").param("size", "2").with(user(viewer)))
                    : mockMvc.perform(get("/api/v1/commits")
                            .param("size", "2")
                            .param("cursor", cursor)
                            .with(user(viewer)));
            String body = res.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> kinds = JsonPath.read(body, "$.items[*].kind");
            List<Integer> predictionIds = JsonPath.read(body, "$.items[*].predictionId");
            List<Integer> reportIds = JsonPath.read(body, "$.items[*].reportId");
            for (int i = 0; i < kinds.size(); i++) {
                seen.add(kinds.get(i) + "-"
                        + ("PREDICTION".equals(kinds.get(i)) ? predictionIds.get(i) : reportIds.get(i)));
            }
            cursor = JsonPath.read(body, "$.nextCursor");
        }

        // 다섯 줄이 겹침 없이 한 번씩, 최신 순으로.
        org.assertj.core.api.Assertions.assertThat(seen)
                .containsExactly(
                        "PREDICTION-" + p3, "REPORT-" + r2, "PREDICTION-" + p2, "REPORT-" + r1, "PREDICTION-" + p1);
        org.assertj.core.api.Assertions.assertThat(cursor).isNull();
    }

    @Test
    @DisplayName("등록 시각이 같으면 예측이 리포트보다 앞 — 그 경계에 커서가 걸려도 줄이 빠지지 않는다")
    void 같은_시각_동점() throws Exception {
        long p1 = insertPrediction("tie", T0);
        long r1 = insertReport("같은 시각 리포트", true, T0);
        long p0 = insertPrediction("older", T0.minusSeconds(5));

        // 첫 장에 예측 하나만 받아, 커서가 "동점 구간의 예측" 을 가리키게 한다.
        String body = mockMvc.perform(get("/api/v1/commits").param("size", "1").with(user(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("PREDICTION"))
                .andExpect(jsonPath("$.items[0].predictionId").value(p1))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = JsonPath.read(body, "$.nextCursor");

        // 같은 시각의 리포트가 다음 장 첫 줄로 와야 한다. 시각만으로 잘랐다면 여기서 사라진다.
        mockMvc.perform(get("/api/v1/commits").param("cursor", cursor).with(user(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("REPORT"))
                .andExpect(jsonPath("$.items[0].reportId").value(r1))
                .andExpect(jsonPath("$.items[1].predictionId").value(p0));
    }

    @Test
    @DisplayName("봉인 이전에 발행된 리포트(body_hash NULL)는 원장에 나오지 않는다 — 앵커 대상과 같은 기준")
    void 봉인_안_된_리포트는_빠진다() throws Exception {
        insertUnsealedReport("옛날 리포트", T0.plusSeconds(60));
        long r1 = insertReport("봉인된 리포트", true, T0);

        ledger().andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reportId").value(r1));
    }

    @Test
    @DisplayName("리포트 줄에 본문도 bodyHash 도 없다 — 원장은 '무엇이 언제 봉인됐나' 까지다")
    void 리포트_본문은_안_실린다() throws Exception {
        insertReport("구독자 전용", false, T0);

        ledger().andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].commitHash").isNotEmpty())
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.items[0].bodyHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].salt").doesNotExist())
                .andExpect(jsonPath("$.items[0].author").doesNotExist());
    }

    @Test
    @DisplayName("배치에 안 묶인 리포트는 배치 세 칸이 비어 온다 — 예측과 같은 '다음 배치 대기'")
    void 배치_대기_리포트() throws Exception {
        long reportId = insertReport("대기 중", true, T0);

        ledger().andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reportId").value(reportId))
                .andExpect(jsonPath("$.items[0].anchorId").value((Object) null))
                .andExpect(jsonPath("$.items[0].businessDate").value((Object) null))
                .andExpect(jsonPath("$.items[0].anchorStatus").value((Object) null));
    }

    @Test
    @DisplayName("배치에 묶인 리포트는 배치 번호·영업일·상태가 함께 온다")
    void 배치에_묶인_리포트() throws Exception {
        long reportId = insertReport("묶임", true, T0);
        AnchorBatch batch = AnchorBatch.open(DAY, hashOf("root"), 1, CONTRACT, 31337L);
        batch.markSending();
        batch.markConfirmed("0x" + "ab".repeat(32), 42L, Instant.parse("2026-09-15T01:10:00Z"));
        em.persist(batch);
        em.flush();
        em.createNativeQuery("UPDATE reports SET anchor_batch_id = ? WHERE id = ?")
                .setParameter(1, batch.getId())
                .setParameter(2, reportId)
                .executeUpdate();
        em.flush();
        em.clear();

        ledger().andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].anchorId").value(batch.getId()))
                .andExpect(jsonPath("$.items[0].businessDate").value(DAY.toString()))
                .andExpect(jsonPath("$.items[0].anchorStatus").value("CONFIRMED"));
    }

    @Test
    @DisplayName("조각 수가 안 맞는 커서는 400 — 손으로 만든 값이다")
    void 잘못된_커서_400() throws Exception {
        mockMvc.perform(get("/api/v1/commits").param("cursor", "123").with(user(viewer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("없는 kind 가 든 커서도 400")
    void 알_수_없는_kind_커서_400() throws Exception {
        mockMvc.perform(get("/api/v1/commits").param("cursor", "1000000:COMMENT:3").with(user(viewer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("인증 없이는 401")
    void 인증_없으면_401() throws Exception {
        mockMvc.perform(get("/api/v1/commits")).andExpect(status().isUnauthorized());
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private ResultActions ledger() throws Exception {
        return mockMvc.perform(get("/api/v1/commits").with(user(viewer)));
    }

    private static String hashOf(String seed) {
        return Numeric.toHexString(Hash.sha3(seed.getBytes()));
    }

    private long insertPrediction(String seed, Instant at) {
        em.createNativeQuery(
                        """
                        INSERT INTO predictions
                          (user_id, track, stock_code, direction, target_price, horizon, status, created_at, updated_at)
                        VALUES (?, 'REAL', '005930', 'UP', 80000.00, 30, 'OPEN', ?, ?)
                        """)
                .setParameter(1, Long.valueOf(viewer))
                .setParameter(2, at)
                .setParameter(3, at)
                .executeUpdate();
        long pid = ((Number) em.createNativeQuery("SELECT MAX(id) FROM predictions").getSingleResult()).longValue();
        em.createNativeQuery("INSERT INTO prediction_commits (prediction_id, commit_hash, salt) VALUES (?, ?, ?)")
                .setParameter(1, pid)
                .setParameter(2, hashOf(seed))
                .setParameter(3, "00".repeat(32))
                .executeUpdate();
        em.flush();
        em.clear();
        return pid;
    }

    private long insertReport(String title, boolean isPublic, Instant at) {
        return insertReport(title, isPublic, at, hashOf("body:" + title), hashOf("report:" + title));
    }

    /** 봉인 이전에 발행된 리포트. 해시 셋이 전부 비어 있다. */
    private long insertUnsealedReport(String title, Instant at) {
        return insertReport(title, true, at, null, null);
    }

    private long insertReport(String title, boolean isPublic, Instant at, String bodyHash, String reportHash) {
        em.createNativeQuery(
                        """
                        INSERT INTO reports
                          (user_id, title, body, is_public, view_count, body_hash, report_hash, created_at)
                        VALUES (?, ?, '본문', ?, 0, ?, ?, ?)
                        """)
                .setParameter(1, Long.valueOf(viewer))
                .setParameter(2, title)
                .setParameter(3, isPublic)
                .setParameter(4, bodyHash)
                .setParameter(5, reportHash)
                .setParameter(6, at)
                .executeUpdate();
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM reports").getSingleResult()).longValue();
    }
}
