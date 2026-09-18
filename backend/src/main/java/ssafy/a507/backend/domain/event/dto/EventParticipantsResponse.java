package ssafy.a507.backend.domain.event.dto;

import java.util.List;

/**
 * GET /api/v1/events/{eventId}/participants 200 — 대회 참가자 (ANT-EVENT-04, 화면 J-01).
 *
 * <p><b>순위가 아니다.</b> 순위({@code /leaderboard})는 정산이 끝나야 나오고 그전에는 빈 목록에
 * 안내 문구만 온다 — 승패를 모르는데 줄을 세우면 사용자가 현재 등수로 읽기 때문이다(09-16 결정).
 * 이 응답은 그 제약과 무관하다. "누가 참가했나" 는 접수 중에도 이미 정해진 사실이라, 접수가
 * 시작된 순간부터 그대로 내린다.
 *
 * <p><b>돈도 방향도 싣지 않는다.</b> 누가 얼마를 어느 쪽으로 걸었는지는 종목 카드의 풀·건수로
 * 집계만 보이고 사람별로는 내리지 않는다 — {@code EventQueryService} 머리말의 "남의 방향·목표가는
 * 나가지 않는다" 와 같은 줄이다. 여기 나가는 것은 "이 사람이 이 종목에 걸었다" 까지이고, 그건
 * 종목 카드의 참가 인원이 이미 세고 있는 사실이다.
 *
 * @param total 참가자 수. {@code items.size()} 와 같지만, 나중에 목록을 잘라 내리게 되면 여기만
 *     전체 수로 남는다 — 화면이 "참가자 37" 을 목록 길이로 세지 않도록 처음부터 따로 준다.
 */
public record EventParticipantsResponse(int total, List<Item> items) {

    /**
     * @param avatarUrl 프로필 사진. 안 올렸거나 파일이 사라졌으면 null 이다 — 화면이 기본 그림을 깐다
     * @param stocks 이 사람이 건 종목 이름, 회차의 종목 순서(idx)대로. 타일에 손을 얹었을 때 뜨는
     *     말풍선의 재료다. 개수가 곧 {@code stockCount} 라 따로 내리지 않는다
     * @param qualified 순위 자격을 갖췄나({@code stocks.size() >= minStocks}). 최소 종목 수는 등록을
     *     막지 않고 순위 자격으로만 걸려서, 화면이 말하지 않으면 못 채운 사람이 참가한 줄 안다
     */
    public record Item(
            Long userId, String nickname, String avatarUrl, List<String> stocks, boolean qualified) {}
}
