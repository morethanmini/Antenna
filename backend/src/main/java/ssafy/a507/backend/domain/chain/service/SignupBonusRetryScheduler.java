package ssafy.a507.backend.domain.chain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 가입 보너스 재시도 주기 (ANT-TOKEN-07) — 기본 10분마다. 새 배치 틀이 아니라 다른 배치와 같은 {@code @Scheduled} 하나다.
 *
 * <p>토큰 인덱서의 5초 루프에 얹지 않은 이유: 인덱서는 읽기 전용(릴레이어 키 불필요)이라 로컬에서도 켜 두는데 여기는 전송을
 * 한다. 로컬 .env 가 운영 릴레이어 키를 쓰므로(09-12 결정) 로컬에서는 따로 꺼야 하고, 그러려면 cron 이 분리돼야 한다.
 * 로컬은 {@code BONUS_RETRY_CRON=-} — {@code ANCHOR_CRON} 과 같은 이유다. 단일 인스턴스 전제(결정 C4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignupBonusRetryScheduler {

    private final SignupBonusService service;

    @Scheduled(cron = "${app.token.bonus-retry-cron:0 */10 * * * *}", zone = "Asia/Seoul")
    public void retry() {
        try {
            int sent = service.retryOnce();
            if (sent > 0) {
                log.info("가입 보너스 재시도 — {}명에게 mint 전송", sent);
            }
        } catch (RuntimeException e) {
            // 스케줄 스레드에서 예외가 새면 다음 회차까지 조용히 죽는다. 여기서 잡아 남긴다.
            log.error("가입 보너스 재시도 회차 실패", e);
        }
    }
}
