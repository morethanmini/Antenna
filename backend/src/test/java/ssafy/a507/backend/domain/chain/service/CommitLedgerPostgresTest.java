package ssafy.a507.backend.domain.chain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ssafy.a507.backend.domain.chain.dto.CommitListResponse;

/**
 * ANT-CHAIN-15 — 커밋 원장 목록을 <b>실제 PostgreSQL</b> 로 본다. Docker 가 없으면 건너뛴다.
 *
 * <p>H2 에서는 통과하는데 운영(PG)에서 500 이 났다. 커서 조건이 {@code :at is null} 로 <b>null 타임스탬프
 * 파라미터</b>를 비교하는데, PG 는 타입을 못 정하면 질의를 거절한다. H2 는 관대해서 그대로 통과한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("커밋 원장 — 실제 PostgreSQL")
class CommitLedgerPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired CommitQueryService service;
    @Autowired JdbcTemplate jdbc;

    /* 컨테이너를 테스트끼리 공유한다. @Transactional 로 묶지 않는 이유는 운영과 같은 경로(커밋된 데이터)를
       보려는 것이라서다 — 그래서 매번 지우고 시작한다. */
    @BeforeEach
    void clean() {
        jdbc.update("delete from prediction_commits");
        jdbc.update("delete from predictions");
        jdbc.update("delete from reports");
    }

    @Test
    @DisplayName("빈 원장의 첫 페이지 — 커서 없이 부른다(운영에서 500 이 난 그 호출)")
    void 첫_페이지() {
        CommitListResponse res = service.list(null, 20);
        assertThat(res.items()).isEmpty();
        assertThat(res.hasNext()).isFalse();
    }

    @Test
    @DisplayName("행이 있는 원장의 첫 페이지")
    void 행이_있을_때() {
        long userId = jdbc.queryForObject(
                "insert into users (wallet_address, role, status, created_at, updated_at)"
                        + " values ('0x1111111111111111111111111111111111111111','USER','ACTIVE',now(),now())"
                        + " returning id",
                Long.class);
        jdbc.update(
                "insert into reports (user_id, title, body, is_public, view_count, body_hash, report_hash, created_at)"
                        + " values (?, '제목', '본문', true, 0, '0xaa', '0xbb', now())",
                userId);

        CommitListResponse res = service.list(null, 20);
        assertThat(res.items()).hasSize(1);
        assertThat(res.nextCursor()).isNull();
    }

    @Test
    @DisplayName("예측·리포트가 섞인 목록을 커서로 끝까지 넘겨도 빠지거나 겹치는 줄이 없다")
    void 섞인_커서_페이징() {
        long userId = newUser("0x2222222222222222222222222222222222222222");
        jdbc.update("insert into stocks (code, name, listed) values ('005930','삼성전자',true) on conflict do nothing");
        long p1 = newPrediction(userId, "2026-09-15 01:00:00+00");
        long r1 = newReport(userId, "리포트 하나", "2026-09-15 01:00:10+00");
        long p2 = newPrediction(userId, "2026-09-15 01:00:20+00");
        long r2 = newReport(userId, "리포트 둘", "2026-09-15 01:00:30+00");

        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 2; page++) {
            CommitListResponse res = service.list(cursor, 2);
            res.items().forEach(i -> seen.add(i.kind() + "-" + (i.predictionId() != null ? i.predictionId() : i.reportId())));
            cursor = res.nextCursor();
        }
        assertThat(seen)
                .containsExactly("REPORT-" + r2, "PREDICTION-" + p2, "REPORT-" + r1, "PREDICTION-" + p1);
        assertThat(cursor).isNull();
    }

    @Test
    @DisplayName("커서가 있는 다음 페이지")
    void 커서_페이지() {
        CommitListResponse res = service.list("1789400000000000:REPORT:1", 20);
        assertThat(res.items()).isEmpty();
    }

    // ── 픽스처 ──────────────────────────────────────────────────────

    private long newUser(String wallet) {
        return jdbc.queryForObject(
                "insert into users (wallet_address, role, status, created_at, updated_at)"
                        + " values (?, 'USER', 'ACTIVE', now(), now()) returning id",
                Long.class,
                wallet);
    }

    private long newPrediction(long userId, String createdAt) {
        Long pid = jdbc.queryForObject(
                "insert into predictions"
                        + " (user_id, track, stock_code, direction, target_price, horizon, status, created_at, updated_at)"
                        + " values (?, 'REAL', '005930', 'UP', 80000.00, 30, 'OPEN', ?::timestamptz, ?::timestamptz)"
                        + " returning id",
                Long.class,
                userId,
                createdAt,
                createdAt);
        jdbc.update(
                "insert into prediction_commits (prediction_id, commit_hash, salt) values (?, ?, ?)",
                pid,
                "0xc" + pid,
                "00".repeat(32));
        return pid;
    }

    private long newReport(long userId, String title, String createdAt) {
        return jdbc.queryForObject(
                "insert into reports (user_id, title, body, is_public, view_count, body_hash, report_hash, created_at)"
                        + " values (?, ?, '본문', true, 0, ?, ?, ?::timestamptz) returning id",
                Long.class,
                userId,
                title,
                "0xb" + title.hashCode(),
                "0xr" + title.hashCode(),
                createdAt);
    }
}
