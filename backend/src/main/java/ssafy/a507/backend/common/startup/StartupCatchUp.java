package ssafy.a507.backend.common.startup;

import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ssafy.a507.backend.domain.market.service.DailyQuoteIngestService;
import ssafy.a507.backend.domain.market.service.IndexQuoteIngestService;
import ssafy.a507.backend.domain.research.service.BriefingGenerationService;

/**
 * 기동 직후 한 번 — 밀린 시세를 받고 그 재료로 시장 브리핑까지 잇는다 (2026-09-23).
 *
 * <p><b>왜 생겼나.</b> 배포하고 화면을 열었는데 2026-09-04 브리핑이 그대로였다. 로그가 이유를
 * 말해 줬다 — {@code 기준일 2026-09-21 지수 시세가 없어 시장 브리핑을 건너뛴다}. 일봉은 09-21
 * 까지 있는데 지수가 09-18 에 멈춰 있었다. 브리핑은 그날 지수가 있어야 만들어진다. 수집 cron 은
 * 13~18시라 그때까지는 배포를 몇 번 해도 화면이 옛날이다.
 *
 * <p><b>왜 한 스레드에서 차례로 도는가.</b> 셋의 순서가 뜻을 가진다. 일봉이 기준일을 정하고,
 * 지수가 그 기준일의 재료가 되고, 브리핑이 둘을 읽는다. 각자 {@code ApplicationReadyEvent} 를
 * 듣게 하면 순서가 없어 브리핑이 빈손으로 끝나고, 그러면 다음 cron 틱까지 또 옛 글이다.
 *
 * <p>수집은 멱등이다({@code ingestPending}). 이미 받은 영업일은 건너뛰고 빠진 것만 채우므로,
 * 하루에 배포를 여러 번 해도 포털 호출이 그만큼 늘지 않는다. 브리핑도 같은 기준일·같은 세대가
 * 있으면 모델을 부르지 않는다.
 *
 * <p>기동을 막지 않도록 별도 스레드에서 돈다 — 헬스체크가 이 회차를 기다리면 배포 스크립트가
 * 포털·GMS 응답 시간만큼 늦어지고, 느린 날엔 롤백으로 읽힌다. 한 단계가 터져도 다음 단계는
 * 돈다. 지수 수집이 실패했다고 브리핑까지 멈추면, 옛 재료로라도 쓸 수 있는 글을 잃는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupCatchUp {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DailyQuoteIngestService dailyQuoteIngestService;
    private final IndexQuoteIngestService indexQuoteIngestService;
    private final BriefingGenerationService briefingGenerationService;

    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        Thread.ofVirtual().name("startup-catch-up").start(this::run);
    }

    private void run() {
        LocalDate today = LocalDate.now(KST);
        step("일봉 수집", () -> dailyQuoteIngestService.ingestPending(today));
        step("지수 수집", () -> indexQuoteIngestService.ingestPending(today));
        step("시장 브리핑", briefingGenerationService::generate);
    }

    private static void step(String name, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            log.warn("[기동] {} 회차 실패 — {}", name, e.toString());
        }
    }
}
