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
        String operationId =
                idempotencyStore.execute(
                        subscriberId,
                        CREATE_ENDPOINT,
                        idempotencyKey,
                        IdempotencyStore.hash(userId + "|" + request.signature()),
                        () -> subscriptionService.subscribe(subscriberId, userId, request).operationId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(subscriptionService.accepted(subscriberId, operationId));
    }
}
