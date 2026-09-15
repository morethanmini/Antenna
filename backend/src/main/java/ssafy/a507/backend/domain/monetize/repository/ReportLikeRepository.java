package ssafy.a507.backend.domain.monetize.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.monetize.entity.ReportLike;

public interface ReportLikeRepository extends JpaRepository<ReportLike, Long> {

    /**
     * 리포트 여러 건의 좋아요 수를 한 쿼리로 센다. 리포트마다 count 를 부르면 목록 크기만큼
     * 쿼리가 늘어난다. 좋아요가 0인 리포트는 결과에 없으므로 호출부가 0으로 채운다.
     */
    @Query("""
            select l.report.id, count(l)
              from ReportLike l
             where l.report.id in :reportIds
             group by l.report.id
            """)
    List<Object[]> countByReportIds(@Param("reportIds") Collection<Long> reportIds);

    boolean existsByReportIdAndUserId(Long reportId, Long userId);

    /** 한 리포트의 좋아요 수. 등록 응답에 갱신된 수를 담아 내리는 데 쓴다. */
    long countByReportId(Long reportId);

    /**
     * 이 페이지에서 내가 이미 좋아요한 리포트들. 리포트마다 exists 를 묻지 않고 한 번에 받아온다
     * (구독 여부를 한 번에 받아오는 것과 같은 이유).
     */
    @Query("""
            select l.report.id
              from ReportLike l
             where l.user.id = :userId
               and l.report.id in :reportIds
            """)
    List<Long> findLikedReportIds(
            @Param("userId") Long userId, @Param("reportIds") Collection<Long> reportIds);

    /**
     * 좋아요 취소. 엔티티를 읽어와 삭제하면 select 가 한 번 더 나가므로 delete 한 문장으로 지운다.
     * 지운 건수를 돌려주지만 호출부는 쓰지 않는다 — 없던 좋아요의 취소도 204 다(멱등).
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from ReportLike l where l.report.id = :reportId and l.user.id = :userId")
    int deleteByReportIdAndUserId(@Param("reportId") Long reportId, @Param("userId") Long userId);
}
