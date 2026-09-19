package ssafy.a507.backend.domain.search.dto;

import java.math.BigDecimal;
import java.util.List;
import ssafy.a507.backend.domain.market.dto.StockSearchItem;

/**
 * GET /api/v1/search 통합 검색 (ANT-DATA-05). 타입별 상위 N건 — 부르지 않은 타입도 빈 배열로 키를 남긴다.
 * 화면이 {@code data.stocks.length} 로 칸을 그리므로 키가 없으면 렌더가 죽는다.
 */
public record SearchResponse(List<StockSearchItem> stocks, List<Channel> channels, List<ReportItem> reports) {

    /** @param hitRate 실전 전체 랭킹 스냅샷의 적중률(%, 0~100) — 랭킹 화면과 같은 숫자. 랭킹에 못 든 회원이면 null */
    public record Channel(Long userId, String nickname, BigDecimal hitRate) {}

    /** @param author 작성자 닉네임 */
    public record ReportItem(Long id, String title, String author) {}
}
