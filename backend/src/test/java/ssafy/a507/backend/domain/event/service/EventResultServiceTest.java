package ssafy.a507.backend.domain.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventResult;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.repository.PredictionEventResultRepository;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * ANT-EVENT-03 — 순위 굳힘. 정렬 3단계(순이익 → HIT 평균 오차 → 첫 등록) · 자격(min_stocks) · FAILED 제외 · 멱등.
 */
@SpringBootTest
@Transactional
@DisplayName("회차 순위 굳힘")
class EventResultServiceTest {

    static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 17);
    static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 21);

    @Autowired EntityManager em;
    @Autowired EventResultService service;
    @Autowired PredictionEventResultRepository results;

    private final Instant now = Instant.now();
    private PredictionEvent event;
    private PredictionEventStock s0;
    private PredictionEventStock s1;

    @BeforeEach
    void setUp() {
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES ('005930', '삼성전자', true), ('000660', 'SK하이닉스', true)").executeUpdate();
        event = PredictionEvent.create("순위", now, now.plus(1, ChronoUnit.DAYS), BASE_DATE, SETTLE_DATE, BigInteger.valueOf(500), BigInteger.valueOf(2000), (short) 2);
        em.persist(event);
        s0 = PredictionEventStock.of(event, (short) 0, em.find(Stock.class, "005930"), new BigDecimal("91000.00"));
        s1 = PredictionEventStock.of(event, (short) 1, em.find(Stock.class, "000660"), new BigDecimal("210000.00"));
        em.persist(s0);
        em.persist(s1);
        em.flush();
    }

    @Test
    @DisplayName("순이익 순 → 동점이면 HIT 평균 오차 작은 쪽 → 2종목 미만은 자격 없음(rank NULL) → FAILED 배팅은 집계 밖")
    void 순위() {
        User a = user("aa");
        User b = user("bb");
        User c = user("cc");
        User d = user("dd");
        // a: +500 (1500 − 1000) + 0 (700 − 700 환불)  → net +500, HIT 1 (오차 2.000)
        bet(a, s0, PredictionEventBet.Status.SETTLED, 1000, 1500, true, "2.000");
        bet(a, s1, PredictionEventBet.Status.REFUNDED, 700, 700, false, null);
        // b: net +500, HIT 2 (오차 1.000 · 3.000 → 평균 2.000)  — a 와 순이익·오차 동점 → 등록 시각(b 가 뒤) → a 가 앞
        bet(b, s0, PredictionEventBet.Status.SETTLED, 1000, 1500, true, "1.000");
        bet(b, s1, PredictionEventBet.Status.SETTLED, 500, 500, true, "3.000");
        // c: net −500, 1종목만 → 자격 없음
        bet(c, s0, PredictionEventBet.Status.SETTLED, 500, 0, false, null);
        // d: FAILED 만 → 집계 밖(행 없음)
        bet(d, s0, PredictionEventBet.Status.FAILED, 800, null, false, null);
        em.flush();

        int written = service.freeze(event.getId());

        assertThat(written).isEqualTo(3);
        List<PredictionEventResult> ranked = results.findRanked(event.getId());
        assertThat(ranked).extracting(r -> r.getKey().getUserId()).containsExactly(a.getId(), b.getId());
        assertThat(ranked.get(0).getRank()).isEqualTo(1);
        assertThat(ranked.get(0).getNet()).isEqualTo(BigInteger.valueOf(500));
        assertThat(ranked.get(0).getStaked()).isEqualTo(BigInteger.valueOf(1700));
        assertThat(ranked.get(0).getPaid()).isEqualTo(BigInteger.valueOf(2200));
        assertThat(ranked.get(0).getHitCount()).isEqualTo((short) 1);
        assertThat(ranked.get(0).getAvgError()).isEqualByComparingTo("2.000");
        assertThat(ranked.get(1).getAvgError()).isEqualByComparingTo("2.000");
        PredictionEventResult cRow = results.findByEventIdAndUserId(event.getId(), c.getId()).orElseThrow();
        assertThat(cRow.isQualified()).isFalse();
        assertThat(cRow.getRank()).isNull();
        assertThat(cRow.getNet()).isEqualTo(BigInteger.valueOf(-500));
        assertThat(cRow.getAvgError()).isNull(); // HIT 없음
        assertThat(results.findByEventIdAndUserId(event.getId(), d.getId())).isEmpty();
    }

    @Test
    @DisplayName("정산 안 끝난 배팅(ACTIVE·REVEALED)이 있으면 EVENT_NOT_READY")
    void 미정산_거부() {
        User a = user("aa");
        bet(a, s0, PredictionEventBet.Status.REVEALED, 1000, null, false, null);
        em.flush();
        assertThatThrownBy(() -> service.freeze(event.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EVENT_NOT_READY);
    }

    @Test
    @DisplayName("두 번 굳혀도 행은 한 벌 — 지우고 다시 쓴다")
    void 멱등() {
        User a = user("aa");
        bet(a, s0, PredictionEventBet.Status.SETTLED, 1000, 0, false, null);
        em.flush();
        service.freeze(event.getId());
        service.freeze(event.getId());
        em.flush();
        assertThat(em.createQuery("select count(r) from PredictionEventResult r where r.key.eventId = :e", Long.class).setParameter("e", event.getId()).getSingleResult())
                .isEqualTo(1L);
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private User user(String suffix) {
        User u = User.create();
        u.linkWallet("0x00000000000000000000000000000000000000" + suffix);
        em.persist(u);
        return u;
    }

    private void bet(User user, PredictionEventStock stock, PredictionEventBet.Status status, long stake, Integer payout, boolean hit, String errorRate) {
        Prediction p =
                Prediction.register(user, stock.getStock(), Prediction.Direction.UP, new BigDecimal("93000.00"), new BigDecimal("91000.00"), (short) 4, BASE_DATE, SETTLE_DATE);
        p.fixBasePrice(stock.getBasePrice());
        p.settle(new BigDecimal("91800.00"), SETTLE_DATE, hit, errorRate == null ? new BigDecimal("9.999") : new BigDecimal(errorRate));
        em.persist(p);
        PredictionEventBet b = PredictionEventBet.place(p, event, stock, BigInteger.valueOf(stake), "0x" + "48".repeat(32), "op_" + p.getId());
        em.persist(b);
        em.flush();
        String sql =
                switch (status) {
                    case SETTLED, REFUNDED -> "UPDATE prediction_event_bets SET bet_status = :s, payout = :p, bet_index = :i WHERE prediction_id = :id";
                    default -> "UPDATE prediction_event_bets SET bet_status = :s, bet_index = :i WHERE prediction_id = :id";
                };
        var q = em.createNativeQuery(sql).setParameter("s", status.name()).setParameter("i", p.getId()).setParameter("id", p.getId());
        if (status == PredictionEventBet.Status.SETTLED || status == PredictionEventBet.Status.REFUNDED) {
            q.setParameter("p", BigInteger.valueOf(payout));
        }
        q.executeUpdate();
        em.clear();
    }
}
