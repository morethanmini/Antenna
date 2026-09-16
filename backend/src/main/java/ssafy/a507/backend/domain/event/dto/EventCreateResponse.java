package ssafy.a507.backend.domain.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * POST /api/v1/admin/events 202 응답 (ANT-EVENT-04, 작업명세 v1.2 §4).
 *
 * <p>202 인 이유는 온체인 {@code openEvent} 전송이 뒤따라서다. <b>R1 단계에서는 DB 행까지만 만들고
 * 전송은 {@code PoolRelayer} 가 붙은 뒤에 잇는다</b>(§0.2 ②) — 그래도 응답 모양은 바꾸지 않는다.
 * 지금 {@code operationId} 는 PENDING 인 작업을 가리키고, 전송이 붙으면 같은 작업에 tx 가 달린다.
 *
 * <p>{@code basePrice} 가 null 인 종목은 {@code daily_quotes} 에 기준일 종가가 없던 것이다.
 * 관리자가 {@code PUT …/stocks/{idx}/base-price} 로 채워야 이벤트가 열린다 — 응답에 그대로 보여
 * 무엇을 더 해야 하는지 한눈에 보이게 한다.
 */
public record EventCreateResponse(Long eventId, String operationId, List<Stock> stocks) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Stock(short idx, String stockCode, BigDecimal basePrice) {}
}
