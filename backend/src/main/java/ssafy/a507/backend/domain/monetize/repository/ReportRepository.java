package ssafy.a507.backend.domain.monetize.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.entity.Subscription;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /**
     * 통합 피드 한 페이지 — 최신순. 커서는 id 하나다.
     *
     * <p>{@code subscriberId} 가 null 이면 scope=ALL, 값이 있으면 scope=SUBSCRIBED 다.
     * 구독 필터를 id 목록(<code>in :ids</code>)이 아니라 exists 서브쿼리로 쓴 이유는, 구독이
     * 하나도 없는 사용자에게 빈 목록을 바인딩해야 해서다 — 빈 컬렉션의 IN 은 DB·드라이버마다
     * 다르게 렌더된다. 이 형태는 파라미터가 항상 스칼라라 그 경우가 생기지 않는다.
     *
     * <p>작성자를 함께 끌어오는 것은 응답에 닉네임이 들어가기 때문이다. LAZY 로 두면 목록
     * 크기만큼 쿼리가 늘어난다.
     */
    @Query("""
            select r from Report r
              join fetch r.user
            where (:subscriberId is null
                   or exists (select 1 from Subscription s
                               where s.subscriber.id = :subscriberId
                                 and s.publisher.id = r.user.id
                                 and s.status = :activeStatus
                                 and (s.expiresAt is null or s.expiresAt > :now)))
              and (:cursorId is null or r.id < :cursorId)
            order by r.id desc
            """)
    List<Report> findFeedPageRecent(
            @Param("subscriberId") Long subscriberId,
            @Param("activeStatus") Subscription.Status activeStatus,
            @Param("now") Instant now,
            @Param("cursorId") Long cursorId,
            Limit limit);

    /**
     * 통합 피드 한 페이지 — 인기순(열람 수). 커서가 {@code (viewCount, id)} 복합이다.
     *
     * <p>열람 수는 값이 겹치고 시간이 지나면 변한다. id 하나만 커서로 쓰면 같은 열람 수 구간
     * 에서 다음 페이지가 어디서 시작해야 하는지 정해지지 않아 건너뛰거나 중복된다. 정렬 키를
     * 커서에 그대로 담고 뒤에 id 로 tie-break 하는 것이 커서 페이징의 일반형이다.
     */
    @Query("""
            select r from Report r
              join fetch r.user
            where (:subscriberId is null
                   or exists (select 1 from Subscription s
                               where s.subscriber.id = :subscriberId
                                 and s.publisher.id = r.user.id
                                 and s.status = :activeStatus
                                 and (s.expiresAt is null or s.expiresAt > :now)))
              and (:cursorViewCount is null
                   or r.viewCount < :cursorViewCount
                   or (r.viewCount = :cursorViewCount and r.id < :cursorId))
            order by r.viewCount desc, r.id desc
            """)
    List<Report> findFeedPagePopular(
            @Param("subscriberId") Long subscriberId,
            @Param("activeStatus") Subscription.Status activeStatus,
            @Param("now") Instant now,
            @Param("cursorViewCount") Integer cursorViewCount,
            @Param("cursorId") Long cursorId,
            Limit limit);

    /** 채널 리포트 목록 한 페이지. 최신순 고정이고 비공개 리포트도 제목까지는 내려간다. */
    @Query("""
            select r from Report r
              join fetch r.user
            where r.user.id = :userId
              and (:cursorId is null or r.id < :cursorId)
            order by r.id desc
            """)
    List<Report> findChannelPage(
            @Param("userId") Long userId, @Param("cursorId") Long cursorId, Limit limit);

    /**
     * 통합 검색의 리포트 칸(ANT-DATA-05) — 제목 부분일치, 최신순. 채널 목록처럼 비공개 리포트도 제목은 나온다.
     *
     * @param contains 소문자 · LIKE 이스케이프('!')를 마친 {@code %q%}
     */
    @Query("""
            select r from Report r
              join fetch r.user
            where lower(r.title) like :contains escape '!'
            order by r.id desc
            """)
    List<Report> searchByTitle(@Param("contains") String contains, Limit limit);

    /** 상세 조회. 목록과 같은 이유로 작성자를 함께 끌어온다. */
    @Query("""
            select r from Report r
              join fetch r.user
            where r.id = :id
            """)
    Optional<Report> findDetailById(@Param("id") Long id);

    /**
     * 열람 수 +1. 엔티티를 읽어 더하지 않고 DB 에서 증가시킨다 — 두 사람이 같은 리포트를 동시에
     * 열면 읽기-수정-쓰기 방식은 한쪽 증가분을 덮어써 잃는다.
     */
    @Modifying
    @Query("update Report r set r.viewCount = r.viewCount + 1 where r.id = :id")
    void incrementViewCount(@Param("id") Long id);

    /**
     * 앵커 대기 중인 리포트 (ANT-CHAIN-14). 순서 규칙은 {@code AnchorLeaves} 에 있다 — 여기서 바꾸면 루트가 달라진다.
     *
     * <p>{@code bodyHash is not null} 로 <b>봉인된 것만</b> 고른다. 이 기능 이전에 발행된 리포트는 해시가 없어
     * 리프를 만들 수 없고, 지금 만들어 올리면 "그때 있었다" 가 아니라 "지금 만들었다" 가 된다.
     */
    @Query("select r from Report r where r.bodyHash is not null and r.anchorBatch is null order by r.id asc")
    List<Report> findSealedWithoutBatch();

    /**
     * 커밋 원장 목록(화면 D-01)의 리포트 줄 (ANT-CHAIN-15). 최신 발행부터, 같은 시각이면 id 내림차순.
     *
     * <p>{@code bodyHash is not null} 은 {@link #findSealedWithoutBatch} 와 <b>같은 기준</b>이다 —
     * 봉인 이전에 발행된 리포트는 앵커 대상이 아니므로 원장에도 오르지 않는다. 한쪽만 고치면
     * "원장에는 있는데 체인에는 없는 줄" 이 생긴다.
     *
     * <p>{@code cursorId} 는 커서의 종류가 REPORT 일 때만 값이 있다. 커서가 예측을 가리키면 null 이 오고,
     * 그때는 같은 시각의 리포트가 아직 안 나갔으므로({@code PREDICTION} 이 같은 시각에서 앞이다)
     * {@code createdAt = :at} 인 리포트를 전부 포함해야 한다 — {@code :cursorId is null} 가지가 그것이다.
     *
     * <p>{@code anchorBatch} 는 left fetch 다. 아직 배치에 안 묶인 리포트도 원장에 나와야 하고
     * (다음 배치 대기), fetch 하지 않으면 한 장에 배치 수만큼 추가 질의가 난다.
     */
    @Query("""
            select r from Report r
              left join fetch r.anchorBatch
            where r.bodyHash is not null
              and (:at is null
                   or r.createdAt < :at
                   or (r.createdAt = :at and (:cursorId is null or r.id < :cursorId)))
            order by r.createdAt desc, r.id desc
            """)
    List<Report> findLedgerPage(@Param("at") Instant at, @Param("cursorId") Long cursorId, Limit limit);

    /** 이 배치에 묶인 리포트. 리프 순서 재현용이라 {@link #findSealedWithoutBatch} 와 같은 정렬이어야 한다. */
    List<Report> findByAnchorBatchOrderByIdAsc(AnchorBatch anchorBatch);
}
