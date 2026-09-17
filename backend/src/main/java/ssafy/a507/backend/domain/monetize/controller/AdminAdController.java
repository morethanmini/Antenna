package ssafy.a507.backend.domain.monetize.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.domain.monetize.dto.AdminAdDtos.AdApproveResponse;
import ssafy.a507.backend.domain.monetize.dto.AdminAdDtos.AdHandleRequest;
import ssafy.a507.backend.domain.monetize.dto.AdminAdDtos.AdminAdListResponse;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.monetize.service.AdminAdService;

/**
 * 관리자 광고 승인 (ANT-ADMIN-03). {@code EventAdminController}·{@code AdminAbuseReportController} 와 같은 자리 규칙 —
 * 관리자 컨트롤러는 그 도메인 안에 둔다. 권한 검사는 {@link AdminAdService} 안에 있다.
 */
@RestController
@RequestMapping("/api/v1/admin/ads")
@RequiredArgsConstructor
public class AdminAdController {

    private final AdminAdService adminAdService;

    /** {@code status} 를 생략하면 전체. 화면 기본값은 PENDING(심사 대기)이다. */
    @GetMapping
    public AdminAdListResponse list(@RequestParam(required = false) AdBanner.Status status) {
        return adminAdService.list(status);
    }

    /**
     * 승인은 <b>202</b> — 소각 tx 가 뒤따르고 확정은 인덱서 몫이라 작업 id 로 폴링한다.
     * 반려는 <b>204</b> — 상태만 바뀌고 돌려줄 표현이 없다.
     */
    @PatchMapping("/{adId}")
    public ResponseEntity<AdApproveResponse> handle(
            @PathVariable Long adId, @Valid @RequestBody AdHandleRequest request) {
        if (request.action() == AdHandleRequest.Action.REJECT) {
            adminAdService.reject(adId);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.accepted().body(adminAdService.approve(adId));
    }
}
