package ssafy.a507.backend.domain.account.dto;

import jakarta.validation.constraints.NotNull;
import ssafy.a507.backend.domain.account.entity.UserSetting;
import ssafy.a507.backend.domain.account.entity.UserSetting.ChartView;

/**
 * 환경 설정 — 명세 §회원 settings (ANT-NOTIFY-02).
 *
 * <p>알림 토글은 넷이다. 명세는 셋(판정 · 시즌 · 구독·구독료)이었는데 알림이 7종으로 늘어
 * "내 채널 반응"(구독·댓글·좋아요)과 "구독 채널 소식"(리포트·예측 등록·구독료 변경)으로
 * 갈랐다(2026-09-16 팀장 결정). 종류 ↔ 토글 대응은 {@code NotificationGate}.
 */
public final class UserSettingDtos {

    private UserSettingDtos() {}

    /** PUT 본문. 전부 필수 — 일부만 보내 나머지를 기본값으로 되돌리는 일이 없게. */
    public record UserSettingRequest(
            @NotNull Boolean notifyVerdict,
            @NotNull Boolean notifySeason,
            @NotNull Boolean notifySocial,
            @NotNull Boolean notifyChannel,
            @NotNull ChartView chartView) {}

    public record UserSettingResponse(
            boolean notifyVerdict,
            boolean notifySeason,
            boolean notifySocial,
            boolean notifyChannel,
            ChartView chartView) {

        public static UserSettingResponse of(UserSetting s) {
            return new UserSettingResponse(
                    s.isNotifyVerdict(), s.isNotifySeason(), s.isNotifySocial(), s.isNotifyChannel(), s.getChartView());
        }
    }
}
