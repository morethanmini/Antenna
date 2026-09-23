package ssafy.a507.backend.domain.community.entity;

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

/** 댓글. 대댓글은 두지 않는다(필요해지면 parent_id를 붙인다). */
@Entity
/* 피드 목록의 댓글 수 집계가 `where post_id in (...) and status = ? group by post_id` 다. FK 는 참조하는
   쪽에 인덱스를 만들어 주지 않아 20만 행을 매번 전부 읽었다(09-16 측정, 37ms → 0.7ms). */
@Table(
        name = "post_comments",
        indexes = @Index(name = "ix_post_comments_post_status", columnList = "post_id, status"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostComment {

    public enum Status {
        VISIBLE,
        BLOCKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private FeedPost post;

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

    public static PostComment create(FeedPost post, User user, String body) {
        PostComment comment = new PostComment();
        comment.post = post;
        comment.user = user;
        comment.body = body;
        comment.status = Status.VISIBLE;
        return comment;
    }

    public boolean isBlocked() {
        return status == Status.BLOCKED;
    }

    /** 관리자 블라인드 (ANT-ADMIN-01). 행은 그대로 두고 상태만 내린다. */
    public void changeStatus(Status next) {
        this.status = next;
    }
}
