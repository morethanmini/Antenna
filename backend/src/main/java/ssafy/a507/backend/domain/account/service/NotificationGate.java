package ssafy.a507.backend.domain.account.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ssafy.a507.backend.domain.account.entity.UserSetting;
import ssafy.a507.backend.domain.account.repository.UserSettingRepository;

/**
 * 받는 사람이 그 종류의 알림을 켜 두었는가 (ANT-NOTIFY-02). 발송 지점이 저장 직전에 묻는다.
 *
 * <p>설정 행이 없으면 전부 켜진 것이다. 모르는 종류도 보낸다 — 새 종류를 붙였는데 여기 대응을
 * 빠뜨렸다고 조용히 사라지는 것보다 낫다.
 *
 * <p>종류 ↔ 토글
 * <ul>
 *   <li>notifyVerdict — VERDICT
 *   <li>notifySeason — SEASON
 *   <li>notifySocial(내 채널 반응) — SUBSCRIBE · REPORT_COMMENT · REPORT_LIKE
 *   <li>notifyChannel(구독 채널 소식) — REPORT_PUBLISHED · PREDICTION_REGISTERED · FEE_CHANGED
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class NotificationGate {

    private final UserSettingRepository settings;

    public boolean allows(Long userId, String type) {
        return settings.findById(userId).map(s -> enabled(s, type)).orElse(true);
    }

    /** 여러 명 중 켜 둔 사람만. 구독자 전체에 보낼 때 쓴다 — 한 번에 읽고 거른다. */
    public List<Long> filter(Collection<Long> userIds, String type) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        Map<Long, UserSetting> found = settings.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserSetting::getUserId, Function.identity()));
        return userIds.stream()
                .filter(id -> {
                    UserSetting s = found.get(id);
                    return s == null || enabled(s, type);
                })
                .toList();
    }

    private static boolean enabled(UserSetting s, String type) {
        return switch (type) {
            case "VERDICT" -> s.isNotifyVerdict();
            case "SEASON" -> s.isNotifySeason();
            case "SUBSCRIBE", "REPORT_COMMENT", "REPORT_LIKE" -> s.isNotifySocial();
            case "REPORT_PUBLISHED", "PREDICTION_REGISTERED", "FEE_CHANGED" -> s.isNotifyChannel();
            default -> true;
        };
    }
}
