package ssafy.a507.backend.domain.community.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.domain.community.dto.AdminModerationDtos.AbuseReportHandleRequest;
import ssafy.a507.backend.domain.community.dto.AdminModerationDtos.AbuseReportListResponse;
import ssafy.a507.backend.domain.community.dto.AdminModerationDtos.ContentStatusUpdateRequest;
import ssafy.a507.backend.domain.community.entity.AbuseReport;
import ssafy.a507.backend.domain.community.service.AdminModerationService;

/**
 * 관리자 신고 처리 · 글/댓글 블라인드 (ANT-ADMIN-01).
 *
 * <p>{@code EventAdminController} 와 같은 자리 규칙을 따른다 — 관리자 컨트롤러라고 {@code domain/admin}
 * 을 새로 파지 않고 <b>그 도메인 안에</b> 둔다. 신고와 글·댓글은 커뮤니티 도메인의 것이다.
 *
 * <p>권한 검사는 {@link AdminModerationService} 안에 있다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAbuseReportController {

    private final AdminModerationService adminModerationService;

    /** {@code status} 를 생략하면 전체. 화면의 기본값은 PENDING 이다. */
    @GetMapping("/abuse-reports")
    public AbuseReportListResponse list(@RequestParam(required = false) AbuseReport.Status status) {
        return adminModerationService.list(status);
    }

    /** 상태만 바뀌고 돌려줄 표현이 없다 — 화면은 처리 뒤 목록을 다시 읽는다. */
    @PatchMapping("/abuse-reports/{reportId}")
    public ResponseEntity<Void> handle(
            @PathVariable Long reportId, @Valid @RequestBody AbuseReportHandleRequest request) {
        adminModerationService.handle(reportId, request);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/posts/{postId}/status")
    public ResponseEntity<Void> changePostStatus(
            @PathVariable Long postId, @Valid @RequestBody ContentStatusUpdateRequest request) {
        adminModerationService.changePostStatus(postId, request.status());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/comments/{commentId}/status")
    public ResponseEntity<Void> changeCommentStatus(
            @PathVariable Long commentId, @Valid @RequestBody ContentStatusUpdateRequest request) {
        adminModerationService.changeCommentStatus(commentId, request.status());
        return ResponseEntity.noContent().build();
    }
}
