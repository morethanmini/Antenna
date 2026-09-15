package ssafy.a507.backend.domain.monetize.report;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;

/**
 * 리포트 본문을 볼 수 있는가 — 잠금 판정 한 곳.
 *
 * <p>규칙은 하나다: {@code is_public=false} 인 리포트의 <b>본문</b>은 작성자 본인과 ACTIVE
 * 구독자만 본다. 제목·작성자·발행일·통계는 늘 공개다(목록이 구독 유인이라는 명세의 전제).
 *
 * <p>별도 컴포넌트로 뺀 이유 — 읽는 쪽({@code ReportService})과 반응하는 쪽
 * ({@code ReportSocialService})이 같은 판정을 쓴다. 복사해 두면 한쪽만 고쳐졌을 때
 * 구독자 전용 리포트에 미구독자가 댓글을 남기거나 좋아요를 누를 수 있게 된다 —
 * 어긋난 것이 화면에 드러나지 않는 부류의 실수다.
 */
@Component
@RequiredArgsConstructor
public class ReportAccess {

    private final SubscriptionRepository subscriptionRepository;

    /** 본인이거나 ACTIVE 구독자면 잠기지 않는다. 전체 공개 리포트는 애초에 잠기지 않는다. */
    public boolean isLocked(Report report, Long viewerId, Set<Long> subscribedAuthorIds) {
        if (report.isPublic()) {
            return false;
        }
        Long authorId = report.getUser().getId();
        return !authorId.equals(viewerId) && !subscribedAuthorIds.contains(authorId);
    }

    /** 한 건만 볼 때. 목록에서는 위 두 인자짜리를 쓴다 — 리포트마다 구독을 묻지 않기 위해서다. */
    public boolean isLocked(Report report, Long viewerId) {
        return isLocked(report, viewerId, subscribedAuthorIds(viewerId, List.of(report)));
    }

    /** 이 페이지에 등장하는 작성자 중 내가 구독 중인 사람들. 리포트마다 묻지 않고 한 번에 받는다. */
    public Set<Long> subscribedAuthorIds(Long viewerId, List<Report> reports) {
        Set<Long> authorIds = new HashSet<>();
        for (Report report : reports) {
            if (!report.isPublic()) {
                authorIds.add(report.getUser().getId());
            }
        }
        authorIds.remove(viewerId);
        if (authorIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(subscriptionRepository.findSubscribedPublisherIds(
                viewerId, authorIds, Subscription.Status.ACTIVE, Instant.now()));
    }
}
