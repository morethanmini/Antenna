package ssafy.a507.backend.domain.monetize.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.entity.Notification;
import ssafy.a507.backend.domain.account.repository.NotificationRepository;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.account.service.NotificationGate;
import ssafy.a507.backend.domain.community.dto.AuthorResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportCommentCreateRequest;
import ssafy.a507.backend.domain.monetize.dto.ReportCommentItemResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportCommentListResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.entity.ReportComment;
import ssafy.a507.backend.domain.monetize.entity.ReportCommentLike;
import ssafy.a507.backend.domain.monetize.entity.ReportLike;
import ssafy.a507.backend.domain.monetize.report.ReportAccess;
import ssafy.a507.backend.domain.monetize.repository.ReportCommentLikeRepository;
import ssafy.a507.backend.domain.monetize.repository.ReportCommentRepository;
import ssafy.a507.backend.domain.monetize.repository.ReportLikeRepository;
import ssafy.a507.backend.domain.monetize.repository.ReportRepository;

/**
 * 리포트 좋아요·댓글·댓글 좋아요 — ANT-REPORT-SOCIAL.
 *
 * <p>모양은 커뮤니티의 {@code CommentService}(ANT-COMMUNITY-03)와 같고 규칙도 그대로 가져왔다:
 * 대댓글 없음(단층) · 좋아요 수는 {@code count(*)} · 취소는 멱등 · 멱등성 헤더는 받지 않는다
 * (명세 §1 필수 대상 8개에 없고, 좋아요는 DELETE 가 있으며 중복은 UQ 가 막는다).
 *
 * <p><b>잠긴 리포트에는 반응할 수 없다</b> — 구독자 전용 리포트에 미구독자가 좋아요·댓글을
 * 시도하면 403 {@code REPORT_FORBIDDEN} 이다. 열람이 200 + {@code locked:true} 인 것과 다른데,
 * 열람에는 보여 줄 것(제목·앞 3줄)이 있지만 반응에는 없다. 댓글은 본문에 대한 반응이고,
 * 좋아요만 허용하면 읽지 않고 누른 인기순이 된다.
 *
 * <p>좋아요는 인기순 정렬에 쓰지 않는다. {@code sort=POPULAR} 는 열람 수 기준 그대로다
 * — 정렬 키를 바꾸면 커서({@code viewCount:id})와 피드 쿼리가 함께 바뀐다(별도 티켓).
 */
@Service
@RequiredArgsConstructor
public class ReportSocialService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    /** {@code notifications.type} 은 24자 제한이다. */
    private static final String LIKE_NOTIFICATION = "REPORT_LIKE";
    private static final String COMMENT_NOTIFICATION = "REPORT_COMMENT";

    private final ReportRepository reportRepository;
    private final ReportLikeRepository reportLikeRepository;
    private final ReportCommentRepository reportCommentRepository;
    private final ReportCommentLikeRepository reportCommentLikeRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationGate notificationGate;
    private final ReportAccess reportAccess;
    private final CurrentUserProvider currentUserProvider;

    /** @return 등록 후 갱신된 좋아요 수. 화면이 재조회 없이 숫자를 바꿀 수 있다. */
    @Transactional
    public long likeReport(Long reportId) {
        Long userId = currentUserProvider.currentUserId();
        Report report = openReport(reportId, userId);

        if (reportLikeRepository.existsByReportIdAndUserId(reportId, userId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_LIKE);
        }
        User user = currentUser(userId);
        save(() -> reportLikeRepository.save(ReportLike.create(report, user)));
        notifyLike(report, user);
        return reportLikeRepository.countByReportId(reportId);
    }

    /**
     * 작성자에게 좋아요 알림. <b>(리포트, 사람)당 한 번</b>이다 — 취소했다 다시 누르는 것을 그때마다
     * 알리면 한 사람이 남의 알림함을 채울 수 있다. 이미 보낸 적이 있는지는 {@code actor} 로 본다.
     * 내 글에 내가 누른 것은 알리지 않는다.
     */
    private void notifyLike(Report report, User liker) {
        User author = report.getUser();
        if (author.getId().equals(liker.getId()) || !notificationGate.allows(author.getId(), LIKE_NOTIFICATION)) {
            return;
        }
        String linkPath = "/reports/" + report.getId();
        if (notificationRepository.existsByUser_IdAndActor_IdAndTypeAndLinkPath(
                author.getId(), liker.getId(), LIKE_NOTIFICATION, linkPath)) {
            return;
        }
        notificationRepository.save(Notification.create(
                author,
                liker,
                LIKE_NOTIFICATION,
                "리포트에 좋아요가 달렸습니다",
                actorName(liker) + " 님이 \"" + report.getTitle() + "\" 을 좋아합니다",
                linkPath));
    }

    /** 작성자에게 댓글 알림. 내가 단 댓글은 알리지 않는다. 댓글은 매번 알린다 — 매번 새 말이다. */
    private void notifyComment(Report report, User commenter, String body) {
        User author = report.getUser();
        if (author.getId().equals(commenter.getId()) || !notificationGate.allows(author.getId(), COMMENT_NOTIFICATION)) {
            return;
        }
        notificationRepository.save(Notification.create(
                author,
                commenter,
                COMMENT_NOTIFICATION,
                "리포트에 댓글이 달렸습니다",
                actorName(commenter) + " · " + body,
                "/reports/" + report.getId()));
    }

    /** 닉네임은 nullable 이다(온보딩 미완료). "null 님이" 로 나가지 않게. */
    private static String actorName(User user) {
        return user.getNickname() == null ? "누군가" : user.getNickname();
    }

    /** 없던 좋아요의 취소도 204 다. 두 번 눌러도 결과가 같은 쪽이 화면에 맞다. */
    @Transactional
    public void unlikeReport(Long reportId) {
        Long userId = currentUserProvider.currentUserId();
        openReport(reportId, userId);
        reportLikeRepository.deleteByReportIdAndUserId(reportId, userId);
    }

    @Transactional
    public Long createComment(Long reportId, ReportCommentCreateRequest request) {
        Long userId = currentUserProvider.currentUserId();
        Report report = openReport(reportId, userId);
        User author = currentUser(userId);

        ReportComment comment = ReportComment.create(report, author, request.body());
        Long id = reportCommentRepository.save(comment).getId();
        notifyComment(report, author, request.body());
        return id;
    }

    /**
     * 댓글 목록. <b>잠긴 리포트여도 읽을 수는 있다</b> — 쓰기(403)와 다르다.
     *
     * <p>미구독자에게도 제목·작성자·통계를 보여 주는 것이 구독 유인이라는 명세의 전제를 따른다.
     * 댓글이 몇 개 달렸고 어떤 이야기가 오가는지가 그 유인의 일부다. 본문은 여전히 잠겨 있다.
     */
    @Transactional(readOnly = true)
    public ReportCommentListResponse listComments(Long reportId, Long cursor, Integer size) {
        Long viewerId = currentUserProvider.currentUserId();
        existingReport(reportId);
        int pageSize = pageSize(size);

        // 한 건 더 읽어 다음 페이지가 있는지 본다(리포트 목록과 같은 방식).
        List<ReportComment> found = reportCommentRepository.findPage(
                reportId, ReportComment.Status.VISIBLE, cursor, Limit.of(pageSize + 1));
        boolean hasNext = found.size() > pageSize;
        List<ReportComment> comments = hasNext ? found.subList(0, pageSize) : found;

        if (comments.isEmpty()) {
            return new ReportCommentListResponse(List.of(), null, false);
        }

        List<Long> commentIds = comments.stream().map(ReportComment::getId).toList();
        Map<Long, Long> likeCounts =
                countsById(reportCommentLikeRepository.countByCommentIds(commentIds));
        Set<Long> liked = new HashSet<>(
                reportCommentLikeRepository.findLikedCommentIds(viewerId, commentIds));

        List<ReportCommentItemResponse> items = comments.stream()
                .map(comment -> new ReportCommentItemResponse(
                        comment.getId(),
                        comment.getBody(),
                        AuthorResponse.from(comment.getUser()),
                        likeCounts.getOrDefault(comment.getId(), 0L),
                        liked.contains(comment.getId()),
                        comment.getCreatedAt()))
                .toList();

        // 오래된 순이라 다음 커서는 마지막(가장 최근) 항목이다.
        Long nextCursor = hasNext ? comments.get(comments.size() - 1).getId() : null;
        return new ReportCommentListResponse(items, nextCursor, hasNext);
    }

    @Transactional
    public long likeComment(Long commentId) {
        Long userId = currentUserProvider.currentUserId();
        ReportComment comment = openComment(commentId, userId);

        if (reportCommentLikeRepository.existsByCommentIdAndUserId(commentId, userId)) {
            throw new BusinessException(ErrorCode.DUPLICATE_LIKE);
        }
        User user = currentUser(userId);
        save(() -> reportCommentLikeRepository.save(ReportCommentLike.create(comment, user)));
        return reportCommentLikeRepository.countByCommentId(commentId);
    }

    @Transactional
    public void unlikeComment(Long commentId) {
        Long userId = currentUserProvider.currentUserId();
        openComment(commentId, userId);
        reportCommentLikeRepository.deleteByCommentIdAndUserId(commentId, userId);
    }

    /**
     * UQ 위반도 409 로 바꾼다. 위의 exists 검사와 저장 사이에 같은 사용자의 두 번째 요청이
     * 끼어들면(따로 열린 트랜잭션) 검사를 둘 다 통과한다 — 그 창을 막는 건 DB 제약뿐이고,
     * 남은 일은 예외를 같은 응답으로 번역하는 것이다.
     */
    private void save(Runnable saving) {
        try {
            saving.run();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_LIKE);
        }
    }

    /** 반응할 수 있는 리포트인가 — 존재하고(404), 이 사람에게 잠겨 있지 않아야(403) 한다. */
    private Report openReport(Long reportId, Long viewerId) {
        Report report = existingReport(reportId);
        if (reportAccess.isLocked(report, viewerId)) {
            throw new BusinessException(ErrorCode.REPORT_FORBIDDEN, "reportId");
        }
        return report;
    }

    /**
     * 작성자까지 함께 읽는다 — 잠금 판정이 {@code report.getUser().getId()} 를 본다.
     * 프록시로 두면 판정하는 순간 SELECT 가 따로 나간다.
     */
    private Report existingReport(Long reportId) {
        return reportRepository
                .findDetailById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND, "reportId"));
    }

    /**
     * 좋아요를 누를 수 있는 댓글인가.
     *
     * <p>BLOCKED 댓글은 없는 것처럼 다룬다 — 가려진 댓글에 좋아요를 눌러 존재를 확인할 수
     * 있으면 안 되므로 403 이 아니라 404 다. 딸린 리포트의 잠금도 함께 본다: 본문을 못 보는
     * 사람이 그 댓글에는 반응할 수 있으면 리포트 쪽 규칙을 우회하는 길이 된다.
     */
    private ReportComment openComment(Long commentId, Long viewerId) {
        ReportComment comment = reportCommentRepository
                .findWithReportById(commentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMENT_NOT_FOUND, "commentId"));
        if (comment.isBlocked()) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND, "commentId");
        }
        if (reportAccess.isLocked(comment.getReport(), viewerId)) {
            throw new BusinessException(ErrorCode.REPORT_FORBIDDEN, "commentId");
        }
        return comment;
    }

    private User currentUser(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }

    /** {@code [id, count]} 행들을 맵으로. 집계에 없는 대상은 호출부가 0으로 채운다. */
    private Map<Long, Long> countsById(Collection<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    private int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
