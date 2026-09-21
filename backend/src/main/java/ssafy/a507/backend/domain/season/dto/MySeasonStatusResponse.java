package ssafy.a507.backend.domain.season.dto;

import java.math.BigDecimal;
import java.util.List;
import ssafy.a507.backend.domain.season.entity.SeasonParticipant;

/**
 * GET /api/v1/seasons/{id}/me 200 응답 — 내 회차의 현황·포트폴리오.
 *
 * <p>{@code pnl} 은 총자산 − 시즌 출발 예수금, {@code pnlRate} 는 그 비율(%)이다.
 * 결과창 포트폴리오도 이 응답을 그대로 쓴다.
 *
 * <p>{@code status} 는 내 회차의 상태(ONGOING · DONE · ABANDONED)다. 주문·진행은 ONGOING 에서만 받는다(아니면
 * 409 SEASON_ATTEMPT_ENDED). <b>진행일로는 알 수 없다</b> — 건너뛰고 종료(skipRemaining)한 회차는 진행일이 마지막
 * 날로 옮겨져 있고, 마지막 날에 아직 종료하지 않은 회차도 진행일이 같다. 그래서 화면이 주문 패널을 잠글 근거로 싣는다
 * (QA 09-21 — 끝난 시즌에서 "매수 주문" 이 켜져 있었다).
 */
public record MySeasonStatusResponse(
        BigDecimal cash,
        BigDecimal totalAsset,
        BigDecimal stockValue,
        BigDecimal pnl,
        BigDecimal pnlRate,
        int currentDay,
        SeasonParticipant.Status status,
        List<MyPositionResponse> positions) {}
