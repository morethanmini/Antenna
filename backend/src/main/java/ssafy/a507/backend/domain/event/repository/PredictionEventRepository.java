package ssafy.a507.backend.domain.event.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;

public interface PredictionEventRepository extends JpaRepository<PredictionEvent, Long> {

    /** 목록. 최근 회차가 위다 — 이번엔 하나뿐이지만 다음 회차가 생겨도 배너가 옛것을 집지 않는다. */
    List<PredictionEvent> findAllByOrderByIdDesc();

    /**
     * OPEN 전이 크론의 대상 (ANT-EVENT-04).
     *
     * <p>세 조건을 <b>쿼리에서 다 건다</b> — {@code opensAt} 도달 · 기준가 완비 · {@code open_tx_hash} 확인.
     * 자바에서 거르면 회차마다 이벤트를 전부 읽어야 하고, 무엇보다 "왜 안 열렸나" 를 로그로 설명할 때
     * 조건이 코드 여러 곳에 흩어진다.
     *
     * <p>기준가 완비는 <b>없는 것이 없다</b>로 본다 — {@code not exists (기준가가 null 인 종목)}. 종목이
     * 0개인 이벤트는 이 조건을 통과해 버리므로 {@code stockCount} 도 함께 센다.
     */
    @Query("""
            select e from PredictionEvent e
             where e.status = ssafy.a507.backend.domain.event.entity.PredictionEvent$Status.SCHEDULED
               and e.opensAt <= :now
               and e.openTxHash is not null
               and exists (select 1 from PredictionEventStock s where s.event = e)
               and not exists (
                     select 1 from PredictionEventStock s
                      where s.event = e and s.basePrice is null)
            """)
    List<PredictionEvent> findOpenable(@Param("now") Instant now);
}
