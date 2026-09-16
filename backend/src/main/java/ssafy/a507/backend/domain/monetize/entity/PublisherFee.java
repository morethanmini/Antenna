package ssafy.a507.backend.domain.monetize.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import ssafy.a507.backend.domain.account.entity.User;

/** 구독료 이력. 현재가는 최신 행이고 기존 구독은 만료까지 옛 가격을 쓴다. */
@Entity
@Table(name = "publisher_fees")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublisherFee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publisher_id", nullable = false)
    private User publisher;

    @Column(nullable = false, precision = 30, scale = 0)
    private BigInteger fee;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    /** 변경 시각. 구독자 알림 발송 근거다. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 구독료 한 줄 추가 (ANT-TOKEN-05). setter 를 두지 않는 이유는 이 표가 쌓는 표이기 때문이다 —
     * 옛 행을 고치면 구독자가 본 가격 이력이 사라진다.
     *
     * <p>{@code effectiveFrom} 을 인자로 받는 것은 예약 변경 여지를 남기려는 것이 아니라, 호출부가
     * 쓰는 "지금"과 저장되는 발효 시각이 같은 값이어야 응답의 {@code effectiveFrom} 과 이력 첫 줄이
     * 어긋나지 않기 때문이다.
     */
    public static PublisherFee of(User publisher, BigInteger fee, Instant effectiveFrom) {
        PublisherFee row = new PublisherFee();
        row.publisher = publisher;
        row.fee = fee;
        row.effectiveFrom = effectiveFrom;
        return row;
    }
}
