package ssafy.a507.backend.domain.prediction.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.prediction.dto.MyPredictionListResponse;
import ssafy.a507.backend.domain.prediction.dto.PortfolioResponse;
import ssafy.a507.backend.domain.prediction.service.MyPredictionQueryService;
import ssafy.a507.backend.domain.prediction.service.PortfolioQueryService;

/**
 * 내 예측 목록·포트폴리오 — ANT-PRED-06, 화면 C-02 · C-04. 명세 §1 의 {@code Base /api/v1} 을 경로에 직접 적는다.
 *
 * <p>같은 스토리의 심리 통계(GET /stocks/{code}/sentiment)는 아직 없다. 부르는 화면이 없어서다 —
 * 종목 탐색은 행별 집계를 목록 응답으로 받는다(행마다 부르면 N+1 이다).
 */
@RestController
@RequestMapping("/api/v1/predictions")
@RequiredArgsConstructor
public class MyPredictionController {

    private final MyPredictionQueryService myPredictionQueryService;
    private final PortfolioQueryService portfolioQueryService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/me")
    public MyPredictionListResponse mine(
            @RequestParam(required = false) MyPredictionQueryService.StatusFilter status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return myPredictionQueryService.list(
                currentUserProvider.currentUserId(), status, cursor, size);
    }

    /**
     * 예측 포트폴리오(C-04) — 누적 성과·상태 분포·온체인 기록 상태.
     *
     * <p>예측이 한 건도 없어도 200 이다. 0 으로 채운 성적표는 "아직 아무것도 안 했다" 를 그대로
     * 말해 주지만, 404 는 화면이 오류로 읽는다.
     */
    @GetMapping("/me/portfolio")
    public PortfolioResponse portfolio() {
        return portfolioQueryService.mine(currentUserProvider.currentUserId());
    }
}
