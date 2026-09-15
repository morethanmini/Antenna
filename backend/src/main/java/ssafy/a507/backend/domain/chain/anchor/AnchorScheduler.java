package ssafy.a507.backend.domain.chain.anchor;

import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 앵커 배치 시각 (ANT-CHAIN-02) — <b>10분마다</b>, 주말·휴장 포함.
 *
 * <p>영업일 13:30 B2(기준가·판정)에 묶지 않는다(결정 A4). 앵커는 시세가 필요 없다. 실행 시각에
 * {@code anchor_batch_id} 가 NULL 인 커밋 전부가 한 배치다 — 앵커 전 구간은 그냥 DB 행이다.
 *
 * <p><b>하루 한 번(00:05)에서 10분으로 줄였다(2026-09-15).</b> 두 가지 때문이다.
 * ① 무보호 구간 — 아침에 등록한 예측이 다음 날 새벽까지 체인 밖에 있었다. 그 사이에는 DB 를 고쳐도
 * 아무도 모른다. ② 검산 화면(D-03)이 등록 직후 "다음 배치 대기" 로만 보였다.
 * 이 체인은 {@code gasPrice 0} 이라(TxSender) 회차를 늘리는 비용이 tx 건수뿐이다.
 *
 * <p><b>등록 직후 즉시 앵커로 가지 않은 이유:</b> 리프가 1개면 머클 증명이 빈 배열이라 D-03 의 ②단계가
 * 보여줄 것이 없어진다. 10분이면 여러 커밋이 한 트리에 모이면서 대기도 짧다.
 *
 * <p>cron 을 설정으로 받는 이유는 다른 배치와 같다 — 시연 때 더 짧게, 테스트에서 "-" 로 끈다.
 * 단일 인스턴스 전제(결정 C4). 인스턴스가 둘이면 같은 커밋이 두 배치로 나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnchorScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final AnchorRunner runner;

    @Scheduled(cron = "${app.chain.anchor.cron:0 */10 * * * *}", zone = "Asia/Seoul")
    public void run() {
        /* business_date 는 "어느 날 실행분인지" 다(AnchorBatch 주석). 00:05 하루 한 번이던 시절에는
           그 회차가 묶는 것이 전부 어제 등록분이라 minusDays(1) 이 맞았는데, 10분마다 도는 지금은
           방금 등록된 커밋을 묶으므로 실행 시각의 날짜가 그대로 맞다. */
        LocalDate businessDate = LocalDate.now(KST);
        // 10분마다라 info 로 남기면 하루 144줄이 "할 일 없음" 으로 찬다. 배치가 실제로 생기면
        // AnchorRunner 가 건수·루트와 함께 info 로 남긴다.
        log.debug("앵커 배치 실행 시작 (business_date={})", businessDate);
        try {
            runner.run(businessDate);
        } catch (RuntimeException e) {
            // 스케줄 스레드에서 예외가 새면 다음 회차까지 조용히 죽는다. 여기서 잡아 남긴다.
            log.error("앵커 배치 실행 실패", e);
        }
    }
}
