package ssafy.a507.backend.domain.monetize.dto;

import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.monetize.entity.Subscription;

/**
 * GET /api/v1/subscriptions/me 200 응답 (ANT-TOKEN-05, 화면 E-03).
 *
 * <p>커서 페이징을 두지 않는다. 한 사람이 거는 구독 수는 화면이 한 번에 그릴 만큼이고, 프론트
 * {@code api/subscriptions.ts} 도 {@code { items }} 한 겹만 본다.
 */
public record MySubscriptionListResponse(List<Item> items) {

    /**
     * 내 구독 한 줄.
     *
     * <p>{@code fee} 는 <b>결제 시점에 박제된 값</b>이라 채널의 현재 구독료와 다를 수 있다. 채널의
     * 현재가를 함께 내리지 않는 이유는 화면이 두 숫자를 나란히 두면 어느 쪽이 내가 내는 값인지
     * 흐려지기 때문이다(E-03 설계 제약).
     */
    public record Item(
            Long id,
            Publisher publisher,
            String fee,
            Subscription.Status status,
            /** 개시 +30일. PENDING 은 아직 개시 전이라 null 이다 */
            Instant expiresAt,
            /** 만료일에 배치 B4 가 재결제할지. 해지하면 false */
            boolean renewEnabled) {

        public static Item of(Subscription s) {
            User p = s.getPublisher();
            return new Item(
                    s.getId(),
                    new Publisher(p.getId(), p.getNickname()),
                    s.getFee() == null ? null : s.getFee().toString(),
                    s.getStatus(),
                    s.getExpiresAt(),
                    s.isAutoRenew());
        }
    }

    /** 구독 대상 채널. 목록에서 채널로 나가는 링크에 쓴다 */
    public record Publisher(Long userId, String nickname) {}
}
