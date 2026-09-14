package ssafy.a507.backend.domain.season.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.season.dto.BadgeListResponse;
import ssafy.a507.backend.domain.season.service.BadgeService;

/**
 * 내 배지 — ANT-SEASON-09, 화면 E-05 · G-09.
 *
 * <p>경로는 {@code /users/me/...} 인데 클래스가 season 도메인에 있다. 배지를 주는 규칙과 시점이
 * 전부 시즌 종료에 붙어 있어서다 — 계정 도메인에 두면 조회 한 줄을 위해 season 을 거꾸로 참조하게
 * 된다. 같은 관례가 이미 있다(ChannelReportController 는 monetize 에 있으면서 /channels 를 맡는다).
 */
@RestController
@RequestMapping("/api/v1/users/me/badges")
@RequiredArgsConstructor
public class BadgeController {

    private final BadgeService badgeService;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public BadgeListResponse mine() {
        return badgeService.mine(currentUser.currentUserId());
    }
}
