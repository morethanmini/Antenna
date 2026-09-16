package ssafy.a507.backend.domain.event.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * POST /api/v1/admin/events 요청 (ANT-EVENT-04, 작업명세 v1.2 §4).
 *
 * <p>여기서 잡는 건 <b>모양</b>뿐이다. 종목이 실재하는지·상장 중인지·날짜 순서가 맞는지는 서비스가
 * 본다 — DB 를 봐야 아는 것들이라서다.
 *
 * <p>{@code stockCodes} 의 <b>순서가 곧 {@code idx}</b> 다(0~9). 컨트랙트의 {@code stockIdx} 와 같은
 * 번호라 순서를 바꾸면 온체인 배팅이 다른 종목을 가리킨다.
 */
public record EventCreateRequest(
        @NotBlank(message = "제목을 입력해주세요.")
                @Size(max = 80, message = "제목은 80자까지 입력할 수 있습니다.")
                String title,
        @NotNull(message = "접수 시작 시각이 필요합니다.") Instant opensAt,
        @NotNull(message = "접수 마감 시각이 필요합니다.") Instant sealsAt,
        @NotNull(message = "기준일이 필요합니다.") LocalDate baseDate,
        @NotNull(message = "판정일이 필요합니다.") LocalDate settleDate,
        @NotNull(message = "최소 배팅금이 필요합니다.")
                @DecimalMin(value = "1", message = "최소 배팅금은 1 이상이어야 합니다.")
                BigInteger minStake,
        @NotNull(message = "최대 배팅금이 필요합니다.")
                @DecimalMin(value = "1", message = "최대 배팅금은 1 이상이어야 합니다.")
                BigInteger maxStake,
        @NotNull(message = "최소 참가 종목 수가 필요합니다.")
                @Min(value = 1, message = "최소 참가 종목 수는 1 이상이어야 합니다.")
                Short minStocks,
        @NotEmpty(message = "종목을 입력해주세요.")
                List<
                                @NotBlank(message = "종목코드가 비어 있습니다.")
                                @Pattern(
                                        regexp = "^\\d{6}$",
                                        message = "종목코드는 숫자 6자리입니다.")
                                String>
                        stockCodes) {}
