package ssafy.a507.backend.domain.prediction.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.common.Track;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    /**
     * 내 예측 목록(ANT-PRED-06) — 최신부터, 커서 id 미만. 정렬 키를 created_at 이 아니라 id 로 두는 이유는
     * 같은 초에 봉인한 두 건이 커서 페이징에서 겹치거나 빠지지 않게 하기 위해서다(id 는 등록 순서와 같다).
     *
     * <p>{@code join fetch} 로 종목을 같이 끌어온다 — 항목마다 stockName 을 실어야 해서 없으면 N+1 이다.
     */
    @Query("""
            select p from Prediction p
             left join fetch p.stock
             where p.user.id = :userId
               and p.status in :statuses
               and (:cursorId is null or p.id < :cursorId)
             order by p.id desc
            """)
    List<Prediction> findMyPage(
            @Param("userId") Long userId,
            @Param("statuses") Collection<Prediction.Status> statuses,
            @Param("cursorId") Long cursorId,
            Limit limit);

    /**
     * 예측 피드 한 페이지 (화면 F-01 예측 탭) — 여러 채널을 가로지르는 최신순 목록.
     *
     * <p>{@code subscriberId} 가 null 이면 scope=ALL, 값이 있으면 scope=SUBSCRIBED 다. 구독 필터를 id 목록
     * ({@code in :ids})이 아니라 exists 서브쿼리로 쓴 이유는 {@code ReportRepository.findFeedPageRecent} 와 같다 —
     * 구독이 하나도 없는 사용자에게 빈 컬렉션을 바인딩해야 하는데, 빈 IN 은 DB·드라이버마다 다르게 렌더된다.
     *
     * <p>내 예측도 뺴지 않는다. "내가 이 종목에 뭘 걸었더라" 를 피드에서 바로 보는 편이 자연스럽고,
     * 빼려면 여기 조건이 하나 더 붙는 만큼 화면에서 "내 것" 표시를 못 하게 된다.
     *
     * <p>{@code join fetch} 둘 다 필요하다 — 줄마다 작성자 닉네임과 종목명을 싣는다. 종목은 <b>left</b> 다
     * (지워진 종목의 예측도 목록에서 빠지면 안 된다).
     */
    @Query("""
            select p from Prediction p
              join fetch p.user
              left join fetch p.stock
             where p.track = :track
               and p.status in :statuses
               and (:subscriberId is null
                    or exists (select 1 from Subscription s
                                where s.subscriber.id = :subscriberId
                                  and s.publisher.id = p.user.id
                                  and s.status = :activeStatus
                                  and (s.expiresAt is null or s.expiresAt > :now)))
               and (:cursorId is null or p.id < :cursorId)
             order by p.id desc
            """)
    List<Prediction> findFeedPage(
            @Param("track") Track track,
            @Param("statuses") Collection<Prediction.Status> statuses,
            @Param("subscriberId") Long subscriberId,
            @Param("activeStatus") Subscription.Status activeStatus,
            @Param("now") Instant now,
            @Param("cursorId") Long cursorId,
            Limit limit);

    /**
     * 슬롯 계산(ANT-PRED-01) — KST 하루 구간 [from, to) 에 이 사용자가 등록한 수. 트랙 구분 없이 센다(예측은 REAL 전용).
     *
     * <p><b>이벤트 참가는 빼고 센다</b>(ANT-EVENT-05). 배팅 이벤트는 ANT 를 내고 참가하는 별개 규칙이라 무료 슬롯 3건과
     * 무관한데, 안 빼면 5종목을 걸자마자 그날 예측을 하나도 못 쓴다. 이벤트 예측인지는 컬럼이 아니라
     * {@code prediction_event_bets} 행의 유무로 가른다 — 배팅 행이 1:1 로 붙어 있고, 그래서 {@code predictions} 에
     * 이벤트 전용 컬럼을 더하지 않아도 된다.
     */
    @Query("""
            select count(p) from Prediction p
             where p.user.id = :userId
               and p.createdAt >= :from and p.createdAt < :to
               and not exists (select 1 from PredictionEventBet b where b.prediction = p)
            """)
    long countFreeToday(@Param("userId") Long userId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * 판정 배치(ANT-PRED-03·04)가 훑을 대상의 <b>id 만</b> 가져온다. 한 건이 트랜잭션 하나라 엔티티는 그 트랜잭션 안에서 다시 읽는다 —
     * 여기서 엔티티를 통째로 들고 나오면 루프 내내 준영속 상태로 붙들고 있게 된다.
     *
     * <p>날짜 조건이 없다. 대상 선별을 "시세가 들어왔나" 로 하기 때문이다(plan §설계 ②) — 날짜로 자르면 그날 종가가 아직 없는
     * 건이 다음 회차에 조건에서 빠져 영영 전이되지 않는다. 13:30 에 볼 수 있는 최신 종가는 어제 것이다.
     *
     * <p>{@code track} 을 거는 이유: REPLAY 는 게임일 진행이 전이시킨다. 실전 배치가 시즌 예측을 건드리면 안 된다.
     */
    @Query("select p.id from Prediction p where p.track = :track and p.status = :status order by p.id")
    List<Long> findIdsByTrackAndStatus(
            @Param("track") Track track, @Param("status") Prediction.Status status);

    /**
     * 종목 예측 분포(ANT-PRED-07)의 재료 — 이 종목에 걸린 판정 대기 예측의 <b>목표가만</b> 가져온다.
     *
     * <p>엔티티가 아니라 값만 받는 이유: 분포 응답에는 개인이 없어야 한다. 서비스가 id·작성자를 손에 쥐지 않으면
     * 실수로 응답에 실을 길도 없다. 구간은 서비스가 자른다 — 경계 가격 계산을 SQL 과 자바 두 곳에 두지 않으려고.
     */
    @Query("""
            select p.targetPrice from Prediction p
             where p.stock.code = :stockCode
               and p.status in :statuses
            """)
    List<BigDecimal> findTargetPricesByStockAndStatusIn(
            @Param("stockCode") String stockCode,
            @Param("statuses") Collection<Prediction.Status> statuses);

    /**
     * 이 종목에서 [from, to) 사이에 판정된 예측 (ANT-PRED-07 "오늘 판정"). 작성자를 같이 끌어온다(N+1 방지).
     *
     * <p><b>updated_at 으로 거르는 이유.</b> 판정 배치는 13:30 에 <b>어제</b> 종가로 판정한다 — settled_on 은 어제다.
     * settled_on = 오늘 로 거르면 영원히 비어 있다. HIT·MISS 로 바뀐 뒤 예측 행을 고치는 코드가 없으므로 판정 건의
     * updated_at 이 곧 판정 시각이다. <b>판정 뒤 예측 행을 수정하는 기능을 만들면 이 기준이 깨진다.</b>
     */
    @Query("""
            select p from Prediction p
              join fetch p.user
             where p.stock.code = :stockCode
               and p.status in :statuses
               and p.updatedAt >= :from and p.updatedAt < :to
             order by p.id
            """)
    List<Prediction> findSettledBetween(
            @Param("stockCode") String stockCode,
            @Param("statuses") Collection<Prediction.Status> statuses,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** 상태별 내 예측 수. 목록 위 요약 칩(total·pending·judged·hitRate)의 재료다. */
    @Query("""
            select p.status, count(p) from Prediction p
             where p.user.id = :userId
             group by p.status
            """)
    List<Object[]> countMineByStatus(@Param("userId") Long userId);

    /**
     * 위와 같되 트랙을 좁힌다. {@code track} 이 null 이면 둘 다 센다 — 기존 호출부의 뜻 그대로다.
     *
     * <p>한 표에 REAL 과 REPLAY 가 함께 들어간다(스키마의 {@code ck_predictions_target_matches_track}).
     * 채널 실적(ANT-TOKEN-09)처럼 "실전 성적"을 묻는 자리는 REAL 만 세야 한다 — 모의투자 성적이 섞이면
     * 적중률이 조용히 달라지고, 오류가 아니라 숫자만 틀리므로 눈치채기 어렵다.
     */
    @Query("""
            select p.status, count(p) from Prediction p
             where p.user.id = :userId
               and (:track is null or p.track = :track)
             group by p.status
            """)
    List<Object[]> countByStatusAndTrack(
            @Param("userId") Long userId, @Param("track") Track track);

    /**
     * 판정된 내 예측의 평균 오차 (C-04). 판정이 없으면 빈 값이다 — 0 이 아니다.
     *
     * <p>{@code avg} 를 DB 에 맡기는 이유는 목록을 끌어와 평균을 내면 예측이 늘수록 읽는 행이 느는데,
     * 화면에 나가는 것은 숫자 하나여서다.
     */
    @Query("""
            select avg(p.errorRate) from Prediction p
             where p.user.id = :userId
               and p.status in (ssafy.a507.backend.domain.prediction.entity.Prediction$Status.HIT,
                                ssafy.a507.backend.domain.prediction.entity.Prediction$Status.MISS)
            """)
    Optional<BigDecimal> avgErrorOfSettled(@Param("userId") Long userId);

    /** 위와 같되 트랙을 좁힌다. {@code track} 이 null 이면 둘 다 본다 — {@link #countByStatusAndTrack} 와 짝이다. */
    @Query("""
            select avg(p.errorRate) from Prediction p
             where p.user.id = :userId
               and (:track is null or p.track = :track)
               and p.status in (ssafy.a507.backend.domain.prediction.entity.Prediction$Status.HIT,
                                ssafy.a507.backend.domain.prediction.entity.Prediction$Status.MISS)
            """)
    Optional<BigDecimal> avgErrorOfSettledByTrack(
            @Param("userId") Long userId, @Param("track") Track track);

    /**
     * 체인에 <b>박힌</b> 내 커밋 수 (C-04). 배치가 CONFIRMED 인 것만 센다 — PENDING 은 아직 안 올라간
     * 것이고 FAILED 는 못 올라간 것이라, 둘을 anchored 라 부르면 화면이 거짓말을 한다.
     */
    @Query("""
            select count(c) from PredictionCommit c
             where c.prediction.user.id = :userId
               and c.anchorBatch.status = ssafy.a507.backend.domain.chain.entity.AnchorBatch$Status.CONFIRMED
            """)
    long countAnchored(@Param("userId") Long userId);

    /**
     * 백테스트 재료 — 구간 안에 판정된 REAL 예측의 (방향, 기준가, 판정가) (ANT-TOKEN-03).
     *
     * <p>엔티티가 아니라 세 값만 꺼내는 이유는 계산에 그것뿐이고, 예측은 사람마다 계속 늘어서다.
     * {@code base_price}·{@code settle_price} 가 비어 있는 행은 뺀다 — 판정 배치가 채우는 값이라
     * HIT/MISS 면 늘 차 있지만, 없으면 수익률을 만들 수 없어 <b>표본에서 빠지는</b> 편이 맞다.
     */
    @Query("""
            select p.direction, p.basePrice, p.settlePrice from Prediction p
             where p.user.id = :userId
               and p.track = ssafy.a507.backend.domain.common.Track.REAL
               and p.status in (ssafy.a507.backend.domain.prediction.entity.Prediction$Status.HIT,
                                ssafy.a507.backend.domain.prediction.entity.Prediction$Status.MISS)
               and p.settledOn >= :from
               and p.basePrice is not null
               and p.settlePrice is not null
               and p.basePrice > 0
            """)
    List<Object[]> backtestRows(@Param("userId") Long userId, @Param("from") LocalDate from);

    /** 백테스트 구간의 양 끝을 잡는다 — 그 사람의 가장 이른/늦은 판정일. 표본이 없으면 빈 값 */
    @Query("""
            select min(p.settledOn), max(p.settledOn) from Prediction p
             where p.user.id = :userId
               and p.track = ssafy.a507.backend.domain.common.Track.REAL
               and p.status in (ssafy.a507.backend.domain.prediction.entity.Prediction$Status.HIT,
                                ssafy.a507.backend.domain.prediction.entity.Prediction$Status.MISS)
               and p.settledOn >= :from
            """)
    List<Object[]> settledSpan(@Param("userId") Long userId, @Param("from") LocalDate from);
}
