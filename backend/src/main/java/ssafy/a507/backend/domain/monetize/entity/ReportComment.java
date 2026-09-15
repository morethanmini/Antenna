package ssafy.a507.backend.domain.monetize.entity;

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
import org.hibernate.annotations.CreationTimestamp;
import ssafy.a507.backend.domain.account.entity.User;

/**
 * 리포트 댓글 — ANT-REPORT-SOCIAL. 대댓글은 두지 않는다(필요해지면 parent_id 를 붙인다).
 *
 * <p>커뮤니티의 {@code PostComment} 와 모양이 같지만 다른 표다. 그쪽 표를 리포트로 돌리지 않은
 * 이유는 {@code ddl-auto: update} 가 컬럼 이름을 바꾸지 못하고, 행이 있는 표에 기본값 없는
 * NOT NULL FK 를 붙이지도 못하기 때문이다 — 배포 서버의 {@code post_comments} 를 손대면
 * 기동이 깨진다. 커뮤니티는 개발 범위에서 빠졌지만 코드와 데이터는 그대로 둔다.
 *
 * <p>해시 앵커링 대상이 아니다. 앵커는 "수정되지 않았다" 를 약속하는데 댓글은 신고·블라인드로
 * 가려져야 한다 — 리포트({@link Report#seal})·예측과 다른 취급이다.
 */
@Entity
@Table(name = "report_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportComment {

    public enum Status {
        VISIBLE,
        BLOCKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 500)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ReportComment create(Report report, User user, String body) {
        ReportComment comment = new ReportComment();
        comment.report = report;
        comment.user = user;
        comment.body = body;
        comment.status = Status.VISIBLE;
        return comment;
    }

    public boolean isBlocked() {
        return status == Status.BLOCKED;
    }
}
