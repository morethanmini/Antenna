package ssafy.a507.backend.domain.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.account.entity.User;

/** 관리자 회원 제재 (ANT-ADMIN-01) 요청·응답. */
public final class AdminUserDtos {

    private AdminUserDtos() {}

    public record AdminUserItem(
            Long id, String nickname, String role, String status, Instant createdAt) {

        public static AdminUserItem of(User u) {
            return new AdminUserItem(
                    u.getId(),
                    u.getNickname(),
                    u.getRole().name(),
                    u.getStatus().name(),
                    u.getCreatedAt());
        }
    }

    /** {@code nextBefore} 가 있으면 뒤가 더 있다 — 그 값을 {@code before} 로 다시 부른다. 없으면 끝이다. */
    public record AdminUserListResponse(List<AdminUserItem> items, Long nextBefore) {}

    /**
     * 회원 상태 전이.
     *
     * <p><b>{@code SUSPENDED} 가 아니라 {@code BANNED} 다.</b> 지라 AC 는 SUSPENDED 로 적혀 있지만
     * {@code User.Status} 가 {@code {ACTIVE, BANNED}} 이고 {@code AuthService} 가 이미 BANNED 로
     * 로그인을 막는다. {@code ddl-auto: update} 라 enum 값 추가는 팀 DB 스키마에 번진다 —
     * 명세 쪽을 코드에 맞춰 정정했다(API 명세서 v0.77).
     *
     * <p>{@code reason} 은 받되 <b>저장하지 않는다.</b> {@code users} 에 사유 칼럼이 없고 추가하면
     * 같은 스키마 번짐이 된다. 신고를 거친 제재의 근거는 {@code abuse_reports.handled_by/handled_at}
     * 에 남는다. 직권 제재의 사유 보존은 후속 이슈다.
     */
    public record UserStatusUpdateRequest(
            @NotNull(message = "변경할 상태를 선택해 주세요.") TargetStatus status,
            @Size(max = 300, message = "사유는 300자까지 입력할 수 있습니다.") String reason) {

        public enum TargetStatus {
            ACTIVE,
            BANNED
        }
    }
}
