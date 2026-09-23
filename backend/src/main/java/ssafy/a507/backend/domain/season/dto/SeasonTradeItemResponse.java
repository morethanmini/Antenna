package ssafy.a507.backend.domain.season.dto;

import java.math.BigDecimal;
import ssafy.a507.backend.domain.season.entity.SeasonTrade;

/**
 * 체결 한 건. {@code realizedPnl} 은 매도에만 있고 매수는 null 이다.
 *
 * <p>{@code amount} 는 비용을 빼기 전 체결 대금(체결가 × 수량)이다. {@code fee}(위탁수수료)와
 * {@code tax}(증권거래세 · 매도에만)는 따로 싣는다 — 화면이 "수수료·세금" 을 한 줄로 보여 준다.
 * 수수료가 없던 때(2026-09-23 이전)의 체결은 둘 다 null 이다.
 *
 * <p>{@code realizedPnl} 은 <b>왕복 비용을 뺀 순액</b>이다. 전량을 정리했다면 이 값들의 합이
 * 예수금의 변화와 같다.
 */
public record SeasonTradeItemResponse(
        Long tradeId,
        Long tickerId,
        String tickerName,
        SeasonTrade.Side side,
        int qty,
        BigDecimal price,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal tax,
        int gameDay,
        BigDecimal realizedPnl) {}
