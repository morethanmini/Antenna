package ssafy.a507.backend.domain.event.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.event.entity.PredictionEventResult;

public interface PredictionEventResultRepository extends JpaRepository<PredictionEventResult, PredictionEventResult.Key> {

    /** 확정 리더보드 — 자격자만 순위 순. 미달자는 {@link #findByEventIdAndUserId} 로 따로 본다. */
    @Query("select r from PredictionEventResult r where r.key.eventId = :eventId and r.qualified = true order by r.rank asc")
    List<PredictionEventResult> findRanked(@Param("eventId") Long eventId);

    @Query("select r from PredictionEventResult r where r.key.eventId = :eventId and r.key.userId = :userId")
    Optional<PredictionEventResult> findByEventIdAndUserId(@Param("eventId") Long eventId, @Param("userId") Long userId);

    /**
     * 다시 굳히기 전에 그 이벤트 행을 전부 지운다 — freeze 가 멱등인 이유.
     *
     * <p>{@code clearAutomatically}: 벌크 삭제는 영속성 컨텍스트를 우회한다. 같은 트랜잭션에서 앞서 읽어 둔 결과 행이 컨텍스트에
     * 남아 있으면 이어지는 {@code saveAll} 이 그걸 "이미 있는 행" 으로 보고 UPDATE 를 보내는데 DB 에는 행이 없다 — 조용히 0건.
     * 컨텍스트를 비워야 새 INSERT 가 나간다(테스트 "두 번 굳혀도 행은 한 벌" 이 이걸 잡았다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PredictionEventResult r where r.key.eventId = :eventId")
    int deleteByEventId(@Param("eventId") Long eventId);
}
