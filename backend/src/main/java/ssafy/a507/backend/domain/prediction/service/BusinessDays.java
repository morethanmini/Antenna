package ssafy.a507.backend.domain.prediction.service;

import java.time.LocalDate;

/**
 * 기준일 · 만기일 · 공개일 계산 (ANT-PRED-01 · 11 · 12).
 *
 * <p>"다음 영업일" 은 <b>주말도 휴장일도 아닌 다음 날</b>이다. 휴장일은 {@link MarketHolidays} 의 목록으로 안다
 * (ANT-PRED-12). 전에는 주말만 걸렀다 — 기준일이 추석에 앉으면 기준가만 연휴 뒤로 밀리고 만기일은 그대로라
 * "3일" 예측이 하루만 쟀고, 평일 휴장이 기간만큼 이어지면 기준가와 판정가가 같은 날 종가가 되어 방향과 무관하게
 * 적중이 됐다(PredictionRevealTimelineTest).
 *
 * <p>누가 계산해도 같은 값이 나와야 한다(D-day 의 기준) — 그래서 시세 테이블이 아니라 고정된 목록을 본다. 목록에
 * 없는 휴장(임시 휴장 · 목록 밖의 해)은 여전히 모른다. 그날 시세가 없을 뿐이고, 판정 배치가 "시세 없으면 보류" 라
 * 다음 거래일 종가를 기다린다.
 *
 * <p><b>등록할 때만 부른다.</b> 이미 등록된 예측의 기준일 · 만기일은 다시 계산하지 않는다.
 */
public final class BusinessDays {

    private BusinessDays() {}

    /**
     * {@code from} 다음의 첫 거래일. 금요일이면 월요일, 토·일이면 월요일, 그날이 휴장이면 그다음 날.
     *
     * <p>이름이 "평일" 인 것은 휴장일을 모르던 때의 흔적이다. 부르는 곳이 있어 그대로 둔다.
     */
    public static LocalDate nextWeekday(LocalDate from) {
        LocalDate d = from.plusDays(1);
        while (!MarketHolidays.isTradingDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /**
     * {@code from} 에서 평일만 {@code days} 번 센 날 — 만기일 계산 (ANT-PRED-11).
     *
     * <p>목요일 + 3 = 금 · 월 · 화 → 화요일. 전에는 달력일로 더해({@code from.plusDays}) 수요일 + 3 이 토요일이 됐다.
     * 판정은 그래도 맞았다 — 판정 배치가 "만기일 또는 그 이후 첫 거래일" 종가를 쓴다(PRED-04). 어긋난 것은 약속이다:
     * 화면에 적힌 만기일(토)과 실제로 판정한 날(월)이 달랐고, 그 사이 D-day 가 음수로 보였다.
     *
     * <p>휴장일도 건너뛴다(ANT-PRED-12) — {@link #nextWeekday} 가 거른다.
     *
     * @param days 1 이상. {@code from} 이 주말이어도 된다 — 그 다음 평일부터 센다
     */
    public static LocalDate plusWeekdays(LocalDate from, int days) {
        LocalDate d = from;
        for (int i = 0; i < days; i++) {
            d = nextWeekday(d);
        }
        return d;
    }

    /**
     * 결과가 공개되는 날 (ANT-PRED-12) — 화면이 "3일" 대신 이 날짜를 적는다.
     *
     * <p>판정은 만기일 <b>이후 첫 거래일</b>의 종가로 하고, 그 종가는 <b>다음 거래일</b> 13:00 에 들어와 13:30 에
     * 판정 · 공개된다(DailyQuoteIngestService · PredictionSettlementScheduler). 만기가 일요일이면 월요일 종가로,
     * 화요일에 공개된다 — 전에는 화면이 기간만 적어 "3일이 지났는데 안 열린다" 로 보였다.
     *
     * <p>만기일 하나로 계산하므로 달력일로 등록된 옛 예측 · 영업일로 등록된 예측 · 날짜를 회차에서 받은 대회 예측에
     * 똑같이 맞는다. <b>예정일이다</b> — 포털이 종가를 늦게 올리면 그만큼 늦어진다.
     *
     * <p>"다음 거래일" 로 센다. 수집 · 판정 크론은 평일이면 휴장일에도 돌지만(MON-FRI), 포털이 휴장일에 전일 종가를
     * 올리는지는 확인된 바 없다. 안 올린다고 보고 늦은 쪽으로 약속한다 — 실제로 더 일찍 들어오면 예정일보다 먼저
     * 열릴 뿐이고, 반대로 잡으면 "공개일이 지났는데 안 열린다" 가 된다.
     *
     * @param settleDate 만기일. 휴장일이어도 된다
     */
    public static LocalDate revealDate(LocalDate settleDate, LocalDate settledOn) {
        if (settleDate == null) {
            return null;
        }
        // 이미 판정됐으면 실제로 종가를 딴 날에서 센다 — 포털이 하루를 통째로 빼먹어 다음 날 종가로 판정한 경우까지 맞는다
        return settledOn != null ? nextWeekday(settledOn) : revealDate(settleDate);
    }

    /** 아직 판정 전인 예측의 공개 예정일. 만기일만으로 센다. */
    public static LocalDate revealDate(LocalDate settleDate) {
        LocalDate judgedOn = settleDate;
        while (!MarketHolidays.isTradingDay(judgedOn)) {
            judgedOn = judgedOn.plusDays(1);
        }
        return nextWeekday(judgedOn);
    }
}
