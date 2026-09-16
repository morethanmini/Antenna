package ssafy.a507.backend.domain.event.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;

public interface PredictionEventStockRepository extends JpaRepository<PredictionEventStock, Long> {

    /** 인덱서 ③ 이 {@code StockResolved(eventId, stockIdx)} 로 종목 칸을 찾는다. UNIQUE(event_id, idx). */
    @Query("select s from PredictionEventStock s where s.event.id = :eventId and s.idx = :idx")
    Optional<PredictionEventStock> findByEventIdAndIdx(@Param("eventId") Long eventId, @Param("idx") short idx);

    /** 한 이벤트의 종목 카드 10장. {@code idx} 순이 곧 화면 순서다(컨트랙트 stockIdx 와 같은 번호). */
    @Query("""
            select s from PredictionEventStock s
              join fetch s.stock
             where s.event.id = :eventId
             order by s.idx
            """)
    List<PredictionEventStock> findCardsByEventId(@Param("eventId") Long eventId);

    /** 기준가가 아직 빈 종목 수. 0 이어야 이벤트가 열린다 — 관리자 화면이 "몇 개 남았나" 를 묻는다. */
    @Query("select count(s) from PredictionEventStock s where s.event.id = :eventId and s.basePrice is null")
    long countMissingBasePrice(@Param("eventId") Long eventId);
}
