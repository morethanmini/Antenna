package ssafy.a507.backend.domain.monetize.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.idempotency.IdempotencyStore;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.monetize.dto.SubscribeRequest;
import ssafy.a507.backend.domain.monetize.dto.SubscribeResponse;
import ssafy.a507.backend.domain.monetize.service.SubscriptionService;

/**
 * 유료 구독 결제 — ANT-TOKEN-01, 화면 E-02.
 *
 * <p>경로가 {@code /channels} 아래라 {@link ChannelController} 와 클래스를 나눈다 — 한 컨트롤러의
 * 클래스 수준 {@code @RequestMapping} 은 하나뿐이다.
 *
 * <p>202 다. 결제가 체인에서 끝나야 구독이 열리므로 여기서는 접수만 하고, 화면은
 * {@code GET /operations/{operationId}} 를 폴링한다.
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class SubscriptionController {

    private static final String CREATE_ENDPOINT = "POST /channels/{userId}/subscriptions";

    private final SubscriptionService subscriptionService;
    private final CurrentUserProvider currentUserProvider;
    private final IdempotencyStore idempotencyStore;

    /**
     * 멱등키가 필수인 이유 — 202 를 받고 화면이 끊겼을 때 사용자가 다시 누르면 같은 채널에 두 번
     * 결제된다. 중복 구독 검사가 한 겹 막지만 그건 <b>접수가 커밋된 뒤</b>라, 동시에 들어온 두 요청은
     * 둘 다 통과할 수 있다. 명세가 온체인 동반 요청 전부에 멱등키를 요구하는 이유가 이것이다.
     */
    @PostMapping("/{userId}/subscriptions")
    public ResponseEntity<SubscribeResponse> subscribe(
            @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubscribeRequest request) {

        Long subscriberId = currentUserProvider.currentUserId();
        /* 본문 해시에 서명을 넣지 않는다(2026-09-19). 서명은 nonce 로 만들어 재시도마다 달라진다 —
           SignatureGuard 가 nonce 를 태우므로 화면은 다시 서명할 수밖에 없다. 서명을 해시에 넣으면
           "같은 키·같은 채널" 의 정당한 재시도가 전부 IDEMPOTENCY_KEY_REUSED(409) 가 되고, 재생
           경로는 영영 못 탄다(QA 09-18: "이미 처리된 요청입니다" 뒤 버튼이 그대로 남음).
           같은 키로 다른 채널을 찍는 것만 막으면 된다 — 이 요청에서 사용자가 고른 값은 그것뿐이다. */
        String operationId =
                idempotencyStore.execute(
                        subscriberId,
                        CREATE_ENDPOINT,
                        idempotencyKey,
                        IdempotencyStore.hash(String.valueOf(userId)),
                        () -> subscriptionService.subscribe(subscriberId, userId, request).operationId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(subscriptionService.accepted(subscriberId, operationId));
    }
}
