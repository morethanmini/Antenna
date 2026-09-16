package ssafy.a507.backend.domain.account.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.account.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 알림함 목록. 팝업이라 페이징 없이 최신 50건만 — 그보다 오래된 것은 지운 것과 같다. */
    List<Notification> findTop50ByUser_IdOrderByIdDesc(Long userId);

    /** 미확인 뱃지 숫자. */
    long countByUser_IdAndReadAtIsNull(Long userId);

    /** 내 알림 한 건. 남의 것은 비어 돌아온다 — 호출부가 404 로 감춘다. */
    Optional<Notification> findByIdAndUser_Id(Long id, Long userId);

    /** 같은 사람이 같은 대상으로 이미 보낸 알림이 있는가 — 좋아요를 (리포트, 사람)당 한 번만 보내는 근거. */
    boolean existsByUser_IdAndActor_IdAndTypeAndLinkPath(
            Long userId, Long actorId, String type, String linkPath);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.user.id = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    /** 내 알림 전부 삭제("모두 삭제"). 한 문장으로 — 행마다 지우면 알림 수만큼 DELETE 다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Notification n where n.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
