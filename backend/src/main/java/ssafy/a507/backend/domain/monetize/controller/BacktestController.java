package ssafy.a507.backend.domain.monetize.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.domain.monetize.dto.BacktestResponse;
import ssafy.a507.backend.domain.monetize.service.BacktestService;

/**
 * "따라 샀다면" 백테스트 — ANT-TOKEN-03, 화면 E-02.
 *
 * <p>무료다. 지라 AC 는 "1,000 PRT 소각 · POST · 기간·초기금 파라미터" 였는데 명세가 <b>무료 · GET ·
 * 파라미터 고정</b>으로 확정했고 화면도 그렇게 만들어져 있다 — 충돌하면 명세서가 이긴다.
 * 그래서 서명도 결제도 없다.
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @GetMapping("/{userId}/backtest")
    public BacktestResponse backtest(@PathVariable Long userId) {
        return backtestService.of(userId);
    }
}
