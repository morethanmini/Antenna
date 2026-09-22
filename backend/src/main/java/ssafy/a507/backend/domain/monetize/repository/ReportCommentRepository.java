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

    /**
     * 도배 제한 — 이 리포트에 이 사람이 마지막으로 쓴 댓글. 지운 댓글은 빼고 본다(오타를 지우고 같은 말을 다시 쓰는 것은
     * 도배가 아니다). 지웠다 쓰기를 되풀이하는 것은 1분 5개 제한이 막는다 — 그쪽 개수는 상태 무관이다.
     */
    Optional<ReportComment> findFirstByReport_IdAndUser_IdAndStatusNotOrderByIdDesc(
            Long reportId, Long userId, ReportComment.Status status);

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
     * 댓글 목록 한 페이지 — 최신 순(QA 09-21). 커서가 {@code id <} 로 거꾸로 나아가는 것만
     * 다르고 나머지는 {@link #findPage} 와 같다. 정렬과 커서 방향은 늘 같이 움직여야 해서
     * 한 쿼리에 방향 인자를 끼우는 대신 메서드를 나눴다 — 조건과 order by 를 동시에 뒤집는
     * case 식은 읽을 수 없고, 인덱스도 타지 못한다.
     */
    @Query("""
            select c from ReportComment c
              join fetch c.user
             where c.report.id = :reportId
               and c.status = :status
               and (:cursor is null or c.id < :cursor)
             order by c.id desc
            """)
    List<ReportComment> findPageLatest(
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
