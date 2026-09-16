package ssafy.a507.backend.domain.event.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * PUT /api/v1/admin/events/{eventId}/stocks/{idx}/base-price 요청 (ANT-EVENT-04).
 *
 * <p>기준가는 목요일 종가다. 이벤트를 열 때 {@code daily_quotes} 에서 복사하는데, 13:00 배치가
 * 늦거나 실패하면 비어 있다 — 접수 시작이 그 30분 뒤라 기다릴 여유가 없어 손으로 넣는 길을 둔다.
 */
public record BasePriceUpdateRequest(
        @NotNull(message = "기준가를 입력해주세요.")
                @DecimalMin(value = "0.01", message = "기준가는 0보다 커야 합니다.")
                @Digits(integer = 12, fraction = 2, message = "기준가는 소수 둘째 자리까지만 입력할 수 있습니다.")
                BigDecimal basePrice) {}
