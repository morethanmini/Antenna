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
import ssafy.a507.backend.domain.event.service.EventAdminService;
import ssafy.a507.backend.domain.event.service.EventEntryService;

/**
 * 이벤트 시계 — 열기 · 봉인 · 실패한 배팅 재전송 (ANT-EVENT-04·05, 작업명세 v1.2 §5).
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
 * <p>봉인과 재전송은 <b>상태를 쓰지 않는다</b> — tx 만 보내고 SEALED 전이·배팅 확정은 인덱서가 로그로
 * 한다(§0.3). 크론 셋을 따로 켜고 끄는 이유는 주기가 다르고(열기·봉인은 분, 재전송은 10분) 사고가
 * 났을 때 하나만 멈출 수 있어야 해서다.
 *
 * <p>단일 인스턴스 가정은 기존 스케줄러와 같다. 로컬은 셋 다 {@code -} 로 끈다 — 운영 릴레이어 키를
 * 공유해서 nonce 가 엉킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventClockScheduler {

    private final PredictionEventRepository events;
    private final EventAdminService eventAdminService;
    private final EventEntryService eventEntryService;

    @Scheduled(cron = "${app.event.clock-cron:-}", zone = "Asia/Seoul")
    @Transactional
    public void openDueEvents() {
        List<PredictionEvent> openable = events.findOpenable(Instant.now());
        for (PredictionEvent event : openable) {
            event.open();
            log.info("[EVENT] {} 접수 시작 — opensAt {} 도달, 기준가 완비, open tx 확인", event.getId(), event.getOpensAt());
        }
    }

    /**
     * 봉인 — {@code seals_at} 이 지난 OPEN 이벤트에 {@code seal} tx 를 보낸다.
     *
     * <p><b>{@code @Transactional} 을 붙이면 안 된다.</b> 전송이 트랜잭션 안에서 일어나고, 그건
     * {@code EventBetDispatcher} 가 거부한다. 대상 선별과 작업 생성은 서비스 안에서 짧은 트랜잭션으로
     * 처리된다.
     *
     * <p>이미 보낸 이벤트를 다시 집지 않는 것은 {@code seal_tx_hash is null} 조건이다. 인덱서가 tx 와
     * 상태를 함께 쓰므로 5초면 조건에서 빠진다.
     */
    @Scheduled(cron = "${app.event.seal-cron:-}", zone = "Asia/Seoul")
    public void sealDueEvents() {
        for (PredictionEvent event : events.findSealable(Instant.now())) {
            log.info("[EVENT] {} 접수 마감 — sealsAt {} 도달, seal 전송", event.getId(), event.getSealsAt());
            eventAdminService.sealSystem(event.getId());
        }
    }

    /**
     * 실패한 배팅 재전송 (§0.3).
     *
     * <p>사용자가 다시 누를 길이 없어서 필요하다 — 같은 종목에 두 번 걸 수 없으므로(UNIQUE · 409)
     * 한 번 FAILED 가 된 배팅은 이 크론만이 되살릴 수 있다. 창은 {@code seals_at} 까지다.
     */
    @Scheduled(cron = "${app.event.retry-cron:-}", zone = "Asia/Seoul")
    public void retryFailedBets() {
        int sent = eventEntryService.retryFailedBets();
        if (sent > 0) {
            log.info("[EVENT] 실패한 배팅 {}건 재전송", sent);
        }
    }
}
