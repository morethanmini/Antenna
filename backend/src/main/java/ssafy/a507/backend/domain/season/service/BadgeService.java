package ssafy.a507.backend.domain.season.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.season.badge.BadgeCode;
import ssafy.a507.backend.domain.season.dto.BadgeListResponse;
import ssafy.a507.backend.domain.season.entity.Season;
import ssafy.a507.backend.domain.season.entity.UserBadge;
import ssafy.a507.backend.domain.season.repository.UserBadgeRepository;

/**
 * 배지 부여·조회 (ANT-SEASON-09).
 *
 * <p>부여는 시즌 종료(POST /seasons/{id}/finish)가 결과를 저장한 그 트랜잭션에서 일어난다. 배치로
 * 미루지 않는 이유는 끝낸 자리에서 바로 보여야 해서다 — 대회 모드가 없어져 "시즌 CLOSED" 라는
 * 시점 자체가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BadgeService {

    private final UserBadgeRepository badges;

    /**
     * 이 회차로 새로 딴 배지를 저장한다. 이미 가진 코드는 건너뛴다 — 계정당 평생 1회다.
     *
     * <p>부르는 쪽이 이미 쓰기 트랜잭션 안이라 {@code REQUIRED} 로 합류한다. 배지 저장이 실패하면
     * 시즌 종료도 함께 롤백되는 것이 맞다 — 결과는 남았는데 배지만 빠지면 다시 줄 계기가 없다
     * (finish 는 끝난 회차를 다시 계산하지 않는다).
     *
     * @param sells 매도 체결 수 — 승률의 분모와 같은 수다
     * @return 이번에 새로 준 배지
     */
    @Transactional
    public List<BadgeCode> award(
            User user, Season season, SeasonResultCalculator.Result result, int sells) {
        Set<String> owned = new HashSet<>(badges.findBadgeCodesByUserId(user.getId()));
        List<BadgeCode> fresh = BadgeCode.earnedBy(result, sells).stream()
                .filter(code -> !owned.contains(code.name()))
                .toList();
        if (fresh.isEmpty()) {
            return List.of();
        }
        badges.saveAll(fresh.stream().map(code -> UserBadge.of(user, code, season)).toList());
        log.info("[SEASON-09] 배지 {}장 부여 user={} season={} {}",
                fresh.size(), user.getId(), season.getId(), fresh);
        return fresh;
    }

    /** 내 배지 전부. 획득한 것만 나간다. */
    public BadgeListResponse mine(Long userId) {
        return BadgeListResponse.of(badges.findByUser_IdOrderByCreatedAtDesc(userId));
    }
}
