package ssafy.a507.backend.domain.monetize.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.monetize.report.ReportSeal;

/** 예측자 리포트. 목록(제목)은 미구독자에게도 보여 구독 유인으로 쓴다. */
@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

    /**
     * 노출 상태. 신고가 승인되면 BLOCKED 로 내려간다(ANT-FE-ABUSE-REPORT · 관리자 ACCEPT).
     *
     * <p><b>행을 지우지 않고 상태로 가리는 이유</b> — 리포트는 봉인돼 체인에 앵커된다. 행이 사라지면
     * 원장·검산이 가리키던 잎이 증발해 "위변조 불가" 약속이 깨진다. 그래서 피드·채널·검색·상세에서만
     * 빼고, 원장과 검산 경로({@code findLedgerPage} · 앵커 배치)는 그대로 둔다.
     */
    public enum Status {
        VISIBLE,
        BLOCKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String title;

    /** 20000자 상한은 애플리케이션에서 검증한다. */
    @Column(columnDefinition = "text")
    private String body;

    /** true면 본문 전체 공개, false면 미구독자에게 본문을 보여 주지 않는다. */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    /**
     * 열람 수. {@code GET /reports/{id}} 에서 증가하며 {@code sort=POPULAR} 정렬 키다(ERD v5).
     *
     * <p>증가는 이 필드에 직접 쓰지 않고 리포지토리의 UPDATE 로 처리한다 — 읽어서 +1 해
     * 저장하면 같은 리포트를 동시에 연 두 요청 중 하나가 사라진다.
     *
     * <p>DB 기본값 0 을 함께 박는 이유 — 이 컬럼은 나중에 추가된 NOT NULL 컬럼이다.
     * {@code ddl-auto: update} 는 기존 행이 있는 테이블에 기본값 없는 NOT NULL 컬럼을 붙일 수
     * 없고, 컬럼을 명시하지 않는 SQL(테스트의 native insert 등)도 실패한다.
     */
    @ColumnDefault("0")
    @Column(name = "view_count", nullable = false)
    private int viewCount;

    /**
     * 본문 봉인. {@code keccak256(body ‖ salt)} — {@link ReportSeal} 참고.
     *
     * <p>이 값이 있어야 앵커 대상이 된다. nullable 인 이유는 둘이다: ① {@code ddl-auto} 가 기존 행이 있는 테이블에
     * NOT NULL 컬럼을 못 붙인다 ② 봉인 전에 발행된 리포트(이 기능 이전 것)를 앵커 대기 목록에서 자연스럽게 빼려면
     * "해시가 없으면 대상이 아니다" 한 줄이면 된다.
     */
    @Column(name = "body_hash", length = 66)
    private String bodyHash;

    /** 머클 트리의 잎. {@code keccak256(ReportSeal.canonical)}. 같은 값이 두 번 나올 수 없어 UQ 다 — 커밋 해시와 같다. */
    @Column(name = "report_hash", length = 66, unique = true)
    private String reportHash;

    /**
     * 본문 salt. 서버가 만든 32바이트(64 hex)다 — 이유는 {@link ReportSeal} javadoc.
     *
     * <p>구독자 전용 리포트의 본문을 비구독자가 후보 해시로 맞추지 못하게 막는 값이라 <b>직렬화에서 항상 뺀다</b>.
     * 예측의 {@code PredictionCommit.salt} 와 같은 취급이다.
     */
    @JsonIgnore
    @Column(length = 64)
    private String salt;

    /**
     * 발행 지갑 서명 (ANT-CHAIN-16). {@code 0x} + r(32) s(32) v(1) = 132자.
     *
     * <p><b>해시 재료가 아니다.</b> {@code reportHash} 는 그대로이고 서명은 여기 따로 남는다 —
     * 예측이 {@code prediction_commits.signature} 를 커밋 해시 밖에 두는 것과 같은 구조다. 덕분에
     * 이 기능 이전에 봉인된 리포트도 봉인 규격이 바뀌지 않아 그대로 검증된다.
     *
     * <p>nullable 인 이유가 둘이다: ① {@code ddl-auto} 가 기존 행이 있는 표에 기본값 없는 NOT NULL 을
     * 못 붙인다 ② 이 기능 이전 발행분은 서명이 없다 — NULL 이 곧 "서명 이전 발행분" 이라는 표시다.
     */
    @Column(length = 132)
    private String signature;

    /**
     * 서명에서 복원한 지갑 주소. 소문자 0x + 40자.
     *
     * <p>이 값이 {@code users.wallet_address} 와 같다는 것을 {@code SignatureGuard.verify} 가 확인한 뒤 저장한다.
     * <b>작성자 증명은 이 칸이 한다</b> — {@code reportHash} 재료의 {@code authorId} 는 서버가 넣은 값이라
     * 그것만으로는 서버를 믿어야 성립한다.
     */
    @Column(name = "signer_address", length = 42)
    private String signerAddress;

    /**
     * 서명 payload 에 박힌 1회용 nonce (ANT-CHAIN-17).
     *
     * <p>검산이 이 값을 쓴다. 브라우저가 {@code signerAddress} 를 그대로 믿지 않고 서명에서 직접 주소를
     * 복원하려면 payload 를 재조립해야 하고, 거기에 nonce 가 들어간다. Redis 의 nonce 는 소비되면
     * 사라지므로 여기 남긴다. <b>공개해도 안전하다</b> — 이미 태운 값이라 그 서명을 다시 쓸 수 없다.
     */
    @Column(name = "sign_nonce", length = 64)
    private String signNonce;

    /**
     * 노출 상태. 해시 재료가 아니다 — 가려도 봉인은 그대로 검증된다.
     *
     * <p>DB 기본값 VISIBLE 을 함께 박는다. 행이 있는 표에 뒤늦게 붙는 NOT NULL 컬럼이라
     * {@code ddl-auto: update} 가 기본값 없이는 추가하지 못한다({@code viewCount} 와 같은 이유).
     */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'VISIBLE'")
    @Column(nullable = false, length = 10)
    private Status status;

    /** 소속 앵커 배치. NULL 이면 앵커 대기 상태다 — {@code PredictionCommit.anchorBatch} 와 같은 뜻이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "anchor_batch_id")
    private AnchorBatch anchorBatch;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 리포트 발행. 본문 길이 상한과 공개 범위는 호출부(요청 DTO)가 이미 검증했다. */
    public static Report create(User user, String title, String body, boolean isPublic) {
        Report report = new Report();
        report.user = user;
        report.title = title;
        report.body = body;
        report.isPublic = isPublic;
        report.viewCount = 0;
        report.status = Status.VISIBLE;
        return report;
    }

    /**
     * 발행 직후 봉인한다(ANT-CHAIN-14). 계산은 {@link ReportSeal} 이 끝내고 온다 — 엔티티는 계산하지 않는다.
     *
     * <p>한 번만 부른다. 리포트에는 수정 API 가 없고, 있어도 봉인을 다시 하면 체인에 박힌 해시와 어긋난다.
     */
    public void seal(ReportSeal seal) {
        this.salt = seal.salt();
        this.bodyHash = seal.bodyHash();
        this.reportHash = seal.reportHash();
    }

    /**
     * 발행 서명을 남긴다 (ANT-CHAIN-16). 봉인과 나눠 부르는 이유는 둘이 다른 것을 증명해서다 —
     * 봉인은 "이 내용이 안 바뀌었다", 서명은 "이 지갑 주인이 썼다".
     */
    public void sign(String signature, String signerAddress, String signNonce) {
        this.signature = signature;
        this.signerAddress = signerAddress;
        this.signNonce = signNonce;
    }

    /**
     * 앵커 배치에 넣는다. 한 번 넣으면 바꾸지 않는다 — 옮기면 이미 계산된 루트와 리프 목록이 어긋난다
     * ({@code PredictionCommit.assignBatch} 와 같은 규칙).
     */
    public void assignBatch(AnchorBatch batch) {
        if (this.anchorBatch == null) {
            this.anchorBatch = batch;
        }
    }

    /** 관리자 처리. 신고 승인(ACCEPT) 때 BLOCKED 로 내린다. */
    public void changeStatus(Status status) {
        this.status = status;
    }

    public boolean isBlocked() {
        return status == Status.BLOCKED;
    }

    /** 가려진 리포트가 원장·앵커 목록에 나올 때 제목 대신 쓰는 말 */
    public static final String BLOCKED_TITLE = "신고로 가려진 리포트";

    /**
     * 목록에 보여 줄 제목. 가려졌으면 {@link #BLOCKED_TITLE}.
     *
     * <p><b>원장·앵커 목록 전용이다. 검산(proof)에는 쓰지 않는다</b> — 제목은 봉인 문자열의 재료라
     * ({@link ReportSeal#canonical}) 바꿔 내리면 검산이 "조작됨" 으로 틀리게 나온다. 목록에서 필요한 것은
     * 해시이고 제목은 표시용이라, 가려도 증명은 그대로 선다. 검산은 대신 상세처럼 막는다(ReportAccess).
     */
    public String listedTitle() {
        return isBlocked() ? BLOCKED_TITLE : title;
    }
}
