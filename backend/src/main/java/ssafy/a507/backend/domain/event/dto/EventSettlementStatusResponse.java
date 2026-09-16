package ssafy.a507.backend.domain.event.dto;

import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.event.entity.EventSettlementRun;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;

/**
 * GET /api/v1/admin/events/{eventId}/status 200 — 정산 진행 (ANT-EVENT-06, 작업명세 v1.2 §4).
 *
 * <p>관리자가 보는 화면이다. 세 가지에 답한다 — <b>지금 돌릴 수 있나</b>({@code reasons} 가 비었나) ·
 * <b>어디까지 갔나</b>({@code run}) · <b>배팅이 성한가</b>({@code bets}).
 *
 * <p>{@code reasons} 를 여기 두는 이유: {@code EVENT_NOT_READY} 는 코드 하나만 나르고 "무엇이
 * 빠졌는지" 를 못 싣는다({@code BusinessException} 이 공용이라 ANT-EVENT-03 이 안 고쳤다). 그 문구를
 * 읽을 자리가 없으면 관리자는 409 만 보고 무엇을 고쳐야 할지 모른다.
 *
 * <p><b>null 은 키를 남긴다.</b> 이 도메인의 null 은 "가려진 값" 이 아니라 "아직 정해지지 않은 값"
 * 이다(정산 전 배당·순위, 미입력 기준가, 안 건 종목). {@code @JsonInclude(NON_NULL)} 로 키를 빼면
 * TypeScript 에서 {@code undefined} 가 되어 {@code === null} 분기가 통째로 빠진다 — 작업명세 §4 의
 * 응답 예시도 {@code "paid": null} 처럼 키를 남겨 적었다.
 */
public record EventSettlementStatusResponse(
        Long eventId,
        PredictionEvent.Status status,
        EventPhase phase,
        boolean ready,
        /** 정산을 막고 있는 것들. 비어 있으면 돌릴 수 있다 */
        List<String> reasons,
        Run run,
        Bets bets,
        /** 순위가 굳었나. 굳힌 줄 수 */
        int frozenResults) {

    /** 마지막 실행. 한 번도 안 돌렸으면 null */
    public record Run(
            Long runId,
            EventSettlementRun.Phase phase,
            int done,
            int total,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt) {

        public static Run of(EventSettlementRun run) {
            return new Run(
                    run.getId(),
                    run.getPhase(),
                    run.getDone(),
                    run.getTotal(),
                    run.getErrorCode(),
                    run.getErrorMessage(),
                    run.getStartedAt(),
                    run.getFinishedAt());
        }
    }

    /**
     * 배팅 상태 분포. {@code pending}·{@code failed} 가 남아 있으면 정산 전에 손봐야 한다 —
     * FAILED 는 돈이 안 움직인 배팅이라 집계 밖이고, PENDING 은 아직 체인에 올라가는 중이다.
     */
    public record Bets(long total, long pending, long active, long failed, long settled) {}
}
