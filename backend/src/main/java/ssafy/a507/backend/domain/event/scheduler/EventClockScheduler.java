package ssafy.a507.backend.domain.event.scheduler;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;

/**
 * 이벤트 시계 — SCHEDULED → OPEN 전이 (ANT-EVENT-04, 작업명세 v1.2 §4).
 *
 * <p><b>REST 가 직접 쓰는 유일한 상태 전이다.</b> 나머지(SEALED·REVEALED·RESOLVED·SETTLED)는 인덱서가
 * 체인 로그를 보고 한다(§0.3). 여기만 예외인 이유는 "열림"이 온체인 사건이 아니라 <b>시각</b>이라서다 —
 * 컨트랙트는 {@code openEvent} 를 받은 순간 열리지만, 서비스는 {@code opensAt} 이 돼야 연다.
 *
 * <p>여는 조건은 셋이고 전부 쿼리에서 건다({@code findOpenable}).
 *
 * <ol>
 *   <li>{@code opensAt} 도달 — 접수 창이 열렸다
 *   <li>기준가 완비 — 종목 하나라도 기준가가 비면 승패를 가릴 수 없다
 *   <li>{@code open_tx_hash} 있음 — 온체인이 먼저 열려야 배팅 전송이 실패하지 않는다.
 *       인덱서가 {@code EventOpened} 를 보고 채운다
 * </ol>
 *
 * <p>매분 돈다. 로컬은 {@code EVENT_CLOCK_CRON=-} 로 끈다 — 운영 릴레이어 키를 공유해서 nonce 가 엉킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventClockScheduler {

    private final PredictionEventRepository events;

    @Scheduled(cron = "${app.event.clock-cron:-}", zone = "Asia/Seoul")
    @Transactional
    public void openDueEvents() {
        List<PredictionEvent> openable = events.findOpenable(Instant.now());
        for (PredictionEvent event : openable) {
            event.open();
            log.info("[EVENT] {} 접수 시작 — opensAt {} 도달, 기준가 완비, open tx 확인", event.getId(), event.getOpensAt());
        }
    }
}
