package ssafy.a507.backend.domain.event.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.service.EventResultService;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * GET /events/{id}/me · GET /events/{id}/leaderboard — ANT-EVENT-06 (작업명세 v1.2 §3-4·5 · §4 · §8).
 *
 * <p>여기서 고정하는 것은 셋이다.
 *
 * <ol>
 *   <li><b>정산 전에는 순위를 내리지 않는다</b> — items 가 비고 notice 만 온다. 승패를 모르는데
 *       등수를 보여주면 사용자는 그걸 현재 등수로 읽는다
 *   <li><b>자격 미달은 리더보드 {@code me} 가 null</b> 이지만 {@code /me} 는 자기 성적을 준다 —
 *       순위 밖인 것과 참가 기록이 없는 것은 다르다
 *   <li><b>{@code paid}·{@code net}·{@code rank}·{@code avgError} 는 정산 전 null</b> — 0 은 "본전",
 *       "꼴찌" 로 읽힌다
 * </ol>
 *
 * <p>읽기만 하는 API 라 {@code @Transactional} 롤백을 쓴다 — 온체인 전송이 없어 디스패처의
 * "트랜잭션 밖" 계약에 걸리지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("이벤트 내 배팅 · 순위 API")
class EventMeLeaderboardControllerTest {

    private static final String ME = "/api/v1/events/{eventId}/me";
    private static final String BOARD = "/api/v1/events/{eventId}/leaderboard";
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 17);
    private static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 21);

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;
    @Autowired EventResultService resultService;

    private PredictionEvent event;
    private PredictionEventStock s0;
    private PredictionEventStock s1;
    private User winner;
    private User loser;
    private User outsider;

    @BeforeEach
    void setUp() {
        em.createNativeQuery("""
                        INSERT INTO stocks (code, name, listed)
                        VALUES ('005930', '삼성전자', true), ('000660', 'SK하이닉스', true)
                        """)
                .executeUpdate();
        Instant now = Instant.now();
        event = PredictionEvent.create(
                "9월 3주 업앤다운",
                now.minus(1, ChronoUnit.HOURS),
                now.plus(1, ChronoUnit.DAYS),
                BASE_DATE,
                SETTLE_DATE,
                BigInteger.valueOf(500),
                BigInteger.valueOf(2000),
                (short) 2);
        em.persist(event);
        s0 = PredictionEventStock.of(event, (short) 0, em.find(Stock.class, "005930"), new BigDecimal("91000.00"));
        s1 = PredictionEventStock.of(event, (short) 1, em.find(Stock.class, "000660"), new BigDecimal("210000.00"));
        em.persist(s0);
        em.persist(s1);
        winner = newUser("승자", "aa");
        loser = newUser("패자", "bb");
        outsider = newUser("구경꾼", "cc");
        em.flush();
    }

    // ── 내 배팅 ──────────────────────────────────────────

    @Test
    @DisplayName("내가 건 종목이 idx 순으로 나오고 종목명·tx 해시·작업 id 가 함께 온다")
    void 내_배팅() throws Exception {
        bet(winner, s1, PredictionEventBet.Status.ACTIVE, 700, null, false, null);
        bet(winner, s0, PredictionEventBet.Status.PENDING, 1000, null, false, null);
        openForEntry();

        mockMvc.perform(get(ME, event.getId()).with(user(String.valueOf(winner.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("ENTRY"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                // idx 순이다 — 요청 순서나 등록 순서가 아니라 화면의 카드 순서와 같아야 한다
                .andExpect(jsonPath("$.entries[0].idx").value(0))
                .andExpect(jsonPath("$.entries[0].stockCode").value("005930"))
                .andExpect(jsonPath("$.entries[0].name").value("삼성전자"))
                .andExpect(jsonPath("$.entries[0].betStatus").value("PENDING"))
                .andExpect(jsonPath("$.entries[0].operationId").isNotEmpty())
                .andExpect(jsonPath("$.entries[1].idx").value(1))
                .andExpect(jsonPath("$.summary.stockCount").value(2))
                .andExpect(jsonPath("$.summary.staked").value(1700))
                .andExpect(jsonPath("$.summary.qualified").value(true))
                // 정산 전이라 없다 — 0 으로 내리면 "본전" 으로 읽힌다
                .andExpect(jsonPath("$.summary.paid").value(nullValue()))
                .andExpect(jsonPath("$.summary.net").value(nullValue()))
                .andExpect(jsonPath("$.summary.rank").value(nullValue()))
                .andExpect(jsonPath("$.summary.avgError").value(nullValue()));
    }

    @Test
    @DisplayName("참가하지 않았으면 빈 목록 — 404 가 아니다. 이벤트는 있고 내 기록만 없다")
    void 미참가는_빈_목록() throws Exception {
        mockMvc.perform(get(ME, event.getId()).with(user(String.valueOf(outsider.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.summary.stockCount").value(0))
                .andExpect(jsonPath("$.summary.qualified").value(false));
    }

    @Test
    @DisplayName("정산 뒤에는 판정 결과와 굳힌 순위·평균 오차가 함께 온다")
    void 정산_뒤_내_배팅() throws Exception {
        settledEvent();

        mockMvc.perform(get(ME, event.getId()).with(user(String.valueOf(winner.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SETTLED"))
                .andExpect(jsonPath("$.summary.paid").value(3000))
                .andExpect(jsonPath("$.summary.net").value(1500))
                .andExpect(jsonPath("$.summary.rank").value(1))
                // HIT 인 예측만의 평균이다 — MISS 를 섞으면 많이 틀린 사람이 유리해진다
                .andExpect(jsonPath("$.summary.avgError").value(1.000))
                .andExpect(jsonPath("$.entries[0].result.winner").value("UP"))
                // 배당 2500 = 원금 1000 + 이익 1500. summary.net 은 환불 500 까지 합친 +1500 이다
                .andExpect(jsonPath("$.entries[0].result.payout").value(2500));
    }

    @Test
    @DisplayName("자격 미달이어도 내 성적은 본다 — 순위 밖인 것과 기록이 없는 것은 다르다")
    void 미달자도_자기_성적은_본다() throws Exception {
        settledEvent();

        mockMvc.perform(get(ME, event.getId()).with(user(String.valueOf(loser.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.qualified").value(false))
                .andExpect(jsonPath("$.summary.net").value(-500))
                .andExpect(jsonPath("$.summary.rank").value(nullValue()));
    }

    @Test
    @DisplayName("없는 이벤트는 404")
    void 없는_이벤트() throws Exception {
        mockMvc.perform(get(ME, 999_999L).with(user(String.valueOf(winner.getId()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    // ── 순위 ─────────────────────────────────────────────

    @Test
    @DisplayName("접수 중에는 순위를 내리지 않는다 — 승패를 모르는데 등수를 보이면 현재 등수로 읽힌다")
    void 접수_중에는_비어_있다() throws Exception {
        bet(winner, s0, PredictionEventBet.Status.ACTIVE, 1000, null, false, null);
        openForEntry();

        mockMvc.perform(get(BOARD, event.getId()).with(user(String.valueOf(winner.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("ENTRY"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.notice").isNotEmpty())
                .andExpect(jsonPath("$.me").value(nullValue()));
    }

    @Test
    @DisplayName("봉인 뒤에도 예상 순위는 없다 — 문구만 바뀐다")
    void 봉인_뒤에도_비어_있다() throws Exception {
        bet(winner, s0, PredictionEventBet.Status.ACTIVE, 1000, null, false, null);
        reattach();
        event.markSealed("0xseal");
        em.flush();

        mockMvc.perform(get(BOARD, event.getId()).with(user(String.valueOf(winner.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SEALED"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.notice").isNotEmpty());
    }

    @Test
    @DisplayName("정산 뒤에는 자격자만 등수 순으로 나오고 수익률을 서버가 계산한다")
    void 정산_뒤_순위() throws Exception {
        settledEvent();

        mockMvc.perform(get(BOARD, event.getId()).with(user(String.valueOf(winner.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SETTLED"))
                .andExpect(jsonPath("$.notice").value(nullValue()))
                // 자격 미달(1종목)인 loser 는 목록에 없다
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(winner.getId()))
                .andExpect(jsonPath("$.items[0].nickname").value("승자"))
                .andExpect(jsonPath("$.items[0].staked").value(1500))
                .andExpect(jsonPath("$.items[0].paid").value(3000))
                .andExpect(jsonPath("$.items[0].net").value(1500))
                // 1500 ÷ 1500 × 100
                .andExpect(jsonPath("$.items[0].profitRate").value(100.00))
                .andExpect(jsonPath("$.items[0].hitCount").value(1))
                .andExpect(jsonPath("$.items[0].doneCount").value(2))
                .andExpect(jsonPath("$.me.rank").value(1))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    @DisplayName("자격 미달자는 리더보드 me 가 null — 순위 밖이라 보여줄 등수가 없다")
    void 미달자는_me_가_null() throws Exception {
        settledEvent();

        mockMvc.perform(get(BOARD, event.getId()).with(user(String.valueOf(loser.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.me").value(nullValue()));
    }

    @Test
    @DisplayName("커서가 이상해도 처음부터 준다 — 400 으로 막으면 옛 커서 하나로 목록을 영영 못 연다")
    void 이상한_커서() throws Exception {
        settledEvent();

        mockMvc.perform(get(BOARD, event.getId())
                        .param("cursor", "zzz")
                        .with(user(String.valueOf(winner.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    // ── 픽스처 ───────────────────────────────────────────

    /**
     * 정산이 끝난 회차를 만든다 — 승자는 2종목(1승 1환불, +1500), 패자는 1종목(−500, 자격 미달).
     * 판정 결과는 인덱서가 하는 일({@code markResolved})을 그대로 재현하고, 순위는 ANT-EVENT-03 의
     * {@code freeze} 가 굳힌다.
     */
    private void settledEvent() {
        bet(winner, s0, PredictionEventBet.Status.SETTLED, 1000, 2500, true, "1.000");
        bet(winner, s1, PredictionEventBet.Status.REFUNDED, 500, 500, false, null);
        bet(loser, s0, PredictionEventBet.Status.SETTLED, 500, 0, false, null);
        // bet() 이 em.clear() 로 끝나 세 엔티티가 준영속이다 — 그대로 고치면 UPDATE 가 안 나간다.
        reattach();
        s0.markResolved(new BigDecimal("91800.00"), PredictionEventStock.Winner.UP, "0xr0");
        s1.markResolved(new BigDecimal("209000.00"), PredictionEventStock.Winner.TIE, "0xr1");
        event.markSettled();
        em.flush();
        resultService.freeze(event.getId());
        em.flush();
        em.clear();
        reattach();
    }

    /** 접수 중인 회차. 크론이 하는 일({@code open()})을 그대로 한다. */
    private void openForEntry() {
        reattach();
        event.open();
        em.flush();
    }

    private void reattach() {
        event = em.find(PredictionEvent.class, event.getId());
        s0 = em.find(PredictionEventStock.class, s0.getId());
        s1 = em.find(PredictionEventStock.class, s1.getId());
    }

    private User newUser(String nickname, String suffix) {
        User u = User.create();
        u.changeNickname(nickname);
        u.linkWallet("0x00000000000000000000000000000000000000" + suffix);
        em.persist(u);
        return u;
    }

    private void bet(
            User user,
            PredictionEventStock stock,
            PredictionEventBet.Status status,
            long stake,
            Integer payout,
            boolean hit,
            String errorRate) {
        Prediction p = Prediction.register(
                user,
                stock.getStock(),
                Prediction.Direction.UP,
                new BigDecimal("93000.00"),
                new BigDecimal("91000.00"),
                (short) 4,
                BASE_DATE,
                SETTLE_DATE);
        if (errorRate != null || payout != null) {
            p.fixBasePrice(stock.getBasePrice(), BASE_DATE);
            p.settle(
                    new BigDecimal("91800.00"),
                    SETTLE_DATE,
                    hit,
                    errorRate == null ? new BigDecimal("9.999") : new BigDecimal(errorRate));
        }
        em.persist(p);
        PredictionEventBet b = PredictionEventBet.place(
                p, event, stock, BigInteger.valueOf(stake), "0x" + "48".repeat(32), "op_" + p.getId());
        em.persist(b);
        em.flush();
        String sql = payout == null
                ? "UPDATE prediction_event_bets SET bet_status = :s, tx_hash = :t WHERE prediction_id = :id"
                : "UPDATE prediction_event_bets SET bet_status = :s, tx_hash = :t, payout = :p WHERE prediction_id = :id";
        var q = em.createNativeQuery(sql)
                .setParameter("s", status.name())
                .setParameter("t", status == PredictionEventBet.Status.PENDING ? null : "0xbet")
                .setParameter("id", p.getId());
        if (payout != null) {
            q.setParameter("p", BigInteger.valueOf(payout));
        }
        q.executeUpdate();
        em.clear();
    }
}
