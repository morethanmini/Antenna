package ssafy.a507.backend.domain.event.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import ssafy.a507.backend.domain.event.entity.PredictionEventResult;

/**
 * GET /api/v1/events/{eventId}/leaderboard 200 — 회차 순위 (ANT-EVENT-06, 작업명세 v1.2 §4 · §8).
 *
 * <p><b>정산 전에는 {@code items} 가 비어 있다</b>(09-16 결정). 봉인된 뒤에도 "예상 순위" 를 내리지
 * 않는 이유는 승패를 모르기 때문이다 — 지금 배당률로 계산한 값은 "이기면 얼마" 일 뿐인데 화면에
 * 순위로 뜨면 사용자는 그걸 현재 등수로 읽는다. 서버가 현재가로 임시 판정하는 것은 더 나쁘다.
 * 대신 {@code notice} 로 언제 공개되는지 말한다.
 *
 * <p>정산 뒤에는 {@code prediction_event_results}(ANT-EVENT-03 이 굳힌 표)만 읽는다 — 페이지마다
 * 집계 쿼리를 돌리지 않는다.
 *
 * <p><b>null 은 키를 남긴다.</b> 이 도메인의 null 은 "가려진 값" 이 아니라 "아직 정해지지 않은 값"
 * 이다(정산 전 배당·순위, 미입력 기준가, 안 건 종목). {@code @JsonInclude(NON_NULL)} 로 키를 빼면
 * TypeScript 에서 {@code undefined} 가 되어 {@code === null} 분기가 통째로 빠진다 — 작업명세 §4 의
 * 응답 예시도 {@code "paid": null} 처럼 키를 남겨 적었다.
 */
public record EventLeaderboardResponse(
        EventPhase phase,
        /** 순위가 아직 없을 때 화면이 그대로 띄우는 문구. 순위가 있으면 null */
        String notice,
        List<Item> items,
        /** 내가 참가하지 않았거나 자격 미달이면 null */
        Item me,
        String nextCursor) {

    /**
     * @param profitRate {@code net ÷ staked × 100}. 표시용이라 서버가 계산한다 — 프론트가 정수 나눗셈
     *     으로 만들면 −0 이나 반올림이 갈린다
     * @param doneCount 정산된 종목 수. 화면의 "적중 n/m" 에서 m 이다
     */
    public record Item(
            Integer rank,
            Long userId,
            String nickname,
            int stockCount,
            BigInteger staked,
            BigInteger paid,
            BigInteger net,
            BigDecimal profitRate,
            int hitCount,
            int doneCount,
            BigDecimal avgError,
            boolean qualified) {

        public static Item of(PredictionEventResult r, String nickname, BigDecimal profitRate) {
            return new Item(
                    r.getRank(),
                    r.getKey().getUserId(),
                    nickname,
                    r.getStockCount(),
                    r.getStaked(),
                    r.getPaid(),
                    r.getNet(),
                    profitRate,
                    r.getHitCount(),
                    r.getStockCount(),
                    r.getAvgError(),
                    r.isQualified());
        }
    }
}
