package ssafy.a507.backend.domain.season.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * GET /api/v1/seasons/{id}/result/me 200 응답 — {@code season_results} 전체.
 * 점수·등급은 아직 채워지지 않는다. 복기는 종료 뒤 따로 만들어진다(ANT-SEASON-09, 2026-09-26) —
 * {@code review} 가 null 이고 {@code reviewPending} 이 true 면 만드는 중, false 면 만들지 못했거나
 * 서버에 키가 없는 것이다(finish 를 다시 부르면 그때 만든다).
 */
public record SeasonResultResponse(
        Long participantId,
        BigDecimal finalAsset,
        BigDecimal returnRate,
        BigDecimal benchmarkReturn,
        BigDecimal maxDrawdown,
        BigDecimal winRate,
        BigDecimal profitFactor,
        BigDecimal avgHoldingDays,
        BigDecimal score,
        String grade,
        String review,
        boolean reviewPending,
        Instant closedAt) {}
