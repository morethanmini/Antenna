package ssafy.a507.backend.domain.account.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.dto.UserSettingDtos.UserSettingRequest;
import ssafy.a507.backend.domain.account.dto.UserSettingDtos.UserSettingResponse;
import ssafy.a507.backend.domain.account.service.UserSettingService;

/** 환경 설정 — 명세 §회원 settings (ANT-NOTIFY-02, 화면 H-03 알림 탭). */
@RestController
@RequestMapping("/api/v1/users/me/settings")
@RequiredArgsConstructor
public class UserSettingController {

    private final UserSettingService userSettingService;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public UserSettingResponse get() {
        return userSettingService.get(currentUser.currentUserId());
    }

    @PutMapping
    public UserSettingResponse update(@Valid @RequestBody UserSettingRequest request) {
        return userSettingService.update(currentUser.currentUserId(), request);
    }
}
