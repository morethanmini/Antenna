package ssafy.a507.backend.domain.monetize.dto;

import java.time.Instant;
import ssafy.a507.backend.domain.community.dto.AuthorResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;

/**
 * GET /api/v1/reports 목록 한 줄. 제목·작성자·통계는 항상 공개이고 본문은 실려 있지 않다 —
 * 화면은 {@code locked} 로 잠금 배지만 그린다.
 *
 * <p>{@code likeCount} · {@code commentCount} 는 <b>잠긴 리포트에도 실린다</b>. 통계가 늘 공개라는
 * 이 화면의 규칙 그대로다 — 반응이 많다는 사실 자체가 구독 유인이다. 누를 수 있는지는 다른
 * 문제이고, 그쪽은 403 {@code REPORT_FORBIDDEN} 으로 막는다(ANT-REPORT-SOCIAL).
 *
 * <p>명세의 {@code sector} 는 빠져 있다. 출처가 될 컬럼이 ERD 에 없어(Jira S15P21A507-69 의
 * ⛔ 항목) 팀 결정 전까지 구현하지 않는다. null 로라도 내리지 않는 이유는, 프론트가 "섹터가
 * 없는 리포트"와 "아직 구현되지 않은 필드"를 구분할 수 없기 때문이다.
 */
public record ReportFeedItemResponse(
        Long id,
        String title,
        AuthorResponse author,
        boolean locked,
        Instant publishedAt,
        int viewCount,
        long likeCount,
        boolean liked,
        long commentCount) {

    public static ReportFeedItemResponse of(
            Report report, boolean locked, long likeCount, boolean liked, long commentCount) {
        return new ReportFeedItemResponse(
                report.getId(),
                report.getTitle(),
                AuthorResponse.from(report.getUser()),
                locked,
                report.getCreatedAt(),
                report.getViewCount(),
                likeCount,
                liked,
                commentCount);
    }
}
