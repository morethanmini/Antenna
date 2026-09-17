package ssafy.a507.backend.domain.community.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.community.entity.AbuseReport;
import ssafy.a507.backend.domain.community.entity.FeedPost;
import ssafy.a507.backend.domain.community.entity.PostComment;

/**
 * 관리자 모더레이션 (ANT-ADMIN-01) 요청·응답.
 *
 * <p>작은 record 가 여럿이라 한 파일에 묶는다 — {@code NotificationDtos} 와 같은 방식이다.
 */
public final class AdminModerationDtos {

    private AdminModerationDtos() {}

    /** 신고 대상 종류. 신고 행은 셋 중 정확히 하나만 채워져 있다(AbuseReport 팩터리 주석). */
    public enum TargetType {
        POST,
        COMMENT,
        USER
    }

    /**
     * 목록 한 줄.
     *
     * <p>{@code targetBody} 는 글·댓글일 때만 채운다. 관리자가 <b>무엇을 가리는지 보지 않고</b>
     * 판단하게 두면 안 되기 때문이다. 회원 신고면 닉네임이 그 자리에 들어간다.
     *
     * <p>{@code targetBlocked} 는 지금 이미 가려져 있는지다 — 다른 신고로 먼저 내려간 글을
     * 또 내리려다 "왜 아무 일도 안 일어나지" 하는 것을 막는다.
     */
    public record AbuseReportItem(
            Long id,
            String reason,
            String detail,
            String status,
            TargetType targetType,
            Long targetId,
            String targetBody,
            Boolean targetBlocked,
            Long reporterId,
            String reporterNickname,
            Long handledById,
            Instant handledAt,
            Instant createdAt) {

        public static AbuseReportItem of(AbuseReport r) {
            FeedPost post = r.getTargetPost();
            PostComment comment = r.getTargetComment();

            /* 삼항으로 엮지 않는다. blocked 는 회원 신고일 때 null 인데, 한 가지(boolean)가
               섞인 조건식은 통째로 boolean 으로 언박싱돼 그 자리에서 NPE 가 난다. */
            TargetType type;
            Long targetId;
            String body;
            Boolean blocked;
            if (post != null) {
                type = TargetType.POST;
                targetId = post.getId();
                body = post.getBody();
                blocked = post.isBlocked();
            } else if (comment != null) {
                type = TargetType.COMMENT;
                targetId = comment.getId();
                body = comment.getBody();
                blocked = comment.isBlocked();
            } else {
                type = TargetType.USER;
                targetId = r.getTargetUser().getId();
                body = r.getTargetUser().getNickname();
                blocked = null;
            }

            return new AbuseReportItem(
                    r.getId(),
                    r.getReason().name(),
                    r.getDetail(),
                    r.getStatus().name(),
                    type,
                    targetId,
                    body,
                    blocked,
                    r.getReporter().getId(),
                    r.getReporter().getNickname(),
                    r.getHandledBy() == null ? null : r.getHandledBy().getId(),
                    r.getHandledAt(),
                    r.getCreatedAt());
        }
    }

    public record AbuseReportListResponse(List<AbuseReportItem> items) {}

    /** 신고 처리. ACCEPT 면 대상 글·댓글이 BLOCKED 로 내려간다. 회원 신고는 상태만 바뀐다. */
    public record AbuseReportHandleRequest(@NotNull(message = "처리 방식을 선택해 주세요.") Action action) {

        public enum Action {
            ACCEPT,
            REJECT
        }
    }

    /** 글·댓글 블라인드 직접 전환. 신고를 거치지 않고 관리자가 바로 가리거나 되돌릴 때. */
    public record ContentStatusUpdateRequest(@NotNull(message = "변경할 상태를 선택해 주세요.") ContentStatus status) {

        public enum ContentStatus {
            VISIBLE,
            BLOCKED
        }
    }
}
