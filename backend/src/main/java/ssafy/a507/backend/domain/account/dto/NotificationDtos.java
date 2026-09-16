package ssafy.a507.backend.domain.account.dto;

import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.account.entity.Notification;

/** 알림함 응답 — 명세 §알림. {@code readAt} 이 null 이면 안 읽은 것이고, 그 수가 {@code unreadCount} 다. */
public final class NotificationDtos {

    private NotificationDtos() {}

    public record NotificationResponse(
            Long id,
            String type,
            String title,
            String body,
            /** 눌렀을 때 갈 곳. 화면이 종류로 주소를 조립하지 않고 이 값을 그대로 쓴다 */
            String linkPath,
            Instant readAt,
            Instant createdAt) {

        public static NotificationResponse of(Notification n) {
            return new NotificationResponse(
                    n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getLinkPath(), n.getReadAt(), n.getCreatedAt());
        }
    }

    public record NotificationListResponse(long unreadCount, List<NotificationResponse> items) {}
}
