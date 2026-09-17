package ssafy.a507.backend.domain.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 업앤다운 배팅 이벤트 한 회차 (ANT-EVENT). 규칙·일정·온체인 상태를 한 행에 둔다.
 *
 * <p>{@code id} 가 곧 온체인 {@code eventId} 다 — 별도 매핑 컬럼을 두지 않는다. 컨트랙트는 소스 하나에 회차를 eventId 차원으로
 * 쌓으므로(팩토리 없음) DB 행 하나 = 컨트랙트 안의 이벤트 하나다.
 *
 * <p>스키마 소유는 REST(ANT-EVENT-04)지만 인덱서 ③(ANT-EVENT-02)이 먼저 필요해 여기서 만들었다. 컬럼은 REST 명세 v1.1 §2.1 그대로.
 */
@Entity
@Table(
        name = "prediction_events",
        uniqueConstraints = @UniqueConstraint(name = "uq_prediction_events_slug", columnNames = "slug"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionEvent {

    /**
     * 컨트랙트 {@code Status} 와 1:1 인데 앞에 SCHEDULED 가 하나 더 있다 — 컨트랙트의 NONE 은 "아직 안 열림" 이고 DB 는 그 사이에
     * 일정·종목·기준가를 준비한다. OPEN 전이는 REST 크론(opensAt 도달 + 기준가 완비 + open tx 확인)이, 그 뒤는 인덱서가 로그로 한다.
     */
    public enum Status {
        SCHEDULED,
        OPEN,
        SEALED,
        REVEALED,
        RESOLVED,
        SETTLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 화면 주소에 쓰는 이름 — {@code /events/scalping} 의 그 칸이다.
     *
     * <p><b>왜 필요한가</b> — 프론트의 대회 화면은 슬러그로 열리는데 응답에 {@code id}(숫자)와
     * {@code title} 뿐이면 "어느 이벤트가 그 대회인가" 를 알 길이 없다. 숫자 id 를 프론트 상수로
     * 박으면 회차가 만들어진 <b>뒤에야</b> 그 값을 알 수 있어 접수 직전에 재배포해야 하고, 제목으로
     * 맞추면 한 글자만 바뀌어도 화면이 빈다.
     *
     * <p>리허설 이벤트를 따로 만들 수 있게 하는 값이기도 하다 — 슬러그가 없으면 "목록 첫 번째" 같은
     * 방법을 쓰게 되는데 그러면 리허설 회차가 사용자 화면에 뜬다.
     *
     * <p>nullable 이다. {@code ddl-auto: update} 가 행 있는 표에 기본값 없는 NOT NULL 을 못 붙이고,
     * 슬러그 없는 회차(운영 점검용)도 만들 수 있어야 한다. 대신 <b>UNIQUE</b> 다 — 같은 슬러그가 둘이면
     * 화면이 어느 쪽을 열지 갈린다.
     */
    @Column(length = 40)
    private String slug;

    /** 화면 제목. */
    @Column(nullable = false, length = 80)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    /** 접수 창. 등록 검사는 서버 시계로 이 값과 비교한다 — 온체인에는 시각 검증이 없다. */
    @Column(name = "opens_at", nullable = false)
    private Instant opensAt;

    @Column(name = "seals_at", nullable = false)
    private Instant sealsAt;

    /** 기준일(목)·판정일(월). 참가 예측의 base_date/settle_date 에 그대로 복사돼 기존 판정 배치가 그대로 판정한다. */
    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "settle_date", nullable = false)
    private LocalDate settleDate;

    /** 배팅 1건의 허용 범위(정수 ANT). 이벤트 행에 박아 두면 다음 회차에 바꿔도 코드가 안 바뀐다. 컨트랙트 openEvent 에도 같은 값이 간다. */
    @Column(name = "min_stake", nullable = false, precision = 30, scale = 0)
    private BigInteger minStake;

    @Column(name = "max_stake", nullable = false, precision = 30, scale = 0)
    private BigInteger maxStake;

    /** 첫 제출 최소 종목 수. 순위 자격(qualified)의 기준이기도 하다. */
    @Column(name = "min_stocks", nullable = false)
    private short minStocks;

    /** 온체인 상태 tx. 인덱서가 EventOpened · EventSealed 로 채운다. */
    @Column(name = "open_tx_hash", length = 66)
    private String openTxHash;

    @Column(name = "seal_tx_hash", length = 66)
    private String sealTxHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 슬러그 없는 회차. 인덱서·정산 테스트처럼 화면 주소가 필요 없는 자리가 쓴다.
     *
     * <p>슬러그를 <b>인자로 더하지 않고 오버로드로 나눈 이유</b> — 이 팩터리는 chain 쪽 테스트 여섯 곳이
     * 이미 부르고 있다. 인자를 하나 더하면 그 여섯이 전부 깨지는데, 그 파일들은 슬러그와 아무 상관이 없다.
     */
    public static PredictionEvent create(
            String title,
            Instant opensAt,
            Instant sealsAt,
            LocalDate baseDate,
            LocalDate settleDate,
            BigInteger minStake,
            BigInteger maxStake,
            short minStocks) {
        return create(null, title, opensAt, sealsAt, baseDate, settleDate, minStake, maxStake, minStocks);
    }

    /** 관리자 생성(REST). SCHEDULED 로 시작하고 온체인 openEvent 는 이 행의 id 가 생긴 뒤 트랜잭션 밖에서 보낸다. */
    public static PredictionEvent create(
            String slug,
            String title,
            Instant opensAt,
            Instant sealsAt,
            LocalDate baseDate,
            LocalDate settleDate,
            BigInteger minStake,
            BigInteger maxStake,
            short minStocks) {
        PredictionEvent e = new PredictionEvent();
        e.slug = slug;
        e.title = title;
        e.status = Status.SCHEDULED;
        e.opensAt = opensAt;
        e.sealsAt = sealsAt;
        e.baseDate = baseDate;
        e.settleDate = settleDate;
        e.minStake = minStake;
        e.maxStake = maxStake;
        e.minStocks = minStocks;
        return e;
    }

    /** SCHEDULED → OPEN. REST 크론이 세 조건(opensAt · 기준가 · open tx)을 확인하고 부른다. */
    public void open() {
        this.status = Status.OPEN;
    }

    /** 인덱서 전용 — {@code EventOpened}. 상태는 안 바꾼다(OPEN 전이는 크론 몫). tx 만 남긴다. */
    public void markOpened(String txHash) {
        this.openTxHash = txHash;
    }

    /** 인덱서 전용 — {@code EventSealed}. 체인이 진실이라 이전 상태를 따지지 않는다. */
    public void markSealed(String txHash) {
        this.status = Status.SEALED;
        this.sealTxHash = txHash;
    }

    /** 인덱서 전용 — {@code EventRevealed}. */
    public void markRevealed() {
        this.status = Status.REVEALED;
    }

    /** 인덱서 전용 — {@code EventResolved}. */
    public void markResolved() {
        this.status = Status.RESOLVED;
    }

    /** 인덱서 전용 — {@code EventSettled}. */
    public void markSettled() {
        this.status = Status.SETTLED;
    }
}
