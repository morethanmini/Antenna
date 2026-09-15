package ssafy.a507.backend.domain.monetize.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.domain.monetize.dto.ChannelProfileResponse;
import ssafy.a507.backend.domain.monetize.service.ChannelQueryService;

/**
 * 채널 프로필 — ANT-TOKEN-09, 화면 E-02.
 *
 * <p>{@code /channels} 아래 목록 둘({@link ChannelReportController} · 채널 예측)과 따로 둔다 — 한
 * 컨트롤러의 클래스 수준 {@code @RequestMapping} 은 하나뿐이라 경로를 나눌 수 없고, 프로필은 목록과
 * 달리 커서도 필터도 없다.
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelQueryService channelQueryService;

    /** 프로필·실적·구독료·내 구독 상태. 미구독자에게도 전부 공개다 — 가리는 것은 예측·리포트 본문이다. */
    @GetMapping("/{userId}")
    public ChannelProfileResponse profile(@PathVariable Long userId) {
        return channelQueryService.profile(userId);
    }
}
