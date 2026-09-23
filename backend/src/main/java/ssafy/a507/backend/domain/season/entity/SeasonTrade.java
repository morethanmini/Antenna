package ssafy.a507.backend.domain.season.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 체결 내역. append-only이며 수정·삭제하지 않는다. */
@Entity
@Table(name = "season_trades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonTrade {

    public enum Side {
        BUY,
        SELL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private SeasonParticipant participant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticker_id", nullable = false)
    private SeasonTicker ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Side side;

    @Column(nullable = false)
    private int qty;

    /** 체결가는 그 게임일 종가다. 슬리피지를 두지 않는다. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "game_day", nullable = false)
    private int gameDay;

    /**
     * 위탁수수료. 매수·매도 양쪽에 붙는다. 요율이 아니라 <b>금액</b>을 박제한다 —
     * 성적 계산이 이 값을 그대로 읽으므로 나중에 요율을 바꿔도 지난 회차는 흔들리지 않는다.
     *
     * <p>수수료가 없던 때(2026-09-23 이전)의 체결은 NULL 이다. {@code season_trades} 는
     * append-only 트리거가 걸려 있어 백필할 수 없고, 백필해서도 안 된다 — 그때는 실제로
     * 무료였다. 읽는 쪽은 {@link #cost()} 로 NULL 을 0 으로 본다.
     */
    @Column(precision = 14, scale = 2)
    private BigDecimal fee;

    /** 증권거래세. <b>매도에만</b> 붙는다. 매수는 0, 수수료가 없던 때의 체결은 NULL 이다. */
    @Column(precision = 14, scale = 2)
    private BigDecimal tax;

    /**
     * 실현손익 = (체결가 − 평단) × 수량 − 왕복 비용. 매도에만 있고 매수는 NULL 이다(ERD v0.9).
     * 체결 시점의 평단으로 굳혀 둔다 — 포지션이 지워진 뒤에는 다시 구할 수 없다.
     *
     * <p><b>순액이다</b>(2026-09-23). 이 매도에 든 수수료·거래세와, 판 수량이 살 때 물었던
     * 수수료(평단 × 수량 × 요율)를 모두 뺀다. 승률·손익비·배지가 이 값의 부호를 보므로
     * 총액으로는 손해인데 승리로 세지 않으려면 여기서 빼 두어야 한다. 전량을 정리하면
     * 실현손익의 합이 예수금의 변화와 정확히 같아진다.
     */
    @Column(name = "realized_pnl", precision = 14, scale = 2)
    private BigDecimal realizedPnl;

    /** 요청 시각. 같은 게임일 안의 순서를 가른다. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static SeasonTrade of(
            SeasonParticipant participant,
            SeasonTicker ticker,
            Side side,
            int qty,
            BigDecimal price,
            int gameDay,
            BigDecimal fee,
            BigDecimal tax,
            BigDecimal realizedPnl) {
        SeasonTrade t = new SeasonTrade();
        t.participant = participant;
        t.ticker = ticker;
        t.side = side;
        t.qty = qty;
        t.price = price;
        t.gameDay = gameDay;
        t.fee = fee;
        t.tax = tax;
        t.realizedPnl = realizedPnl;
        return t;
    }

    /** 체결 대금. 비용을 빼기 전의 총액이다 — 화면의 "금액" 도 이 값이다. */
    public BigDecimal amount() {
        return price.multiply(BigDecimal.valueOf(qty));
    }

    /** 이 체결에 든 비용(수수료 + 거래세). 수수료가 없던 때의 체결은 0 이다. */
    public BigDecimal cost() {
        return nz(fee).add(nz(tax));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
