package ssafy.a507.backend.domain.monetize.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.monetize.dto.ChannelFeeAppliedResponse;
import ssafy.a507.backend.domain.monetize.dto.ChannelFeeResponse;
import ssafy.a507.backend.domain.monetize.dto.ChannelFeeUpdateRequest;
import ssafy.a507.backend.domain.monetize.service.ChannelFeeService;

/**
 * 내 채널 구독료 — ANT-TOKEN-05, 화면 E-04.
 *
 * <p>경로가 {@code /me/channel} 이라 {@link ChannelController}({@code /channels})와 클래스를 나눈다 —
 * 한 컨트롤러의 클래스 수준 {@code @RequestMapping} 은 하나뿐이다.
 *
 * <p>대상은 늘 <b>나</b>다. 경로에 발행자 id 가 없는 이유는 남의 구독료를 바꿀 수 있는 경로를
 * 아예 만들지 않으려는 것이다 — 소유자 검사를 잊는 실수가 들어설 자리가 없다.
 */
@RestController
@RequestMapping("/api/v1/me/channel")
@RequiredArgsConstructor
public class MyChannelController {

    private final ChannelFeeService channelFeeService;
    private final CurrentUserProvider currentUserProvider;

    /** 현재값 + 변경 이력. 한 번도 안 정했으면 {@code fee} 는 null 이고 이력은 빈 배열이다. */
    @GetMapping("/fee")
    public ChannelFeeResponse fee() {
        return channelFeeService.fee(currentUserProvider.currentUserId());
    }

    /**
     * 구독료 변경. 200 으로 적용 예정 정보를 돌려준다.
     *
     * <p>멱등키를 요구하지 않는다. 같은 값으로 두 번 들어와도 이력에 줄이 하나 더 생기고 알림이
     * 한 번 더 나갈 뿐, 돈이 두 번 나가지 않는다 — 온체인 동반 요청이 아니다.
     */
    @PutMapping("/fee")
    public ChannelFeeAppliedResponse updateFee(@Valid @RequestBody ChannelFeeUpdateRequest request) {
        return channelFeeService.update(currentUserProvider.currentUserId(), request.fee());
    }
}
