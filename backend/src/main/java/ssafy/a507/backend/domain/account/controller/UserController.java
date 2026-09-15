package ssafy.a507.backend.domain.account.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.dto.UserDtos.MeResponse;
import ssafy.a507.backend.domain.account.dto.UserDtos.NicknameAvailability;
import ssafy.a507.backend.domain.account.dto.UserDtos.ProfileUpdateRequest;
import ssafy.a507.backend.domain.account.service.UserService;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;
    private final CurrentUserProvider currentUser;

    /** 세션 복원용. 새로고침 후 refresh 로 access 를 되찾은 프론트가 곧바로 부른다. */
    @GetMapping("/me")
    public MeResponse me() {
        return userService.me(currentUser.currentUserId());
    }

    /** 온보딩 입력 중 실시간 중복 검사. */
    @GetMapping("/nickname/availability")
    public NicknameAvailability availability(
            @RequestParam @NotBlank @Size(min = 2, max = 30) String nickname) {
        return new NicknameAvailability(userService.isNicknameAvailable(nickname));
    }

    /**
     * 프로필 수정 (명세 §회원). 온보딩의 닉네임 확정도 여기로 온다 — 닉네임만 실어 보낸다.
     *
     * <p>네 필드가 모두 선택이라 보낸 것만 바뀐다. 배열 둘(관심 섹터·외부 채널)은 보냈으면 전체
     * 교체다. 자세한 규칙은 {@link ProfileUpdateRequest} 머리말에 있다.
     */
    @PatchMapping("/me")
    public MeResponse updateMe(@Valid @RequestBody ProfileUpdateRequest request) {
        return userService.updateProfile(currentUser.currentUserId(), request);
    }
}
