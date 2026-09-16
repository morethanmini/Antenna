package ssafy.a507.backend.domain.event.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * GET /api/v1/events/{eventId} 200 응답 — 이벤트 홈 (ANT-EVENT-04, 작업명세 v1.2 §4).
 *
 * <p><b>남의 방향·목표가는 실리지 않는다.</b> 집계(풀·건수·배당률)만 공개하고, {@code mine} 에만
 * 내 값이 들어간다 — §1 "비공개" 규칙이다.
 *
 * <p><b>null 은 키를 남긴다.</b> 이 도메인의 null 은 "가려진 값" 이 아니라 "아직 정해지지 않은 값"
 * 이다(정산 전 배당·순위, 미입력 기준가, 안 건 종목). {@code @JsonInclude(NON_NULL)} 로 키를 빼면
 * TypeScript 에서 {@code undefined} 가 되어 {@code === null} 분기가 통째로 빠진다 — 작업명세 §4 의
 * 응답 예시도 {@code "paid": null} 처럼 키를 남겨 적었다.
 */
public record EventHomeResponse(
        Long eventId,
        String title,
        PredictionEvent.Status status,
        EventPhase phase,
        Instant opensAt,
        Instant sealsAt,
        LocalDate baseDate,
        LocalDate settleDate,
        Rules rules,
        List<StockCard> stocks,
        Me me) {

    /** 회차마다 달라질 수 있어 응답에 싣는다 — 화면이 500·2000·5 를 하드코딩하면 다음 회차에 어긋난다 */
    public record Rules(BigInteger minStake, BigInteger maxStake, short minStocks, int stockCount) {}

    public record StockCard(
            short idx,
            String stockCode,
            String name,
            /** 목요일 종가. 이벤트를 열 때 복사하고, 없으면 관리자가 넣을 때까지 null */
            BigDecimal basePrice,
            /** 최신 종가 — 표시용이다. 판정에는 쓰지 않는다 */
            BigDecimal lastPrice,
            Pool pool,
            /** 내 배팅. 없으면 null */
            Mine mine,
            /** 정산 뒤에만 찬다 */
            Result result) {}

    /**
     * 종목별 배팅 집계.
     *
     * <p><b>PENDING 은 빼고 센다</b> — 전송 중이라 체인에 아직 안 올라간 돈이다. 그걸 배당률에 넣으면
     * 실패한 전송이 남의 배당을 흔든다.
     */
    public record Pool(
            BigInteger up,
            BigInteger down,
            long upCount,
            long downCount,
            /** (up+down)/up. 소수 둘째 자리. 해당 풀이 0 이면 null — 0 으로 나눌 수 없고, 0 배당은 거짓이다 */
            BigDecimal oddsUp,
            BigDecimal oddsDown) {}

    public record Mine(
            Long predictionId,
            BigInteger stake,
            PredictionEventBet.Status betStatus,
            Prediction.Direction direction,
            BigDecimal targetPrice) {

        public static Mine of(PredictionEventBet bet) {
            Prediction p = bet.getPrediction();
            return new Mine(
                    bet.getPredictionId(),
                    bet.getStake(),
                    bet.getBetStatus(),
                    p.getDirection(),
                    p.getTargetPrice());
        }
    }

    public record Result(
            BigDecimal closePrice, PredictionEventStock.Winner winner, BigInteger payout) {}

    /**
     * 내 참가 요약.
     *
     * <p>{@code paid}·{@code net}·{@code rank} 는 정산 전에는 null 이다 — 0 으로 내리면 "본전"으로
     * 읽힌다. {@code qualified} 는 {@code stockCount >= minStocks} 로, 순위에 드는지를 미리 보여준다.
     */
    public record Me(
            int stockCount,
            BigInteger staked,
            boolean qualified,
            int remainingStocks,
            BigInteger paid,
            BigInteger net,
            Integer rank) {}
}
