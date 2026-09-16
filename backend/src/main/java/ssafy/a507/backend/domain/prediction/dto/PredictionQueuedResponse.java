package ssafy.a507.backend.domain.prediction.dto;

import ssafy.a507.backend.domain.chain.entity.Operation;

/**
 * {@code POST /predictions} 202 — 슬롯을 넘겨 소각으로 접수했다(ANT-PRED-10). 명세의 {@code { operationId, status }} 에
 * {@code predictionId} 를 더했다 — 예측은 접수 시점에 이미 저장되므로 화면이 폴링 뒤 갈 곳을 안다(작업의 resource 와 같은 값).
 */
public record PredictionQueuedResponse(String operationId, long predictionId, Operation.Status status) {}
