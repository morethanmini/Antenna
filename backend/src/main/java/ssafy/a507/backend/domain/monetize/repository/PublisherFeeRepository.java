package ssafy.a507.backend.domain.monetize.repository;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.monetize.entity.PublisherFee;

public interface PublisherFeeRepository extends JpaRepository<PublisherFee, Long> {

    /**
     * 지금 적용 중인 구독료. 아직 정하지 않았으면 빈 값이다 — 0 이 아니다(화면이 무료 채널로 읽는다).
     *
     * <p>행을 갈아끼우지 않고 쌓는 표라(effective_from 으로 예약 변경을 담는다) "지금"은 발효일이
     * 이미 지난 것 중 가장 늦은 한 줄이다. 미래 발효분을 함께 읽으면 아직 오지 않은 가격을 보여준다.
     */
    @Query("""
            select f from PublisherFee f
             where f.publisher.id = :publisherId
               and f.effectiveFrom <= :now
             order by f.effectiveFrom desc, f.id desc
            """)
    Optional<PublisherFee> findCurrent(
            @Param("publisherId") Long publisherId, @Param("now") Instant now, Limit limit);
}
