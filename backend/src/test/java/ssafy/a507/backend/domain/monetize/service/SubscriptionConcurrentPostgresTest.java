package ssafy.a507.backend.domain.monetize.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.domain.chain.service.FakeTokenRelayer;
import ssafy.a507.backend.domain.chain.service.TokenOperationService;
import ssafy.a507.backend.domain.monetize.dto.SubscribeRequest;

/**
 * ANT-TOKEN-01 — 같은 채널에 <b>동시 요청</b> 둘이 들어와도 결제가 한 번인지, 실제 PostgreSQL 로 본다.
 *
 * <p>H2 로는 검증이 안 되는 것: 구독자 행 {@code PESSIMISTIC_WRITE}({@code SELECT … FOR UPDATE})가 다른
 * 커넥션의 같은 구독자 요청을 실제로 줄 세우는가. 잠금은 DB 엔진 구현이라 H2 에서 통과해도 PostgreSQL
 * 에서 통과한다는 보장이 없다. {@code PredictionSlotLockPostgresTest} 와 같은 모양이다.
 *
 * <p><b>테스트에 {@code @Transactional} 을 붙이면 안 된다</b> — 붙이면 스레드가 한 트랜잭션 안에서 돌아
 * 레이스 자체가 생기지 않는다. 스레드마다 접수 트랜잭션이 따로 커밋돼야 한다.
 *
 * <p>멱등키는 여기서 검증 대상이 아니다. 그건 컨트롤러 앞단이고, 서로 다른 키를 들고 온 동시 요청 둘은
 * 애초에 그 앞을 통과한다. 여기서 보는 것은 접수 안쪽의 검사→생성 구간뿐이다.
 *
 * <p>서명과 체인 전송은 대역이다 — 실제 nonce 는 사용자당 하나만 살아 있어 동시 요청 둘이 모두 통과할 수
 * 없고, 전송은 접수가 커밋된 뒤의 일이다. Docker 가 없으면 건너뛴다.
 */
@SpringBootTest
@Import(FakeTokenRelayer.Config.class)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("구독 결제 — PostgreSQL 동시 요청")
class SubscriptionConcurrentPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private static final BigDecimal FEE = new BigDecimal("1000000000000000000");

    @Autowired SubscriptionService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SignatureGuard signatureGuard;
    @MockitoBean TokenOperationService tokenOperationService;
    @Autowired FakeTokenRelayer relayer;

    private long subscriberId;
    private long publisherId;

    @BeforeEach
    void setUp() {
        subscriberId = insertUser("11");
        publisherId = insertUser("22");
        jdbc.update(
                "insert into publisher_fees (publisher_id, fee, effective_from, created_at) values (?, ?, ?, now())",
                publisherId, FEE, Timestamp.from(Instant.now().minusSeconds(60)));
        // 잔액 — 구독료의 열 배. 잔액 부족으로 먼저 걸려 레이스가 안 보이면 안 된다.
        // 기준이 원장 → 가용잔액(체인 잔액 − PENDING 배팅·구독·광고, ANT-TOKEN-11)으로 바뀌어 체인 대역에 심는다.
        relayer.reset();
        relayer.withBalance(FEE.multiply(BigDecimal.TEN).toBigInteger());
    }

    @Test
    @DisplayName("같은 채널에 동시에 2건을 보내면 구독 행은 정확히 1개다 — 나머지는 409 ALREADY_SUBSCRIBED")
    void 동시_2건_중_1건만() throws Exception {
        int attempts = 2;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            Callable<String> call = () -> {
                start.await();
                try {
                    service.subscribe(subscriberId, publisherId, new SubscribeRequest("0x" + "ab".repeat(65)));
                    return "OK";
                } catch (BusinessException e) {
                    return e.getErrorCode().name();
                }
            };
            results.add(pool.submit(call));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        Integer rows = jdbc.queryForObject(
                "select count(*) from subscriptions where subscriber_id = ? and publisher_id = ?",
                Integer.class, subscriberId, publisherId);
        assertThat(rows).isEqualTo(1);
        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(ErrorCode.ALREADY_SUBSCRIBED.name()::equals).hasSize(1);
    }

    private long insertUser(String octet) {
        return jdbc.queryForObject(
                "insert into users (wallet_address, role, status, created_at, updated_at)"
                        + " values (?, 'USER', 'ACTIVE', now(), now()) returning id",
                Long.class, "0x" + octet.repeat(20));
    }
}
