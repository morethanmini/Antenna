package ssafy.a507.backend.domain.prediction.service;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 기준일 계산 (ANT-PRED-01, plan §5-②).
 *
 * <p>"다음 영업일" 을 <b>다음 평일</b>로 근사한다. 미래의 휴장일은 시세 테이블에 없어 알 수 없고, 누가 계산해도 같은 값이
 * 나와야 한다(커밋·D-day 의 기준). 공휴일에 걸리면 그날 시세가 없을 뿐이고, 기준가 배치(PRED-03)는 "시세 없으면 보류" 라
 * 다음 거래일 종가를 기다린다. 기준가가 늦게 확정될 뿐 예측이 틀어지진 않는다.
 */
public final class BusinessDays {

    private BusinessDays() {}

    /** {@code from} 다음의 첫 평일(월~금). 금요일이면 월요일, 토·일이면 월요일. */
    public static LocalDate nextWeekday(LocalDate from) {
        LocalDate d = from.plusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
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
     * <p>공휴일은 여전히 모른다(위 클래스 머리말과 같은 이유). 만기일이 공휴일에 걸리면 그날 시세가 없을 뿐이고,
     * 판정 배치가 다음 거래일 종가를 기다린다. 주말만 걸러도 어긋나는 날이 일 년에 열흘 남짓으로 준다.
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
}
