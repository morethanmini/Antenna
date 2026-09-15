package ssafy.a507.backend.domain.prediction.dto;

import java.util.List;

/**
 * GET /api/v1/predictions/feed 200 응답. 커서는 마지막으로 받은 예측 id 하나다 — 리포트 피드(RECENT)와 같은 계약이다.
 *
 * <p>정렬을 {@code createdAt} 이 아니라 <b>id 내림차순</b>으로 둔 이유는 {@code PredictionRepository.findMyPage} 와 같다:
 * 같은 초에 등록한 두 건이 커서 페이징에서 겹치거나 빠지지 않아야 하고, id 는 등록 순서와 같다.
 */
public record PredictionFeedResponse(List<PredictionFeedItemResponse> items, Long nextCursor, boolean hasNext) {}
