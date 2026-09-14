package ssafy.a507.backend.domain.season.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.season.entity.UserBadge;

public interface UserBadgeRepository extends JpaRepository<UserBadge, Long> {

    /**
     * 내 배지 전부 (GET /users/me/badges). 정렬은 화면이 다시 잡으므로(전시 고정을 앞으로)
     * 여기서는 딴 순서만 보장한다.
     *
     * <p>시즌을 함께 끌어오지 않는다 — 응답에 나가는 것은 {@code seasonId} 하나이고, 그 값은 FK
     * 라 프록시를 깨우지 않고 읽힌다.
     */
    List<UserBadge> findByUser_IdOrderByCreatedAtDesc(Long userId);

    /**
     * 이미 가진 배지 코드. 부여 전에 걸러 내는 데 쓴다.
     *
     * <p>{@code uq_user_badges_user_code} 가 있으니 그냥 넣고 예외를 삼켜도 되지만, 그러면 한
     * 트랜잭션에서 제약을 건드리는 순간 그 트랜잭션 전체가 롤백 대상이 된다 — 배지를 주려다 시즌
     * 종료가 통째로 날아간다.
     */
    @Query("select b.badgeCode from UserBadge b where b.user.id = :userId")
    List<String> findBadgeCodesByUserId(@Param("userId") Long userId);
}
