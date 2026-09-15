package ssafy.a507.backend.domain.account.entity;

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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * 예측자의 외부 채널 (ERD user_channels · ANT-AUTH-05).
 *
 * <p>플랫폼은 화면 아이콘을 정하는 값이라 네 가지로 닫는다(ERD). 섹터와 달리 서비스가 늘려 가는
 * 어휘가 아니므로 enum 으로 둔다 — 오타난 플랫폼이 들어오면 아이콘 없이 그려지는 편보다 400 이 낫다.
 *
 * <p><b>url 은 https 만 받는다</b>(ERD · 명세 §회원). 남의 프로필에 걸리는 링크라 http·javascript:
 * 같은 것을 그대로 두면 피싱 통로가 된다. 검사는 요청 DTO 가 한다 — 엔티티는 이미 통과한 값을 담는다.
 */
@Entity
@Table(
        name = "user_channels",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_user_channels_user_url",
                        columnNames = {"user_id", "url"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserChannel {

    /** 화면 아이콘을 정한다. 표에 없는 곳은 ETC 로 담는다. */
    public enum Platform {
        YOUTUBE,
        BLOG,
        X,
        ETC
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Platform platform;

    @Column(nullable = false, length = 300)
    private String url;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static UserChannel of(User user, Platform platform, String url) {
        UserChannel channel = new UserChannel();
        channel.user = user;
        channel.platform = platform;
        channel.url = url;
        return channel;
    }
}
