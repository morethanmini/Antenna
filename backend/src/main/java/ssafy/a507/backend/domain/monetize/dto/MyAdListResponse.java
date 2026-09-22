package ssafy.a507.backend.domain.monetize.dto;

import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;

/**
 * 내가 낸 광고 신청 목록 — 광고 등록 화면(H-04)이 머리에 건다.
 *
 * <p>신청하면 게재료가 가용잔액에서 묶이는데, 그 뒤로 신청을 다시 볼 자리가 없어 <b>묶인 ANT 를
 * 스스로 풀 길이 없었다</b>(2026-09-22 QA · ANT-FE-AD). 사용자용 광고 목록 화면을 새로 내지 않고
 * 되돌아오는 자리인 등록 화면에 얹는다.
 *
 * <p>{@code burnPending} 은 관리자가 승인을 눌러 소각 tx 가 나가는 중이라는 뜻이다 — 배너 상태는
 * 아직 PENDING 이라 심사 대기와 구분되지 않는다(관리자 목록과 같은 이유). 이때는 취소가 막힌다.
 */
public record MyAdListResponse(List<Item> items) {

    public record Item(
            Long id,
            String imageUrl,
            String linkUrl,
            Instant startsAt,
            Instant endsAt,
            String status,
            String price,
            Instant createdAt,
            boolean burnPending,
            boolean cancelable) {

        public static Item of(AdBanner b, boolean burnPending) {
            return new Item(
                    b.getId(),
                    b.getImageUrl(),
                    b.getLinkUrl(),
                    b.getStartsAt(),
                    b.getEndsAt(),
                    b.getStatus().name(),
                    b.getPriceWei().toString(),
                    b.getCreatedAt(),
                    burnPending,
                    b.isPending() && !burnPending);
        }
    }
}
