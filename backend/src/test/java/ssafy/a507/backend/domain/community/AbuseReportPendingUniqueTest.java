package ssafy.a507.backend.domain.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureDataSourceInitialization;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ANT-FE-ABUSE-REPORT — 신고 중복은 "처리 대기(PENDING) 중 한 건" 만 막는다. db/enum-checks.sql 의 부분 유니크 인덱스.
 *
 * <p>부분 인덱스({@code … WHERE status = 'PENDING'})는 H2 로 검증할 수 없고 JPA 로 표현할 수도 없어 SQL 로만 건다.
 * 그래서 실제 PostgreSQL 로 본다. 옛 제약(신고자, 대상, status)은 같은 사람이 같은 대상을 두 번 신고해 두 번 다
 * 기각되면 두 번째 처리에서 (…, REJECTED) 중복으로 500 을 냈다 — 그 경로가 이제 통과하는지가 핵심이다.
 *
 * <p>{@code AppendOnlyTriggerTest} 와 같은 방식이다 — 실패한 문장이 트랜잭션을 abort 시키므로 autocommit 으로 돈다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@AutoConfigureDataSourceInitialization
@TestPropertySource(properties = "spring.sql.init.mode=always")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class AbuseReportPendingUniqueTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired JdbcTemplate jdbc;

    private Long reporterId;
    private Long reportId;

    @BeforeEach
    void setUp() {
        Long authorId = jdbc.queryForObject(
                "insert into users (role, status, created_at, updated_at) values ('USER', 'ACTIVE', now(), now()) returning id",
                Long.class);
        reporterId = jdbc.queryForObject(
                "insert into users (role, status, created_at, updated_at) values ('USER', 'ACTIVE', now(), now()) returning id",
                Long.class);
        reportId = jdbc.queryForObject(
                "insert into reports (user_id, title, body, is_public, view_count, created_at)"
                        + " values (?, '리포트', '본문', true, 0, now()) returning id",
                Long.class, authorId);
    }

    @Test
    @DisplayName("같은 사람이 같은 리포트를 두 번 신고해 두 번 다 기각돼도 두 번째 처리가 통과한다")
    void 두_번째_처리가_막히지_않는다() {
        Long first = pending();
        assertThatCode(() -> handle(first, "REJECTED")).doesNotThrowAnyException();

        Long second = pending(); // 처리 대기 건이 없으니 다시 받는다
        assertThatCode(() -> handle(second, "REJECTED")).doesNotThrowAnyException();

        // 수락 → 복구 → 재신고 → 수락 경로도 같다
        Long third = pending();
        assertThatCode(() -> handle(third, "ACCEPTED")).doesNotThrowAnyException();
        Long fourth = pending();
        assertThatCode(() -> handle(fourth, "ACCEPTED")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("처리 대기 중인 같은 신고는 두 건이 될 수 없다 — 동시 접수를 DB 가 마지막으로 막는다")
    void 처리대기_중복은_막힌다() {
        pending();
        assertThatThrownBy(this::pending).isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("옛 유니크 제약(status 포함)은 남아 있지 않다")
    void 옛_제약은_지워졌다() {
        Integer old = jdbc.queryForObject(
                "select count(*) from pg_constraint where conrelid = 'abuse_reports'::regclass"
                        + " and conname like 'uk_abuse_reports_reporter_%_status'",
                Integer.class);
        assertThat(old).isZero();
    }

    private Long pending() {
        return jdbc.queryForObject(
                "insert into abuse_reports (reporter_id, target_report_id, reason, status, created_at)"
                        + " values (?, ?, 'SPAM', 'PENDING', now()) returning id",
                Long.class, reporterId, reportId);
    }

    private void handle(Long id, String status) {
        jdbc.update("update abuse_reports set status = ?, handled_at = now() where id = ?", status, id);
    }
}
