package ssafy.a507.backend.domain.community.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.community.entity.AbuseReport;
import ssafy.a507.backend.domain.community.entity.FeedPost;
import ssafy.a507.backend.domain.community.entity.PostComment;
import ssafy.a507.backend.domain.community.dto.AbuseReportCreateRequest;
import ssafy.a507.backend.domain.community.repository.AbuseReportRepository;
import ssafy.a507.backend.domain.community.repository.FeedPostRepository;
import ssafy.a507.backend.domain.community.repository.PostCommentRepository;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.entity.ReportComment;
import ssafy.a507.backend.domain.monetize.repository.ReportCommentRepository;
import ssafy.a507.backend.domain.monetize.repository.ReportRepository;

/**
 * 신고 접수. 접수까지만 하고 블라인드 처리는 하지 않는다 —
 * 대상을 BLOCKED 로 내리는 것은 관리자 몫이다(ANT-ADMIN-01).
 */
@Service
@RequiredArgsConstructor
public class AbuseReportService {

    private final AbuseReportRepository abuseReportRepository;
    private final FeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final UserRepository userRepository;
    private final ReportRepository reportRepository;
    private final ReportCommentRepository reportCommentRepository;
    private final CurrentUserProvider currentUserProvider;

    @Transactional
    public Long report(AbuseReportCreateRequest request) {
        Long reporterId = currentUserProvider.currentUserId();
        User reporter = userRepository.findById(reporterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        AbuseReport report = switch (request.targetType()) {
            case POST -> againstPost(reporter, request);
            case COMMENT -> againstComment(reporter, request);
            case USER -> againstUser(reporter, request);
            case REPORT -> againstReport(reporter, request);
            case REPORT_COMMENT -> againstReportComment(reporter, request);
        };
        return abuseReportRepository.save(report).getId();
    }

    private AbuseReport againstPost(User reporter, AbuseReportCreateRequest request) {
        FeedPost post = feedPostRepository.findById(request.targetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND, "targetId"));
        rejectSelf(reporter.getId(), post.getUser().getId());
        rejectDuplicate(abuseReportRepository.existsByReporterIdAndTargetPostIdAndStatus(
                reporter.getId(), post.getId(), AbuseReport.Status.PENDING));
        return AbuseReport.againstPost(reporter, post, request.reason(), request.detail());
    }

    private AbuseReport againstComment(User reporter, AbuseReportCreateRequest request) {
        PostComment comment = postCommentRepository.findById(request.targetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND, "targetId"));
        rejectSelf(reporter.getId(), comment.getUser().getId());
        rejectDuplicate(abuseReportRepository.existsByReporterIdAndTargetCommentIdAndStatus(
                reporter.getId(), comment.getId(), AbuseReport.Status.PENDING));
        return AbuseReport.againstComment(reporter, comment, request.reason(), request.detail());
    }

    private AbuseReport againstUser(User reporter, AbuseReportCreateRequest request) {
        User target = userRepository.findById(request.targetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND, "targetId"));
        rejectSelf(reporter.getId(), target.getId());
        rejectDuplicate(abuseReportRepository.existsByReporterIdAndTargetUserIdAndStatus(
                reporter.getId(), target.getId(), AbuseReport.Status.PENDING));
        return AbuseReport.againstUser(reporter, target, request.reason(), request.detail());
    }

    /**
     * 리포트 신고. 작성자를 함께 끌어오는 {@code findDetailById} 를 쓴다 — 자기 신고 판정이 작성자를 본다.
     *
     * <p>이미 가려진(BLOCKED) 리포트는 없는 것으로 다룬다. 화면에 보이지 않는 것을 신고할 길이 없고,
     * 받아 두면 관리자 목록에 처리할 것도 없는 신고가 쌓인다.
     *
     * <p>구독자 전용(잠긴) 리포트도 신고를 받는다 — 비구독자에게도 제목은 보이고, 제목 자체가 문제일 수 있다.
     */
    private AbuseReport againstReport(User reporter, AbuseReportCreateRequest request) {
        Report report = reportRepository.findDetailById(request.targetId())
                .filter(r -> !r.isBlocked())
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND, "targetId"));
        rejectSelf(reporter.getId(), report.getUser().getId());
        rejectDuplicate(abuseReportRepository.existsByReporterIdAndTargetReportIdAndStatus(
                reporter.getId(), report.getId(), AbuseReport.Status.PENDING));
        return AbuseReport.againstReport(reporter, report, request.reason(), request.detail());
    }

    /** 리포트 댓글 신고. 가려진 댓글은 리포트와 같은 이유로 없는 것으로 다룬다. */
    private AbuseReport againstReportComment(User reporter, AbuseReportCreateRequest request) {
        ReportComment comment = reportCommentRepository.findById(request.targetId())
                .filter(c -> !c.isBlocked())
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND, "targetId"));
        rejectSelf(reporter.getId(), comment.getUser().getId());
        rejectDuplicate(abuseReportRepository.existsByReporterIdAndTargetReportCommentIdAndStatus(
                reporter.getId(), comment.getId(), AbuseReport.Status.PENDING));
        return AbuseReport.againstReportComment(reporter, comment, request.reason(), request.detail());
    }

    private void rejectSelf(Long reporterId, Long targetOwnerId) {
        if (reporterId.equals(targetOwnerId)) {
            throw new BusinessException(ErrorCode.SELF_REPORT, "targetId");
        }
    }

    /*
     * ponytail: 이 검사와 save 사이에는 빈틈이 있다. 같은 신고가 동시에 두 건 들어오면
     * 둘 다 여기를 통과하고, 뒤엣것이 부분 유니크 인덱스(uq_abuse_reports_pending_*)에
     * 걸려 500 으로 나간다 — 409 가 맞는 자리다. 데이터는 제약이 지키므로 손상은 없다.
     * 제대로 하려면 DataIntegrityViolationException 을 제약 이름으로 골라 409 로 되돌려야
     * 하는데, 공통 핸들러가 특정 도메인의 제약 이름을 알게 된다. 실제로 부딪히면 그때 하자.
     */
    private void rejectDuplicate(boolean exists) {
        if (exists) {
            throw new BusinessException(ErrorCode.DUPLICATE_REPORT, "targetId");
        }
    }
}
