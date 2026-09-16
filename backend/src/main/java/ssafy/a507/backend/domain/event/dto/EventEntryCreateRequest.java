package ssafy.a507.backend.domain.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * POST /api/v1/events/{eventId}/entries 요청 — 묶음 등록 (ANT-EVENT-05, 작업명세 v1.2 §4).
 *
 * <p><b>왜 묶어 받나</b> — "첫 참가는 5종목 이상" 을 등록 시점에 막으려면 다섯 개가 한 요청에
 * 있어야 하고, 서명도 한 번이면 지갑 팝업이 다섯 번 뜨지 않는다. 종목마다 서명받으면 그 사이
 * 하나만 거절해도 규칙이 깨진 채 절반만 등록된다.
 *
 * <p>여기서 잡는 건 <b>모양</b>뿐이다. 종목이 이벤트에 속하는지·이미 걸었는지·금액이 범위인지·
 * 잔액이 되는지는 서비스가 본다 — DB 와 체인을 봐야 아는 것들이다.
 */
public record EventEntryCreateRequest(
        @NotEmpty(message = "종목을 선택해주세요.")
                @Size(max = 10, message = "한 번에 10종목까지 등록할 수 있습니다.")
                List<@Valid @NotNull(message = "항목이 비어 있습니다.") Item> items,
        @NotBlank(message = "서명이 필요합니다.")
                @Pattern(regexp = "^0x[0-9a-fA-F]{130}$", message = "서명 형식이 올바르지 않습니다.")
                String signature) {

    /**
     * 한 종목.
     *
     * <p>{@code note} 는 <b>빈 문자열을 허용한다</b> — 근거는 선택이고, {@code noteHash} 는
     * {@code noteSalt} 가 있어 빈 근거여도 난수다. {@code horizon} 은 받지 않는다: 이벤트가 정한
     * 값(목→월)을 서버가 채운다.
     */
    public record Item(
            @NotBlank(message = "종목코드를 입력해주세요.")
                    @Pattern(regexp = "^\\d{6}$", message = "종목코드는 숫자 6자리입니다.")
                    String stockCode,
            @NotNull(message = "방향(UP/DOWN)을 선택해주세요.") Prediction.Direction direction,
            @NotNull(message = "목표가를 입력해주세요.")
                    @DecimalMin(value = "0.01", message = "목표가는 0보다 커야 합니다.")
                    @Digits(integer = 12, fraction = 2, message = "목표가는 소수 둘째 자리까지만 입력할 수 있습니다.")
                    BigDecimal targetPrice,
            @NotNull(message = "배팅 금액을 입력해주세요.")
                    @DecimalMin(value = "1", message = "배팅 금액은 1 이상이어야 합니다.")
                    BigInteger stake,
            @Size(max = 5000, message = "근거는 5000자까지 입력할 수 있습니다.") String note,
            @NotBlank(message = "noteSalt 가 필요합니다.")
                    @Pattern(regexp = "^[0-9a-f]{64}$", message = "noteSalt 는 소문자 64자리 hex 여야 합니다.")
                    String noteSalt,
            List<@NotNull(message = "근거 포인트 id 가 비어 있습니다.") Long> evidencePointIds) {

        /** 근거는 선택이라 null 을 빈 문자열로 접는다 — 커밋 해시 재료라 null 이 들어가면 안 된다. */
        public String noteOrEmpty() {
            return note == null ? "" : note;
        }

        public List<Long> evidencePointIdsOrEmpty() {
            return evidencePointIds == null ? List.of() : evidencePointIds;
        }
    }
}
