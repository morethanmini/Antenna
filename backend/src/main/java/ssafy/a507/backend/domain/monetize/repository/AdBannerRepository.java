package ssafy.a507.backend.domain.monetize.repository;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;

public interface AdBannerRepository extends JpaRepository<AdBanner, Long> {

    /**
     * 지금 노출할 배너. 상태와 기간을 함께 본다 — ACTIVE 로 두고 만료만 지난 행이 남으므로
     * 상태만 보면 끝난 광고가 계속 뜬다. 상태를 ENDED 로 바꾸는 배치가 없어도 정확하다.
     */
    List<AdBanner> findByStatusAndStartsAtLessThanEqualAndEndsAtAfterOrderByStartsAtAsc(
            AdBanner.Status status, Instant at, Instant sameAt);

    /**
     * 요청 기간에서 자리를 차지하고 있는 신청 수. 선착순 슬롯 판정에 쓴다.
     *
     * <p>PENDING 도 센다. 심사·확정을 기다리는 신청을 비어 있는 것으로 보면, 확정되는 순간
     * 같은 기간에 배너가 두 개가 되고 자리는 하나뿐이다.
     *
     * <p><b>유예 창이 없어졌다(ANT-ADMIN-03).</b> 승인제 전에는 "접수는 토큰을 차감하지 않으니 오래된 PENDING 은
     * 자리를 놓은 것으로 본다" 는 창이 있었다 — 확정이 몇 초라는 전제였다. 승인 대기는 몇 시간일 수 있어 그 창이
     * 지나면 자리를 놓친 것으로 오판한다. 이제 대기는 자리를 기간 내내 잡고, 자리만 붙잡는 신청은 관리자가 반려한다.
     * 신청자가 스스로 취소할 수도 있다.
     *
     * <p>{@code excludeId} 는 <b>승인 시 재검사</b>용이다 — 승인이 며칠 뒤면 기간이 밀려 다른 광고와 겹칠 수 있는데,
     * 이 배너 자신은 세면 안 된다. 접수 때는 아직 행이 없으니 0 을 넘긴다(IDENTITY 는 1부터라 0 인 행은 없다).
     * 널 대신 0 인 이유는 널 파라미터가 PostgreSQL 에서 타입을 못 정해 깨지기 때문이다({@code UserRepository} 주석).
     *
     * <p>겹침 판정이 {@code startsAt < 요청 끝} 과 {@code endsAt > 요청 시작} 인 것은 반열린
     * 구간 규칙이다 — 한 배너가 끝나는 순간 다음 배너가 시작하는 경우를 겹침으로 세지 않는다.
     */
    @Query("""
            select count(b) from AdBanner b
            where b.startsAt < :endsAt and b.endsAt > :startsAt
              and b.status in :occupying
              and b.id <> :excludeId
            """)
    long countOccupying(
            @Param("occupying") Collection<AdBanner.Status> occupying,
            @Param("startsAt") Instant startsAt,
            @Param("endsAt") Instant endsAt,
            @Param("excludeId") Long excludeId);

    /**
     * 승인 대기 중인 내 광고의 게재료 합 — 가용잔액에서 뺀다 (ANT-TOKEN-11).
     *
     * <p>승인제(ANT-ADMIN-03)에서는 신청 때 돈이 안 나가고 승인 때 나간다. 그 사이 이 돈을 다른 데(배팅·구독)에
     * 쓸 수 있으면 승인 시점에 잔액이 없다 — 사용자가 "광고 신청한 거 기억하고 남겨 둬야" 하는 UX 가 된다.
     * 빼 두면 쓰려는 순간 막힌다. {@code PredictionEventBetRepository.sumPendingStake} 와 같은 꼴이다.
     */
    @Query("select coalesce(sum(b.priceWei), 0) from AdBanner b"
            + " where b.advertiser.id = :userId"
            + " and b.status = ssafy.a507.backend.domain.monetize.entity.AdBanner$Status.PENDING")
    BigInteger sumPendingPrice(@Param("userId") Long userId);

    /** 관리자 목록 (ANT-ADMIN-03). 신청자 닉네임을 같이 그리므로 fetch join. 전체를 볼 때는 상태를 다 넘긴다. */
    @Query("""
            select b from AdBanner b
              join fetch b.advertiser
            where b.status in :statuses
            order by b.id desc
            """)
    List<AdBanner> findForAdmin(@Param("statuses") Collection<AdBanner.Status> statuses);

    /** 승인·반려·취소가 한 건을 열 때. 신청자의 지갑·잔액을 봐야 하므로 같이 끌어온다. */
    @Query("select b from AdBanner b join fetch b.advertiser where b.id = :id")
    Optional<AdBanner> findWithAdvertiser(@Param("id") Long id);
}
