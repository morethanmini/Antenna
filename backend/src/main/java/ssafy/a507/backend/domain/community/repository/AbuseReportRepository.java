package ssafy.a507.backend.domain.community.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.community.entity.AbuseReport;

public interface AbuseReportRepository extends JpaRepository<AbuseReport, Long> {

    /**
     * 관리자 목록 (ANT-ADMIN-01). {@code status} 가 null 이면 전체.
     *
     * <p>연관을 전부 fetch join 하는 이유 — 목록 한 줄마다 신고자 닉네임과 대상을 같이 보여 준다.
     * 지연 로딩에 맡기면 행 하나에 쿼리 네 개가 더 나간다. 셋 중 하나만 채워지는 구조(팩터리 주석)라
     * left join 이어야 한다.
     *
     * <p>페이지네이션을 두지 않았다 — 운영자 한 명이 보는 목록이고 신고 자체가 드물다.
     * 쌓이기 시작하면 그때 커서를 넣는다.
     */
    @Query("""
            select r from AbuseReport r
              left join fetch r.reporter
              left join fetch r.targetPost
              left join fetch r.targetComment
              left join fetch r.targetUser
              left join fetch r.handledBy
            where (:status is null or r.status = :status)
            order by r.id desc
            """)
    List<AbuseReport> findForAdmin(@Param("status") AbuseReport.Status status);

    /** 처리 화면에서 한 건을 열 때. 대상까지 같이 끌어와 서비스가 다시 조회하지 않게 한다. */
    @Query("""
            select r from AbuseReport r
              left join fetch r.targetPost
              left join fetch r.targetComment
              left join fetch r.targetUser
            where r.id = :id
            """)
    Optional<AbuseReport> findWithTargets(@Param("id") Long id);

    /**
     * 같은 신고자가 같은 대상을 이미 접수해 두었는지.
     * PENDING 만 본다 — 관리자가 처리(ACCEPTED/REJECTED)한 뒤의 재신고는 새 사안으로 받는다.
     */
    boolean existsByReporterIdAndTargetPostIdAndStatus(
            Long reporterId, Long targetPostId, AbuseReport.Status status);

    boolean existsByReporterIdAndTargetCommentIdAndStatus(
            Long reporterId, Long targetCommentId, AbuseReport.Status status);

    boolean existsByReporterIdAndTargetUserIdAndStatus(
            Long reporterId, Long targetUserId, AbuseReport.Status status);
}
