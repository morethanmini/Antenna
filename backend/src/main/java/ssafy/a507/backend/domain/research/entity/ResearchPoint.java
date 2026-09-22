package ssafy.a507.backend.domain.research.entity;

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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import ssafy.a507.backend.domain.market.entity.Stock;

/** 리서치 포인트. 예측 등록 시 "내 근거로 선택"의 대상이 된다. */
@Entity
@Table(name = "research_points")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResearchPoint {

    /** 화면 3열의 축. */
    public enum Kind {
        POSITIVE,
        RISK,
        CHECK
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_code", nullable = false)
    private Stock stock;

    /** 어느 영업일 기준으로 뽑은 포인트인지. */
    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Kind kind;

    @Column(nullable = false, length = 300)
    private String body;

    /** "근거 보기" 원문. 출처 태그는 여기서 파생하며 NULL이면 종합 포인트다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private ResearchDocument document;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 만든 세대({@code app.ai.prompt-version}). 이 칸이 생기기 전(2026-09-23) 행은 NULL 이다.
     *
     * <p>프롬프트·모델을 바꾸고 값을 올리면 같은 종목·같은 기준일에도 다음 조회가 새 세대를 만든다.
     * 옛 행은 지우지 않고 곁에 남긴다 — 사용자가 고른 포인트는 {@code prediction_evidences} 가 FK
     * 로 물고 있어 지우면 남의 예측 근거가 사라진다. 화면은 그날 가장 나중에 만든 세대만 보여 준다.
     */
    @Column(name = "prompt_version", length = 16)
    private String promptVersion;

    /**
     * 생성 (ANT-RESEARCH-04).
     *
     * @param document 근거가 된 원문 · null 이면 여러 재료를 묶은 종합 포인트다
     * @param promptVersion 만든 세대 · {@code AiProperties.promptVersion()}
     */
    public static ResearchPoint of(
            Stock stock, LocalDate targetDate, Kind kind, String body, ResearchDocument document,
            String promptVersion) {
        ResearchPoint point = new ResearchPoint();
        point.stock = stock;
        point.targetDate = targetDate;
        point.kind = kind;
        point.body = body;
        point.document = document;
        point.promptVersion = promptVersion;
        return point;
    }
}
