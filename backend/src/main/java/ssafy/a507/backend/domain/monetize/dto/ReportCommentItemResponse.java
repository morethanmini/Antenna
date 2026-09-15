package ssafy.a507.backend.domain.monetize.dto;

import java.time.Instant;
import ssafy.a507.backend.domain.community.dto.AuthorResponse;

/**
 * 댓글 목록의 한 건.
 *
 * <p>{@code liked} 는 <b>요청한 사람 기준</b>이다. 목록 한 페이지의 댓글 id 를 한 번에 조회해
 * 채운다 — 댓글마다 묻지 않는다.
 */
public record ReportCommentItemResponse(
        Long id,
        String body,
        AuthorResponse author,
        long likeCount,
        boolean liked,
        Instant createdAt) {
}
