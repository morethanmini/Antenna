package ssafy.a507.backend.domain.ranking.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.common.Track;
import ssafy.a507.backend.domain.ranking.entity.Ranking;

public interface RankingRepository extends JpaRepository<Ranking, Long> {

    /**
     * 랭킹 한 장(ANT-RANK-02). 커서와 fromRank 가 같은 자리를 가리키므로 조회는 이것 하나다 —
     * 순위가 필터 조합마다 1부터 연속이라 "몇 등부터" 가 곧 오프셋이고, 스냅샷이라 그 사이에 행이 끼지 않는다.
     *
     * <p>{@code join fetch} 로 회원을 같이 끌어온다. 줄마다 nickname 을 실어야 해서 없으면 N+1 이다.
     *
     * <p>동점 회원 사이의 순서를 user.id 로 고정한다. 정렬 키가 rank 하나뿐이면 같은 rank 를 가진 두 줄의
     * 앞뒤가 호출마다 달라지고, 그 경계에서 페이지를 끊으면 한 회원이 두 장에 나오거나 어디에도 안 나온다.
     *
     * <p>ponytail: 순위 자체가 겹치지 않는다는 보장은 <b>쓰는 쪽</b>이 진다 — 랭킹 스냅샷 배치(ANT-RANK-01)는
     * 필터 조합마다 1부터 빈틈·중복 없는 rank 를 써야 한다(SQL 로는 {@code RANK()} 가 아니라
     * {@code ROW_NUMBER()}). 응답에 nextCursor 가 없어 프론트가 "받은 마지막 rank + 1" 로 다음 장을 부르기
     * 때문이다. 배치가 동점에 같은 rank 를 주면 그 등수의 뒷줄이 페이지 경계에서 통째로 건너뛰어진다.
     * 읽는 쪽에서 막으려면 경계의 동점 묶음을 통째로 실어야 하는데, 그건 이 API 가 질 복잡도가 아니다.
     */
    @Query("""
            select r from Ranking r
             join fetch r.user u
             where r.track = :track
               and r.filterKey = :filterKey
               and r.rank >= :fromRank
             order by r.rank asc, u.id asc
            """)
    List<Ranking> findPage(
            @Param("track") Track track,
            @Param("filterKey") String filterKey,
            @Param("fromRank") int fromRank,
            Limit limit);

    /**
     * 통합 검색의 예측가 칸(ANT-DATA-05) — <b>회원</b>에서 닉네임 부분일치로 찾고, 한 스냅샷(트랙·필터)의
     * 순위·적중률을 곁들인다. 랭킹에 든 사람이 먼저(순위순), 나머지는 닉네임순이다.
     *
     * <p>전에는 랭킹 스냅샷 안에서만 찾았다(2026-09-19 까지). 랭킹은 판정 3건이 쌓이고 배치가 돈 뒤에야
     * 들어가므로 그 전인 사람은 닉네임을 정확히 쳐도 나오지 않았다 — 예측가를 닉네임으로 찾는 길이 통합
     * 검색뿐인데 "찾아서 구독" 이 대부분의 채널에서 막혀 있었다. 뿌리를 회원으로 옮기고 랭킹은 left join 한다.
     *
     * <p>닉네임을 아직 정하지 않은 회원(null·빈 문자열)은 검색어가 비어 있지 않으므로 LIKE 에서 저절로 빠진다.
     *
     * @param contains 소문자 · LIKE 이스케이프('!')를 마친 {@code %q%}
     * @param status 검색에 나올 회원 상태 — 제재된 회원은 빼려고 ACTIVE 를 넘긴다
     */
    @Query("""
            select new ssafy.a507.backend.domain.ranking.repository.NicknameHit(u.id, u.nickname, r.hitRate)
              from User u
              left join Ranking r on r.user = u and r.track = :track and r.filterKey = :filterKey
             where u.status = :status
               and lower(u.nickname) like :contains escape '!'
             order by case when r.rank is null then 1 else 0 end, r.rank asc, u.nickname asc, u.id asc
            """)
    List<NicknameHit> searchByNickname(
            @Param("track") Track track,
            @Param("filterKey") String filterKey,
            @Param("contains") String contains,
            @Param("status") User.Status status,
            Limit limit);

    /** 내 순위 한 줄(ANT-RANK-03). 없으면 빈 값 — 배치 전이거나 표본이 모자라 랭킹에 안 든 것이다. */
    Optional<Ranking> findByTrackAndFilterKeyAndUserId(Track track, String filterKey, Long userId);

    /** 백분위의 분모. 같은 트랙·필터 안의 인원수다. */
    long countByTrackAndFilterKey(Track track, String filterKey);
}
