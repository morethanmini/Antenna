package ssafy.a507.backend.domain.prediction.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.market.entity.DailyQuote;
import ssafy.a507.backend.domain.market.repository.DailyQuoteRepository;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;
import ssafy.a507.backend.domain.prediction.dto.ChannelPredictionItemResponse;
import ssafy.a507.backend.domain.prediction.dto.ChannelPredictionListResponse;
import ssafy.a507.backend.domain.prediction.dto.PredictionDetailResponse;
import ssafy.a507.backend.domain.prediction.dto.PredictionFeedItemResponse;
import ssafy.a507.backend.domain.prediction.dto.PredictionFeedResponse;
import ssafy.a507.backend.domain.common.Track;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.entity.PredictionNote;
import ssafy.a507.backend.domain.prediction.repository.PredictionCommitRepository;
import ssafy.a507.backend.domain.prediction.repository.PredictionEvidenceRepository;
import ssafy.a507.backend.domain.prediction.repository.PredictionNoteRepository;
import ssafy.a507.backend.domain.prediction.repository.PredictionRepository;

/**
 * 남의 예측을 읽는 두 길 — 채널 예측 목록(E-02)과 예측 상세(C-03) (ANT-PRED-05).
 *
 * <p><b>공개 규칙은 필드 단위다</b>(2026-09-11 개정, ANT-PRED-07). 403 으로 통째로 막지 않는다.
 * <pre>
 *   필드                 판정 전(BASE·OPEN)   판정 후(HIT·MISS)
 *   종목                 전체                 전체
 *   방향·목표가·근거포인트  작성자·구독자          전체
 *   근거 본문(note)       작성자·구독자          작성자·구독자 (D6)
 * </pre>
 * 명세의 옛 규칙("미판정은 403")을 버린 이유: 종목 상세가 개별 예측을 뺀 뒤로 개인 예측은 채널과 상세에서만 보인다.
 * 여기서 존재까지 막으면 미판정 예측을 볼 길이 아예 사라지고, "존재는 공개 · 내용은 구독" 이라는 구독 유인도 없어진다.
 *
 * <p>근거 포인트를 방향과 같이 잠그는 이유: 포인트 종류(POSITIVE·RISK)만으로 방향이 읽힌다.
 *
 * <p>proof({@code /predictions/{id}/proof})는 미판정 403 을 유지한다 — payload 에 방향·목표가가 그대로 들어 있다.
 * 구독 판정은 {@link SubscriptionRepository#findSubscribedPublisherIds} 하나를 쓴다(ACTIVE + 기간 유효).
 */
@Service
@RequiredArgsConstructor
public class PredictionViewService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** D-day 는 장 기준이라 KST 로 센다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PredictionRepository predictions;
    private final PredictionNoteRepository notes;
    private final PredictionEvidenceRepository evidences;
    private final PredictionCommitRepository commits;
    private final DailyQuoteRepository quotes;
    private final SubscriptionRepository subscriptions;
    private final UserRepository users;
    private final CurrentUserProvider currentUserProvider;

    /** 피드 탭. 리포트 피드({@code ReportService.Scope})와 같은 어휘다 — 한 화면의 두 탭이 다른 말을 쓰면 안 된다. */
    public enum Scope {
        ALL,
        SUBSCRIBED
    }

    /**
     * 예측 피드 (화면 F-01 예측 탭). 여러 채널을 가로지르는 최신순 목록이다.
     *
     * <p>이 경로가 왜 필요했나 — 예측을 읽는 길이 "한 채널({@code /channels/{userId}/predictions})" 과
     * "내 것({@code /predictions/me})" 뿐이라, 구독 중인 채널들의 예측을 한 줄로 모을 수가 없었다.
     * 리포트 탭에서 예측을 같이 보여주려면 리포트 피드와 짝이 되는 경로가 하나 있어야 한다.
     *
     * <p>게이팅은 채널 목록과 같은 코드를 쓴다({@link #canRead}·{@link #isPending}). 다만 <b>구독 판정을 한 번에
     * 받는다</b> — 채널 목록은 채널이 하나라 판정도 하나면 됐지만, 피드는 한 페이지에 여러 작성자가 섞여
     * 줄마다 물으면 페이지 크기만큼 쿼리가 늘어난다(리포트 피드의 {@code subscribedAuthorIds} 와 같은 이유).
     *
     * <p>REAL 트랙만 낸다. REPLAY(연습 시즌) 예측은 게임일 기준이라 같은 목록에 섞으면 D-day·만기일이
     * 다른 뜻이 된다.
     */
    @Transactional(readOnly = true)
    public PredictionFeedResponse feed(
            Scope scope, MyPredictionQueryService.StatusFilter status, Long cursor, Integer size) {
        Long viewerId = currentUserProvider.currentUserId();
        int pageSize = pageSize(size);
        MyPredictionQueryService.StatusFilter filter =
                status == null ? MyPredictionQueryService.StatusFilter.ALL : status;
        Long subscriberId = scope == Scope.SUBSCRIBED ? viewerId : null;

        List<Prediction> found = predictions.findFeedPage(
                Track.REAL,
                filter.statuses,
                subscriberId,
                Subscription.Status.ACTIVE,
                Instant.now(),
                cursor,
                Limit.of(pageSize + 1));
        boolean hasNext = found.size() > pageSize;
        List<Prediction> page = hasNext ? found.subList(0, pageSize) : found;
        if (page.isEmpty()) {
            return new PredictionFeedResponse(List.of(), null, false);
        }

        Set<Long> readable = readableAuthorIds(viewerId, page);
        LocalDate today = LocalDate.now(KST);
        List<PredictionFeedItemResponse> items = page.stream()
                .map(p -> PredictionFeedItemResponse.of(
                        p, today, isPending(p) && !readable.contains(p.getUser().getId())))
                .toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new PredictionFeedResponse(items, nextCursor, hasNext);
    }

    /**
     * 이 페이지에서 내가 내용을 볼 수 있는 작성자들 — 나 자신 + 유효 구독 중인 사람.
     *
     * <p>판정이 끝난 건만 있는 페이지에서는 구독을 묻지 않는다: 그때는 잠금 자체가 없어서 답이 쓰이지 않는다.
     */
    private Set<Long> readableAuthorIds(Long viewerId, List<Prediction> page) {
        Set<Long> authorIds = new HashSet<>();
        for (Prediction p : page) {
            if (isPending(p)) {
                authorIds.add(p.getUser().getId());
            }
        }
        boolean own = authorIds.remove(viewerId);
        Set<Long> readable = new HashSet<>();
        if (own) {
            readable.add(viewerId);
        }
        if (!authorIds.isEmpty()) {
            readable.addAll(subscriptions.findSubscribedPublisherIds(
                    viewerId, authorIds, Subscription.Status.ACTIVE, Instant.now()));
        }
        return readable;
    }

    /** 채널 예측 목록. 잠긴 건도 빼지 않고 잠금 카드로 내린다. 필터 어휘는 {@code /predictions/me} 와 같다. */
    @Transactional(readOnly = true)
    public ChannelPredictionListResponse channelPredictions(
            Long userId, MyPredictionQueryService.StatusFilter status, Long cursor, Integer size) {
        if (!users.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "userId");
        }
        Long viewerId = currentUserProvider.currentUserId();
        int pageSize = pageSize(size);
        MyPredictionQueryService.StatusFilter filter =
                status == null ? MyPredictionQueryService.StatusFilter.ALL : status;

        // "이 사용자의 예측 최신순" 이라 내 목록 쿼리와 같다. 게이팅은 쿼리가 아니라 아래에서 필드로 한다.
        List<Prediction> found = predictions.findMyPage(userId, filter.statuses, cursor, Limit.of(pageSize + 1));
        boolean hasNext = found.size() > pageSize;
        List<Prediction> page = hasNext ? found.subList(0, pageSize) : found;
        if (page.isEmpty()) {
            return new ChannelPredictionListResponse(List.of(), null, false);
        }

        // 채널 하나라 구독 판정도 한 번이면 된다.
        boolean canRead = canRead(userId, viewerId);
        LocalDate today = LocalDate.now(KST);
        List<ChannelPredictionItemResponse> items = page.stream()
                .map(p -> ChannelPredictionItemResponse.of(p, today, isPending(p) && !canRead))
                .toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new ChannelPredictionListResponse(items, nextCursor, hasNext);
    }

    /** 예측 상세. 없으면 404, 있으면 누구에게나 200 — 가릴 것은 필드로 가린다. */
    @Transactional(readOnly = true)
    public PredictionDetailResponse detail(long predictionId) {
        Prediction p = predictions
                .findById(predictionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PREDICTION_NOT_FOUND, "predictionId"));
        Long viewerId = currentUserProvider.currentUserId();

        boolean canRead = canRead(p.getUser().getId(), viewerId);
        boolean locked = isPending(p) && !canRead;
        // 잠긴 값은 아예 읽지 않는다 — 읽어 두고 응답에서 빼는 것보다 새어 나갈 길이 하나 적다.
        String note = canRead ? notes.findById(predictionId).map(PredictionNote::getBody).orElse(null) : null;
        List<PredictionDetailResponse.Evidence> evidenceList = locked
                ? List.of()
                : evidences.findWithPointByPredictionId(predictionId).stream()
                        .map(e -> PredictionDetailResponse.Evidence.from(e.getPoint()))
                        .toList();
        DailyQuote lastQuote = p.getStock() == null
                ? null
                : quotes.findTopByStock_CodeOrderByTradeDateDesc(p.getStock().getCode()).orElse(null);

        return PredictionDetailResponse.of(
                p,
                LocalDate.now(KST),
                locked,
                !canRead,
                note,
                evidenceList,
                lastQuote,
                commits.findById(predictionId).orElse(null));
    }

    /** 판정 전인가. 판정 전에만 방향·목표가가 잠긴다. */
    private static boolean isPending(Prediction p) {
        return p.getStatus() == Prediction.Status.BASE || p.getStatus() == Prediction.Status.OPEN;
    }

    /** 작성자 본인이거나 그 채널을 유효하게 구독 중인가. 본인이면 구독 조회를 하지 않는다. */
    private boolean canRead(Long ownerId, Long viewerId) {
        return ownerId.equals(viewerId)
                || !subscriptions
                        .findSubscribedPublisherIds(viewerId, List.of(ownerId), Subscription.Status.ACTIVE, Instant.now())
                        .isEmpty();
    }

    private int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
