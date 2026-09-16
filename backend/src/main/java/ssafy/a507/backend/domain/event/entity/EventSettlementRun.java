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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 정산 러너 실행 한 번의 기록 (ANT-EVENT-03). 관리자가 정산 버튼을 누를 때마다 한 행.
 *
 * <p>메모리가 아니라 테이블에 두는 이유(09-16 결정): 이벤트는 회차마다 열리고, 러너가 도는 중에 서버가 죽으면
 * "어디까지 갔고 왜 멈췄나" 가 메모리와 함께 사라진다. 정산의 <b>정확성</b>은 체인이 지키지만(다시 누르면 온체인 상태로
 * 이어 간다), <b>관측</b>은 이 행이 지킨다. REST 의 상태 API 가 이 행을 읽고, 202 의 {@code runId} 가 이 행의 id 다.
 *
 * <p>동시 실행 방지는 이 행이 아니라 러너의 메모리 잠금이 한다 — 서버가 죽으면 RUNNING 행이 남는데, 그걸 잠금으로 쓰면
 * 재시작 뒤 영영 못 누른다. 그런 행은 다음 실행이 {@code INTERRUPTED} 로 닫는다.
 */
@Entity
@Table(name = "event_settlement_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventSettlementRun {

    /** 러너 단계. 순서가 곧 진행 순서이고, DONE·FAILED 만 끝난 상태다. */
    public enum Phase {
        CHECKING,
        REVEALING,
        RESOLVING,
        SETTLING,
        SWEEPING,
        VERIFYING,
        DONE,
        FAILED;

        public boolean isTerminal() {
            return this == DONE || this == FAILED;
        }
    }

    /** 서버가 죽어 끝을 못 본 실행의 {@link #errorCode}. 다음 실행이 붙인다. */
    public static final String INTERRUPTED = "INTERRUPTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private PredictionEvent event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Phase phase;

    /** 현재 단계에서 끝낸 건수 / 전체 건수. reveal·settle 은 배팅 수, resolve 는 종목 수. 단계가 바뀌면 다시 센다. */
    @Column(nullable = false)
    private int done;

    @Column(nullable = false)
    private int total;

    /** FAILED 일 때만. {@code ErrorCode} 이름 · 컨트랙트 revert 이름 · {@link #INTERRUPTED}. */
    @Column(name = "error_code", length = 32)
    private String errorCode;

    /** 운영자용 원문(tx 해시 · 무엇이 빠졌는지). 응답에는 코드만 나간다(작업 테이블과 같은 규칙). */
    @Column(name = "error_message", length = 300)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public static EventSettlementRun start(PredictionEvent event) {
        EventSettlementRun r = new EventSettlementRun();
        r.event = event;
        r.phase = Phase.CHECKING;
        return r;
    }

    public void progress(Phase phase, int done, int total) {
        this.phase = phase;
        this.done = done;
        this.total = total;
    }

    public void finish() {
        this.phase = Phase.DONE;
        this.finishedAt = Instant.now();
    }

    public void fail(String errorCode, String errorMessage) {
        this.phase = Phase.FAILED;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage == null ? null : errorMessage.substring(0, Math.min(300, errorMessage.length()));
        this.finishedAt = Instant.now();
    }

    public boolean isRunning() {
        return !phase.isTerminal();
    }
}
