package ssafy.a507.backend.domain.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigInteger;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * 배팅 이벤트의 배팅 한 건 — 예측과 1:1 (ANT-EVENT, 09-15 밤 결정).
 *
 * <p>배팅에 관한 것(금액·커밋·온체인 상태·정산액)은 전부 여기 두고 {@code predictions} 에는 컬럼을 더하지 않는다. 예측 자체
 * (방향·목표가·근거·기준일·만기일·판정·오차)는 기존 컬럼 그대로다. 이 행이 있으면 이벤트 예측, 없으면 무료 예측이다.
 * {@code @MapsId} 로 예측 PK 를 그대로 쓰는 것은 {@code PredictionCommit} 과 같은 방식.
 *
 * <p>{@code user} 는 {@code predictions.user_id} 의 복사다 — 순위 집계와 "종목당 1건" UNIQUE 를 조인 없이 걸기 위해.
 * 컬럼은 REST 명세 v1.1 §2.3 그대로.
 */
@Entity
@Table(
        name = "prediction_event_bets",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_event_bets_user_stock", columnNames = {"event_id", "user_id", "event_stock_id"}),
            @UniqueConstraint(name = "uq_event_bets_index", columnNames = {"event_id", "bet_index"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionEventBet {

    /**
     * PENDING(placeBet 전송 중) → ACTIVE(BetPlaced) → REVEALED(BetRevealed) → SETTLED(BetSettled) / REFUNDED(BetRefunded).
     * FAILED 는 체인에 못 올라간 것(작업이 SEND_LOST·REVERTED·TX_DROPPED 로 닫힘) — 돈이 안 움직였으니 집계 밖.
     * 전이는 인덱서 ③ 이 로그를 보고 한다. <b>체인이 진실</b>이라 FAILED 뒤에 BetPlaced 가 오면 다시 ACTIVE 가 된다.
     */
    public enum Status {
        PENDING,
        ACTIVE,
        REVEALED,
        SETTLED,
        REFUNDED,
        FAILED
    }

    @Id
    @Column(name = "prediction_id")
    private Long predictionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prediction_id")
    private Prediction prediction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private PredictionEvent event;

    /** 종목 칸. 컨트랙트 {@code stockIdx} 는 이 행의 {@code idx}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_stock_id", nullable = false)
    private PredictionEventStock eventStock;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 배팅금. 정수 ANT(decimals 0). */
    @Column(nullable = false, precision = 30, scale = 0)
    private BigInteger stake;

    /** 온체인 커밋 {@code keccak(abi.encodePacked(eventId, idx, side, noteHash))}. 리빌 때 대조용. {@code EventCommits.commitHex}. */
    @Column(name = "event_commit", nullable = false, length = 66)
    private String eventCommit;

    @Enumerated(EnumType.STRING)
    @Column(name = "bet_status", nullable = false, length = 10)
    private Status betStatus;

    /** 온체인 betId. {@code BetPlaced} 에서 채운다. 이후 로그(BetRevealed · BetSettled · BetRefunded)는 이 값으로 행을 찾는다. */
    @Column(name = "bet_index")
    private Long betIndex;

    /** 정산액. 환불 = stake, 패배 = 0. {@code BetSettled} / {@code BetRefunded} 에서. */
    @Column(precision = 30, scale = 0)
    private BigInteger payout;

    /** placeBet 작업(202 폴링용). 재전송하면 새 작업 id 로 바뀐다 — 인덱서 ⑤ 가 이 작업의 FAILED 를 보고 배팅을 FAILED 로 맞춘다. */
    @Column(name = "operation_id", length = 40)
    private String operationId;

    /** 온체인 tx 셋. 인덱서가 로그의 tx 로 채운다. */
    @Column(name = "tx_hash", length = 66)
    private String txHash;

    @Column(name = "reveal_tx_hash", length = 66)
    private String revealTxHash;

    @Column(name = "settle_tx_hash", length = 66)
    private String settleTxHash;

    /** placeBet 전송 횟수. 재시도 크론(REST)이 센다. */
    @Column(nullable = false)
    private short attempts;

    /** 등록 순 타이브레이커. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 묶음 등록(REST)이 예측·작업과 같은 트랜잭션에서 만든다. PENDING 이고 placeBet 은 트랜잭션 밖에서 나간다. */
    public static PredictionEventBet place(
            Prediction prediction,
            PredictionEvent event,
            PredictionEventStock eventStock,
            BigInteger stake,
            String eventCommit,
            String operationId) {
        PredictionEventBet b = new PredictionEventBet();
        b.prediction = prediction;
        b.event = event;
        b.eventStock = eventStock;
        b.user = prediction.getUser();
        b.stake = stake;
        b.eventCommit = eventCommit;
        b.betStatus = Status.PENDING;
        b.operationId = operationId;
        b.attempts = 1;
        return b;
    }

    /** 재시도 크론(REST) — FAILED 를 새 작업으로 다시 보낼 때. 작업 id 를 바꾸지 않으면 인덱서 ⑤ 가 옛 작업의 FAILED 를 보고 다시 FAILED 로 되돌린다. */
    public void retry(String newOperationId) {
        this.operationId = newOperationId;
        this.betStatus = Status.PENDING;
        this.attempts++;
    }

    /** 인덱서 전용 — {@code BetPlaced}. FAILED 였어도 ACTIVE 로 간다(체인이 진실 — SEND_LOST 창에서 실제로 나간 tx). */
    public void markPlaced(long betIndex, String txHash) {
        this.betIndex = betIndex;
        this.txHash = txHash;
        this.betStatus = Status.ACTIVE;
    }

    /** 인덱서 전용 — {@code BetRevealed}. */
    public void markRevealed(String txHash) {
        this.revealTxHash = txHash;
        this.betStatus = Status.REVEALED;
    }

    /** 인덱서 전용 — {@code BetSettled}(SETTLED, payout 은 0 일 수 있다) / {@code BetRefunded}(REFUNDED, payout = stake). */
    public void settle(BigInteger payout, Status status, String txHash) {
        if (status != Status.SETTLED && status != Status.REFUNDED) {
            throw new IllegalArgumentException("정산 상태가 아니다: " + status);
        }
        this.payout = payout;
        this.settleTxHash = txHash;
        this.betStatus = status;
    }

    /** 작업이 FAILED 로 닫혔다. 사유는 {@code operations.error_code} 에 있다 — 여기 복사하지 않는다(어휘를 두 곳에 두지 않으려고). */
    public void fail() {
        this.betStatus = Status.FAILED;
    }
}
