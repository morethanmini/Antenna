package ssafy.a507.backend.domain.event.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * GET /api/v1/events/{eventId}/me 200 — 내 배팅 (ANT-EVENT-06, 작업명세 v1.2 §4).
 *
 * <p>홈({@code GET /events/{id}})과 무엇이 다른가 — 홈은 <b>종목 카드</b>가 주인공이라 내 배팅이
 * 카드에 딸린 값으로 들어가고, 여기는 <b>내가 건 것</b>만 모아 tx 해시·작업 id·오차까지 보여준다.
 * 화면 J-01 의 "내 참가 내역" 탭이다.
 *
 * <p>{@code paid}·{@code net}·{@code rank} 는 정산 전 null 이다 — 0 으로 내리면 "본전" 과 "꼴찌" 로
 * 읽힌다.
 *
 * <p><b>null 은 키를 남긴다.</b> 이 도메인의 null 은 "가려진 값" 이 아니라 "아직 정해지지 않은 값"
 * 이다(정산 전 배당·순위, 미입력 기준가, 안 건 종목). {@code @JsonInclude(NON_NULL)} 로 키를 빼면
 * TypeScript 에서 {@code undefined} 가 되어 {@code === null} 분기가 통째로 빠진다 — 작업명세 §4 의
 * 응답 예시도 {@code "paid": null} 처럼 키를 남겨 적었다.
 */
public record EventMeResponse(EventPhase phase, Summary summary, List<Entry> entries) {

    /**
     * @param avgError HIT 인 예측만의 평균 오차(09-16 담당자 확정). MISS 를 섞으면 "많이 틀린 사람이
     *     적게 참가해서" 평균이 좋아진다
     */
    public record Summary(
            int stockCount,
            BigInteger staked,
            boolean qualified,
            BigInteger paid,
            BigInteger net,
            BigDecimal avgError,
            Integer rank) {}

    /**
     * 한 종목.
     *
     * <p>{@code txHash}·{@code operationId} 를 싣는 이유 — PENDING 인 배팅이 "전송 중" 인지 "실패" 인지
     * 화면이 폴링으로 알아야 하고, 확정된 배팅은 사용자가 체인에서 직접 확인할 수 있어야 한다.
     * 이 대회의 값은 "온체인이라 검증된다" 는 것이라 그 링크를 숨길 이유가 없다.
     */
    public record Entry(
            Long predictionId,
            short idx,
            String stockCode,
            String name,
            Prediction.Direction direction,
            BigDecimal targetPrice,
            BigInteger stake,
            PredictionEventBet.Status betStatus,
            String txHash,
            String operationId,
            Result result) {

        public static Entry of(PredictionEventBet bet) {
            PredictionEventStock slot = bet.getEventStock();
            Prediction p = bet.getPrediction();
            return new Entry(
                    bet.getPredictionId(),
                    slot.getIdx(),
                    slot.getStock().getCode(),
                    slot.getStock().getName(),
                    p.getDirection(),
                    p.getTargetPrice(),
                    bet.getStake(),
                    bet.getBetStatus(),
                    bet.getTxHash(),
                    bet.getOperationId(),
                    Result.of(bet));
        }
    }

    /** 종목이 판정된 뒤에만 찬다. {@code errorRate} 는 예측 쪽 값이라 HIT/MISS 가 나야 있다. */
    public record Result(
            BigDecimal closePrice,
            PredictionEventStock.Winner winner,
            BigInteger payout,
            BigDecimal errorRate) {

        static Result of(PredictionEventBet bet) {
            PredictionEventStock slot = bet.getEventStock();
            if (slot.getWinner() == null) {
                return null;
            }
            return new Result(
                    slot.getClosePrice(),
                    slot.getWinner(),
                    bet.getPayout(),
                    bet.getPrediction().getErrorRate());
        }
    }
}
