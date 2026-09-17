package ssafy.a507.backend.domain.account.controller;

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
import ssafy.a507.backend.domain.account.dto.AdminUserDtos.AdminUserListResponse;
import ssafy.a507.backend.domain.account.dto.AdminUserDtos.UserStatusUpdateRequest;
import ssafy.a507.backend.domain.account.service.AdminUserService;

/**
 * 관리자 회원 제재 (ANT-ADMIN-01).
 *
 * <p>권한 검사는 {@link AdminUserService} 안에 있다.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public AdminUserListResponse search(@RequestParam(required = false) String q) {
        return adminUserService.search(q);
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<Void> changeStatus(
            @PathVariable Long userId, @Valid @RequestBody UserStatusUpdateRequest request) {
        adminUserService.changeStatus(userId, request);
        return ResponseEntity.noContent().build();
    }
}
