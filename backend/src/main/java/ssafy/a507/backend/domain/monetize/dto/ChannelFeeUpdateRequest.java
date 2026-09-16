package ssafy.a507.backend.domain.monetize.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * PUT /api/v1/me/channel/fee 요청 본문 (ANT-TOKEN-05, 화면 E-04).
 *
 * <p>명세가 확정한 것은 이 한 필드다 — {@code { fee: 정수 ANT 문자열 }}.
 *
 * <p><b>숫자가 아니라 문자열인 이유</b>: 금액 컬럼이 {@code numeric(30,0)} 이라 자바 {@code long} 을
 * 넘길 수 있고, 프론트도 잔액·수수료를 전부 문자열로 다룬다(ANT-TOKEN-08). 상한 검사는 서비스가
 * 한다 — 여기서 자릿수로 막으면 "몇 자리까지 되는지"가 두 곳에 적힌다.
 */
public record ChannelFeeUpdateRequest(
        @NotBlank(message = "구독료가 필요합니다.")
                @Pattern(regexp = "^[0-9]{1,30}$", message = "구독료는 정수로만 정할 수 있습니다.")
                String fee) {}
