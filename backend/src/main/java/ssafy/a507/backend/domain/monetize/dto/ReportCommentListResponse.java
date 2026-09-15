package ssafy.a507.backend.domain.monetize.dto;

import java.util.List;

/** GET /api/v1/reports/{reportId}/comments 200 응답. 오래된 순이라 커서는 id 가 커지는 방향이다. */
public record ReportCommentListResponse(
        List<ReportCommentItemResponse> items, Long nextCursor, boolean hasNext) {
}
