package ssafy.a507.backend.domain.season.config;

/**
 * AI 복기를 만드는 자리(ANT-SEASON-09, 2026-09-26). 요청 스레드 밖이다 — 종료(finish)는 성적표만 굳히고
 * 바로 돌아오고, 복기는 여기서 뒤따라 만든다. 인터페이스로 둔 이유는 테스트가 같은 스레드에서 도는
 * 대체({@code Runnable::run})를 끼우기 위해서다 — 테스트 트랜잭션 안의 데이터는 다른 스레드에서 보이지 않는다.
 */
@FunctionalInterface
public interface SeasonReviewExecutor {

    void execute(Runnable job);
}
