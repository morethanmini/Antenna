package ssafy.a507.backend.domain.event.dto;

/**
 * POST /api/v1/admin/events/{eventId}/settle 202 (ANT-EVENT-06, 작업명세 v1.2 §4).
 *
 * <p>202 인 이유는 러너가 온체인 tx 를 10건 넘게 순서대로 보내며 receipt 를 기다려 <b>몇 분</b>이
 * 걸리기 때문이다. 요청 스레드에서 끝까지 붙들면 게이트웨이가 먼저 끊는다.
 *
 * <p>{@code runId} 는 {@code event_settlement_runs} 행의 id 다 — 러너 메모리가 아니라 표라서 서버가
 * 죽어도 남는다. 진행은 {@code GET /admin/events/{eventId}/status} 로 본다.
 */
public record EventSettleResponse(Long eventId, Long runId) {}
