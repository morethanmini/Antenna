package ssafy.a507.backend.domain.event.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;

public interface PredictionEventStockRepository extends JpaRepository<PredictionEventStock, Long> {

    /** 인덱서 ③ 이 {@code StockResolved(eventId, stockIdx)} 로 종목 칸을 찾는다. UNIQUE(event_id, idx). */
    @Query("select s from PredictionEventStock s where s.event.id = :eventId and s.idx = :idx")
    Optional<PredictionEventStock> findByEventIdAndIdx(@Param("eventId") Long eventId, @Param("idx") short idx);
}
