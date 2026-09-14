package ssafy.a507.backend.domain.season.dto;

import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.season.entity.UserBadge;

/**
 * GET /api/v1/users/me/badges 200 응답 (ANT-SEASON-09).
 *
 * <p>획득한 것만 내린다 — 잠긴 배지 목록은 주지 않는다(설계서 §9.2 G-09). 화면이 전시 고정을 앞으로
 * 다시 정렬하므로 순서는 계약이 아니다.
 */
public record BadgeListResponse(List<Item> items) {

    /**
     * @param seasonId 딴 시즌 · 실전 트랙 배지면 null
     * @param pinned 프로필 전시 여부 · 바꾸는 API 가 아직 없어 지금은 전부 false 다
     */
    public record Item(String badgeCode, Long seasonId, boolean pinned, Instant createdAt) {

        static Item from(UserBadge badge) {
            // getSeason().getId() 는 프록시를 깨우지 않는다 — 식별자 게터라 FK 값을 그대로 준다
            return new Item(
                    badge.getBadgeCode(),
                    badge.getSeason() == null ? null : badge.getSeason().getId(),
                    badge.isPinned(),
                    badge.getCreatedAt());
        }
    }

    public static BadgeListResponse of(List<UserBadge> badges) {
        return new BadgeListResponse(badges.stream().map(Item::from).toList());
    }
}
