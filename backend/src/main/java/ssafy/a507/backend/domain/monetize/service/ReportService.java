package ssafy.a507.backend.domain.monetize.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.domain.account.entity.Notification;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.NotificationRepository;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.monetize.dto.ChannelReportItemResponse;
import ssafy.a507.backend.domain.monetize.dto.ChannelReportListResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportCreateRequest;
import ssafy.a507.backend.domain.monetize.dto.ReportDetailResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportFeedItemResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportFeedResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.entity.ReportComment;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.report.ReportAccess;
import ssafy.a507.backend.domain.monetize.report.ReportSeal;
import ssafy.a507.backend.domain.monetize.repository.ReportCommentRepository;
import ssafy.a507.backend.domain.monetize.repository.ReportLikeRepository;
import ssafy.a507.backend.domain.monetize.repository.ReportRepository;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;

/**
 * 리포트 발행·열람 — ANT-COMMUNITY-01.
 *
 * <p>잠금 정책은 근거 게이팅과 같다: {@code is_public=false} 인 리포트의 <b>본문</b>은 작성자
 * 본인과 ACTIVE 구독자만 본다. 제목·작성자·발행일·열람 수는 항상 공개다 — 목록이 구독 유인
 * 이라는 것이 명세의 전제다.
 *
 * <p>미구독 열람을 403 이 아니라 200 + {@code locked:true} 로 돌려주는 이유는 화면이 잠금
 * 배지와 구독 유도를 그려야 하기 때문이다(AC 명시). 403 이면 프론트가 제목조차 못 받는다.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    /** 같은 사람의 같은 리포트 열람을 다시 세기까지의 간격 */
    private static final Duration VIEW_ONCE_PER = Duration.ofHours(24);

    /** 알림 종류 키. {@code notifications.type} 은 24자 제한이다. */
    private static final String NOTIFICATION_TYPE = "REPORT_PUBLISHED";

    private final ReportRepository reportRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ReportLikeRepository reportLikeRepository;
    private final ReportCommentRepository reportCommentRepository;
    private final ReportAccess reportAccess;
    private final SignatureGuard signatureGuard;
    private final CurrentUserProvider currentUserProvider;
    private final StringRedisTemplate redis;

    /** 통합 피드 탭. */
    public enum Scope {
        ALL,
        SUBSCRIBED
    }

    /** 통합 피드 정렬. */
    public enum Sort {
        RECENT,
        POPULAR
    }

    /**
     * 리포트 발행.
     *
     * <p><b>서명을 먼저 검증한다(ANT-CHAIN-16).</b> 저장 뒤에 확인하면 실패한 발행이 행으로 남고, 그 행은
     * 삭제 API 가 없어 지울 수단이 없다. {@code verify} 는 지갑 미연동(404 가 아니라 {@code WALLET_NOT_LINKED})과
     * 서명자 불일치({@code SIGNER_MISMATCH})를 갈라 주고, nonce 도 여기서 소비된다 — 같은 서명을 두 번 쓸 수 없다.
     *
     * <p>멱등성 키와 겹치지 않는다. 같은 키로 재시도하면 컨트롤러가 최초 결과를 돌려주므로 이 메서드가
     * 다시 불리지 않고, 따라서 nonce 도 한 번만 탄다.
     */
    @Transactional
    public Long create(ReportCreateRequest request) {
        Long userId = currentUserProvider.currentUserId();
        // nonce 까지 받는다 — 검산이 서명 payload 를 재조립하는 데 쓴다(ANT-CHAIN-17).
        SignatureGuard.Verified verified = signatureGuard.verifyWithNonce(userId, request);
        User author = userRepository
                .findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        Report report = reportRepository.save(
                Report.create(author, request.title(), request.body(), request.visibility()));
        report.sign(request.signature(), verified.signerAddress(), verified.nonce());
        /* 발행 즉시 봉인한다(ANT-CHAIN-14). 다음 앵커 회차(10분)가 이 해시를 머클 트리에 얹는다.
           저장 뒤에 부르는 이유는 없다 — 해시 재료에 id 가 들어가지 않으므로 순서는 자유다. 같은
           트랜잭션 안이라 변경 감지로 UPDATE 가 나간다. */
        report.seal(ReportSeal.of(report, request.body()));
        notifySubscribers(author, report);
        return report.getId();
    }

    /**
     * 전체 리포트 통합 피드.
     *
     * <p>{@code sector} 는 받지 않는다 — 출처 컬럼이 ERD 에 없다(Jira S15P21A507-69 ⛔).
     * 파라미터가 오면 400 으로 거절하는 판단은 컨트롤러에 적어 두었다.
     */
    @Transactional(readOnly = true)
    public ReportFeedResponse feed(Scope scope, Sort sort, String cursor, Integer size) {
        Long viewerId = currentUserProvider.currentUserId();
        int pageSize = pageSize(size);
        Long subscriberId = scope == Scope.SUBSCRIBED ? viewerId : null;

        // 한 건 더 읽어 다음 페이지가 있는지 본다. count 쿼리를 따로 돌리지 않는다.
        Limit limit = Limit.of(pageSize + 1);
        Instant now = Instant.now();
        List<Report> found = sort == Sort.POPULAR
                ? reportRepository.findFeedPagePopular(
                        subscriberId,
                        Subscription.Status.ACTIVE,
                        now,
                        PopularCursor.viewCountOf(cursor),
                        PopularCursor.idOf(cursor),
                        limit)
                : reportRepository.findFeedPageRecent(
                        subscriberId, Subscription.Status.ACTIVE, now, recentCursor(cursor), limit);

        boolean hasNext = found.size() > pageSize;
        List<Report> reports = hasNext ? found.subList(0, pageSize) : found;
        if (reports.isEmpty()) {
            return new ReportFeedResponse(List.of(), null, false);
        }

        Set<Long> subscribed = reportAccess.subscribedAuthorIds(viewerId, reports);
        Social social = socialOf(viewerId, reports);
        List<ReportFeedItemResponse> items = reports.stream()
                .map(report -> ReportFeedItemResponse.of(
                        report,
                        reportAccess.isLocked(report, viewerId, subscribed),
                        social.likeCount(report.getId()),
                        social.liked(report.getId()),
                        social.commentCount(report.getId())))
                .toList();

        Report last = reports.get(reports.size() - 1);
        String nextCursor = hasNext ? encodeCursor(sort, last) : null;
        return new ReportFeedResponse(items, nextCursor, hasNext);
    }

    /** 채널 리포트 목록. 미구독자에게도 제목까지는 공개다(구독 유인). */
    @Transactional(readOnly = true)
    public ChannelReportListResponse channelReports(Long userId, Long cursor, Integer size) {
        Long viewerId = currentUserProvider.currentUserId();
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "userId");
        }

        int pageSize = pageSize(size);
        List<Report> found =
                reportRepository.findChannelPage(userId, cursor, Limit.of(pageSize + 1));
        boolean hasNext = found.size() > pageSize;
        List<Report> reports = hasNext ? found.subList(0, pageSize) : found;
        if (reports.isEmpty()) {
            return new ChannelReportListResponse(List.of(), null, false);
        }

        // 채널 하나라 구독 판정도 한 번이면 된다.
        Set<Long> subscribed = reportAccess.subscribedAuthorIds(viewerId, reports);
        List<ChannelReportItemResponse> items = reports.stream()
                .map(report -> ChannelReportItemResponse.of(
                        report, reportAccess.isLocked(report, viewerId, subscribed)))
                .toList();

        Long nextCursor = hasNext ? reports.get(reports.size() - 1).getId() : null;
        return new ChannelReportListResponse(items, nextCursor, hasNext);
    }

    /**
     * 리포트 열람. 잠기면 전문 대신 앞 3줄을 돌려준다.
     *
     * <p>읽기 전용이 아니다 — ERD 대로 이 엔드포인트가 {@code view_count} 를 증가시킨다.
     * 잠긴 미리보기도 열람으로 센다 — 목록에서 눌러 들어온 유입이고, 그것이 인기순이
     * 나타내려는 값이다.
     *
     * <p>작성자 본인의 열람은 세지 않고, <b>같은 사람은 하루에 한 번만 센다</b>(2026-09-16).
     * 전에는 열 때마다 셌는데, 목록에서 들어갔다 뒤로 갔다를 반복하면 그 수만큼 올라갔다.
     * 인당 평생 한 번으로 하지 않은 이유 — 사용자가 적을 때 그 수가 그대로 드러난다.
     * 열쇠는 (리포트, 열람자) 이고 Redis SETNX + 24시간 TTL 이다(넣었으면 오늘 첫 열람).
     * 자정 기준이 아니라 마지막으로 센 때부터 24시간이다 — 키에 날짜를 넣어 자정에 맞추는
     * 것보다 단순하고, 하루 한 번이라는 뜻은 같다.
     */
    @Transactional
    public ReportDetailResponse detail(Long reportId) {
        Long viewerId = currentUserProvider.currentUserId();
        Report report = reportRepository
                .findDetailById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND, "reportId"));

        boolean own = report.getUser().getId().equals(viewerId);
        int viewCount = report.getViewCount();
        if (!own && firstViewToday(reportId, viewerId)) {
            reportRepository.incrementViewCount(reportId);
            // UPDATE 는 영속성 컨텍스트를 거치지 않아 엔티티 값이 옛것이다. 응답에는 이번
            // 열람이 반영된 값을 담는다 — 눌러 들어왔는데 숫자가 그대로면 버그로 보인다.
            viewCount += 1;
        }

        boolean locked = reportAccess.isLocked(report, viewerId);
        Social social = socialOf(viewerId, List.of(report));
        ReportDetailResponse.Social counts = new ReportDetailResponse.Social(
                social.likeCount(reportId), social.liked(reportId), social.commentCount(reportId));
        return locked
                ? ReportDetailResponse.locked(report, viewCount, counts)
                : ReportDetailResponse.full(report, viewCount, counts);
    }

    /** 이 사람이 이 리포트를 오늘 처음 여는가. 키를 넣을 수 있었으면 처음이다 */
    private boolean firstViewToday(Long reportId, Long viewerId) {
        String key = "report:view:" + reportId + ":" + viewerId;
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", VIEW_ONCE_PER));
    }

    /** 발행 알림. 구독자가 없으면 아무것도 하지 않는다. */
    private void notifySubscribers(User author, Report report) {
        List<Long> subscriberIds = subscriptionRepository.findSubscriberIds(
                author.getId(), Subscription.Status.ACTIVE, Instant.now());
        if (subscriberIds.isEmpty()) {
            return;
        }

        // 닉네임은 nullable 이다 — NULL 이면 온보딩 미완료다(User.nickname). 그대로 이어붙이면
        // 알림 한 줄이 "null · 제목" 으로 나간다. 발행을 막을 근거는 명세에 없으므로 제목만 남긴다.
        String nickname = author.getNickname();
        // 구독자는 어느 쪽이든 받지만, 여는 것이 구독자만 볼 수 있는 글인지는 알아야 한다.
        String body = (nickname == null ? report.getTitle() : nickname + " · " + report.getTitle())
                + (report.isPublic() ? " · 공개" : " · 구독자 전용");
        String linkPath = "/reports/" + report.getId();
        List<Notification> notifications = subscriberIds.stream()
                // getReferenceById 는 프록시라 SELECT 를 내지 않는다. 알림 행에 필요한 것은
                // FK 값 뿐이므로 구독자 수만큼 User 를 적재할 이유가 없다.
                .map(subscriberId -> Notification.create(
                        userRepository.getReferenceById(subscriberId),
                        NOTIFICATION_TYPE,
                        "새 리포트가 발행되었습니다",
                        body,
                        linkPath))
                .toList();
        notificationRepository.saveAll(notifications);
    }

    /**
     * 이 페이지의 좋아요·댓글 수와 내가 누른 것 — ANT-REPORT-SOCIAL.
     *
     * <p>리포트마다 세면 목록 크기만큼 쿼리가 늘어난다. 페이지의 id 를 한 번에 넘겨 세 쿼리로
     * 끝낸다(구독 여부를 한 번에 받아오는 것과 같은 이유). 잠긴 리포트도 센다 — 통계는 늘 공개다.
     */
    private Social socialOf(Long viewerId, List<Report> reports) {
        List<Long> reportIds = reports.stream().map(Report::getId).toList();
        return new Social(
                countsById(reportLikeRepository.countByReportIds(reportIds)),
                countsById(reportCommentRepository.countByReportIds(
                        reportIds, ReportComment.Status.VISIBLE)),
                new HashSet<>(reportLikeRepository.findLikedReportIds(viewerId, reportIds)));
    }

    /** {@code [id, count]} 행들을 맵으로. 집계에 없는 리포트는 아래 조회부가 0 으로 읽는다. */
    private Map<Long, Long> countsById(Collection<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    /** 한 페이지분 반응 통계. 집계에 없는 리포트는 0 · false 다. */
    private record Social(
            Map<Long, Long> likeCounts, Map<Long, Long> commentCounts, Set<Long> likedIds) {

        long likeCount(Long reportId) {
            return likeCounts.getOrDefault(reportId, 0L);
        }

        long commentCount(Long reportId) {
            return commentCounts.getOrDefault(reportId, 0L);
        }

        boolean liked(Long reportId) {
            return likedIds.contains(reportId);
        }
    }

    private String encodeCursor(Sort sort, Report last) {
        return sort == Sort.POPULAR
                ? PopularCursor.encode(last.getViewCount(), last.getId())
                : String.valueOf(last.getId());
    }

    /** 최신순 커서 — id 하나다. */
    private Long recentCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
        }
    }

    private int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 인기순 커서 {@code "<viewCount>:<id>"}.
     *
     * <p>정렬 키(열람 수)를 커서에 담아야 하는 이유는 값이 겹치기 때문이다. 열람 수 0 인
     * 리포트가 30건이면 id 만으로는 다음 페이지의 시작점이 정해지지 않는다. 뒤에 id 를 붙여
     * 순서를 완전히 정한다.
     *
     * <p>불투명한 문자열(base64 등)로 감싸지 않았다. 값 자체가 응답에 이미 들어 있는 공개
     * 정보(viewCount)이고, 조작해도 다른 사람의 리포트가 열리지 않는다 — 잠금은 커서와
     * 무관하게 구독 상태로 판정한다.
     *
     * <p><b>정렬 키가 변하는 값이라 페이지 경계가 흔들린다.</b> 1페이지를 받은 뒤 그 안의
     * 리포트를 열면 그 리포트의 열람 수가 커서를 넘어가고, 2페이지에서는 경계를 함께 넘은
     * 다른 리포트가 건너뛰어지거나 이미 본 줄이 다시 나온다. 커서로는 못 막는다 — 막으려면
     * 순위를 스냅샷 컬럼으로 굳혀야 한다. "더 보기"가 정확한 집합을 보장하지 않는 화면이라
     * 지금은 감수한다.
     */
    private static final class PopularCursor {

        private static final String SEPARATOR = ":";

        static String encode(int viewCount, long id) {
            return viewCount + SEPARATOR + id;
        }

        /**
         * 커서의 열람 수 부분.
         *
         * <p>{@code Integer.parseInt} 로 읽는다 — long 으로 읽어 int 로 캐스팅하면 범위를 넘은
         * 값이 조용히 다른 숫자로 감싸져(예: {@code 4294967296} → {@code 0}) 엉뚱한 페이지가
         * 정답처럼 나간다. 범위 초과는 잘못된 커서이므로 400 이어야 한다.
         */
        static Integer viewCountOf(String cursor) {
            String[] parts = split(cursor);
            if (parts == null) {
                return null;
            }
            try {
                return Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
        }

        static Long idOf(String cursor) {
            String[] parts = split(cursor);
            return parts == null ? null : parseNumber(parts[1]);
        }

        private static String[] split(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            String[] parts = cursor.trim().split(SEPARATOR);
            if (parts.length != 2) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
            return parts;
        }

        private static long parseNumber(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "cursor");
            }
        }
    }
}
