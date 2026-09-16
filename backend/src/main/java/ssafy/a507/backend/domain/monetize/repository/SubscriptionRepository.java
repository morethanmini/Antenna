package ssafy.a507.backend.domain.monetize.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.monetize.entity.Subscription;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /**
     * 내가 지금 유효하게 구독 중인 발행자 id 들. 잠금 판정의 단일 정의다.
     *
     * <p>글마다 "이 작성자를 구독했나"를 묻지 않고 한 번에 받아오는 이유는 목록 크기만큼
     * 쿼리가 늘어나기 때문이다. PENDING(결제 대기)은 아직 열람 권한이 아니다.
     *
     * <p><b>{@code status = ACTIVE} 만으로는 부족하다.</b> EXPIRED 로 넘기는 것은 만료 배치
     * (B4)이고 그 배치가 아직 없다. 상태만 보면 한 달 전에 만료된 구독이 유료 리포트 본문을
     * 계속 열어준다. 기간을 함께 보면 배치가 늦거나 멈춰도 열람 권한은 정확하다 —
     * 상태 전이가 권한의 근거가 아니라 <b>기간이 근거</b>이기 때문이다.
     *
     * <p>{@code expiresAt is null} 을 유효로 보는 이유는 인덱서가 ACTIVE 로 올리면서 기간을
     * 채우기 전 짧은 창이 있어서다. 결제가 확정된 구독을 잠그는 쪽이 더 나쁘다.
     */
    @Query("""
            select s.publisher.id
              from Subscription s
             where s.subscriber.id = :subscriberId
               and s.publisher.id in :publisherIds
               and s.status = :status
               and (s.expiresAt is null or s.expiresAt > :now)
            """)
    List<Long> findSubscribedPublisherIds(
            @Param("subscriberId") Long subscriberId,
            @Param("publisherIds") Collection<Long> publisherIds,
            @Param("status") Subscription.Status status,
            @Param("now") Instant now);

    /**
     * 이 발행자를 지금 유효하게 구독 중인 사람들. 리포트 발행 알림 대상이다.
     *
     * <p>User 엔티티가 아니라 id 만 받는다 — 알림 행의 FK 에 필요한 것은 id 뿐이고, 구독자
     * 수만큼 User 를 적재하면 발행 한 번에 그만큼 메모리를 쓴다.
     *
     * <p>기간 조건은 위와 같다. 만료된 구독자에게 "새 리포트" 알림을 보내면 눌러도 잠긴
     * 미리보기만 보인다.
     */
    @Query("""
            select s.subscriber.id
              from Subscription s
             where s.publisher.id = :publisherId
               and s.status = :status
               and (s.expiresAt is null or s.expiresAt > :now)
            """)
    List<Long> findSubscriberIds(
            @Param("publisherId") Long publisherId,
            @Param("status") Subscription.Status status,
            @Param("now") Instant now);

    /**
     * 이 발행자를 구독 중인 사람들 — <b>확정 대기(PENDING)까지 포함</b>한다. 구독료 변경 알림
     * 대상이다 (ANT-TOKEN-05).
     *
     * <p>{@link #findSubscriberIds} 와 나눠 둔 이유는 대상이 다르기 때문이다. 리포트 발행 알림은
     * 지금 읽을 수 있는 사람에게만 가면 되지만, 가격 변경은 <b>결제가 확정되는 중인 사람</b>도
     * 알아야 한다 — 그 사람의 다음 갱신부터 새 가격이 매겨진다.
     */
    @Query("""
            select s.subscriber.id
              from Subscription s
             where s.publisher.id = :publisherId
               and s.status in :statuses
               and (s.expiresAt is null or s.expiresAt > :now)
            """)
    List<Long> findFeeNoticeTargets(
            @Param("publisherId") Long publisherId,
            @Param("statuses") Collection<Subscription.Status> statuses,
            @Param("now") Instant now);

    /**
     * 이 사람이 저 채널에 건 구독 중 <b>가장 최근 한 줄</b>. 없으면 빈 값이다.
     *
     * <p>한 쌍에 행이 여럿 쌓인다 — 만료된 뒤 다시 구독하면 EXPIRED 가 남은 채로 새 행이 생긴다.
     * 화면이 물어보는 것은 "지금 내 상태"라 가장 최근 것만 본다. 여기서는 기간으로 거르지 않는다 —
     * 열람 권한을 판정하는 {@link #findSubscribedPublisherIds} 와 달리, 만료된 구독도 "만료됨"으로
     * 보여줘야 재구독 안내를 그릴 수 있다.
     */
    @Query("""
            select s from Subscription s
             where s.subscriber.id = :subscriberId
               and s.publisher.id = :publisherId
             order by s.id desc
            """)
    Optional<Subscription> findLatestBetween(
            @Param("subscriberId") Long subscriberId,
            @Param("publisherId") Long publisherId,
            Limit limit);

    /**
     * 내가 건 구독 전부. 최신순이다 (ANT-TOKEN-05, 화면 E-03).
     *
     * <p>만료분도 담는다 — 화면이 "만료됨" 줄에 재구독 안내를 그린다. {@code publisher} 를 함께
     * 가져오는 이유는 목록이 채널 닉네임을 그리기 때문이다. 안 걸면 행 수만큼 SELECT 가 더 나간다.
     */
    @Query("""
            select s from Subscription s
              join fetch s.publisher
             where s.subscriber.id = :subscriberId
             order by s.id desc
            """)
    List<Subscription> findMine(@Param("subscriberId") Long subscriberId);
}
