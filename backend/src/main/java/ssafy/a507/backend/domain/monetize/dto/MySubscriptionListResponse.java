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
            boolean renewEnabled,
            /**
             * 결제 tx 해시. 인덱서가 ACTIVE 로 올리며 붙이므로 PENDING 은 null 이다(ANT-TOKEN-10).
             * 구독은 커밋 원장(앵커)에 오르지 않는다 — 온체인 흔적은 이 tx 하나라, 화면이 "내 결제가
             * 체인에 있다" 를 보여 줄 근거가 이것뿐이다. SSAFY 체인엔 탐색기가 없어 복사용으로만 쓴다.
             */
            String txHash) {

        /** {@code avatarUrl} 은 호출부가 AvatarUrls 로 한 번에 구해 넘긴다 — 줄마다 찾으면 N+1 이다 */
        public static Item of(Subscription s, String avatarUrl) {
            User p = s.getPublisher();
            return new Item(
                    s.getId(),
                    new Publisher(p.getId(), p.getNickname(), avatarUrl),
                    s.getFee() == null ? null : s.getFee().toString(),
                    s.getStatus(),
                    s.getExpiresAt(),
                    s.isAutoRenew(),
                    s.getTxHash());
        }
    }

    /**
     * 구독 대상 채널. 목록에서 채널로 나가는 링크에 쓴다.
     *
     * <p>{@code avatarUrl} 은 예측가의 프로필 사진이다(S15P21A507-273). 안 올렸거나 파일이 사라졌으면
     * null 이고 화면이 기본 아바타를 그린다. 이름을 {@code MeResponse}·{@code ChannelProfileResponse} 와
     * 같게 둔다 — 같은 값에 화면마다 다른 이름이 붙으면 그게 더 헷갈린다.
     */
    public record Publisher(Long userId, String nickname, String avatarUrl) {}
}
