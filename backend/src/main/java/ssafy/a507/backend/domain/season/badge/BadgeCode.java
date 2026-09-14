package ssafy.a507.backend.domain.season.badge;

import java.math.BigDecimal;
import java.util.List;
import ssafy.a507.backend.domain.season.service.SeasonResultCalculator;

/**
 * 배지 어휘와 부여 조건 (ANT-SEASON-09 · 명세 §모의투자 GET /users/me/badges).
 *
 * <p>이름 넷은 프론트 {@code api/account.ts} 의 {@code BADGE_LABEL} 과 같아야 한다 — 화면이 코드로
 * 한글 라벨을 찾고, 없는 코드는 코드 문자열이 그대로 노출된다.
 *
 * <p>조건은 시즌 결과 지표만 본다. 시즌 간 이력을 보지 않는 이유는 "연승" 까지 회차 하나로 판정하기
 * 위해서다 — 대회 모드가 없어져 연습만 남았고, 한 판을 끝낸 자리에서 바로 배지가 보여야 한다.
 *
 * <p><b>지표의 부호·단위를 잘못 읽으면 조건이 조용히 항상 거짓이 된다.</b> {@code maxDrawdown} 은
 * 고점 대비 낙폭의 <b>크기</b>라 양수 퍼센트이고(SeasonResultCalculator), {@code winRate} 도 0~100 이다.
 *
 * <p>null 은 "정의할 수 없다" 다 — 매도가 없으면 승률·보유일이, 손실이 없으면 손익비가 비어 있다.
 * 비어 있는 지표는 조건을 만족하지 않은 것으로 본다. 그러지 않으면 한 번도 팔지 않은 회차가 승률
 * 배지를 받는다.
 */
public enum BadgeCode {

    /** 고점 대비 15% 이상 밀렸는데도 플러스로 끝냈다. */
    CRISIS_SURVIVOR,
    /** 평균 보유일 10일 이상. */
    LONG_TERM_INVESTOR,
    /** 최종 수익률 10% 이상. */
    PROFIT_ACHIEVED,
    /** 매도 5회 이상에서 승률 70% 이상. */
    WINNING_STREAK;

    private static final BigDecimal CRISIS_DRAWDOWN_PCT = new BigDecimal("15");
    private static final BigDecimal LONG_TERM_DAYS = new BigDecimal("10");
    private static final BigDecimal PROFIT_RETURN_PCT = new BigDecimal("10");
    private static final BigDecimal STREAK_WIN_RATE_PCT = new BigDecimal("70");

    /** 승률을 배지로 인정하는 최소 표본. 한두 번 팔아 다 맞힌 것은 연승이 아니다. */
    private static final int STREAK_MIN_SELLS = 5;

    /**
     * 이 회차로 얻는 배지들. 이미 가진 배지를 거르는 일은 부르는 쪽(BadgeService)이 한다 —
     * 여기서는 DB 를 보지 않는다.
     *
     * @param sells 매도 체결 수. {@code winRate} 의 분모와 같은 수다
     */
    public static List<BadgeCode> earnedBy(SeasonResultCalculator.Result result, int sells) {
        return List.of(values()).stream().filter(code -> code.earned(result, sells)).toList();
    }

    private boolean earned(SeasonResultCalculator.Result r, int sells) {
        return switch (this) {
            case CRISIS_SURVIVOR ->
                    atLeast(r.maxDrawdown(), CRISIS_DRAWDOWN_PCT) && r.returnRate().signum() > 0;
            case LONG_TERM_INVESTOR -> atLeast(r.avgHoldingDays(), LONG_TERM_DAYS);
            case PROFIT_ACHIEVED -> atLeast(r.returnRate(), PROFIT_RETURN_PCT);
            case WINNING_STREAK ->
                    sells >= STREAK_MIN_SELLS && atLeast(r.winRate(), STREAK_WIN_RATE_PCT);
        };
    }

    /** null 은 못 넘은 것으로 본다. */
    private static boolean atLeast(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }
}
