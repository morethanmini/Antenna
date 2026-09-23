package ssafy.a507.backend.domain.season.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 모의투자 매매 비용 — 위탁수수료와 증권거래세. 서버의 요율은 여기 한 곳이고, 화면이 "예상" 비용과
 * "최대" 수량을 그리려고 {@code frontend/src/api/seasonPlay.ts} 에 같은 값을 둔다 — 바꾸면 둘을 같이 고친다.
 *
 * <p><b>요율은 계산에 쓰고 결과는 체결 행에 박제한다.</b> {@code season_trades.fee}·{@code tax} 에
 * 남긴 금액이 단일 진실이고, 성적 계산({@link ssafy.a507.backend.domain.season.service.SeasonResultCalculator})은
 * 요율을 보지 않는다. 요율만 두고 계산 시점에 다시 곱하면, 요율을 한 번 바꾸는 순간 이미 차감된
 * 예수금과 재계산한 최종 자산이 어긋난다 — 예수금은 원장이고 성적표는 재생이라 둘이 같아야 한다.
 *
 * <p><b>기본값을 두지 않는다</b>({@code TokenAmountProperties} 와 같은 규칙). 값은 application.yaml
 * 한 곳에만 있고 비었거나 음수면 부팅을 멈춘다. 환경변수로도 받지 않는다 — 요율은 환경이 아니라
 * 정책이라 모든 환경이 같아야 하고 바뀌면 git 에 남아야 한다.
 *
 * <p><b>원 단위 절사.</b> 화면의 {@code won()} 이 {@code toLocaleString} 이라 소수를 그대로 그린다 —
 * 스케일 2 로 두면 "4,500.75원" 이 샌다. 버리는 방향인 것도 뜻이 있다: 실제 증권사 관행이고,
 * 계산한 비용이 이론값을 절대 넘지 않아 "최대" 수량의 경계에서 예수금이 모자라지 않는다.
 *
 * @param commissionRate 위탁수수료율. 매수·매도 양쪽에 붙는다 (0.00015 = 0.015%)
 * @param sellTaxRate 증권거래세율. <b>매도에만</b> 붙는다 (0.0015 = 0.15%)
 */
@ConfigurationProperties(prefix = "app.season.fees")
public record SeasonFeeProperties(BigDecimal commissionRate, BigDecimal sellTaxRate) {

    public SeasonFeeProperties {
        requireRate("commission-rate", commissionRate);
        requireRate("sell-tax-rate", sellTaxRate);
    }

    /** 위탁수수료. 매수·매도 모두 대금 × 요율이다. */
    public BigDecimal commission(BigDecimal amount) {
        return floorWon(amount.multiply(commissionRate));
    }

    /** 증권거래세. 매도 대금 × 요율이고 매수는 0 이다. */
    public BigDecimal sellTax(BigDecimal amount) {
        return floorWon(amount.multiply(sellTaxRate));
    }

    /** 원 단위 절사. 비용은 늘 버리는 방향이라 이론값을 넘지 않는다. */
    private static BigDecimal floorWon(BigDecimal raw) {
        return raw.setScale(0, RoundingMode.DOWN);
    }

    private static void requireRate(String key, BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalStateException(
                    "app.season.fees." + key + " 가 없거나 0~1 밖이다 — 요율은 application.yaml 에만 있고 코드 기본값이 없다");
        }
    }
}
