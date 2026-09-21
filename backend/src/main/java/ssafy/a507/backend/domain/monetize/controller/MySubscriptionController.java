package ssafy.a507.backend.domain.monetize.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.monetize.dto.MySubscriptionListResponse;
import ssafy.a507.backend.domain.monetize.service.SubscriptionService;

/**
 * 내 구독 — ANT-TOKEN-05, 화면 E-03.
 *
 * <p>경로가 {@code /subscriptions} 라 결제 접수({@link SubscriptionController}, {@code /channels}
 * 아래)와 클래스를 나눈다.
 *
 * <p><b>해지가 DELETE 인데 구독을 지우지 않는다.</b> 지우는 것은 "다음 주기 갱신" 이다 — 그래서
 * 경로가 {@code /subscriptions/{id}/renewal} 이고 구독 자체가 아니다. 만료일까지는 ACTIVE 그대로다.
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class MySubscriptionController {

    private final SubscriptionService subscriptionService;
    private final CurrentUserProvider currentUserProvider;

    /** 만료분까지 최신순 전부. 페이징 없다. */
    @GetMapping("/me")
    public MySubscriptionListResponse mine() {
        return subscriptionService.mine(currentUserProvider.currentUserId());
    }

    /** 자동 갱신 해지. 이미 꺼져 있어도 204 다 — 같은 요청을 두 번 보내도 결과가 같다. */
    @DeleteMapping("/{id}/renewal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRenewal(@PathVariable Long id) {
        subscriptionService.cancelRenewal(currentUserProvider.currentUserId(), id);
    }

    /** 자동 갱신 다시 켜기(해지 번복). 이미 켜져 있어도 204 다. 끝난 구독은 409. */
    @PutMapping("/{id}/renewal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resumeRenewal(@PathVariable Long id) {
        subscriptionService.resumeRenewal(currentUserProvider.currentUserId(), id);
    }
}
