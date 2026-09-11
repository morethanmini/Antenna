package ssafy.a507.backend.domain.search.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** ANT-DATA-05 — GET /api/v1/search 통합 검색. 종목·예측가·리포트 타입별 상위 N건. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SearchControllerTest {

    private static final String URL = "/api/v1/search";
    private static final LocalDate LATEST = LocalDate.of(2026, 8, 28);
    private static final LocalDate PREVIOUS = LocalDate.of(2026, 8, 27);
    private static final LocalDate LONG_AGO = LocalDate.of(2026, 7, 1);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private Long viewerId;

    @BeforeEach
    void setUp() {
        insertStock("005930", "삼성전자", "반도체", true, 5_969_782_550L);
        insertStock("006400", "삼성SDI", "2차전지", true, 68_764_530L);
        // 상장주식수가 없어 시총을 못 낸다 — 시총 순서의 맨 뒤다.
        insertStock("028260", "삼성물산", null, true, null);
        insertStock("000660", "SK하이닉스", "반도체", true, 728_002_365L);
        // 상장폐지. 탐색 목록과 같이 검색에도 안 나온다.
        insertStock("111111", "삼성폐지", null, false, null);
        // 한때 수집 범위에 들었다 빠진 종목 — 마지막 시세가 활성 창 밖이다.
        insertStock("222222", "삼성옛종목", null, true, null);

        insertQuote("005930", LATEST, "71500");
        insertQuote("006400", LATEST, "300000");
        // 그날 거래정지 — 전역 최신일이 아니라 자기 마지막 거래일 종가를 준다.
        insertQuote("028260", PREVIOUS, "120000");
        insertQuote("000660", LATEST, "180000");
        insertQuote("111111", LATEST, "500");
        insertQuote("222222", LONG_AGO, "900");

        viewerId = insertUser("조회자");
        Long top = insertUser("삼성고수");
        Long second = insertUser("삼성개미");
        Long unranked = insertUser("삼성초보");
        insertRanking("REAL", "ALL", second, 2, "71.80");
        insertRanking("REAL", "ALL", top, 1, "78.60");
        // 같은 사람의 다른 필터 행 — 검색 결과에 두 번 나오면 안 된다.
        insertRanking("REAL", "30D", top, 1, "80.00");
        // 모의투자 트랙 랭킹은 실전 예측가 검색 대상이 아니다.
        insertRanking("REPLAY", "ALL", unranked, 1, "90.00");

        insertReport(top, "삼성전자 3분기 점검");
        insertReport(top, "SK 이야기");
        insertReport(second, "반도체 사이클과 삼성");
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("종목명 부분일치 — 활성 상장 종목만, 시가총액 큰 순, 시총을 못 내면 뒤")
    void 종목명으로_찾는다() throws Exception {
        mockMvc.perform(get(URL).param("q", "삼성").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks.length()").value(3))
                .andExpect(jsonPath("$.stocks[0].code").value("005930"))
                .andExpect(jsonPath("$.stocks[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.stocks[0].sector").value("반도체"))
                .andExpect(jsonPath("$.stocks[0].prevClose").value(71500))
                .andExpect(jsonPath("$.stocks[0].watching").value(false))
                .andExpect(jsonPath("$.stocks[1].code").value("006400"))
                .andExpect(jsonPath("$.stocks[2].code").value("028260"))
                .andExpect(jsonPath("$.stocks[2].prevClose").value(120000));
    }

    @Test
    @DisplayName("종목코드는 앞에서부터만 맞춘다")
    void 종목코드는_prefix() throws Exception {
        mockMvc.perform(get(URL).param("q", "0059").with(viewer()))
                .andExpect(jsonPath("$.stocks.length()").value(1))
                .andExpect(jsonPath("$.stocks[0].code").value("005930"));

        mockMvc.perform(get(URL).param("q", "5930").with(viewer()))
                .andExpect(jsonPath("$.stocks.length()").value(0));
    }

    @Test
    @DisplayName("섹터 키워드로도 찾고, 영문은 대소문자를 가리지 않는다")
    void 섹터와_영문_대소문자() throws Exception {
        mockMvc.perform(get(URL).param("q", "반도체").with(viewer()))
                .andExpect(jsonPath("$.stocks.length()").value(2))
                .andExpect(jsonPath("$.stocks[0].code").value("005930"))
                .andExpect(jsonPath("$.stocks[1].code").value("000660"));

        mockMvc.perform(get(URL).param("q", "sk").with(viewer()))
                .andExpect(jsonPath("$.stocks[0].code").value("000660"));
    }

    @Test
    @DisplayName("내 관심 종목이면 watching 이 true 다")
    void 관심_종목_표시() throws Exception {
        em.createNativeQuery("""
                        INSERT INTO watchlist_items (user_id, stock_code, created_at)
                        VALUES (?, '006400', CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, viewerId)
                .executeUpdate();

        mockMvc.perform(get(URL).param("q", "삼성SDI").with(viewer()))
                .andExpect(jsonPath("$.stocks[0].code").value("006400"))
                .andExpect(jsonPath("$.stocks[0].watching").value(true));
    }

    @Test
    @DisplayName("예측가는 실전 전체 랭킹에 든 사람만, 순위순으로 적중률(%)과 함께")
    void 예측가는_랭킹에서_찾는다() throws Exception {
        mockMvc.perform(get(URL).param("q", "삼성").with(viewer()))
                .andExpect(jsonPath("$.channels.length()").value(2))
                .andExpect(jsonPath("$.channels[0].nickname").value("삼성고수"))
                .andExpect(jsonPath("$.channels[0].hitRate").value(78.6))
                .andExpect(jsonPath("$.channels[1].nickname").value("삼성개미"))
                .andExpect(jsonPath("$.channels[1].userId").isNumber());
    }

    @Test
    @DisplayName("리포트는 제목 부분일치, 최신순, 작성자 닉네임과 함께")
    void 리포트는_제목으로_찾는다() throws Exception {
        mockMvc.perform(get(URL).param("q", "삼성").with(viewer()))
                .andExpect(jsonPath("$.reports.length()").value(2))
                .andExpect(jsonPath("$.reports[0].title").value("반도체 사이클과 삼성"))
                .andExpect(jsonPath("$.reports[0].author").value("삼성개미"))
                .andExpect(jsonPath("$.reports[1].title").value("삼성전자 3분기 점검"))
                .andExpect(jsonPath("$.reports[1].id").isNumber());
    }

    @Test
    @DisplayName("type 을 주면 그 타입만 찾고 나머지는 빈 배열이다 — 키는 남긴다")
    void 타입_지정() throws Exception {
        mockMvc.perform(get(URL).param("q", "삼성").param("type", "STOCK").with(viewer()))
                .andExpect(jsonPath("$.stocks.length()").value(3))
                .andExpect(jsonPath("$.channels.length()").value(0))
                .andExpect(jsonPath("$.reports.length()").value(0));
    }

    @Test
    @DisplayName("limit 은 타입별 건수다")
    void limit_은_타입별() throws Exception {
        mockMvc.perform(get(URL).param("q", "삼성").param("limit", "1").with(viewer()))
                .andExpect(jsonPath("$.stocks.length()").value(1))
                .andExpect(jsonPath("$.stocks[0].code").value("005930"))
                .andExpect(jsonPath("$.channels.length()").value(1))
                .andExpect(jsonPath("$.reports.length()").value(1));
    }

    @Test
    @DisplayName("% · _ 는 와일드카드가 아니라 글자 그대로 찾는다")
    void LIKE_와일드카드는_이스케이프() throws Exception {
        mockMvc.perform(get(URL).param("q", "%").with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks.length()").value(0))
                .andExpect(jsonPath("$.channels.length()").value(0))
                .andExpect(jsonPath("$.reports.length()").value(0));

        mockMvc.perform(get(URL).param("q", "_").with(viewer()))
                .andExpect(jsonPath("$.stocks.length()").value(0));
    }

    @Test
    @DisplayName("검색어가 비었거나 너무 길면 400 INVALID_REQUEST")
    void 검색어_검증() throws Exception {
        mockMvc.perform(get(URL).param("q", "  ").with(viewer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("q"));

        mockMvc.perform(get(URL).param("q", "가".repeat(51)).with(viewer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("q"));

        mockMvc.perform(get(URL).with(viewer())).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("모르는 type 은 400")
    void 모르는_타입은_400() throws Exception {
        mockMvc.perform(get(URL).param("q", "삼성").param("type", "POST").with(viewer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("비로그인은 401")
    void 비로그인은_401() throws Exception {
        mockMvc.perform(get(URL).param("q", "삼성")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor viewer() {
        return user(String.valueOf(viewerId));
    }

    private void insertStock(String code, String name, String sector, boolean listed, Long listedShares) {
        em.createNativeQuery("""
                        INSERT INTO stocks (code, name, sector, market, listed)
                        VALUES (?, ?, ?, 'KOSPI', ?)
                        """)
                .setParameter(1, code)
                .setParameter(2, name)
                .setParameter(3, sector)
                .setParameter(4, listed)
                .executeUpdate();
        if (listedShares != null) {
            em.createNativeQuery("UPDATE stocks SET listed_shares = ? WHERE code = ?")
                    .setParameter(1, listedShares)
                    .setParameter(2, code)
                    .executeUpdate();
        }
    }

    private void insertQuote(String code, LocalDate tradeDate, String close) {
        em.createNativeQuery("""
                        INSERT INTO daily_quotes (stock_code, trade_date, close, collected_at)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, code)
                .setParameter(2, tradeDate)
                .setParameter(3, new BigDecimal(close))
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

    private void insertRanking(String track, String filterKey, Long userId, int rank, String hitRate) {
        em.createNativeQuery("""
                        INSERT INTO rankings
                          (track, filter_key, user_id, score, hit_rate, avg_error, done_count, "rank", computed_at)
                        VALUES (?, ?, ?, 50, ?, 3, 10, ?, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, track)
                .setParameter(2, filterKey)
                .setParameter(3, userId)
                .setParameter(4, new BigDecimal(hitRate))
                .setParameter(5, rank)
                .executeUpdate();
    }

    private void insertReport(Long userId, String title) {
        em.createNativeQuery("""
                        INSERT INTO reports (user_id, title, body, is_public, view_count, created_at)
                        VALUES (?, ?, '본문', true, 0, CURRENT_TIMESTAMP)
                        """)
                .setParameter(1, userId)
                .setParameter(2, title)
                .executeUpdate();
    }
}
