package ssafy.a507.backend.domain.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.CreationTimestamp;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.entity.ReportComment;

/**
 * 신고. 대상 다섯(글·댓글·유저·리포트·리포트 댓글) 중 정확히 하나만 채워야 하고 자기 신고는 막는다.
 *
 * 리포트·리포트 댓글은 ANT-FE-ABUSE-REPORT 에서 더했다. 커뮤니티(글·댓글)가 개발 범위에서 빠지고
 * 리포트가 그 자리를 맡으면서, 사용자가 실제로 보는 글이 리포트 쪽 표에 있게 됐다. 리포트 댓글을
 * 기존 COMMENT 로 받지 않는 이유는 표가 다르기 때문이다 — post_comments 와 report_comments 는
 * id 번호 체계가 달라, 같은 칼럼에 넣으면 엉뚱한 댓글을 가리키게 된다.
 *
 * 두 규칙 다 CHECK 로 DB 에 건다. 애플리케이션 쪽 정적 팩터리·서비스 검사가
 * 이미 막고 있지만, 배치나 직접 SQL 처럼 그 경로를 타지 않는 쓰기가 언제든 생긴다.
 *
 * 자기 신고 CHECK 는 유저 신고만 덮는다 — 글·댓글·리포트의 작성자는 다른 테이블에 있어
 * CHECK 로는 볼 수 없다. 그쪽은 AbuseReportService 가 유일한 방어선이다.
 *
 * 중복 신고는 <b>처리 대기(PENDING) 중인 것끼리만</b> 막는다 — db/enum-checks.sql 의 부분 유니크 인덱스
 * {@code uq_abuse_reports_pending_*}(… WHERE status = 'PENDING'). 관리자가 처리한 뒤의 재신고는 새 사안이다.
 *
 * 전에는 (신고자, 대상, status) 유니크 제약이었다. status 를 키에 넣으면 PENDING 끼리는 막지만
 * <b>처리된 행끼리도 부딪힌다</b> — 같은 사람이 같은 대상을 두 번 신고해 두 번 다 기각(또는 수락)되면
 * 두 번째 처리의 UPDATE 가 (신고자, 대상, REJECTED) 중복으로 500 이 났고, 그 신고는 영원히 PENDING 에
 * 남았다(ANT-FE-ABUSE-REPORT 검토에서 실제 PostgreSQL 로 재현). 규칙은 "처리 대기 중복 금지" 인데 키는
 * "상태별 한 건" 이었던 것이다. 부분 인덱스는 JPA 로 표현할 수 없어 SQL 로 건다 — 그래서 여기에는
 * {@code @UniqueConstraint} 를 두지 않는다(두면 ddl-auto 가 매 부팅 옛 제약을 다시 만든다).
 * H2 테스트에는 이 인덱스가 없고 서비스의 중복 검사만 돈다. 인덱스 자체는
 * {@code AbuseReportPendingUniqueTest}(실제 PostgreSQL)가 본다.
 */
@Entity
@Table(name = "abuse_reports")
// Postgres 의 num_nonnulls 가 짧지만 H2 에 없어 테스트가 통째로 깨진다. CASE 합산은 양쪽에서 돈다.
// 이미 있는 DB 에는 ddl-auto 가 이 제약을 고쳐 주지 않는다 — db/enum-checks.sql 이 같은 글자로 다시 건다.
// 여기를 고치면 그 파일도 같이 고친다(한쪽만 고치면 새 DB 와 옛 DB 가 다른 규칙을 갖는다).
@Check(
        name = "ck_abuse_reports_exactly_one_target",
        constraints = "(case when target_post_id is null then 0 else 1 end"
                + " + case when target_comment_id is null then 0 else 1 end"
                + " + case when target_user_id is null then 0 else 1 end"
                + " + case when target_report_id is null then 0 else 1 end"
                + " + case when target_report_comment_id is null then 0 else 1 end) = 1")
@Check(
        name = "ck_abuse_reports_no_self_report",
        constraints = "target_user_id is null or target_user_id <> reporter_id")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbuseReport {

    public enum Reason {
        SPAM,
        ABUSE,
        FRAUD,
        ETC
    }

    /** ACCEPTED면 대상을 BLOCKED로 내린다. */
    public enum Status {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_post_id")
    private FeedPost targetPost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_comment_id")
    private PostComment targetComment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private User targetUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_report_id")
    private Report targetReport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_report_comment_id")
    private ReportComment targetReportComment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Reason reason;

    /** 상세 사유. 선택 입력이다. */
    @Column(length = 300)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by")
    private User handledBy;

    @Column(name = "handled_at")
    private Instant handledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /*
     * 대상별 정적 팩터리 다섯. 생성 경로를 대상마다 나눠두면
     * "대상 중 정확히 하나"를 런타임 검사 없이 타입 수준에서 지킬 수 있다.
     */

    public static AbuseReport againstPost(User reporter, FeedPost post, Reason reason, String detail) {
        AbuseReport report = newPending(reporter, reason, detail);
        report.targetPost = post;
        return report;
    }

    public static AbuseReport againstComment(User reporter, PostComment comment, Reason reason, String detail) {
        AbuseReport report = newPending(reporter, reason, detail);
        report.targetComment = comment;
        return report;
    }

    public static AbuseReport againstUser(User reporter, User target, Reason reason, String detail) {
        AbuseReport report = newPending(reporter, reason, detail);
        report.targetUser = target;
        return report;
    }

    public static AbuseReport againstReport(User reporter, Report target, Reason reason, String detail) {
        AbuseReport report = newPending(reporter, reason, detail);
        report.targetReport = target;
        return report;
    }

    public static AbuseReport againstReportComment(
            User reporter, ReportComment comment, Reason reason, String detail) {
        AbuseReport report = newPending(reporter, reason, detail);
        report.targetReportComment = comment;
        return report;
    }

    private static AbuseReport newPending(User reporter, Reason reason, String detail) {
        AbuseReport report = new AbuseReport();
        report.reporter = reporter;
        report.reason = reason;
        report.detail = detail;
        report.status = Status.PENDING;
        return report;
    }

    /*
     * 관리자 처리 (ANT-ADMIN-01). 처리자와 처리 시각을 여기서 함께 박는다 —
     * 상태만 바꾸고 handledBy 를 서비스가 따로 넣게 두면 한쪽을 빠뜨린 행이 생긴다.
     */

    /** 신고를 받아들인다. 대상을 BLOCKED 로 내리는 것은 서비스 몫이다 — 대상 종류마다 다르므로. */
    public void accept(User admin) {
        handle(Status.ACCEPTED, admin);
    }

    /** 신고를 기각한다. 대상은 건드리지 않는다. */
    public void reject(User admin) {
        handle(Status.REJECTED, admin);
    }

    private void handle(Status next, User admin) {
        this.status = next;
        this.handledBy = admin;
        this.handledAt = Instant.now();
    }

    /** 아직 처리 전인가. 두 번 처리하는 것을 서비스가 막을 때 쓴다. */
    public boolean isPending() {
        return status == Status.PENDING;
    }
}
