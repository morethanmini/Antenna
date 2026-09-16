package ssafy.a507.backend.domain.account.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.dto.NotificationDtos.NotificationListResponse;
import ssafy.a507.backend.domain.account.dto.NotificationDtos.NotificationResponse;
import ssafy.a507.backend.domain.account.entity.Notification;
import ssafy.a507.backend.domain.account.repository.NotificationRepository;

/**
 * 알림함 — 목록·읽음·삭제. <b>발송은 여기 없다.</b> 알림은 그것을 일으킨 트랜잭션 안에서 저장돼야
 * (발행이 롤백되면 알림도 함께) 각 도메인 서비스가 {@link Notification#create} 로 직접 쌓는다.
 *
 * <p>남의 알림은 404 다. 403 으로 나누면 "그 id 가 있다" 를 알려 주는 셈이라 없는 것과 같게 감춘다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notifications;

    @Transactional(readOnly = true)
    public NotificationListResponse list(Long userId) {
        return new NotificationListResponse(
                notifications.countByUser_IdAndReadAtIsNull(userId),
                notifications.findTop50ByUser_IdOrderByIdDesc(userId).stream()
                        .map(NotificationResponse::of)
                        .toList());
    }

    @Transactional
    public void markRead(Long userId, Long id) {
        mine(userId, id).read(Instant.now());
    }

    @Transactional
    public void markAllRead(Long userId) {
        notifications.markAllRead(userId, Instant.now());
    }

    @Transactional
    public void delete(Long userId, Long id) {
        notifications.delete(mine(userId, id));
    }

    private Notification mine(Long userId, Long id) {
        return notifications
                .findByIdAndUser_Id(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND, "notificationId"));
    }
}
