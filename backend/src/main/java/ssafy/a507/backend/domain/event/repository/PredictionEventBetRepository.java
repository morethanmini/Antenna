package ssafy.a507.backend.domain.event.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;

public interface PredictionEventBetRepository extends JpaRepository<PredictionEventBet, Long> {

    /**
     * 인덱서 ③ 이 {@code BetPlaced(eventId, user, stockIdx)} 로 배팅 행을 찾는다 — 컨트랙트의 종목당 1건 키({@code hasBet})와 같은 셋.
     * tx 해시로 찾지 않는 이유: 작업이 SEND_LOST 로 닫힌 뒤 실제로 나간 tx 는 작업 tx_hash 가 비어 있다.
     */
    @Query("select b from PredictionEventBet b where b.event.id = :eventId and b.user.id = :userId and b.eventStock.idx = :idx")
    Optional<PredictionEventBet> findByEventIdAndUserIdAndStockIdx(
            @Param("eventId") Long eventId, @Param("userId") Long userId, @Param("idx") short idx);

    /** BetPlaced 뒤의 로그(BetRevealed · BetSettled · BetRefunded)는 온체인 betId 로 찾는다. UNIQUE(event_id, bet_index). */
    @Query("select b from PredictionEventBet b where b.event.id = :eventId and b.betIndex = :betIndex")
    Optional<PredictionEventBet> findByEventIdAndBetIndex(@Param("eventId") Long eventId, @Param("betIndex") long betIndex);

    /** 인덱서 ⑤ 가 작업을 FAILED 로 닫을 때 같은 트랜잭션에서 배팅도 FAILED 로 맞추려고 찾는다. 재전송하면 새 작업 id 로 바뀌므로 최대 한 건. */
    Optional<PredictionEventBet> findByOperationId(String operationId);

    /**
     * 작업은 FAILED 인데 배팅은 아직 PENDING 인 행 — 인덱서 ⑤ 가 배팅을 FAILED 로 맞춘다.
     * 풀 인덱서가 아닌 쪽(토큰 인덱서 ⑤ 가 먼저 봤을 때, 전송 주체가 잔액 부족·revert 로 바로 닫았을 때)이 닫은 작업을 따라간다.
     */
    @Query("select b from PredictionEventBet b where b.betStatus = :pending"
            + " and exists (select 1 from Operation o where o.id = b.operationId and o.status = :failed)")
    List<PredictionEventBet> findPendingWithFailedOperation(
            @Param("pending") PredictionEventBet.Status pending, @Param("failed") Operation.Status failed);

    default List<PredictionEventBet> findPendingWithFailedOperation() {
        return findPendingWithFailedOperation(PredictionEventBet.Status.PENDING, Operation.Status.FAILED);
    }

    /**
     * 종목별 방향별 배팅 집계 — 홈 화면의 풀·배당률 재료 (ANT-EVENT-04).
     *
     * <p>돌려주는 행은 {@code [eventStockId, direction, sumStake, count]} 다. 종목 카드 10장을
     * 한 번에 채우려고 이벤트 단위로 묶어 읽는다 — 카드마다 부르면 페이지 한 장에 쿼리가 열 번이다.
     *
     * <p><b>PENDING 은 뺀다.</b> 전송 중이라 체인에 아직 안 올라간 돈이고, 그 전송이 실패하면
     * 남의 배당률만 흔들어 놓는다. 반대로 FAILED 도 뺀다 — 그 둘을 뺀 나머지가 "체인에 있는 돈" 이다.
     */
    @Query("""
            select b.eventStock.id, p.direction, sum(b.stake), count(b)
              from PredictionEventBet b
              join b.prediction p
             where b.event.id = :eventId
               and b.betStatus in :live
             group by b.eventStock.id, p.direction
            """)
    List<Object[]> sumStakeByStockAndDirection(
            @Param("eventId") Long eventId, @Param("live") Collection<PredictionEventBet.Status> live);

    /** 체인에 올라간 것으로 보는 상태들 — 풀 집계의 기준이다. PENDING(전송 중)·FAILED(못 올라감)는 뺀다. */
    List<PredictionEventBet.Status> LIVE_FOR_POOL =
            List.of(
                    PredictionEventBet.Status.ACTIVE,
                    PredictionEventBet.Status.REVEALED,
                    PredictionEventBet.Status.SETTLED,
                    PredictionEventBet.Status.REFUNDED);

    default List<Object[]> sumStakeByStockAndDirection(Long eventId) {
        return sumStakeByStockAndDirection(eventId, LIVE_FOR_POOL);
    }

    /**
     * 내 배팅 전부 — 홈 화면의 {@code mine} 과 {@code me} 요약 재료.
     *
     * <p>{@code prediction} 을 페치 조인한다. 카드가 방향·목표가를 보여주는데 {@code open-in-view: false}
     * 라 서비스 밖에서 지연 로딩이 터진다.
     */
    @Query("""
            select b from PredictionEventBet b
              join fetch b.prediction
             where b.event.id = :eventId and b.user.id = :userId
            """)
    List<PredictionEventBet> findMine(@Param("eventId") Long eventId, @Param("userId") Long userId);
}
