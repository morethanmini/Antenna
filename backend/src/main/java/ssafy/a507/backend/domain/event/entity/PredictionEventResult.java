package ssafy.a507.backend.domain.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 정산 뒤 굳힌 회차 순위 한 줄 — (이벤트, 회원) 당 하나 (ANT-EVENT-03 · REST 명세 §2.4 · §8).
 *
 * <p>정산 전 "예상 순위" 는 이 테이블을 안 쓰고 배팅을 즉석 집계한다. 정산 뒤엔 이 테이블만 읽는다 — 리더보드가 페이지마다
 * 집계 쿼리를 돌리지 않게. 굳히는 곳은 {@code EventResultService.freeze} 하나이고, 다시 굳히면 그 이벤트 행을 지우고 새로 쓴다.
 *
 * <p>순위 규칙(09-15 결정): 순이익 → HIT 건만의 평균 오차 → 첫 등록 시각 → 회원 id. 자격은 참가 종목 수 ≥ 이벤트의 min_stocks.
 * 체인에 못 올라간 배팅(FAILED)은 집계 밖 — 돈이 안 움직였다.
 */
@Entity
@Table(name = "prediction_event_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionEventResult {

    /** 복합 키. 이벤트별로 지우고 다시 쓰므로 대리 키가 필요 없다. */
    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {

        @Column(name = "event_id", nullable = false)
        private Long eventId;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        public Key(Long eventId, Long userId) {
            this.eventId = eventId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(eventId, k.eventId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, userId);
        }
    }

    @EmbeddedId
    private Key key;

    /** 참가 종목 수 — 체인에 올라가 정산된 배팅 수. */
    @Column(name = "stock_count", nullable = false)
    private short stockCount;

    /** 적중 수. 화면의 "적중 n/m" 재료. */
    @Column(name = "hit_count", nullable = false)
    private short hitCount;

    /** {@code stock_count >= min_stocks}. false 면 순위 밖(rank NULL). */
    @Column(nullable = false)
    private boolean qualified;

    /** Σstake · Σpayout · paid − staked. 정수 ANT. */
    @Column(nullable = false, precision = 30, scale = 0)
    private BigInteger staked;

    @Column(nullable = false, precision = 30, scale = 0)
    private BigInteger paid;

    @Column(nullable = false, precision = 30, scale = 0)
    private BigInteger net;

    /** HIT 예측의 error_rate 평균. HIT 이 없으면 NULL — 정렬 맨 뒤. */
    @Column(name = "avg_error", precision = 6, scale = 3)
    private BigDecimal avgError;

    /** qualified 만. 동순위 없음. */
    @Column
    private Integer rank;

    @CreationTimestamp
    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    public static PredictionEventResult of(
            Long eventId,
            Long userId,
            int stockCount,
            int hitCount,
            BigInteger staked,
            BigInteger paid,
            BigDecimal avgError,
            boolean qualified,
            Integer rank) {
        PredictionEventResult r = new PredictionEventResult();
        r.key = new Key(eventId, userId);
        r.stockCount = (short) stockCount;
        r.hitCount = (short) hitCount;
        r.staked = staked;
        r.paid = paid;
        r.net = paid.subtract(staked);
        r.avgError = avgError;
        r.qualified = qualified;
        r.rank = rank;
        return r;
    }
}
