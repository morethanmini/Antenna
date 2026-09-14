package ssafy.a507.backend.domain.season.dto;

import java.util.List;

/**
 * GET /api/v1/seasons/{id}/news 200 응답. 최근 게임일부터다.
 *
 * <p>진행일을 넘는 행은 들어 있지 않다 — 가격과 같은 커닝 차단이라 서버가 아예 내리지 않는다.
 */
public record SeasonNewsListResponse(List<SeasonNewsItem> items) {}
