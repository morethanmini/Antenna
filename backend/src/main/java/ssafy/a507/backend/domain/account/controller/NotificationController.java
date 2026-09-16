package ssafy.a507.backend.domain.account.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.dto.NotificationDtos.NotificationListResponse;
import ssafy.a507.backend.domain.account.service.NotificationService;

/**
 * 알림함 — 명세 §알림 (화면 H-02, 상단바 팝업).
 *
 * <p>STOMP 푸시는 붙이지 않았다. 종을 열 때마다 다시 읽으므로 실시간이 필요해지면 그때 붙인다.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public NotificationListResponse list() {
        return notificationService.list(currentUserProvider.currentUserId());
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id) {
        notificationService.markRead(currentUserProvider.currentUserId(), id);
    }

    @PatchMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead() {
        notificationService.markAllRead(currentUserProvider.currentUserId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        notificationService.delete(currentUserProvider.currentUserId(), id);
    }

    /** 모두 삭제. 명세에는 없던 것 — 팝업의 "모두 삭제" 버튼이 쓴다(2026-09-16). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll() {
        notificationService.deleteAll(currentUserProvider.currentUserId());
    }
}
