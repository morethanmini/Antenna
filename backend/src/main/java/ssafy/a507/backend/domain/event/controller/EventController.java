package ssafy.a507.backend.domain.event.controller;

import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.idempotency.IdempotencyStore;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.event.dto.EventEntryCreateRequest;
import ssafy.a507.backend.domain.event.dto.EventEntryCreateResponse;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse;
import ssafy.a507.backend.domain.event.dto.EventLeaderboardResponse;
import ssafy.a507.backend.domain.event.dto.EventListResponse;
import ssafy.a507.backend.domain.event.dto.EventMeResponse;
import ssafy.a507.backend.domain.event.dto.EventParticipantsResponse;
import ssafy.a507.backend.domain.event.service.EventEntryService;
import ssafy.a507.backend.domain.event.service.EventLeaderboardService;
import ssafy.a507.backend.domain.event.service.EventQueryService;

/** 배팅 이벤트 조회·등록 — ANT-EVENT-04·05·06, 화면 J-01. 작업명세 v1.2 §3-1~5. */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private static final String ENTRY_ENDPOINT = "POST /events/{eventId}/entries";

    private final EventQueryService eventQueryService;
    private final EventEntryService eventEntryService;
    private final EventLeaderboardService eventLeaderboardService;
    private final CurrentUserProvider currentUserProvider;
    private final IdempotencyStore idempotencyStore;

    /** 목록. 배너·탭 진입점이라 종목·풀은 싣지 않는다 — 그건 홈 몫이다. */
    @GetMapping
    public EventListResponse list() {
        return eventQueryService.list();
    }

    /** 이벤트 홈 — 일정·규칙·종목 카드·내 배팅. 남의 방향·목표가는 나가지 않는다(집계만). */
    @GetMapping("/{eventId}")
    public EventHomeResponse home(@PathVariable Long eventId) {
        return eventQueryService.home(eventId);
    }

    /**
     * 묶음 등록 — 5~10 종목을 서명 한 번으로. 202 이고, 화면은 종목마다 {@code operationId} 를 폴링한다.
     *
     * <p><b>멱등키가 필수인 이유</b> — 202 를 받고 화면이 끊겼을 때 다시 누르면 같은 돈이 두 번 나간다.
     * "이미 건 종목" 검사가 한 겹 막지만 그건 <b>접수가 커밋된 뒤</b>라, 동시에 들어온 두 요청은 둘 다
     * 통과할 수 있다. 명세가 온체인 동반 요청 전부에 멱등키를 요구하는 이유가 이것이다.
     *
     * <p>저장하는 값은 응답 전체가 아니라 <b>예측 id 목록</b>이다 — 나머지(종목코드·커밋·작업 id)는
     * 배팅 행이 들고 있어 되짚을 수 있고, 응답을 통째로 Redis 에 넣으면 본문이 커진다.
     */
    @PostMapping("/{eventId}/entries")
    public ResponseEntity<EventEntryCreateResponse> register(
            @PathVariable Long eventId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EventEntryCreateRequest request) {

        Long userId = currentUserProvider.currentUserId();
        String predictionIds =
                idempotencyStore.execute(
                        userId,
                        ENTRY_ENDPOINT,
                        idempotencyKey,
                        IdempotencyStore.hash(eventId + "|" + request.signature()),
                        () ->
                                eventEntryService.register(userId, eventId, request).entries().stream()
                                        .map(e -> String.valueOf(e.predictionId()))
                                        .collect(Collectors.joining(",")));

        List<Long> ids =
                Arrays.stream(predictionIds.split(",")).map(Long::valueOf).toList();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(eventEntryService.accepted(userId, eventId, ids));
    }

    /**
     * 내 배팅 — 화면 J-01 의 "내 참가 내역". 홈과 달리 tx 해시·작업 id·오차까지 준다.
     *
     * <p>{@code paid}·{@code net}·{@code rank}·{@code avgError} 는 정산 전 null 이다 — 0 으로 내리면
     * "본전" 과 "꼴찌" 로 읽힌다.
     */
    @GetMapping("/{eventId}/me")
    public EventMeResponse me(@PathVariable Long eventId) {
        return eventQueryService.me(eventId, currentUserProvider.currentUserId());
    }

    /**
     * 순위 — 정산이 끝나야 나온다. 그전에는 {@code items} 가 비고 {@code notice} 만 온다.
     *
     * <p>봉인 뒤라도 "예상 순위" 를 내리지 않는 이유는 승패를 모르기 때문이다(09-16 결정). 지금
     * 배당률로 계산한 값은 "이기면 얼마" 일 뿐인데 화면에 순위로 뜨면 현재 등수로 읽힌다.
     */
    @GetMapping("/{eventId}/leaderboard")
    public EventLeaderboardResponse leaderboard(
            @PathVariable Long eventId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return eventLeaderboardService.of(eventId, cursor, size);
    }

    /**
     * 참가자 — 순위와 다르다. 순위는 정산이 끝나야 나오지만 이쪽은 <b>접수 중에도 나온다</b>.
     *
     * <p>"누가 참가했나" 는 승패와 무관하게 이미 정해진 사실이라 미리 보여줘도 잘못 읽을 것이 없다.
     * 순위를 미리 안 내리는 이유(승패를 모르는데 줄을 세우면 현재 등수로 읽힌다)가 여기엔 없다.
     *
     * <p>돈은 싣지 않는다 — 사람별 배팅액은 내리지 않고, 몇 종목에 걸었는지만 준다.
     */
    @GetMapping("/{eventId}/participants")
    public EventParticipantsResponse participants(@PathVariable Long eventId) {
        return eventQueryService.participants(eventId);
    }
}
