package ssafy.a507.backend.domain.community.service;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.community.dto.AdminModerationDtos.AbuseReportHandleRequest;
import ssafy.a507.backend.domain.community.dto.AdminModerationDtos.AbuseReportItem;
import ssafy.a507.backend.domain.community.dto.AdminModerationDtos.AbuseReportListResponse;
import ssafy.a507.backend.domain.community.dto.AdminModerationDtos.ContentStatusUpdateRequest.ContentStatus;
import ssafy.a507.backend.domain.community.entity.AbuseReport;
import ssafy.a507.backend.domain.community.entity.FeedPost;
import ssafy.a507.backend.domain.community.entity.PostComment;
import ssafy.a507.backend.domain.community.repository.AbuseReportRepository;
import ssafy.a507.backend.domain.community.repository.FeedPostRepository;
import ssafy.a507.backend.domain.community.repository.PostCommentRepository;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.entity.ReportComment;
import ssafy.a507.backend.domain.monetize.repository.ReportCommentRepository;
import ssafy.a507.backend.domain.monetize.repository.ReportRepository;

/**
 * 신고 처리와 글·댓글·리포트·리포트 댓글 블라인드 (ANT-ADMIN-01 · 리포트 쪽은 ANT-FE-ABUSE-REPORT).
 *
 * <p>접수는 {@link AbuseReportService} 가 하고 여기는 그 뒤를 맡는다 — 접수 서비스 주석이
 * "블라인드는 관리자 몫" 이라고 남겨 둔 자리가 여기다.
 *
 * <p><b>권한은 이 서비스 안에서 본다</b>({@code role != ADMIN → ADMIN_ONLY}). 매처나
 * {@code @PreAuthorize} 가 아닌 이유는 {@code EVENT_ADMIN_ONLY} 주석과 같다 — JWT 의 {@code role}
 * 클레임을 스프링 권한으로 바꾸는 컨버터가 없어 그 둘은 공용 {@code SecurityConfig} 신설을 부른다.
 */
@Service
@RequiredArgsConstructor
public class AdminModerationService {

    private final AbuseReportRepository abuseReportRepository;
    private final FeedPostRepository feedPostRepository;
    private final PostCommentRepository postCommentRepository;
    private final ReportRepository reportRepository;
    private final ReportCommentRepository reportCommentRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public AbuseReportListResponse list(AbuseReport.Status status) {
        requireAdmin();
        /* 상태를 안 고르면 전 상태를 넘긴다. 널을 넘기면 PostgreSQL 이 파라미터 타입을
           정하지 못해 거부한다 — 리포지토리 주석 참고. */
        List<AbuseReport.Status> statuses =
                status == null ? Arrays.asList(AbuseReport.Status.values()) : List.of(status);
        List<AbuseReportItem> items = abuseReportRepository.findForAdmin(statuses).stream()
                .map(AbuseReportItem::of)
                .toList();
        return new AbuseReportListResponse(items);
    }

    /**
     * 신고 처리.
     *
     * <p>ACCEPT 가 <b>회원을 차단하지는 않는다.</b> 신고 한 건으로 계정을 막는 것은 과하다 —
     * 회원 제재는 {@code PATCH /admin/users/{id}/status} 로 관리자가 따로 누른다.
     */
    @Transactional
    public void handle(Long reportId, AbuseReportHandleRequest request) {
        User admin = requireAdmin();

        AbuseReport report = abuseReportRepository
                .findWithTargets(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND));

        // 두 번 눌러도 두 번 반영되지 않는다. handledAt 이 덮여 "언제 처리했나" 가 흐려지는 것도 막는다.
        if (!report.isPending()) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_HANDLED);
        }

        if (request.action() == AbuseReportHandleRequest.Action.REJECT) {
            report.reject(admin);
            return;
        }

        report.accept(admin);
        if (report.getTargetPost() != null) {
            report.getTargetPost().changeStatus(FeedPost.Status.BLOCKED);
        } else if (report.getTargetComment() != null) {
            report.getTargetComment().changeStatus(PostComment.Status.BLOCKED);
        } else if (report.getTargetReport() != null) {
            // 행은 남긴다 — 봉인·앵커된 리포트라 지우면 원장 잎이 사라진다(Report.Status 주석).
            report.getTargetReport().changeStatus(Report.Status.BLOCKED);
        } else if (report.getTargetReportComment() != null) {
            report.getTargetReportComment().changeStatus(ReportComment.Status.BLOCKED);
        }
    }

    @Transactional
    public void changePostStatus(Long postId, ContentStatus status) {
        requireAdmin();
        FeedPost post = feedPostRepository
                .findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
        post.changeStatus(FeedPost.Status.valueOf(status.name()));
    }

    @Transactional
    public void changeCommentStatus(Long commentId, ContentStatus status) {
        requireAdmin();
        PostComment comment = postCommentRepository
                .findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TARGET_NOT_FOUND));
        comment.changeStatus(PostComment.Status.valueOf(status.name()));
    }

    @Transactional
    public void changeReportStatus(Long reportId, ContentStatus status) {
        requireAdmin();
        Report report = reportRepository
                .findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        report.changeStatus(Report.Status.valueOf(status.name()));
    }

    @Transactional
    public void changeReportCommentStatus(Long commentId, ContentStatus status) {
        requireAdmin();
        ReportComment comment = reportCommentRepository
                .findById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND));
        comment.changeStatus(ReportComment.Status.valueOf(status.name()));
    }

    private User requireAdmin() {
        User user = userRepository
                .findById(currentUserProvider.currentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (user.getRole() != User.Role.ADMIN) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        return user;
    }
}
