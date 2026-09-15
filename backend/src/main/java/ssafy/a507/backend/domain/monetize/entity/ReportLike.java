package ssafy.a507.backend.domain.monetize.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import ssafy.a507.backend.domain.account.entity.User;

/**
 * 리포트 좋아요 — ANT-REPORT-SOCIAL. 좋아요 수는 {@code count(*)} 로 센다.
 *
 * <p>카운터 컬럼을 두지 않는 이유는 취소가 있기 때문이다. 동시 증감이 유실되지 않게 하려면
 * 잠금이 필요하고, 값이 실제 행 수와 어긋나면 되돌릴 근거가 없다 — {@code post_likes} 와 같은 판단이다.
 * ({@link Report#getViewCount()} 는 반대로 컬럼인데, 그쪽은 감소가 없고 정렬 키다.)
 */
@Entity
@Table(
        name = "report_likes",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_report_likes_report_user",
                        columnNames = {"report_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ReportLike create(Report report, User user) {
        ReportLike like = new ReportLike();
        like.report = report;
        like.user = user;
        return like;
    }
}
