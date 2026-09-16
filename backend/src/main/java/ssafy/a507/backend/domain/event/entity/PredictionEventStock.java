package ssafy.a507.backend.domain.event.entity;

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
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ssafy.a507.backend.domain.market.entity.Stock;

/**
 * 배팅 이벤트의 종목 한 칸 (ANT-EVENT). 컨트랙트의 {@code stockIdx} 와 서버의 종목 코드를 잇는다.
 *
 * <p>{@code idx} 가 컨트랙트 {@code stockIdx} 와 같은 번호다. 커밋 해시에 들어가는 값이라 이벤트를 연 뒤에는 바꿀 수 없다.
 * 컬럼은 REST 명세 v1.1 §2.2 그대로.
 */
@Entity
@Table(
        name = "prediction_event_stocks",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_event_stocks_idx", columnNames = {"event_id", "idx"}),
            @UniqueConstraint(name = "uq_event_stocks_code", columnNames = {"event_id", "stock_code"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionEventStock {

    /**
     * 판정 결과. 컨트랙트 값({@code WINNER_UP 0 · WINNER_DOWN 1 · WINNER_TIE 2})과의 대응은 {@link #ofChainValue} 의 switch 로
     * <b>명시</b>한다 — enum 순서(ordinal)에 기대지 않는다. 릴레이어·인덱서(chain 패키지)가 이 enum 을 그대로 쓴다(09-16 결정: 어휘를 한 곳에).
     * TIE 는 환불이다.
     */
    public enum Winner {
        UP,
        DOWN,
        TIE;

        /** 로그·조회의 uint8 → 이름. 모르는 값은 컨트랙트가 바뀐 것이라 바로 터뜨린다. */
        public static Winner ofChainValue(int value) {
            return switch (value) {
                case 0 -> UP;
                case 1 -> DOWN;
                case 2 -> TIE;
                default -> throw new IllegalArgumentException("컨트랙트 winner 값이 아니다: " + value);
            };
        }

        /** 이름 → 컨트랙트 uint8. {@link #ofChainValue} 의 역. */
        public int chainValue() {
            return switch (this) {
                case UP -> 0;
                case DOWN -> 1;
                case TIE -> 2;
            };
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private PredictionEvent event;

    /** 0부터. 컨트랙트 {@code stockIdx}. */
    @Column(nullable = false)
    private short idx;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_code", nullable = false)
    private Stock stock;

    /** 목요일 종가. 이벤트 열 때 daily_quotes 에서 복사, 없으면 관리자가 넣는다. resolveStock 의 basePrice 가 이 값 ×100 이다. */
    @Column(name = "base_price", precision = 14, scale = 2)
    private BigDecimal basePrice;

    /** 월요일 종가. 인덱서가 {@code StockResolved} 의 closePrice(×100) 를 되돌려 채운다 — 컨트랙트가 실제로 본 값이 진실. */
    @Column(name = "close_price", precision = 14, scale = 2)
    private BigDecimal closePrice;

    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private Winner winner;

    @Column(name = "resolve_tx_hash", length = 66)
    private String resolveTxHash;

    public static PredictionEventStock of(PredictionEvent event, short idx, Stock stock, BigDecimal basePrice) {
        PredictionEventStock s = new PredictionEventStock();
        s.event = event;
        s.idx = idx;
        s.stock = stock;
        s.basePrice = basePrice;
        return s;
    }

    /** 관리자 수동 입력(REST) — 배치가 목요일 종가를 못 받았을 때. 이벤트가 열린 뒤에는 REST 가 막는다. */
    public void fixBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    /** 인덱서 전용 — {@code StockResolved}. */
    public void markResolved(BigDecimal closePrice, Winner winner, String txHash) {
        this.closePrice = closePrice;
        this.winner = winner;
        this.resolveTxHash = txHash;
    }
}
