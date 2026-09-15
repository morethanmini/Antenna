package ssafy.a507.backend.domain.monetize.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import ssafy.a507.backend.domain.chain.dto.ProofResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.report.ReportSeal;

/**
 * GET /api/v1/reports/{reportId}/proof — 리포트 검산 재료 (ANT-CHAIN-17, 화면 D-04).
 *
 * <p><b>판정을 내려 주지 않는다.</b> 검산은 브라우저가 한다 — 서버가 "검증됨" 이라고 말하면 증명이 아니다
 * (D-03 이 세운 원칙 그대로). 여기 담기는 것은 재계산에 필요한 <b>재료</b>뿐이고, 같은지 다른지는 화면이 판단한다.
 *
 * <p>세 단계에 쓰인다.
 * <ul>
 *   <li><b>① 봉인 대조</b> — {@code salt} 로 {@code keccak256(본문 ‖ salt)} 를 접어 {@code bodyHash} 와,
 *       {@code bodyHash} 로 {@link ReportSeal#canonical} 를 접어 {@code reportHash} 와 대조한다.
 *   <li><b>② 배치 포함 증명</b> — {@code anchor.merkleProof} 로 루트를 접고 체인이 답한 루트와 대조한다.
 *   <li><b>③ 작성자 대조</b> — {@code signature} 에서 주소를 복원해 {@code signerAddress} 와 대조한다.
 *       payload 재조립에 {@code signNonce}·{@code chainId} 가 필요하다.
 * </ul>
 *
 * @param salt 공개 리포트는 <b>누구에게나</b>, 구독자 전용은 작성자·ACTIVE 구독자에게만. 그 외 null —
 *     ①의 본문 대조만 잠기고 {@code reportHash} 대조와 ②·③은 그대로 돈다
 * @param signature 서명 이전에 발행된 리포트는 null 이다. ③이 "해당 없음" 이지 실패가 아니다
 * @param anchor 아직 배치에 안 묶였으면 null. {@code anchorStatus} 가 WAITING 이다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportProofResponse(
        Long reportId,
        String title,
        boolean visibility,
        Long authorId,
        Instant publishedAt,
        String bodyHash,
        String reportHash,
        String salt,
        String signature,
        String signerAddress,
        String signNonce,
        Long chainId,
        ProofResponse.Anchor anchor,
        ProofResponse.AnchorStatus anchorStatus) {

    /** 봉인 문자열의 머리글. 화면이 ①을 접을 때 쓰는 값이라 응답에 함께 내린다. */
    public static final String SEAL_HEADER = ReportSeal.HEADER;

    public static ReportProofResponse of(
            Report report,
            String salt,
            Long chainId,
            ProofResponse.Anchor anchor,
            ProofResponse.AnchorStatus anchorStatus) {
        return new ReportProofResponse(
                report.getId(),
                report.getTitle(),
                report.isPublic(),
                report.getUser().getId(),
                report.getCreatedAt(),
                report.getBodyHash(),
                report.getReportHash(),
                salt,
                report.getSignature(),
                report.getSignerAddress(),
                report.getSignNonce(),
                chainId,
                anchor,
                anchorStatus);
    }
}
