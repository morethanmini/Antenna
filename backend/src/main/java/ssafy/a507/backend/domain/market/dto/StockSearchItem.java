package ssafy.a507.backend.domain.market.dto;

import java.math.BigDecimal;

/**
 * 통합 검색(GET /api/v1/search)의 종목 한 줄 (ANT-DATA-05).
 *
 * @param prevClose 그 종목의 마지막 거래일 종가 — 활성 종목만 나오므로 비지 않는다
 * @param watching 내 관심 종목인가
 */
public record StockSearchItem(String code, String name, String sector, BigDecimal prevClose, boolean watching) {}
