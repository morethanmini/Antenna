package ssafy.a507.backend.domain.monetize.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.domain.chain.anchor.AnchorLeaves;
import ssafy.a507.backend.domain.chain.anchor.AnchorProof;
import ssafy.a507.backend.domain.chain.dto.ProofResponse;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.monetize.dto.ReportProofResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.report.ReportAccess;
import ssafy.a507.backend.domain.monetize.repository.ReportRepository;

/**
 * 리포트 검산 재료 조립 (ANT-CHAIN-17, 화면 D-04).
 *
 * <p>예측 검산({@code ProofService})과 <b>같은 원칙</b>이다 — 판정을 내려 주지 않고 재료만 내린다.
 * 서버가 "검증됨" 이라고 말하면 증명이 아니다.
 *
 * <h4>게이팅이 예측과 다르다</h4>
 *
 * <p>리포트 자체는 <b>아무도 막지 않는다.</b> 제목·작성자·통계가 미구독자에게도 공개라는 것이 이 도메인의
 * 전제이고({@code ReportService}), 봉인 해시는 그보다 더 공개해도 되는 값이다 — 해시로는 본문을 되돌릴 수 없다.
 * 미판정 예측을 403 으로 막는 {@code ProofService} 와 갈리는 지점이다.
 *
 * <p>가리는 것은 {@code salt} 하나다. 그리고 <b>공개 리포트의 salt 는 누구에게나 준다.</b>
 * salt 가 하는 일이 {@code ReportSeal} javadoc 에 하나로 적혀 있다 — "구독자 전용 리포트의 짧은 제목·본문을
 * 비구독자가 후보 해시로 맞추지 못하게 막는 것". 공개 리포트는 본문을 이미 전부 주므로 후보 해시로 맞출
 * 이유가 없고, 숨겨서 지키는 것이 없는데 숨기면 <b>아무도 ①을 못 한다.</b>
 *
 * <p>구독자 전용이면 작성자·ACTIVE 구독자에게만 준다 — 예측의 {@code noteSalt} 와 같은 규칙이고, 판정은
 * {@link ReportAccess} 하나를 쓴다(정의를 둘로 만들지 않는다). 못 받아도 {@code reportHash} 대조와 ②·③은
 * 그대로 돈다: 바깥 해시는 {@code bodyHash} 만 있으면 다시 조립된다(명세 v0.59 가 약속한 그것이다).
 */
@Service
@RequiredArgsConstructor
public class ReportProofService {

    private final ReportRepository reportRepository;
    private final ReportAccess reportAccess;
    private final AnchorProof anchorProof;
    private final SignatureGuard signatureGuard;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public ReportProofResponse proof(Long reportId) {
        Long viewerId = currentUserProvider.currentUserId();
        Report report = reportRepository
                .findDetailById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND, "reportId"));

        /* 봉인 이전에 발행된 리포트(body_hash NULL)는 검산할 거리가 없다. 404 로 숨기지 않는다 —
           리포트는 있고 앵커 대상이 아닐 뿐이라, 화면이 "봉인 이전 발행분" 이라고 말할 수 있어야 한다.
           소급 봉인은 하지 않는다는 결정(ANT-CHAIN-14)의 겉면이다. */
        if (report.getBodyHash() == null) {
            throw new BusinessException(ErrorCode.REPORT_NOT_SEALED, "reportId");
        }

        AnchorBatch batch = report.getAnchorBatch();
        ProofResponse.Anchor anchor =
                batch == null ? null : anchorProof.of(AnchorLeaves.Kind.REPORT, report.getId(), batch);

        return ReportProofResponse.of(
                report,
                saltFor(report, viewerId),
                signatureGuard.chainId(),
                anchor,
                ProofResponse.AnchorStatus.of(batch));
    }

    /** 공개 리포트는 전원, 구독자 전용은 작성자·ACTIVE 구독자만. 그 외 null — 잠김이지 실패가 아니다. */
    private String saltFor(Report report, Long viewerId) {
        return reportAccess.isLocked(report, viewerId) ? null : report.getSalt();
    }
}
