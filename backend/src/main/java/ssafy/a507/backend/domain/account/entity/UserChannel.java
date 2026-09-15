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
 * <p>플랫폼은 <b>화면 아이콘을 정하는 값</b>이라 네 가지로 닫는다(ERD). 섹터와 달리 서비스가 늘려
 * 가는 어휘가 아니므로 enum 으로 둔다 — 오타난 플랫폼이 들어오면 아이콘 없이 그려지는 편보다 400 이 낫다.
 *
 * <p><b>이름은 따로 받는다</b>({@code label}, 2026-09-15). 표에 없는 곳(브런치·디스코드 …)을 ETC 로만
 * 담으면 화면에 죄다 "기타" 로 뜬다. 아이콘은 enum 이 정하고 이름은 사용자가 정한다 — 둘을 한 칼럼에
 * 섞으면(플랫폼을 자유 문자열로) 아이콘 대응표가 오타에 무너진다.
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

    /**
     * 화면에 보일 이름. 비어 있으면 플랫폼의 기본 이름을 쓴다(화면이 정한다).
     *
     * <p>ERD V3 에는 없는 칼럼이다 — 사용자가 직접 이름을 붙이는 요구가 뒤에 왔다.
     */
    @Column(length = 30)
    private String label;

    @Column(nullable = false, length = 300)
    private String url;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static UserChannel of(User user, Platform platform, String label, String url) {
        UserChannel channel = new UserChannel();
        channel.user = user;
        channel.platform = platform;
        // 공백만 있는 이름은 없는 것으로 본다 — 화면에서 빈 칸으로 보이느니 기본 이름이 낫다
        channel.label = (label == null || label.isBlank()) ? null : label.strip();
        channel.url = url;
        return channel;
    }
}
