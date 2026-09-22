package ssafy.a507.backend.domain.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.market.repository.StockRepository;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.repository.PredictionRepository;

/**
 * ANT-PRED-12 — "언제 판정되고 언제 공개(리빌)되는가" 를 <b>날짜를 하루씩 넘기며</b> 본다. H2.
 *
 * <p>{@link PredictionSettlementRunnerTest} 는 한 회차 안의 규칙을 본다. 여기는 그 회차를 달력 위에 늘어놓는다 —
 * 운영에서 헷갈린 것은 규칙 하나하나가 아니라 <b>규칙 둘이 겹친 결과</b>였다.
 *
 * <ul>
 *   <li>종가는 <b>다음 영업일 13:00</b> 에 들어온다. 월요일 회차가 금요일 종가를 집는다(DailyQuoteIngestService).
 *   <li>판정 배치는 <b>영업일 13:30</b> 에 돈다. 공휴일에도 돈다 — 공휴일 달력이 없다.
 *   <li>그래서 공개 시각 = "만기일 이후 첫 거래일의 종가가 들어온 날 13:30" 이다. 만기 다음 날이 아니다.
 * </ul>
 *
 * <p>{@link #day} 가 하루다: 그날 13:00 에 들어올 종가를 넣고 13:30 배치를 돌린다. 수집 배치 자체는 부르지 않는다 —
 * 외부 API 가 필요하고, 어느 날짜의 종가가 언제 들어오는지는 DailyQuoteIngestServiceTest 가 따로 고정한다.
 *
 * <p>날짜는 2026년 9월 실제 달력이다. 17 목 · 18 금 · 19·20 주말 · 21 월 · 22 화 · 23 수 · 24 목(추석 연휴) ·
 * 25 금(추석) · 26·27 주말 · 28 월 · 29 화. {@code @Transactional} 을 안 붙이는 이유는 옆 테스트와 같다.
 *
 * <p><b>"현재 동작" 이라고 적힌 테스트는 옳다는 뜻이 아니다.</b> 지금 이렇게 돈다는 기록이고, 고치면 같이 고친다.
 */
@SpringBootTest
@DisplayName("판정·공개 타임라인 (ANT-PRED-12)")
class PredictionRevealTimelineTest {

    private static final String STOCK = "999911";

    private static final LocalDate SEP17_THU = LocalDate.of(2026, 9, 17);
    private static final LocalDate SEP18_FRI = LocalDate.of(2026, 9, 18);
    private static final LocalDate SEP20_SUN = LocalDate.of(2026, 9, 20);
    private static final LocalDate SEP21_MON = LocalDate.of(2026, 9, 21);
    private static final LocalDate SEP22_TUE = LocalDate.of(2026, 9, 22);
    private static final LocalDate SEP23_WED = LocalDate.of(2026, 9, 23);
    private static final LocalDate SEP24_THU = LocalDate.of(2026, 9, 24); // 추석 연휴 · 휴장
    private static final LocalDate SEP25_FRI = LocalDate.of(2026, 9, 25); // 추석 · 휴장
    private static final LocalDate SEP28_MON = LocalDate.of(2026, 9, 28);
    private static final LocalDate SEP29_TUE = LocalDate.of(2026, 9, 29);
    private static final LocalDate SEP30_WED = LocalDate.of(2026, 9, 30);

    @Autowired PredictionSettlementRunner runner;
    @Autowired PredictionRepository predictions;
    @Autowired UserRepository users;
    @Autowired StockRepository stocks;
    @Autowired JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        clean();
        userId = users.save(User.create()).getId();
        jdbc.update("INSERT INTO stocks (code, name, listed) VALUES (?, ?, true)", STOCK, "타임라인종목");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ── 운영에서 본 사례 ──────────────────────────────────────────────

    @Test
    @DisplayName("기준 9/17(목) · 만기 9/20(일) — 9/21(월) 종가로 판정하고, 공개는 그 종가가 들어오는 9/22(화) 13:30 이다")
    void 만기가_일요일이면_화요일에_공개된다() {
        // ANT-PRED-11 전의 달력일 방식으로 등록된 예측. 화면에는 "3일" 이라고만 보인다
        long id = given(SEP17_THU, SEP20_SUN, Prediction.Direction.UP);
        assertThat(BusinessDays.revealDate(SEP20_SUN)).isEqualTo(SEP22_TUE); // 화면이 "3일" 대신 적는 날짜

        day(SEP18_FRI, quote(SEP17_THU, "80000"));
        assertThat(status(id)).isEqualTo(Prediction.Status.OPEN);

        // 만기 다음 날인 월요일. 들어오는 것은 금요일 종가라 판정할 값이 아직 없다 — "3일이 지났는데 공개가 안 됐다"
        day(SEP21_MON, quote(SEP18_FRI, "81000"));
        assertThat(status(id)).isEqualTo(Prediction.Status.OPEN);
        assertThat(revealed(id)).isFalse();

        day(SEP22_TUE, quote(SEP21_MON, "83000"));
        Prediction p = predictions.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Prediction.Status.HIT);
        assertThat(p.getSettlePrice()).isEqualByComparingTo("83000");
        assertThat(p.getSettledOn()).isEqualTo(SEP21_MON);
        assertThat(p.getSettleDate()).isEqualTo(SEP20_SUN); // 약속한 만기일은 안 움직인다
        assertThat(revealed(id)).isTrue();
    }

    @Test
    @DisplayName("9/17(목)에 3일짜리를 등록하면 — 기준 9/18(금) · 만기 9/23(수). 9/23 종가가 휴장일인 9/24 에 들어오면 그날 공개된다")
    void 목요일_등록_3영업일() {
        LocalDate base = BusinessDays.nextWeekday(SEP17_THU);
        LocalDate settle = BusinessDays.plusWeekdays(base, 3);
        assertThat(base).isEqualTo(SEP18_FRI);
        assertThat(settle).isEqualTo(SEP23_WED);
        long id = given(base, settle, Prediction.Direction.DOWN);

        // 등록 다음 날에도 기준가가 없다 — 금요일 종가는 월요일 13:00 에 온다. 주말을 끼면 BASE 로 나흘을 보낸다
        day(SEP18_FRI, quote(SEP17_THU, "80000"));
        assertThat(status(id)).isEqualTo(Prediction.Status.BASE);

        day(SEP21_MON, quote(SEP18_FRI, "81000"));
        Prediction opened = predictions.findById(id).orElseThrow();
        assertThat(opened.getStatus()).isEqualTo(Prediction.Status.OPEN);
        assertThat(opened.getBasePrice()).isEqualByComparingTo("81000");
        assertThat(opened.getBasedOn()).isEqualTo(SEP18_FRI);

        day(SEP22_TUE, quote(SEP21_MON, "80500"));
        day(SEP23_WED, quote(SEP22_TUE, "80000"));
        assertThat(status(id)).isEqualTo(Prediction.Status.OPEN); // 만기 당일에도 판정되지 않는다

        // 9/24 는 휴장이지만 크론은 MON-FRI 라 돈다. 포털이 그날 9/23 종가를 올리면 판정된다.
        // 화면의 공개 예정일은 9/28 이다 — 휴장일엔 안 올린다고 보고 늦은 쪽으로 약속한다(BusinessDays.revealDate).
        // 그래서 이 경우는 예정일보다 먼저 열린다. 어느 쪽인지는 추석에 운영 로그로 확인한다
        assertThat(BusinessDays.revealDate(settle)).isEqualTo(SEP28_MON);
        day(SEP24_THU, quote(SEP23_WED, "79000"));
        Prediction p = predictions.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Prediction.Status.HIT);
        assertThat(p.getSettledOn()).isEqualTo(SEP23_WED);
        assertThat(revealed(id)).isTrue();
    }

    // ── 추석 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("만기일이 추석 휴장(9/24)인 예측 — 연휴 뒤 첫 거래일 9/28(월) 종가로 판정하고 9/29(화) 13:30 에 공개된다")
    void 만기가_추석이면_연휴_뒤_종가로_판정() {
        // 휴장일을 알기 전(ANT-PRED-12 배포 전)에 9/18(금) 등록 · 3일 → 기준 9/21(월) · 만기 9/24(목)로 저장된 행이다.
        // 지금 등록하면 만기가 9/28 로 잡힌다(BusinessDaysTest). 이미 저장된 날짜는 다시 계산하지 않는다
        long id = given(SEP21_MON, SEP24_THU, Prediction.Direction.UP);
        assertThat(BusinessDays.revealDate(SEP24_THU)).isEqualTo(SEP29_TUE); // 화면에 적히는 공개일과 같은 날이다

        day(SEP22_TUE, quote(SEP21_MON, "80000"));
        day(SEP23_WED, quote(SEP22_TUE, "80100"));
        day(SEP24_THU, quote(SEP23_WED, "80200"));
        day(SEP25_FRI); // 9/24 는 휴장이라 들어올 종가가 없다
        day(SEP28_MON); // 9/25 도 휴장
        assertThat(status(id)).isEqualTo(Prediction.Status.OPEN);
        assertThat(revealed(id)).isFalse();

        day(SEP29_TUE, quote(SEP28_MON, "82000"));
        Prediction p = predictions.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Prediction.Status.HIT);
        assertThat(p.getSettledOn()).isEqualTo(SEP28_MON);
        assertThat(p.getSettleDate()).isEqualTo(SEP24_THU);
        assertThat(revealed(id)).isTrue();
    }

    @Test
    @DisplayName("배포 전 등록분 — 기준일이 추석 휴장으로 저장된 예측은 기준가만 연휴 뒤로 밀린다. 3일이 실제로는 9/28→9/29 하루를 잰다")
    void 기준일이_추석이면_측정_구간이_줄어든다() {
        // 휴장일을 모르던 때 9/23(수)에 등록하면 이렇게 저장됐다. 지금은 기준 9/28 · 만기 10/1 로 잡힌다(BusinessDaysTest).
        // 저장된 날짜는 옮기지 않으므로 그런 행이 있으면 여전히 이렇게 판정된다
        long id = given(SEP24_THU, SEP29_TUE, Prediction.Direction.UP);

        day(SEP24_THU, quote(SEP23_WED, "80000"));
        day(SEP25_FRI);
        day(SEP28_MON);
        assertThat(status(id)).isEqualTo(Prediction.Status.BASE);

        day(SEP29_TUE, quote(SEP28_MON, "81000"));
        day(SEP30_WED, quote(SEP29_TUE, "80500"));

        Prediction p = predictions.findById(id).orElseThrow();
        assertThat(p.getBasedOn()).isEqualTo(SEP28_MON);
        assertThat(p.getSettledOn()).isEqualTo(SEP29_TUE);
        // "3일" 이라고 등록했지만 기준가와 판정가 사이는 1거래일이다. 만기일은 기준가를 따라 밀리지 않는다
        assertThat(p.getStatus()).isEqualTo(Prediction.Status.MISS);
    }

    @Test
    @DisplayName("현재 동작 — 목록에 없는 휴장이 기간만큼 이어지면 기준가와 판정가가 같은 날 종가가 되어 상승·하락 모두 적중이다")
    void 연휴가_기간만큼_길면_전원_적중() {
        // 9/28(월)까지 쉰다고 가정한 달력이다(평일 3일 연속 휴장). 기간 최솟값이 3일이라 이것이 최소 조건이다.
        // 휴장일 목록(MarketHolidays)이 맞으면 기준일이 휴장일에 앉지 않아 이 길로 오지 않는다 — 임시 휴장 · 목록 누락의 경우다
        long up = given(SEP24_THU, SEP29_TUE, Prediction.Direction.UP);
        long down = given(SEP24_THU, SEP29_TUE, Prediction.Direction.DOWN);

        day(SEP30_WED, quote(SEP29_TUE, "81000")); // 9/24 · 25 · 28 종가는 없다

        Prediction u = predictions.findById(up).orElseThrow();
        Prediction d = predictions.findById(down).orElseThrow();
        assertThat(u.getBasedOn()).isEqualTo(SEP29_TUE);
        assertThat(u.getSettledOn()).isEqualTo(SEP29_TUE);
        assertThat(u.getBasePrice()).isEqualByComparingTo(u.getSettlePrice());
        // 보합은 HIT 이라는 규칙(SettlementRules)과 만나 방향이 의미를 잃는다 — 랭킹에도 그대로 들어간다
        assertThat(u.getStatus()).isEqualTo(Prediction.Status.HIT);
        assertThat(d.getStatus()).isEqualTo(Prediction.Status.HIT);
    }

    // ── 종가가 제때 안 들어올 때 ─────────────────────────────────────

    @Test
    @DisplayName("만기일 종가가 이틀 늦게 들어와도 그 종가로 판정한다 — 그사이에는 판정하지도 공개하지도 않는다")
    void 종가가_늦게_들어오면_기다린다() {
        long id = givenOpen(SEP21_MON, Prediction.Direction.UP, "80000");

        day(SEP22_TUE); // 포털이 9/21 종가를 아직 안 올렸다
        day(SEP23_WED);
        assertThat(status(id)).isEqualTo(Prediction.Status.OPEN);
        assertThat(revealed(id)).isFalse();

        day(SEP24_THU, quote(SEP21_MON, "85000"));
        Prediction p = predictions.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Prediction.Status.HIT);
        assertThat(p.getSettledOn()).isEqualTo(SEP21_MON);
    }

    @Test
    @DisplayName("현재 동작 — 만기일 종가는 빠지고 다음 날 종가만 들어오면, 말없이 다음 날 종가로 판정한다")
    void 만기일_종가가_빠지면_다음_날_종가로_판정() {
        // 수집이 그날만 실패한 채 되짚기 창(10일)을 넘기거나, 그 종목만 하루 거래정지였던 경우다
        long id = givenOpen(SEP21_MON, Prediction.Direction.UP, "80000");

        day(SEP23_WED, quote(SEP22_TUE, "79000"));

        Prediction p = predictions.findById(id).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(Prediction.Status.MISS);
        assertThat(p.getSettledOn()).isEqualTo(SEP22_TUE);
        // 나중에 9/21 종가가 들어와도 되돌리지 않는다 — 판정은 한 번이다
        day(SEP24_THU, quote(SEP21_MON, "90000"));
        assertThat(status(id)).isEqualTo(Prediction.Status.MISS);
    }

    @Test
    @DisplayName("현재 동작 — 종가가 끝내 안 들어오는 종목(거래정지·상장폐지)의 예측은 끝없이 판정 대기다")
    void 종가가_영영_없으면_끝나지_않는다() {
        long id = givenOpen(SEP21_MON, Prediction.Direction.UP, "80000");

        for (LocalDate d = SEP22_TUE; !d.isAfter(SEP30_WED); d = BusinessDays.nextWeekday(d)) {
            day(d);
        }

        assertThat(status(id)).isEqualTo(Prediction.Status.OPEN);
        assertThat(revealed(id)).isFalse();
    }

    @Test
    @DisplayName("판정이 끝난 뒤 배치를 다시 돌려도 공개 시각은 처음 그대로다")
    void 공개_시각은_한_번만_찍힌다() {
        long id = givenOpen(SEP21_MON, Prediction.Direction.UP, "80000");
        day(SEP22_TUE, quote(SEP21_MON, "85000"));
        Object first = revealedAt(id);

        day(SEP23_WED, quote(SEP22_TUE, "70000"));

        assertThat(revealedAt(id)).isEqualTo(first);
        assertThat(predictions.findById(id).orElseThrow().getSettlePrice()).isEqualByComparingTo("85000");
    }

    // ── 등록일별 날짜 계산 ───────────────────────────────────────────

    @Test
    @DisplayName("등록 요일별 기준일·만기일 — 금요일 · 토요일 · 추석 당일 등록")
    void 등록일별_날짜() {
        // 금요일 3일: 기준 월 · 만기는 추석을 건너 다음 월요일
        assertThat(BusinessDays.nextWeekday(SEP18_FRI)).isEqualTo(SEP21_MON);
        assertThat(BusinessDays.plusWeekdays(SEP21_MON, 3)).isEqualTo(SEP28_MON);
        // 토요일 등록도 받는다. 금요일 등록과 같은 날짜가 된다
        assertThat(BusinessDays.nextWeekday(LocalDate.of(2026, 9, 19))).isEqualTo(SEP21_MON);
        // 추석 당일(9/24 목) 등록도 받는다: 기준일은 연휴 뒤 첫 거래일이다
        assertThat(BusinessDays.nextWeekday(SEP24_THU)).isEqualTo(SEP28_MON);
        assertThat(BusinessDays.plusWeekdays(SEP28_MON, 3)).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private record Quote(LocalDate tradeDate, String close) {}

    private static Quote quote(LocalDate tradeDate, String close) {
        return new Quote(tradeDate, close);
    }

    /** 하루. 13:00 에 들어올 종가를 넣고 13:30 판정 배치를 돌린다. 종가가 없는 날은 인자 없이 부른다. */
    private void day(LocalDate businessDate, Quote... arrivals) {
        for (Quote q : arrivals) {
            jdbc.update(
                    "INSERT INTO daily_quotes (stock_code, trade_date, close, collected_at)"
                            + " VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    STOCK,
                    q.tradeDate(),
                    new BigDecimal(q.close()));
        }
        runner.run(businessDate);
    }

    /** 등록 직후(BASE)의 예측. 커밋 행을 같이 넣어야 리빌 대상이 된다. */
    private long given(LocalDate baseDate, LocalDate settleDate, Prediction.Direction direction) {
        Stock stock = stocks.findById(STOCK).orElseThrow();
        long id = predictions.save(Prediction.register(
                        users.findById(userId).orElseThrow(),
                        stock,
                        direction,
                        new BigDecimal("82000"),
                        new BigDecimal("80000"),
                        (short) 3,
                        baseDate,
                        settleDate))
                .getId();
        jdbc.update(
                "INSERT INTO prediction_commits (prediction_id, commit_hash, salt) VALUES (?, ?, ?)",
                id,
                "0x" + String.format("%064x", id),
                "a".repeat(64));
        return id;
    }

    /** 기준가까지 확정된 예측. 만기 쪽만 볼 때 쓴다. */
    private long givenOpen(LocalDate settleDate, Prediction.Direction direction, String basePrice) {
        long id = given(SEP17_THU, settleDate, direction);
        jdbc.update(
                "update predictions set status = 'OPEN', base_price = ? where id = ?", new BigDecimal(basePrice), id);
        return id;
    }

    private Prediction.Status status(long id) {
        return predictions.findById(id).orElseThrow().getStatus();
    }

    private boolean revealed(long id) {
        return revealedAt(id) != null;
    }

    private Object revealedAt(long id) {
        return jdbc.queryForObject(
                "select revealed_at from prediction_commits where prediction_id = ?", Object.class, id);
    }

    private void clean() {
        jdbc.update("delete from notifications");
        jdbc.update("delete from user_settings");
        jdbc.update("delete from prediction_commits");
        jdbc.update("delete from prediction_evidences");
        jdbc.update("delete from prediction_notes");
        jdbc.update("delete from predictions");
        jdbc.update("delete from batch_runs");
        jdbc.update("delete from daily_quotes where stock_code = ?", STOCK);
        jdbc.update("delete from stocks where code = ?", STOCK);
    }
}
