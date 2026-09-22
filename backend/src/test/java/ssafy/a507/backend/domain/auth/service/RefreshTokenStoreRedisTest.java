package ssafy.a507.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * refresh 회전 유예 창(ANT-AUTH-07)을 진짜 Redis 로 검증한다.
 *
 * <p>컨트롤러 테스트들은 {@code StringRedisTemplate} 을 Mockito 로 갈아 끼우므로 키 TTL·만료 같은 회전 규칙을
 * 확인할 수 없다. 여기서만 컨테이너를 띄운다 — 도커가 없는 환경에서는 건너뛴다(PostgreSQL 트리거 테스트와 같은 방식).
 *
 * <p>유예는 1초로 준다. 운영값(30초)을 그대로 쓰면 "유예가 지나면 재사용 탐지" 케이스를 30초 기다려야 한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenStoreRedisTest {

    /** compose 와 같은 이미지. 이미 받아 둔 이미지를 쓰므로 첫 실행도 빠르다. */
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    private static final long GRACE_SECONDS = 1;

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private RefreshTokenStore store;
    /** 테스트마다 다른 사용자. 유저별 키가 겹치지 않게 한다. */
    private Long userId;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void setUp() {
        store = new RefreshTokenStore(redis, 14, GRACE_SECONDS);
        userId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        store.revokeAll(userId);
    }

    @Test
    @DisplayName("정상 회전은 새 토큰을 주고 옛 토큰을 폐기한다")
    void 정상_회전() {
        String t1 = store.issue(userId);

        RefreshTokenStore.Rotated r = store.rotate(t1);

        assertThat(r.userId()).isEqualTo(userId);
        assertThat(r.token()).isNotEqualTo(t1);
        assertThat(redis.hasKey("rt:" + t1)).isFalse();
        assertThat(redis.hasKey("rt:" + r.token())).isTrue();
        assertThat(redis.opsForSet().isMember("rt:user:" + userId, r.token())).isTrue();
    }

    @Test
    @DisplayName("유예 안에 옛 토큰이 다시 오면 새로 발급하지 않고 같은 토큰을 돌려준다")
    void 유예_안_재요청은_같은_토큰() {
        String t1 = store.issue(userId);
        RefreshTokenStore.Rotated first = store.rotate(t1);

        // 새로고침 취소·두 번째 탭 — 옛 쿠키 T1 이 두 번 더 온다
        RefreshTokenStore.Rotated second = store.rotate(t1);
        RefreshTokenStore.Rotated third = store.rotate(t1);

        assertThat(second.token()).isEqualTo(first.token());
        assertThat(third.token()).isEqualTo(first.token());
        assertThat(second.userId()).isEqualTo(userId);
        // 전체 폐기가 일어나지 않았다
        assertThat(redis.hasKey("rt:" + first.token())).isTrue();
        assertThat(redis.opsForSet().size("rt:user:" + userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("유예가 지난 옛 토큰은 재사용 탐지로 그 유저 토큰을 전부 폐기한다")
    void 유예가_지나면_재사용_탐지() throws InterruptedException {
        String t1 = store.issue(userId);
        RefreshTokenStore.Rotated r = store.rotate(t1);
        String other = store.issue(userId); // 다른 기기의 세션

        Thread.sleep(GRACE_SECONDS * 1000 + 300);

        assertThatThrownBy(() -> store.rotate(t1))
                .isInstanceOf(RefreshTokenStore.InvalidRefreshTokenException.class);
        assertThat(redis.hasKey("rt:" + r.token())).isFalse();
        assertThat(redis.hasKey("rt:" + other)).isFalse();
        assertThat(redis.hasKey("rt:user:" + userId)).isFalse();
    }

    @Test
    @DisplayName("유예 안에 연달아 회전됐으면 살아 있는 끝 토큰을 돌려준다 (T1→T2→T3 뒤 T1)")
    void 연달아_회전되면_끝_토큰() {
        String t1 = store.issue(userId);
        String t2 = store.rotate(t1).token();
        String t3 = store.rotate(t2).token();

        RefreshTokenStore.Rotated r = store.rotate(t1);

        assertThat(r.token()).isEqualTo(t3);
        assertThat(store.rotate(t2).token()).isEqualTo(t3);
    }

    @Test
    @DisplayName("유예 안이라도 이어받은 토큰이 이미 죽었으면 401 이고, 다른 세션은 건드리지 않는다")
    void 이어받은_토큰이_죽었으면_거부만() {
        String t1 = store.issue(userId);
        String t2 = store.rotate(t1).token();
        String other = store.issue(userId);
        store.revoke(t2); // T2 로 로그아웃

        assertThatThrownBy(() -> store.rotate(t1))
                .isInstanceOf(RefreshTokenStore.InvalidRefreshTokenException.class);
        // 재사용 탐지(revokeAll)가 아니다 — 다른 기기 세션은 살아 있다
        assertThat(redis.hasKey("rt:" + other)).isTrue();
    }

    @Test
    @DisplayName("옛 쿠키로 로그아웃해도 이어받은 새 토큰까지 지운다")
    void 옛_쿠키로_로그아웃() {
        String t1 = store.issue(userId);
        String t2 = store.rotate(t1).token();

        store.revoke(t1); // 새로고침 취소 직후 로그아웃 — 브라우저 쿠키는 아직 T1

        assertThat(redis.hasKey("rt:" + t2)).isFalse();
        assertThat(redis.opsForSet().size("rt:user:" + userId)).isZero();
        assertThatThrownBy(() -> store.rotate(t2))
                .isInstanceOf(RefreshTokenStore.InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("유예를 0 으로 두면 지금까지처럼 즉시 재사용 탐지다")
    void 유예_0이면_즉시_탐지() {
        RefreshTokenStore noGrace = new RefreshTokenStore(redis, 14, 0);
        String t1 = noGrace.issue(userId);
        noGrace.rotate(t1);

        assertThat(redis.hasKey("rt:grace:" + t1)).isFalse();
        assertThatThrownBy(() -> noGrace.rotate(t1))
                .isInstanceOf(RefreshTokenStore.InvalidRefreshTokenException.class);
    }
}
