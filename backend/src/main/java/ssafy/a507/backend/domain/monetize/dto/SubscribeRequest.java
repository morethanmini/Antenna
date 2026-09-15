package ssafy.a507.backend.domain.monetize.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * POST /api/v1/channels/{userId}/subscriptions 요청 본문 (ANT-TOKEN-01).
 *
 * <p>서명 하나뿐이다. <b>구독료를 요청으로 받지 않는다</b> — 채널이 정해 둔 값이라 서버가 읽는다.
 * 요청으로 받으면 싸게 적어 보낸 값으로 결제된다.
 *
 * <p>대신 <b>서명 페이로드에는 fee 가 들어간다</b>({@code SubscriptionService.Signed}). 사용자가 화면에서
 * 본 가격과 서버가 결제하는 가격이 같다는 것을 지갑 서명으로 묶는 것이다. 서명 뒤 접수 전에 채널이
 * 가격을 올렸다면 서명이 안 맞아 거절된다 — 모르는 값으로 결제되는 것보다 낫다.
 */
public record SubscribeRequest(
        @NotBlank(message = "서명이 필요합니다.")
                @Pattern(regexp = "^0x[0-9a-fA-F]{130}$", message = "서명 형식이 올바르지 않습니다.")
                String signature) {}
