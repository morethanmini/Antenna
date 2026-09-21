package ssafy.a507.backend.domain.monetize.entity;

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
import java.math.BigInteger;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import ssafy.a507.backend.domain.account.entity.User;

/**
 * 구독. 근거 열람 게이팅의 판정값이다.
 *
 * <p>자기 구독 금지는 {@code @Check} 로 건다. 부분 유니크
 * (subscriber_id, publisher_id) WHERE status IN ('PENDING','ACTIVE') — 만료 후 재구독과
 * 자동 갱신 허용 — 는 JPA 애너테이션으로 표현할 수 없어 아직 DDL 에 없다.
 */
@Entity
@Table(name = "subscriptions")
@Check(name = "ck_subscriptions_no_self_subscribe", constraints = "subscriber_id <> publisher_id")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

    /**
     * PENDING 접수(전송 대기·확정 대기) · ACTIVE 확정 · EXPIRED 기간 만료 · FAILED 결제 실패.
     *
     * <p>FAILED 는 2026-09-19 에 갈라냈다. 그전에는 전송 실패도 EXPIRED 로 닫아, 한 번도 구독된 적 없는
     * 채널이 화면에 "만료됨 · 다시 구독하기" 로 보였다(QA 09-18). 만료는 "있었다가 끝났다", 실패는
     * "시작된 적이 없다" 라 같은 말로 덮을 수 없다. 게이팅·중복 검사에서는 둘 다 "구독 아님" 으로 같다.
     */
    public enum Status {
        PENDING,
        ACTIVE,
        EXPIRED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscriber_id", nullable = false)
    private User subscriber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publisher_id", nullable = false)
    private User publisher;

    /** 결제 시점 가격 박제(wei). 이후 가격이 바뀌어도 이 행은 그대로다. */
    @Column(nullable = false, precision = 30, scale = 0)
    private BigInteger fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    /** 결제 트랜잭션. 70:30 배분 확인 증빙이다. */
    @Column(name = "tx_hash", length = 66)
    private String txHash;

    @Column(name = "started_at")
    private Instant startedAt;

    /** 개시 +30일. 갱신은 기간 연장이 아니라 새 행이다. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** true면 만료 배치가 다음 주기 결제를 자동 개시한다. */
    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    /** 자동 갱신 해지 시각. 남은 기간은 유지하고 다음 주기부터 결제하지 않는다. */
    @Column(name = "canceled_at")
    private Instant canceledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** 구독 기간. 명세 §4 "인덱서 확정 시 ACTIVE, expires_at = 개시 +30일". */
    private static final int PERIOD_DAYS = 30;

    /**
     * 인덱서 ②(ANT-CHAIN-11)가 {@code Subscribed} 이벤트를 보고 부른다. PENDING → ACTIVE, 개시는 확정 시각.
     * 이미 ACTIVE 면 그대로 둔다 — 재훑기·수동 재처리에서 개시 시각이 뒤로 밀리면 안 된다.
     */
    public void activate(String txHash, Instant now) {
        if (this.status == Status.ACTIVE) {
            return;
        }
        this.status = Status.ACTIVE;
        this.txHash = txHash;
        this.startedAt = now;
        this.expiresAt = now.plus(PERIOD_DAYS, java.time.temporal.ChronoUnit.DAYS);
    }

    /**
     * 접수 (ANT-TOKEN-01). PENDING 으로 시작하고 기간은 비운다 — 개시·만료는 확정 시각 기준이라
     * {@link #activate} 가 채운다. 접수 시각으로 30일을 세면 체인이 밀린 만큼 구독이 짧아진다.
     *
     * <p>{@code fee} 는 <b>결제 시점 가격을 박제</b>한 값이다. 채널이 나중에 값을 올려도 이 구독은
     * 만료까지 이 가격이고, 자동 갱신 배치도 이 값이 아니라 그때의 현재가로 다시 결제한다.
     *
     * <p>{@code autoRenew} 는 true 로 시작한다 — 최초 구독 서명에 위임 승인이 들어가 있고(명세 §4.2),
     * 끄는 길은 따로 있다(DELETE /subscriptions/{id}/renewal, ANT-TOKEN-05).
     */
    public static Subscription request(User subscriber, User publisher, BigInteger fee) {
        Subscription s = new Subscription();
        s.subscriber = subscriber;
        s.publisher = publisher;
        s.fee = fee;
        s.status = Status.PENDING;
        s.autoRenew = true;
        return s;
    }

    /**
     * 결제가 못 나갔거나 체인에서 되돌아갔다 (ANT-TOKEN-01). 안 닫으면 영원히 PENDING 인 행이 남아
     * 중복 구독 검사에 걸려 사용자가 다시 시도할 수 없다. {@code AdBanner.reject()} 와 같은 규칙이다.
     *
     * <p>이미 ACTIVE 면 건드리지 않는다 — 전송은 실패로 보였는데 인덱서가 먼저 확정을 본 경우,
     * 여기서 내리면 결제된 구독이 닫힌다. 이미 FAILED 여도 시각을 덮지 않는다(같은 요청을 두 번 받아도
     * 결과가 같다).
     *
     * <p>2026-09-19 전에는 {@link #expire()} 가 이 일을 했다 — 실패가 "만료됨" 으로 보였다.
     */
    public void fail() {
        if (this.status == Status.ACTIVE || this.status == Status.FAILED) {
            return;
        }
        this.status = Status.FAILED;
        this.canceledAt = Instant.now();
    }

    /**
     * 기간 만료 (ANT-TOKEN-06 · 배치 B4 몫). 지금은 호출부가 없다 — 열람 판정은
     * {@code SubscriptionRepository.findSubscribedPublisherIds} 가 ACTIVE + expires_at 으로 하므로
     * 이 전이가 없어도 만료된 구독은 닫힌 것으로 읽힌다. 배치가 들어오면 여기서 상태를 맞춘다.
     *
     * <p>ACTIVE 가 아니면 건드리지 않는다 — 접수 중(PENDING)이나 실패(FAILED)를 만료로 덮으면
     * "있었다가 끝났다" 가 된다.
     */
    public void expire() {
        if (this.status != Status.ACTIVE) {
            return;
        }
        this.status = Status.EXPIRED;
        this.canceledAt = Instant.now();
    }

    /**
     * 자동 갱신 해지 (ANT-TOKEN-05). <b>구독을 끊지 않는다</b> — 상태는 ACTIVE 그대로고 만료일까지
     * 열람 권한이 유지된다. 끄는 것은 다음 주기 결제뿐이다(설계서 §4 E-03 이 못 박은 제약).
     *
     * <p>이미 꺼져 있으면 {@code canceledAt} 을 덮지 않는다. 화면에 해지 시각을 적을 때 두 번째
     * 요청이 시각을 뒤로 밀면 "언제 껐는지" 가 사라진다. 같은 요청을 두 번 보내도 결과가 같다.
     */
    public void cancelRenewal(Instant now) {
        if (!this.autoRenew) {
            return;
        }
        this.autoRenew = false;
        this.canceledAt = now;
    }

    /**
     * 자동 갱신 다시 켜기 — 해지를 번복한다(09-21 QA). 해지가 DB 값만 바꾸고 체인에는 아무것도
     * 하지 않으므로 되돌리기도 값만 되돌린다. 위임 승인이 실제로 체인에 걸리게 되면(ANT-TOKEN-06)
     * 해지는 철회가 되고 여기서 다시 서명을 받아야 한다.
     *
     * <p><b>ACTIVE 이고 만료 전일 때만.</b> {@code canceledAt} 은 {@link #fail()} · {@link #expire()}
     * 도 쓰는 칸이라, 끝난 구독에서 지우면 언제 끝났는지가 사라진다. 이미 켜져 있으면 그대로다.
     *
     * @return 켜져 있게 됐으면 true, 끝났거나 결제가 끝나지 않은 구독이면 false
     */
    public boolean resumeRenewal(Instant now) {
        if (this.status != Status.ACTIVE || this.expiresAt == null || !this.expiresAt.isAfter(now)) {
            return false;
        }
        if (!this.autoRenew) {
            this.autoRenew = true;
            this.canceledAt = null;
        }
        return true;
    }
}
