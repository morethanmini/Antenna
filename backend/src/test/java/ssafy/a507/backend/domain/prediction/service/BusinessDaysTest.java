package ssafy.a507.backend.domain.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("영업일 계산 — 기준일 · 만기일")
class BusinessDaysTest {

    /** 2026-09-14 는 월요일이다. */
    private static final LocalDate MON = LocalDate.of(2026, 9, 14);

    @Test
    @DisplayName("수요일에서 3영업일은 다음 주 월요일이다 — 달력일로 더하면 토요일이 된다(운영에서 실제로 난 일)")
    void 수요일_더하기_3() {
        LocalDate wed = MON.plusDays(2);

        assertThat(wed.plusDays(3).getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(BusinessDays.plusWeekdays(wed, 3)).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    @DisplayName("목요일에서 3영업일은 금 · 월 · 화 — 화요일")
    void 목요일_더하기_3() {
        assertThat(BusinessDays.plusWeekdays(MON.plusDays(3), 3)).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    @DisplayName("주말을 끼지 않으면 달력일과 같다 — 월요일 + 3 = 목요일")
    void 주말을_안_끼면_그대로() {
        assertThat(BusinessDays.plusWeekdays(MON, 3)).isEqualTo(MON.plusDays(3));
    }

    @Test
    @DisplayName("5영업일은 정확히 한 주 뒤 같은 요일이다 — 휴장일이 없는 주에서")
    void 다섯_영업일은_한_주() {
        // 9/14 주에서 세면 추석(9/24 · 25)을 건너뛰어 하루 이틀 더 간다(ANT-PRED-12). 휴장일이 없는 11월 첫 주로 옮겼다
        LocalDate quietMonday = LocalDate.of(2026, 11, 2);
        for (int i = 0; i < 5; i++) {
            LocalDate from = quietMonday.plusDays(i);
            assertThat(BusinessDays.plusWeekdays(from, 5)).isEqualTo(from.plusWeeks(1));
        }
    }

    @Test
    @DisplayName("어느 요일에서 몇 영업일을 세든 결과는 평일이다 — 3~90 전 구간")
    void 결과는_늘_평일() {
        for (int start = 0; start < 7; start++) {
            for (int days = 3; days <= 90; days++) {
                DayOfWeek dow = BusinessDays.plusWeekdays(MON.plusDays(start), days).getDayOfWeek();
                assertThat(dow).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            }
        }
    }

    @Test
    @DisplayName("토요일에서 세면 월요일이 첫 영업일이다")
    void 주말에서_시작() {
        assertThat(BusinessDays.plusWeekdays(MON.plusDays(5), 1)).isEqualTo(MON.plusWeeks(1));
    }

    @Test
    @DisplayName("다음 평일 — 금요일 다음은 월요일")
    void 다음_평일() {
        assertThat(BusinessDays.nextWeekday(MON.plusDays(4))).isEqualTo(MON.plusWeeks(1));
        assertThat(BusinessDays.nextWeekday(MON)).isEqualTo(MON.plusDays(1));
    }

    // ── 휴장일 (ANT-PRED-12) ─────────────────────────────────────────
    // 2026-09: 23 수 · 24 목(추석 연휴) · 25 금(추석) · 26·27 주말 · 28 월

    @Test
    @DisplayName("추석 전날(9/23 수) 등록 — 기준일이 연휴를 건너 9/28(월)이고, 3일 만기는 10/1(목)이다")
    void 기준일이_연휴를_건너뛴다() {
        LocalDate base = BusinessDays.nextWeekday(LocalDate.of(2026, 9, 23));

        assertThat(base).isEqualTo(LocalDate.of(2026, 9, 28));
        // 전에는 기준일 9/24(휴장) · 만기 9/29 였다 — 기준가가 9/28 종가로 밀려 "3일" 이 하루만 쟀다
        assertThat(BusinessDays.plusWeekdays(base, 3)).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("만기일을 세다가 휴장일을 만나면 세지 않는다 — 9/21(월) + 3 = 22 · 23 · 28")
    void 만기일이_연휴를_건너뛴다() {
        assertThat(BusinessDays.plusWeekdays(LocalDate.of(2026, 9, 21), 3)).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    @DisplayName("휴장일 목록 — 추석 이틀은 쉬고 9/28(월)은 선다. 추석은 일요일과 겹칠 때만 대체휴일이다")
    void 휴장일() {
        assertThat(MarketHolidays.isTradingDay(LocalDate.of(2026, 9, 24))).isFalse();
        assertThat(MarketHolidays.isTradingDay(LocalDate.of(2026, 9, 25))).isFalse();
        assertThat(MarketHolidays.isTradingDay(LocalDate.of(2026, 9, 28))).isTrue();
        assertThat(MarketHolidays.isTradingDay(LocalDate.of(2026, 10, 5))).isFalse(); // 개천절 대체
        assertThat(MarketHolidays.isTradingDay(LocalDate.of(2026, 12, 31))).isFalse(); // 연말 휴장
        assertThat(MarketHolidays.isTradingDay(LocalDate.of(2026, 9, 26))).isFalse(); // 토요일
    }

    @Test
    @DisplayName("공개일 — 만기일 이후 첫 거래일의 다음 거래일이다. 종가는 다음 거래일 13:00 에 들어온다")
    void 공개일() {
        // 평일 만기: 그날 종가가 다음 날 들어온다
        assertThat(BusinessDays.revealDate(LocalDate.of(2026, 9, 22))).isEqualTo(LocalDate.of(2026, 9, 23));
        // 금요일 만기: 월요일 회차가 금요일 종가를 집는다
        assertThat(BusinessDays.revealDate(LocalDate.of(2026, 9, 18))).isEqualTo(LocalDate.of(2026, 9, 21));
        // 일요일 만기(옛 달력일 방식 예측): 월요일 종가로 판정 → 화요일. 운영에서 "3일인데 안 열린다" 로 보인 사례
        assertThat(BusinessDays.revealDate(LocalDate.of(2026, 9, 20))).isEqualTo(LocalDate.of(2026, 9, 22));
        // 추석 전날 만기: 다음 거래일이 연휴 뒤다
        assertThat(BusinessDays.revealDate(LocalDate.of(2026, 9, 23))).isEqualTo(LocalDate.of(2026, 9, 28));
        // 추석 당일 만기(배포 전에 등록된 예측): 9/28 종가로 판정 → 9/29
        assertThat(BusinessDays.revealDate(LocalDate.of(2026, 9, 24))).isEqualTo(LocalDate.of(2026, 9, 29));
    }

    @Test
    @DisplayName("공개일 — 이미 판정됐으면 실제로 종가를 딴 날에서 센다. 만기일이 없으면 null")
    void 공개일_판정된_건() {
        // 만기 9/21 인데 그날 종가가 빠져 9/22 종가로 판정된 건
        assertThat(BusinessDays.revealDate(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 22)))
                .isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(BusinessDays.revealDate(LocalDate.of(2026, 9, 21), null)).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(BusinessDays.revealDate(null, null)).isNull();
    }
}
