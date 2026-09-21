package ssafy.a507.backend.domain.monetize.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
/* 목록도 개수도 report_id 로 거른다 — 리포트 상세를 열 때마다 두 번 탄다.
   id 를 뒤에 붙인 이유는 페이지 쿼리가 `where report_id = ? and id > ? order by id asc` 라
   같은 인덱스로 거르기와 정렬이 함께 끝나기 때문이다. FK 는 참조하는 쪽에 인덱스를
   만들어 주지 않는다(post_comments 가 그래서 없다). */
@Table(
        name = "report_comments",
        indexes = @Index(name = "ix_report_comments_report", columnList = "report_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportComment {

    public enum Status {
        VISIBLE,
        BLOCKED,
        /** 작성자가 지웠다. 행은 남긴다 — 신고(abuse_reports)·좋아요가 물고 있고, 신고당한 댓글을 지워 증거를 없앨 수 없어야 한다 */
        DELETED
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

    /** 목록에 나오고 반응할 수 있는가. BLOCKED 도 DELETED 도 없는 것처럼 다룬다 */
    public boolean isVisible() {
        return status == Status.VISIBLE;
    }

    /** 작성자 삭제(QA 09-21 — 도배는 막았지만 올라간 댓글을 지울 방법이 없었다). 목록·개수 쿼리는 VISIBLE 만 센다 */
    public void delete() {
        this.status = Status.DELETED;
    }

    /** 관리자 처리. 신고 승인(ACCEPT) 때 BLOCKED 로 내린다 — 목록·개수 쿼리가 이미 BLOCKED 를 뺀다. */
    public void changeStatus(Status status) {
        this.status = status;
    }
}
