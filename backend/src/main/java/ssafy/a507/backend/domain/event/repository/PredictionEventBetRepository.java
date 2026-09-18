package ssafy.a507.backend.domain.event.repository;

import java.math.BigInteger;
import java.time.Instant;
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
     * 참가자 — 화면 J-01 의 "대회 참가자" 타일 (ANT-EVENT-04).
     *
     * <p>돌려주는 행은 배팅 한 건에 하나씩, {@code [user, 종목 idx, 종목명, 건 시각]} 이다.
     * 사람 단위로 묶는 일은 서비스가 한다 — 타일에 닉네임·사진과 함께 <b>건 종목 목록</b>까지
     * 필요해서, 세는 쿼리와 목록 쿼리를 따로 두느니 한 번에 읽고 자바에서 묶는 편이 싸다.
     * {@code user} 를 통째로 돌려주는 것은 닉네임과 프로필 사진 id 를 같이 쓰기 위해서다.
     *
     * <p><b>돈도 방향도 없다.</b> 이 도메인의 "남의 방향·목표가는 나가지 않는다" 규칙을 따른다 —
     * 누가 얼마를 어느 쪽으로 걸었는지는 집계(종목 카드의 풀)로만 보인다. 여기서 나가는 것은
     * "이 사람이 이 종목에 걸었다" 까지이고, 그건 카드의 참가 인원이 이미 세고 있는 사실이다.
     *
     * <p>상태는 풀 집계와 같은 {@code LIVE_FOR_POOL} 을 쓴다. PENDING 은 아직 체인에 없는 배팅이라
     * 이름이 먼저 올라갔다가 전송이 실패하면 사라지는 일이 생긴다 — 자기 것은 "내 참가 내역" 이
     * 전송 중까지 다 보여 준다.
     */
    @Query("""
            select b.user, b.eventStock.idx, b.eventStock.stock.name, b.createdAt
              from PredictionEventBet b
             where b.event.id = :eventId
               and b.betStatus in :live
             order by b.createdAt asc, b.eventStock.idx asc
            """)
    List<Object[]> findParticipantBets(
            @Param("eventId") Long eventId, @Param("live") Collection<PredictionEventBet.Status> live);

    /** 건 순서대로. 회차가 한 번뿐이라 통째로 읽는다 — 수천이 되면 커서를 붙인다. */
    default List<Object[]> findParticipantBets(Long eventId) {
        return findParticipantBets(eventId, LIVE_FOR_POOL);
    }

    /**
     * 아직 전송이 끝나지 않은 내 배팅의 합계 — 묶음 등록의 잔액 검사에서 뺀다 (ANT-EVENT-05).
     *
     * <p><b>왜 빼나</b> — PENDING 은 체인에 나갔거나 나가는 중이라 곧 잔액에서 사라질 돈이다. 그걸 안
     * 빼면 잔액 1,000 인 사용자가 500 짜리 배팅을 연달아 세 번 넣어도 매번 통과한다. 이벤트를 가리지
     * 않고 센다 — 지갑 하나가 모든 이벤트의 돈을 낸다.
     */
    @Query("select coalesce(sum(b.stake), 0) from PredictionEventBet b"
            + " where b.user.id = :userId and b.betStatus = ssafy.a507.backend.domain.event.entity.PredictionEventBet$Status.PENDING")
    BigInteger sumPendingStake(@Param("userId") Long userId);

    /**
     * 재전송 대상 — FAILED 인데 아직 접수가 안 끝난 이벤트의 배팅 (ANT-EVENT-05, §0.3).
     *
     * <p>봉인 시각을 넘긴 것은 고르지 않는다. 컨트랙트가 SEALED 뒤의 {@code placeBet} 을 revert 하므로
     * 보내 봐야 서버 버그로 기록될 뿐이다. 상한 횟수는 두지 않는다 — 잔액이 모자라 실패한 사용자가
     * 충전하면 다음 회차에 그대로 나가야 하고, 창은 어차피 {@code sealsAt} 이 닫는다.
     *
     * <p>{@code prediction} 과 {@code user} 를 페치한다. 전송에 지갑 주소와 방향이 필요한데
     * {@code open-in-view: false} 라 스케줄러 트랜잭션 밖에서 지연 로딩이 터진다.
     */
    @Query("""
            select b from PredictionEventBet b
              join fetch b.prediction
              join fetch b.user
              join fetch b.eventStock
              join fetch b.event e
             where b.betStatus = ssafy.a507.backend.domain.event.entity.PredictionEventBet$Status.FAILED
               and e.status = ssafy.a507.backend.domain.event.entity.PredictionEvent$Status.OPEN
               and e.sealsAt > :now
            """)
    List<PredictionEventBet> findRetryable(@Param("now") Instant now);

    /**
     * 정산 러너(ANT-EVENT-03)의 후보 — 체인에 올라간(bet_index 있음) 배팅 중 주어진 상태. 리빌 후보는 ACTIVE, 정산 후보는
     * ACTIVE·REVEALED. 예측을 페치한다(방향·판정 상태가 필요하고 러너는 트랜잭션 밖에서 값을 쓴다).
     */
    @Query("""
            select b from PredictionEventBet b
              join fetch b.prediction
             where b.event.id = :eventId
               and b.betStatus in :statuses
               and b.betIndex is not null
             order by b.betIndex asc
            """)
    List<PredictionEventBet> findOnChainByStatus(
            @Param("eventId") Long eventId, @Param("statuses") Collection<PredictionEventBet.Status> statuses);

    /** 순위 굳힘(ANT-EVENT-03) 재료 — 이벤트의 배팅 전부(예측·회원 페치). FAILED·PENDING 도 포함해 돌려주고 서비스가 가른다. */
    @Query("""
            select b from PredictionEventBet b
              join fetch b.prediction
              join fetch b.user
             where b.event.id = :eventId
             order by b.createdAt asc, b.predictionId asc
            """)
    List<PredictionEventBet> findAllForResults(@Param("eventId") Long eventId);

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

    /**
     * 내 배팅 — {@code GET /events/{id}/me} 용 (ANT-EVENT-06).
     *
     * <p>{@link #findMine} 과 달리 <b>종목까지 페치</b>하고 {@code idx} 순으로 준다. 그 화면은 종목코드·
     * 이름·판정 결과를 함께 보여주는데, {@code open-in-view: false} 라 DTO 를 만드는 시점에는
     * 세션이 닫혀 있다. 홈은 이미 종목을 따로 읽어 두므로 그쪽에는 이 페치가 낭비다.
     */
    @Query("""
            select b from PredictionEventBet b
              join fetch b.prediction
              join fetch b.eventStock s
              join fetch s.stock
             where b.event.id = :eventId and b.user.id = :userId
             order by s.idx
            """)
    List<PredictionEventBet> findMineDetailed(@Param("eventId") Long eventId, @Param("userId") Long userId);

    /**
     * 상태별 배팅 수 — 관리자 상태 화면 (ANT-EVENT-06). 돌려주는 행은 {@code [status, count]} 다.
     *
     * <p>상태마다 세면 여섯 번이다. 관리자 화면 한 장에 쿼리 여섯은 과하고, 무엇보다 "합이 안 맞는"
     * 순간(집계 사이에 인덱서가 상태를 바꾸는)이 생긴다.
     */
    @Query("select b.betStatus, count(b) from PredictionEventBet b where b.event.id = :eventId group by b.betStatus")
    List<Object[]> countByStatus(@Param("eventId") Long eventId);
}
