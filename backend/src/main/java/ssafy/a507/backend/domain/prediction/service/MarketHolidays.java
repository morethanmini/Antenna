package ssafy.a507.backend.domain.prediction.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * 증시 휴장일 (ANT-PRED-12). 주말이 아닌데 장이 안 서는 날이다.
 *
 * <p><b>왜 표가 아니라 상수인가.</b> 표로 두면 스키마가 하나 늘고(ddl-auto 라 팀 DB 에 번진다) 채우는 배치·관리 화면이
 * 따라온다. 필요한 것은 "올해 남은 휴장일을 안다" 뿐이고 그 목록은 한 해에 한 번, 전년 말에 확정된다. 해가 바뀌면
 * 이 파일에 한 줄씩 더한다. 목록 밖의 해는 주말만 거른다 — 옛 동작과 같고, 아래 안전망이 받친다.
 *
 * <p><b>이 목록이 틀려도 판정은 틀리지 않는다.</b> 판정 배치는 달력을 보지 않고 시세를 본다 — "만기일 또는 그 이후 첫
 * 거래일의 종가"(PredictionSettlementService.closeOnOrAfter). 임시 휴장처럼 여기 없는 날이 생겨도 그날 시세가 없을
 * 뿐이고 다음 거래일 종가를 기다린다. 이 목록이 고치는 것은 <b>약속</b>이다: 등록 때 잡는 기준일·만기일이 휴장일에
 * 앉지 않게 하고, 화면에 적는 공개일이 연휴를 건너뛰게 한다.
 *
 * <p>화면에 같은 목록이 있다({@code frontend/src/api/marketHolidays.ts}) — 등록 전에 보여 주는 만기일이 서버와
 * 같아야 해서다. <b>둘을 같이 고친다.</b>
 *
 * <p>출처: 한국거래소 휴장일 공고(관공서 공휴일 + 근로자의 날 + 연말 휴장). 2026-09-21 기준으로 남은 날만 적었다 —
 * 지난 날은 이미 등록된 예측의 날짜를 바꾸지 않으므로 적어도 쓸 데가 없다.
 */
public final class MarketHolidays {

    private MarketHolidays() {}

    private static final Set<LocalDate> CLOSED = Set.of(
            LocalDate.of(2026, 9, 24), // 추석 연휴
            LocalDate.of(2026, 9, 25), // 추석 (9/26 연휴 마지막 날은 토요일. 추석은 일요일과 겹칠 때만 대체휴일이라 9/28 은 개장)
            LocalDate.of(2026, 10, 5), // 개천절(10/3 토) 대체공휴일
            LocalDate.of(2026, 10, 9), // 한글날
            LocalDate.of(2026, 12, 25), // 성탄절
            LocalDate.of(2026, 12, 31)); // 연말 휴장

    /** 장이 서는 날인가 — 주말도 휴장일도 아니다. */
    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !CLOSED.contains(date);
    }
}
