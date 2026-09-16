package ssafy.a507.backend.domain.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.UpdateTimestamp;

/** 알림·표시 설정. users와 PK를 공유하며, 행이 없으면 전부 기본값이다. */
@Entity
@Table(name = "user_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSetting {

    public enum ChartView {
        BEGINNER,
        ADVANCED
    }

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    /** 판정 결과(HIT/MISS) 알림. */
    @Column(name = "notify_verdict", nullable = false)
    private boolean notifyVerdict;

    /** 시즌 진행·종료·보상 알림. */
    @Column(name = "notify_season", nullable = false)
    private boolean notifySeason;

    /** 내 채널 반응 — 새 구독자·내 리포트 댓글·좋아요. */
    @Column(name = "notify_social", nullable = false)
    private boolean notifySocial;

    /**
     * 구독 채널 소식 — 리포트 발행·예측 등록·구독료 변경. 명세의 셋에 더한 넷째(2026-09-16).
     * 행이 있는 표에 NOT NULL 을 더하므로 DEFAULT 를 같이 준다 — 없으면 ddl-auto 가 조용히 실패한다.
     */
    @Column(name = "notify_channel", nullable = false)
    @ColumnDefault("true")
    private boolean notifyChannel;

    @Enumerated(EnumType.STRING)
    @Column(name = "chart_view", nullable = false, length = 10)
    private ChartView chartView;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** 행이 없을 때의 값 — 전부 켜짐, 초급 뷰. 저장하지 않는 응답용. */
    public static UserSetting defaults() {
        UserSetting s = new UserSetting();
        s.notifyVerdict = true;
        s.notifySeason = true;
        s.notifySocial = true;
        s.notifyChannel = true;
        s.chartView = ChartView.BEGINNER;
        return s;
    }

    /** 첫 변경 때 만드는 행. 값은 {@link #update} 가 바로 덮어쓴다. */
    public static UserSetting defaultsFor(User user) {
        UserSetting s = defaults();
        s.user = user;
        return s;
    }

    public void update(
            boolean notifyVerdict,
            boolean notifySeason,
            boolean notifySocial,
            boolean notifyChannel,
            ChartView chartView) {
        this.notifyVerdict = notifyVerdict;
        this.notifySeason = notifySeason;
        this.notifySocial = notifySocial;
        this.notifyChannel = notifyChannel;
        this.chartView = chartView;
    }
}
