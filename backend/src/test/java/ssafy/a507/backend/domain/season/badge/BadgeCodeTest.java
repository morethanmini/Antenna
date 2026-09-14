package ssafy.a507.backend.domain.season.badge;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ssafy.a507.backend.domain.season.service.SeasonResultCalculator;

/**
 * 배지 부여 조건 (ANT-SEASON-09). 스프링이 필요 없는 순수 판정이라 단위 테스트로 본다.
 *
 * <p>지표의 부호·단위를 여기서 못 박는다 — maxDrawdown 은 <b>양수 퍼센트</b>(고점 대비 낙폭 크기)이고
 * winRate 도 0~100 퍼센트다. 부호를 반대로 읽으면 조건이 조용히 항상 거짓이 된다.
 */
class BadgeCodeTest {

    private static SeasonResultCalculator.Result result(
            String returnRate, String maxDrawdown, String winRate, String avgHoldingDays) {
        return new SeasonResultCalculator.Result(
                new BigDecimal("10000000"),
                new BigDecimal(returnRate),
                null,
                maxDrawdown == null ? null : new BigDecimal(maxDrawdown),
                winRate == null ? null : new BigDecimal(winRate),
                null,
                avgHoldingDays == null ? null : new BigDecimal(avgHoldingDays));
    }

    @Test
    @DisplayName("15% 이상 떨어졌다가 플러스로 끝내면 위기 극복")
    void 낙폭을_견디고_수익으로_끝내면_위기_극복() {
        List<BadgeCode> earned = BadgeCode.earnedBy(result("3.2", "18.4", null, null), 0);

        assertThat(earned).contains(BadgeCode.CRISIS_SURVIVOR);
    }

    @Test
    @DisplayName("낙폭이 깊어도 손실로 끝내면 위기 극복이 아니다")
    void 낙폭만_깊고_손실이면_위기_극복이_아니다() {
        List<BadgeCode> earned = BadgeCode.earnedBy(result("-4.0", "22.0", null, null), 0);

        assertThat(earned).doesNotContain(BadgeCode.CRISIS_SURVIVOR);
    }

    @Test
    @DisplayName("평균 보유일이 10일 이상이면 장기 투자자")
    void 오래_들고_있으면_장기_투자자() {
        List<BadgeCode> earned = BadgeCode.earnedBy(result("1.0", "2.0", null, "10.0"), 0);

        assertThat(earned).contains(BadgeCode.LONG_TERM_INVESTOR);
    }

    @Test
    @DisplayName("수익률 10% 이상이면 수익 달성")
    void 수익률_10퍼센트면_수익_달성() {
        List<BadgeCode> earned = BadgeCode.earnedBy(result("10.0", "1.0", null, null), 0);

        assertThat(earned).contains(BadgeCode.PROFIT_ACHIEVED);
    }

    @Test
    @DisplayName("승률 70% 이상이되 매도가 5회는 돼야 연승")
    void 승률이_높아도_매도가_적으면_연승이_아니다() {
        List<BadgeCode> few = BadgeCode.earnedBy(result("5.0", "1.0", "100.00", null), 4);
        List<BadgeCode> enough = BadgeCode.earnedBy(result("5.0", "1.0", "70.00", null), 5);

        assertThat(few).doesNotContain(BadgeCode.WINNING_STREAK);
        assertThat(enough).contains(BadgeCode.WINNING_STREAK);
    }

    @Test
    @DisplayName("정의할 수 없는 지표(null)는 조건을 만족하지 않은 것으로 본다")
    void 지표가_null_이면_그_배지는_안_준다() {
        List<BadgeCode> earned = BadgeCode.earnedBy(result("1.0", null, null, null), 0);

        assertThat(earned).isEmpty();
    }

    @Test
    @DisplayName("한 회차가 여러 조건을 채우면 여러 장을 준다")
    void 조건을_여러_개_채우면_여러_장() {
        List<BadgeCode> earned = BadgeCode.earnedBy(result("12.5", "16.0", "80.00", "11.0"), 6);

        assertThat(earned).containsExactlyInAnyOrder(
                BadgeCode.CRISIS_SURVIVOR,
                BadgeCode.LONG_TERM_INVESTOR,
                BadgeCode.PROFIT_ACHIEVED,
                BadgeCode.WINNING_STREAK);
    }
}
