package ssafy.a507.backend.domain.season.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보유 포지션. 참가자·종목 조합당 1행이고 전량 매도로 수량이 0이 되면 행을 지운다. */
@Entity
@Table(
        name = "season_positions",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_season_positions_participant_ticker",
                        columnNames = {"participant_id", "ticker_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private SeasonParticipant participant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticker_id", nullable = false)
    private SeasonTicker ticker;

    @Column(nullable = false)
    private int qty;

    /** 평균 매입단가. 실현손익 계산 기준이다. */
    @Column(name = "avg_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal avgPrice;

    /**
     * 남은 수량 몫의 매수 수수료 — 이 포지션을 사며 <b>실제로 낸</b> 금액 중 아직 실현손익에서 빼지
     * 않은 부분이다(2026-09-23). 요율로 되짚지 않고 낸 금액을 들고 다닌다: 수수료를 붙이기 전에 산
     * 물량은 공짜였고(NULL), 체결마다 원 단위로 버린 합은 평단 × 수량 × 요율과 몇 원 다르다.
     */
    @Column(name = "buy_fee", precision = 14, scale = 2)
    private BigDecimal buyFee;

    /** 첫 매수. 평단은 곧 체결가다. */
    public static SeasonPosition open(
            SeasonParticipant participant, SeasonTicker ticker, int qty, BigDecimal price, BigDecimal fee) {
        SeasonPosition p = new SeasonPosition();
        p.participant = participant;
        p.ticker = ticker;
        p.qty = qty;
        p.avgPrice = price;
        p.buyFee = fee;
        return p;
    }

    /** 추가 매수. 평단은 수량 가중 평균으로 다시 구한다(소수 둘째 자리 반올림). 수수료는 평단에 얹지 않고 따로 쌓는다. */
    public void buy(int addQty, BigDecimal price, BigDecimal fee) {
        BigDecimal held = avgPrice.multiply(BigDecimal.valueOf(qty));
        BigDecimal added = price.multiply(BigDecimal.valueOf(addQty));
        this.qty += addQty;
        this.avgPrice = held.add(added).divide(BigDecimal.valueOf(qty), 2, RoundingMode.HALF_UP);
        this.buyFee = remainingBuyFee().add(fee);
    }

    /**
     * 일부·전량 매도. 평단은 그대로다 — 수량만 준다. 0 이 되면 호출부가 행을 지운다.
     *
     * @return 판 수량 몫의 매수 수수료. 수량 비례로 떼고 원 단위로 버린다 — 전량이면 남은 몫을
     *     다 가져가므로 몫의 합이 낸 금액과 같다
     */
    public BigDecimal sell(int soldQty) {
        BigDecimal remaining = remainingBuyFee();
        BigDecimal share = soldQty == qty
                ? remaining
                : remaining.multiply(BigDecimal.valueOf(soldQty))
                        .divide(BigDecimal.valueOf(qty), 0, RoundingMode.DOWN);
        this.qty -= soldQty;
        this.buyFee = remaining.subtract(share);
        return share;
    }

    private BigDecimal remainingBuyFee() {
        return buyFee == null ? BigDecimal.ZERO : buyFee;
    }
}
