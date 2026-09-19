package ssafy.a507.backend.common.throttle;

import java.time.Duration;
import java.time.Instant;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;

/**
 * 댓글 도배 제한(2026-09-19 · QA 09-18 "댓글 도배 제한"). 리포트 댓글과 커뮤니티 댓글이 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li><b>1분에 {@value #MAX_PER_WINDOW}개</b> — 넘기면 429 {@code COMMENT_RATE_LIMITED}. 사람이 대화하는
 *       속도로는 닿지 않고 연타·스크립트만 걸린다.
 *   <li><b>같은 대상에 같은 내용 연속 금지</b> — 그 글(리포트)에 내가 마지막으로 쓴 댓글과 본문이 같으면 409
 *       {@code COMMENT_DUPLICATED}. 앞뒤 공백은 무시한다. 다른 글에 같은 인사를 남기는 것, 다른 사람이 같은
 *       말을 하는 것은 막지 않는다.
 * </ul>
 *
 * <p>값을 읽는 일은 부르는 쪽이 한다 — 테이블이 둘이라 저장소가 다르다. 여기는 판단만 한다.
 *
 * <p>ponytail: 세고 나서 저장하므로 동시에 쏘면 한두 개 더 들어갈 수 있다. 도배를 "어렵게" 하는 것이
 * 목적이라 잠그지 않았다 — 정확히 막아야 하면 Redis INCR+TTL 로 옮긴다(좋아요·신고에도 같이 쓸 수 있다).
 */
public final class CommentThrottle {

    public static final int MAX_PER_WINDOW = 5;
    public static final Duration WINDOW = Duration.ofMinutes(1);

    private CommentThrottle() {}

    /** 이 시각 이후에 쓴 댓글을 센다. */
    public static Instant windowStart() {
        return Instant.now().minus(WINDOW);
    }

    /**
     * @param recentCount {@link #windowStart()} 이후에 이 사람이 쓴 댓글 수
     * @param lastBodyOnTarget 같은 대상에 이 사람이 마지막으로 쓴 댓글 본문. 없으면 null
     * @param newBody 지금 쓰려는 본문
     */
    public static void check(long recentCount, String lastBodyOnTarget, String newBody) {
        if (lastBodyOnTarget != null && lastBodyOnTarget.strip().equals(newBody.strip())) {
            throw new BusinessException(ErrorCode.COMMENT_DUPLICATED);
        }
        if (recentCount >= MAX_PER_WINDOW) {
            throw new BusinessException(ErrorCode.COMMENT_RATE_LIMITED);
        }
    }
}
