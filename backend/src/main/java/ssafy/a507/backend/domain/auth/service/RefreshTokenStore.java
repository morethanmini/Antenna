package ssafy.a507.backend.domain.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Refresh 토큰 회전 관리.
 *
 * <p>키 4종 — {@code rt:{token}} 유효 토큰→userId, {@code rt:user:{userId}} 유저별 활성 토큰 집합, {@code
 * rt:used:{token}} 회전으로 폐기된 토큰, {@code rt:grace:{token}} 폐기된 토큰→이어받은 토큰(짧은 유예). 폐기된
 * 토큰이 유예를 지나 다시 들어오면 탈취로 보고 해당 유저 토큰을 전부 지운다.
 *
 * <p><b>유예를 두는 이유(ANT-AUTH-07).</b> 새로고침은 진행 중인 {@code /auth/refresh} 를 취소한다. 서버는 이미 옛
 * 토큰을 폐기하고 새 토큰을 발급했는데 브라우저는 Set-Cookie 를 못 받아 옛 토큰을 다시 보낸다. 이걸 탈취로 판정하면
 * 새로고침 연타·다중 탭·로딩 중 탭 닫기가 전부 "그 유저 전체 로그아웃"이 된다. 그래서 폐기 직후 짧은 시간 동안은 옛
 * 토큰에 "네 새 토큰은 이거다"라고 같은 답을 돌려준다. Okta(30초)·Supabase(10초)·Auth0·Cognito 의 reuse
 * interval 과 같은 방식이다.
 */
@Component
public class RefreshTokenStore {

    /** 회전 성공 시 새로 발급된 토큰과 소유자. */
    public record Rotated(Long userId, String token) {}

    /** 재사용 탐지·만료 등 refresh 흐름이 끊긴 경우. 호출부가 401로 바꾼다. */
    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }

    private static final String TOKEN_KEY = "rt:";
    private static final String USER_KEY = "rt:user:";
    private static final String USED_KEY = "rt:used:";
    private static final String GRACE_KEY = "rt:grace:";

    /**
     * 유예 매핑을 따라가는 최대 홉. 유예 안에 T1→T2→T3 로 연달아 회전되면 T1 의 매핑은 죽은 T2 를 가리키므로 한 번
     * 더 따라가야 한다. 상한을 두는 건 무한 루프 방지 — 정상 사용에서 두 홉을 넘는 일은 사실상 없다.
     */
    private static final int MAX_GRACE_HOPS = 3;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final Duration grace;

    public RefreshTokenStore(
            StringRedisTemplate redis,
            @Value("${app.auth.refresh-ttl-days}") long refreshTtlDays,
            @Value("${app.auth.refresh-grace-seconds}") long refreshGraceSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofDays(refreshTtlDays);
        this.grace = Duration.ofSeconds(refreshGraceSeconds);
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(TOKEN_KEY + token, String.valueOf(userId), ttl);
        redis.opsForSet().add(USER_KEY + userId, token);
        redis.expire(USER_KEY + userId, ttl);
        return token;
    }

    public Rotated rotate(String token) {
        /* 유예 확인이 재사용 탐지보다 먼저다. rt:used 는 폐기 즉시 쓰고, rt:grace 가 살아 있는 동안만 여기서
           가로챈다. 유예가 끝나면 rt:grace 만 만료되고 rt:used 가 남아 아래 재사용 탐지가 그대로 동작한다 —
           지연 쓰기나 정리 작업이 필요 없다. */
        String next = redis.opsForValue().get(GRACE_KEY + token);
        if (next != null) {
            Rotated alive = resolveAlive(next);
            if (alive == null) {
                // 이어받은 토큰이 로그아웃·차단으로 이미 죽었다. 같은 브라우저의 늦은 요청이므로 전체 폐기는 하지 않는다.
                throw new InvalidRefreshTokenException("유예 중이지만 이어받은 refresh 토큰이 이미 폐기됨");
            }
            // 새로 발급하지 않고 같은 토큰을 돌려준다. 세 번째·네 번째 요청도 같은 답이라 어느 탭이 먼저 받든 쿠키가 같다.
            return alive;
        }

        String reusedBy = redis.opsForValue().get(USED_KEY + token);
        if (reusedBy != null) {
            revokeAll(Long.valueOf(reusedBy));
            throw new InvalidRefreshTokenException("폐기된 refresh 토큰 재사용");
        }

        String userId = redis.opsForValue().get(TOKEN_KEY + token);
        if (userId == null) {
            throw new InvalidRefreshTokenException("알 수 없거나 만료된 refresh 토큰");
        }

        redis.delete(TOKEN_KEY + token);
        redis.opsForSet().remove(USER_KEY + userId, token);
        redis.opsForValue().set(USED_KEY + token, userId, ttl);
        String issued = issue(Long.valueOf(userId));
        if (!grace.isZero() && !grace.isNegative()) {
            // 0 이면 유예 없음. Duration.ZERO 를 그대로 넘기면 스프링이 "만료 없음"으로 저장해 유예가 영구가 된다.
            redis.opsForValue().set(GRACE_KEY + token, issued, grace);
        }
        return new Rotated(Long.valueOf(userId), issued);
    }

    /**
     * 로그아웃. 제시된 토큰만 지운다.
     *
     * <p>새로고침 취소 직후 로그아웃하면 브라우저는 폐기된 옛 토큰을 보낸다. 유예 매핑을 따라 이어받은 토큰을 지워야
     * 로그아웃이 된다 — 안 그러면 사용자는 나갔다고 믿는데 새 토큰이 살아 있다.
     */
    public void revoke(String token) {
        Rotated alive = resolveAlive(token);
        if (alive == null) {
            return;
        }
        String userId = String.valueOf(alive.userId());
        redis.delete(TOKEN_KEY + alive.token());
        redis.opsForSet().remove(USER_KEY + userId, alive.token());
        redis.opsForValue().set(USED_KEY + alive.token(), userId, ttl);
    }

    public void revokeAll(Long userId) {
        Set<String> tokens = redis.opsForSet().members(USER_KEY + userId);
        if (tokens != null) {
            tokens.forEach(t -> redis.delete(TOKEN_KEY + t));
        }
        redis.delete(USER_KEY + userId);
    }

    /**
     * 토큰이 살아 있으면 그대로, 폐기됐으면 유예 매핑을 따라가 살아 있는 후계 토큰을 찾는다. 끝까지 없으면 null.
     */
    private Rotated resolveAlive(String token) {
        String current = token;
        for (int hop = 0; hop < MAX_GRACE_HOPS && current != null; hop++) {
            String userId = redis.opsForValue().get(TOKEN_KEY + current);
            if (userId != null) {
                return new Rotated(Long.valueOf(userId), current);
            }
            current = redis.opsForValue().get(GRACE_KEY + current);
        }
        return null;
    }
}
