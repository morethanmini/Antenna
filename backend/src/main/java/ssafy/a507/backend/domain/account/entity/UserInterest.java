package ssafy.a507.backend.domain.account.entity;

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

/**
 * 관심 섹터 (ERD user_interests · ANT-AUTH-05).
 *
 * <p>섹터는 {@code stocks.sector} 와 <b>같은 어휘</b>를 쓴다(ERD). 그래서 별도 enum 을 두지 않고
 * 문자열로 받는다 — 종목 섹터가 늘 때마다 이쪽 enum 을 따라 고쳐야 하는 구조를 만들지 않는다.
 *
 * <p>UQ(user_id, sector) 가 같은 섹터를 두 번 담는 것을 막는다. 다만 프로필 수정은 배열 전체 교체라
 * 서비스가 지우고 다시 넣으므로, 이 제약에 실제로 걸리는 것은 한 요청 안에 같은 값이 두 번 온 경우뿐이다.
 */
@Entity
@Table(
        name = "user_interests",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_user_interests_user_sector",
                        columnNames = {"user_id", "sector"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserInterest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 30)
    private String sector;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static UserInterest of(User user, String sector) {
        UserInterest interest = new UserInterest();
        interest.user = user;
        interest.sector = sector;
        return interest;
    }
}
