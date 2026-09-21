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
            /**
             * 구독이 개시된 시각. <b>PENDING 은 null</b> 이다 — 결제가 체인에서 확정될 때
             * 인덱서가 채운다(Subscription.activate). 화면은 "언제부터 구독했는가" 로 쓴다.
             */
            Instant startedAt,
            /** 개시 +30일. PENDING 은 아직 개시 전이라 null 이다 */
            Instant expiresAt,
            /** 만료일에 배치 B4 가 재결제할지. 해지하면 false */
            boolean renewEnabled,
            /**
             * 결제 tx 해시. 인덱서가 ACTIVE 로 올리며 붙이므로 PENDING 은 null 이다(ANT-TOKEN-10). 무료 채널(구독료 0)은 tx 없이 ACTIVE 라 늘 null 이다.
             * 구독은 커밋 원장(앵커)에 오르지 않는다 — 온체인 흔적은 이 tx 하나라, 화면이 "내 결제가
             * 체인에 있다" 를 보여 줄 근거가 이것뿐이다. SSAFY 체인엔 탐색기가 없어 복사용으로만 쓴다.
             */
            String txHash) {

        /** {@code publisher} 는 호출부가 목록 전체를 한 번에 모아 만든다 — 줄마다 찾으면 N+1 이다 */
        public static Item of(Subscription s, Publisher publisher) {
            return new Item(
                    s.getId(),
                    publisher,
                    s.getFee() == null ? null : s.getFee().toString(),
                    s.getStatus(),
                    s.getStartedAt(),
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
    public record Publisher(
            Long userId,
            String nickname,
            String avatarUrl,
            /** 관심 섹터. 담은 순서대로다. 안 고른 사람은 빈 배열 */
            List<String> interests,
            /**
             * 가장 최근 예측 시각(REAL 만). 한 건도 없으면 null 이다.
             *
             * <p>구독은 <b>앞으로 올 예측</b>을 사는 것이라, 마지막 활동이 언제인지가 갱신을
             * 정하는 근거가 된다. 그래서 목록에 둔다.
             */
            Instant latestPredictionAt) {}
}
