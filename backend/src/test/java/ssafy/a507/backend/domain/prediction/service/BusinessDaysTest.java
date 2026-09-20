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
    @DisplayName("5영업일은 정확히 한 주 뒤 같은 요일이다")
    void 다섯_영업일은_한_주() {
        for (int i = 0; i < 5; i++) {
            LocalDate from = MON.plusDays(i);
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
}
