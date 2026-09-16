package ssafy.a507.backend.domain.event.dto;

import java.util.List;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse.Me;

/**
 * POST /api/v1/events/{eventId}/entries 202 응답 (ANT-EVENT-05, 작업명세 v1.2 §4).
 *
 * <p>202 인 이유는 배팅이 체인에 올라가야 끝나서다. 여기서는 예측·배팅·작업 행까지 만들고,
 * 화면은 종목마다 {@code operationId} 로 {@code GET /operations/{id}} 를 폴링한다.
 *
 * <p>{@code me} 를 함께 내리는 이유 — 등록 직후 화면이 "몇 종목 걸었고 몇 개 남았나" 를 다시
 * 물어보지 않아도 되게 한다. 홈을 한 번 더 부르면 방금 만든 PENDING 배팅이 풀 집계에서 빠져 있어
 * 사용자가 보기에 아무 일도 안 일어난 것처럼 보인다.
 */
public record EventEntryCreateResponse(Long eventId, List<Entry> entries, Me me) {

    public record Entry(String stockCode, Long predictionId, String commitHash, String operationId) {}
}
