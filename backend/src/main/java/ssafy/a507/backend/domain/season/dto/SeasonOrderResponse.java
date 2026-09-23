package ssafy.a507.backend.domain.season.dto;

import java.math.BigDecimal;

/**
 * POST /api/v1/seasons/{id}/orders 201 응답. 체결가는 그 게임일 종가다.
 *
 * <p>{@code fee} 는 위탁수수료(매수·매도 양쪽), {@code tax} 는 증권거래세(매도에만, 매수는 0)다.
 * 둘 다 원 단위로 떨어진다. 실제로 오간 금액은 매수면 {@code price × qty + fee},
 * 매도면 {@code price × qty − fee − tax} 다.
 */
public record SeasonOrderResponse(
        Long tradeId, BigDecimal price, int gameDay, BigDecimal fee, BigDecimal tax) {}
