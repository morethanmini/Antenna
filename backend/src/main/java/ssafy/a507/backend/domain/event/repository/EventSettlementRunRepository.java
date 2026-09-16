package ssafy.a507.backend.domain.event.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.event.entity.EventSettlementRun;

public interface EventSettlementRunRepository extends JpaRepository<EventSettlementRun, Long> {

    /** 이벤트의 마지막 실행 — REST 상태 API 와 러너의 "지금 도는 중인가" 판단 재료. */
    @Query("select r from EventSettlementRun r where r.event.id = :eventId order by r.id desc limit 1")
    Optional<EventSettlementRun> findLatest(@Param("eventId") Long eventId);

    /** 끝나지 않은 채 남은 실행(서버 크래시). 다음 실행이 INTERRUPTED 로 닫는다. */
    @Query("select r from EventSettlementRun r where r.event.id = :eventId and r.phase not in :terminal")
    List<EventSettlementRun> findUnfinished(
            @Param("eventId") Long eventId, @Param("terminal") Collection<EventSettlementRun.Phase> terminal);

    default List<EventSettlementRun> findUnfinished(Long eventId) {
        return findUnfinished(eventId, List.of(EventSettlementRun.Phase.DONE, EventSettlementRun.Phase.FAILED));
    }
}
