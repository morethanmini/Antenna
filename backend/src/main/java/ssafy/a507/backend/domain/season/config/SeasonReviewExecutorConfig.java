package ssafy.a507.backend.domain.season.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeasonReviewExecutorConfig {

    /**
     * 스레드 하나. GMS 는 회차당 한 번이고 수십 초라 줄을 세워도 된다 — 둘이 동시에 돌아 봐야 같은 키로
     * 같은 상한을 나눠 쓴다. 데몬이다 — 재배포로 앱이 내려갈 때 돌던 복기가 JVM 종료를 붙잡지 않는다.
     * 끊긴 복기는 review_body 가 null 로 남고, 결과 화면의 "다시 만들기" 가 finish 를 다시 불러 채운다.
     */
    @Bean
    public SeasonReviewExecutor seasonReviewExecutor() {
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "season-review");
            thread.setDaemon(true);
            return thread;
        });
        return pool::execute;
    }
}
