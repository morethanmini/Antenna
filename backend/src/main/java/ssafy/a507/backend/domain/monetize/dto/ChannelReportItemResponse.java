package ssafy.a507.backend.domain.monetize.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import ssafy.a507.backend.domain.monetize.entity.Report;

/**
 * GET /api/v1/channels/{userId}/reports 목록 한 줄 — 제목·발행일·공개 여부.
 *
 * <p>{@code visibility} 와 {@code locked} 는 다른 것을 말한다. {@code visibility} 는 리포트
 * 자체의 속성(전체 공개인가)이고 {@code locked} 는 <b>이 요청자가</b> 본문을 볼 수 있는가다.
 * 구독자에게는 {@code visibility=false, locked=false} 가 나온다 — 화면은 잠금 배지에
 * {@code locked} 를, "구독자 전용" 배지에 {@code visibility} 를 쓴다.
 *
 * <p>반응 수 · 표지 · 발췌는 채널 화면이 리포트 피드(F-01)와 같은 카드를 그리려고 싣는다
 * (2026-09-25). 규칙도 피드와 같다 — 통계는 잠겨도 싣고, 본문에서 나온 표지 · 발췌는 잠기면
 * 싣지 않는다({@link ReportFeedItemResponse} 머리말). 작성자는 싣지 않는다 — 채널 하나라 전부
 * 같은 사람이고, 화면이 이미 채널 머리에서 그린다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelReportItemResponse(
        Long id,
        String title,
        boolean visibility,
        boolean locked,
        Instant publishedAt,
        int viewCount,
        long likeCount,
        boolean liked,
        long commentCount,
        String thumbnailUrl,
        String summary) {

    public static ChannelReportItemResponse of(
            Report report, boolean locked, long likeCount, boolean liked, long commentCount) {
        String body = locked ? null : report.getBody();
        return new ChannelReportItemResponse(
                report.getId(),
                report.getTitle(),
                report.isPublic(),
                locked,
                report.getCreatedAt(),
                report.getViewCount(),
                likeCount,
                liked,
                commentCount,
                ReportFeedItemResponse.firstImage(body),
                ReportFeedItemResponse.summarize(body));
    }
}
