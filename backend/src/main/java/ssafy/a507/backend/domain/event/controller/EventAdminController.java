package ssafy.a507.backend.domain.event.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.event.dto.BasePriceUpdateRequest;
import ssafy.a507.backend.domain.event.dto.EventCreateRequest;
import ssafy.a507.backend.domain.event.dto.EventCreateResponse;
import ssafy.a507.backend.domain.event.service.EventAdminService;

/**
 * 배팅 이벤트 관리자 — ANT-EVENT-04, 작업명세 v1.2 §3-6·7.
 *
 * <p>권한은 {@link EventAdminService} 안에서 본다(§0.2 ④) — {@code SecurityConfig} 는 건드리지 않는다.
 * 여기 매처를 걸면 JWT {@code role} 클레임을 Spring 권한으로 바꾸는 컨버터가 함께 필요하다.
 */
@RestController
@RequestMapping("/api/v1/admin/events")
@RequiredArgsConstructor
public class EventAdminController {

    private final EventAdminService eventAdminService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 이벤트 생성. 202 인 이유는 온체인 {@code openEvent} 전송이 뒤따라서다 —
     * 행을 먼저 커밋하고 {@code openEvent} 를 그 뒤에 보낸다 — 전송이 실패해도 이벤트는 남고,
     * {@code POST …/open} 으로 다시 보낼 수 있다.
     */
    @PostMapping
    public ResponseEntity<EventCreateResponse> create(@Valid @RequestBody EventCreateRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(eventAdminService.create(currentUserProvider.currentUserId(), request));
    }

    /**
     * {@code openEvent} 재전송 — 생성 때 전송이 못 나갔을 때(체인 장애 등) 쓰는 길. 이벤트 행은 그대로
     * 두고 tx 만 다시 보낸다. 다시 만들면 종목 칸 {@code idx} 가 달라져 이미 걸린 배팅의 커밋과 어긋난다.
     */
    @PostMapping("/{eventId}/open")
    public ResponseEntity<Void> open(@PathVariable Long eventId) {
        eventAdminService.openNow(currentUserProvider.currentUserId(), eventId);
        return ResponseEntity.accepted().build();
    }

    /**
     * 봉인 — 접수를 닫고 온체인 {@code seal} 을 보낸다. 평소에는 매분 크론이 하고, 이 길은 크론이 못
     * 돌았거나 일찍 닫아야 할 때 쓴다. SEALED 전이는 인덱서 몫이라 응답은 202 다.
     */
    @PostMapping("/{eventId}/seal")
    public ResponseEntity<Void> seal(@PathVariable Long eventId) {
        eventAdminService.seal(currentUserProvider.currentUserId(), eventId);
        return ResponseEntity.accepted().build();
    }

    /** 기준가 수동 입력. 13:00 배치가 늦거나 실패했을 때 접수(13:30)를 지키는 길이다. */
    @PutMapping("/{eventId}/stocks/{idx}/base-price")
    public ResponseEntity<Void> fixBasePrice(
            @PathVariable Long eventId,
            @PathVariable short idx,
            @Valid @RequestBody BasePriceUpdateRequest request) {
        eventAdminService.fixBasePrice(
                currentUserProvider.currentUserId(), eventId, idx, request.basePrice());
        return ResponseEntity.noContent().build();
    }
}
