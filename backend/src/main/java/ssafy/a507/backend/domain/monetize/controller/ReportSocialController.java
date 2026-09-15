package ssafy.a507.backend.domain.monetize.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.domain.monetize.dto.ReportCommentCreateRequest;
import ssafy.a507.backend.domain.monetize.dto.ReportCommentCreateResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportCommentListResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportLikeResponse;
import ssafy.a507.backend.domain.monetize.service.ReportSocialService;

/**
 * 리포트 좋아요 · 댓글 · 댓글 좋아요 — ANT-REPORT-SOCIAL.
 *
 * <p>클래스 접두사가 {@code /api/v1} 까지인 이유는 경로가 두 갈래이기 때문이다 — 댓글은
 * 리포트에 딸리지만({@code /reports/{reportId}/comments}) 댓글 좋아요는 리포트를 거치지 않는다
 * ({@code /report-comments/{commentId}/like}). 화면도 댓글 id 하나만 들고 좋아요를 누른다.
 * 커뮤니티의 {@code CommentController} 와 같은 구성이다.
 *
 * <p>{@code /comments} 가 아니라 {@code /report-comments} 인 이유 — 커뮤니티의
 * {@code POST /comments/{commentId}/like} 가 이미 그 경로를 쓰고 있고, 그쪽은 {@code post_comments}
 * 의 id 다. 같은 경로에 두 시퀀스를 태우면 엉뚱한 댓글에 좋아요가 달린다.
 *
 * <p>{@code ReportController} 와 나눠 둔 이유는 한쪽이 발행·열람(리포트 그 자체)이고 다른 쪽이
 * 반응이라서다. 반응은 리포트를 수정하지 않고, 앵커 대상도 아니다.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReportSocialController {

    private final ReportSocialService reportSocialService;

    /**
     * 리포트 좋아요. 본문 없는 201 을 주지 않는다 — Content-Type 이 없어서 프론트 공용
     * 클라이언트가 JSON 파싱 실패로 처리한다({@link ReportLikeResponse} 주석).
     */
    @PostMapping("/reports/{reportId}/like")
    public ResponseEntity<ReportLikeResponse> likeReport(@PathVariable Long reportId) {
        long likeCount = reportSocialService.likeReport(reportId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ReportLikeResponse(likeCount));
    }

    @DeleteMapping("/reports/{reportId}/like")
    public ResponseEntity<Void> unlikeReport(@PathVariable Long reportId) {
        reportSocialService.unlikeReport(reportId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reports/{reportId}/comments")
    public ReportCommentListResponse listComments(
            @PathVariable Long reportId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return reportSocialService.listComments(reportId, cursor, size);
    }

    /**
     * 댓글 작성. 멱등성 키를 받지 않는다 — 명세 §1 의 필수 대상이 아니고, 재시도로 같은
     * 댓글이 두 건 남는 것은 리포트와 달리 대화 맥락에서 눈에 보이는 실수다.
     */
    @PostMapping("/reports/{reportId}/comments")
    public ResponseEntity<ReportCommentCreateResponse> createComment(
            @PathVariable Long reportId, @Valid @RequestBody ReportCommentCreateRequest request) {
        Long id = reportSocialService.createComment(reportId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ReportCommentCreateResponse(id));
    }

    @PostMapping("/report-comments/{commentId}/like")
    public ResponseEntity<ReportLikeResponse> likeComment(@PathVariable Long commentId) {
        long likeCount = reportSocialService.likeComment(commentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ReportLikeResponse(likeCount));
    }

    @DeleteMapping("/report-comments/{commentId}/like")
    public ResponseEntity<Void> unlikeComment(@PathVariable Long commentId) {
        reportSocialService.unlikeComment(commentId);
        return ResponseEntity.noContent().build();
    }
}
