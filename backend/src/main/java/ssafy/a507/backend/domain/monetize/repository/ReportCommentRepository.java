package ssafy.a507.backend.domain.monetize.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.monetize.entity.ReportComment;

public interface ReportCommentRepository extends JpaRepository<ReportComment, Long> {

    /** 도배 제한 — 이 사람이 since 이후에 쓴 댓글 수(상태 무관). */
    long countByUser_IdAndCreatedAtAfter(Long userId, Instant since);

    /** 도배 제한 — 이 리포트에 이 사람이 마지막으로 쓴 댓글. */
    Optional<ReportComment> findFirstByReport_IdAndUser_IdOrderByIdDesc(Long reportId, Long userId);

    /**
     * 리포트 여러 건의 댓글 수를 한 쿼리로 센다. BLOCKED 댓글은 세지 않는다 — 목록에서
     * 빠지는 댓글을 개수에 넣으면 "댓글 3개"를 눌렀는데 1개만 보이는 화면이 된다.
     * 댓글이 0인 리포트는 결과에 없으므로 호출부가 0으로 채운다.
     */
    @Query("""
            select c.report.id, count(c)
              from ReportComment c
             where c.report.id in :reportIds
               and c.status = :status
             group by c.report.id
            """)
    List<Object[]> countByReportIds(
            @Param("reportIds") Collection<Long> reportIds,
            @Param("status") ReportComment.Status status);

    /**
     * 댓글 목록 한 페이지. 오래된 순이라 커서는 {@code id >} 로 나아간다 — 목록 순서와
     * 커서 방향이 어긋나면 다음 페이지가 앞쪽을 다시 준다. BLOCKED 는 제외한다.
     */
    @Query("""
            select c from ReportComment c
              join fetch c.user
             where c.report.id = :reportId
               and c.status = :status
               and (:cursor is null or c.id > :cursor)
             order by c.id asc
            """)
    List<ReportComment> findPage(
            @Param("reportId") Long reportId,
            @Param("status") ReportComment.Status status,
            @Param("cursor") Long cursor,
            Limit limit);

    /**
     * 댓글 좋아요가 대상 댓글과 그 댓글이 달린 리포트를 한 번에 필요로 한다.
     *
     * <p>{@code report} 를 함께 읽는 이유는 잠금 판정 때문이다 — 구독자 전용 리포트의 댓글에
     * 미구독자가 좋아요를 누를 수 없다. 프록시로 두면 판정하는 순간 SELECT 가 따로 나간다.
     */
    @Query("""
            select c from ReportComment c
              join fetch c.report r
              join fetch r.user
             where c.id = :commentId
            """)
    Optional<ReportComment> findWithReportById(@Param("commentId") Long commentId);
}
