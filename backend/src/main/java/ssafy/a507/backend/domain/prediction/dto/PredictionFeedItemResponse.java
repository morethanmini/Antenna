package ssafy.a507.backend.domain.prediction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import ssafy.a507.backend.domain.community.dto.AuthorResponse;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * GET /api/v1/predictions/feed 목록 한 줄 (ANT-PRED-05, 화면 F-01 예측 탭).
 *
 * <p>{@link ChannelPredictionItemResponse} 에 <b>작성자와 등록 시각</b>을 더한 것이다. 채널 목록은 "이 사람의 예측"
 * 이라 작성자가 머리글에 한 번 나오면 되지만, 피드는 여러 채널이 섞이므로 줄마다 누구 것인지 적어야 한다.
 * 필드 이름은 {@link MyPredictionItemResponse}·{@link ChannelPredictionItemResponse} 와 맞췄다 — 같은 예측을 세 화면이
 * 다른 이름으로 받지 않게 한다.
 *
 * <p>게이팅은 채널 목록과 같은 규칙이다({@code PredictionViewService}): 종목·기간은 전체 공개, 판정 전이면
 * 방향·목표가만 작성자·구독자에게. 잠긴 건도 목록에서 빼지 않는다 — 빼면 피드가 실제보다 한가해 보이고
 * 구독 유인이 사라진다.
 *
 * <p>근거 본문(note)은 여기 없다. 목록이 미리보기를 흘리지 않는다는 규칙이 리포트 피드와 같고, 근거는
 * 예측 상세(C-03)에서 구독자에게만 보인다.
 */
public record PredictionFeedItemResponse(
        long id,
        AuthorResponse author,
        String stockCode,
        String stockName,
        Prediction.Direction direction,
        BigDecimal targetPrice,
        short horizon,
        Prediction.Status status,
        Integer dday,
        BigDecimal errorRate,
        LocalDate settleDate,
        Instant createdAt,
        boolean locked) {

    public static PredictionFeedItemResponse of(Prediction p, LocalDate today, boolean locked) {
        Stock stock = p.getStock();
        return new PredictionFeedItemResponse(
                p.getId(),
                AuthorResponse.from(p.getUser()),
                stock == null ? null : stock.getCode(),
                stock == null ? null : stock.getName(),
                locked ? null : p.getDirection(),
                locked ? null : p.getTargetPrice(),
                p.getHorizon(),
                p.getStatus(),
                MyPredictionItemResponse.dday(p, today),
                p.getErrorRate(),
                p.getSettleDate(),
                p.getCreatedAt(),
                locked);
    }
}
