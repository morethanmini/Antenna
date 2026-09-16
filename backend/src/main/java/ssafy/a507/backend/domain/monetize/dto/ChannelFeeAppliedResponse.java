package ssafy.a507.backend.domain.monetize.dto;

import java.time.Instant;

/**
 * PUT /api/v1/me/channel/fee 200 응답 — 명세의 "적용 예정 정보" (ANT-TOKEN-05, 화면 E-04).
 *
 * <p>{@code effectiveFrom} 을 함께 내리는 이유는 화면이 "언제부터" 를 말해야 하기 때문이다. 이 값을
 * 빼면 사용자는 지금 당장 기존 구독자에게도 새 가격이 매겨진 줄로 읽는다 — 실제로는 기존 구독이
 * 결제 시점 가격을 박제하고 있어서 만료까지 옛 가격이다.
 */
public record ChannelFeeAppliedResponse(String fee, Instant effectiveFrom) {}
