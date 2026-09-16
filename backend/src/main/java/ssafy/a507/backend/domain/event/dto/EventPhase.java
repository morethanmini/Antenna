package ssafy.a507.backend.domain.event.dto;

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
}
