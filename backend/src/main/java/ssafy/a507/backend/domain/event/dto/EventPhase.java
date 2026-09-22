package ssafy.a507.backend.domain.event.dto;

import java.time.Instant;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;

/**
 * 화면용 단계 — 여섯 갈래 {@link PredictionEvent.Status} 를 넷으로 접는다 (작업명세 v1.2 §4).
 *
 * <p>화면이 물어보는 것은 "지금 등록할 수 있나 / 결과가 나왔나" 넷뿐이다. REVEALED·RESOLVED 는
 * 정산 진행 중의 내부 단계라 사용자에게는 SEALED 와 같은 그림이다 — 그 셋을 화면에 그대로 내리면
 * 프론트가 온체인 진행 순서를 알아야 한다.
 */
public enum EventPhase {
    /** 아직 안 열렸다. 기준가가 덜 찼거나 opensAt 전 */
    BEFORE,
    /** 등록할 수 있다 */
    ENTRY,
    /** 접수는 닫혔고 결과를 기다린다(SEALED · REVEALED · RESOLVED) */
    SEALED,
    /** 정산까지 끝났다 */
    SETTLED;

    public static EventPhase of(PredictionEvent.Status status) {
        return switch (status) {
            case SCHEDULED -> BEFORE;
            case OPEN -> ENTRY;
            case SEALED, REVEALED, RESOLVED -> SEALED;
            case SETTLED -> SETTLED;
        };
    }

    /**
     * 상태에 더해 <b>마감 시각</b>도 본다 — 화면에는 이쪽을 쓴다.
     *
     * <p>{@code status} 만 보면 봉인 tx 가 늦거나 실패한 동안 회차가 계속 "등록 중" 으로 보인다.
     * 그런데 접수는 이미 닫혀 있다 — {@code EventEntryService.ensureOpen()} 이 {@code sealsAt} 을
     * 지나면 {@code EVENT_SEALED} 로 거절한다. 그래서 참가 입구만 열린 채로 남아, 누르고 들어간
     * 사람이 등록 단계에서야 닫힌 것을 알게 된다(2026-09-22 QA).
     *
     * <p>접수 창 판단을 두 곳이 다른 규칙으로 하지 않게, 표시도 서버 규칙과 같은 식을 쓴다.
     */
    public static EventPhase of(PredictionEvent event) {
        EventPhase phase = of(event.getStatus());
        if (phase == ENTRY && event.getSealsAt() != null && !Instant.now().isBefore(event.getSealsAt())) {
            return SEALED;
        }
        return phase;
    }
}
