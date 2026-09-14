package ssafy.a507.backend.domain.chain.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.chain.dto.CommitListResponse;
import ssafy.a507.backend.domain.chain.service.CommitQueryService;

/**
 * 커밋 원장 — 커밋 목록 (화면 D-01). 회원 전용이라 사용자 id 를 읽어 인증만 확인한다.
 *
 * <p>{@code AnchorQueryController} 와 같은 규칙이다 — <b>사용자로 거르지 않는다.</b> 원장은 서비스 전체의 기록이고,
 * 남의 커밋까지 다 보여야 머클루트를 다시 접어 "조작하지 않았다" 를 확인할 수 있다.
 */
@RestController
@RequestMapping("/api/v1/commits")
@RequiredArgsConstructor
public class CommitQueryController {

    private final CommitQueryService commitQueryService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public CommitListResponse list(
            @RequestParam(required = false) Long cursor, @RequestParam(required = false) Integer size) {
        currentUserProvider.currentUserId();
        return commitQueryService.list(cursor, size);
    }
}
