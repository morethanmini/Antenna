package ssafy.a507.backend.domain.event.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse;
import ssafy.a507.backend.domain.event.dto.EventListResponse;
import ssafy.a507.backend.domain.event.service.EventQueryService;

/** 배팅 이벤트 조회 — ANT-EVENT-04, 화면 J-01. 작업명세 v1.2 §3-1·2. */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventQueryService eventQueryService;

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
}
