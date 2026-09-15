package ssafy.a507.backend.domain.monetize.dto;

import ssafy.a507.backend.domain.chain.entity.Operation;

/**
 * POST /api/v1/channels/{userId}/subscriptions 202 응답 (ANT-TOKEN-01).
 *
 * <p>202 인 이유는 결제가 체인에서 끝나야 구독이 열려서다. 여기서는 접수만 하고, 확정(PENDING →
 * ACTIVE)은 토큰 인덱서가 이벤트를 보고 한다. 화면은 {@code operationId} 로
 * {@code GET /operations/{id}} 를 폴링한다.
 *
 * <p>{@code subscriptionId} 를 함께 내리는 이유 — 폴링이 성공으로 끝난 뒤 화면이 그 구독을 바로
 * 가리킬 수 있어야 한다. 작업 id 만 주면 구독 목록을 다시 훑어 찾아야 한다.
 */
public record SubscribeResponse(String operationId, Long subscriptionId, Operation.Status status) {}
