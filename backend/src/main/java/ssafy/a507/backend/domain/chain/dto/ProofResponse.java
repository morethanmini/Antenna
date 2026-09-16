package ssafy.a507.backend.domain.chain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * GET /api/v1/predictions/{id}/proof 200 응답 — 4단계 검산 재료 일체 (ANT-CHAIN-06 · ANT-CHAIN-18, 화면 D-03).
 *
 * <p>서버는 검증하지 않는다. ① {@code hash(payload) == commitHash} ② proof 로 루트 복원 → 체인 {@code anchoredAt(root)}
 * 조회(v3, ANT-CHAIN-13) ③ 서명 문자열 재조립 → 서명자 주소 복원(ANT-CHAIN-18) ④ 판정 종가 ↔ 공공데이터 원본,
 * 넷 다 <b>브라우저가 실행</b>한다. 이 응답은 그 계산의 입력값이다.
 *
 * <p>명세의 평면 필드(merkleProof·merkleRoot·anchorTxHash·anchorBlockNumber)를 {@link Anchor} 하나로 묶었다(09-06 결정).
 * 앵커 전에는 넷이 각각 null 이 아니라 {@code anchor == null} + {@link #anchorStatus} 하나로 "대기" 를 말한다 —
 * 프론트 AnchorBadge 의 입력이 상태값 하나면 된다.
 *
 * <p><b>{@code signNonce}·{@code chainId} 를 함께 내린다(ANT-CHAIN-18).</b> 서명 문자열의 마지막 두 줄이라,
 * 이 둘이 없으면 브라우저가 payload 를 다시 조립할 수 없고 {@code signerAddress} 를 <b>서버 말로만</b> 받게 된다.
 * nonce 는 검증 시점에 Redis 에서 이미 소비된 값이라 공개해도 재사용되지 않는다 — 리포트 검산이 먼저 같은 판단을 했다.
 * 이 기능 이전에 등록된 커밋은 {@code signNonce} 가 null 이고, 그때 ③은 실패가 아니라 잠김이다.
 *
 * <p>비밀은 {@code salt} 하나다(ANT-PRED-02, 09-09 결정 — 커밋 salt 를 따로 두지 않는다). 이 값은 근거 salt 이며
 * <b>작성자·구독자만</b> 받는다(결정 D6) — 근거 본문은 판정 후에도 구독자 전용인데 {@code noteHash} 가 공개되면 짧은 근거를 후보 해시로
 * 맞출 수 있어서다. <b>리빌 조건은 걸지 않는다(2026-09-15)</b> — 받을 자격은 구독이지 시간이 아니라서, 구독자는 등록 직후부터
 * 근거 대조가 된다. 비구독자의 ①단계 검산에는 salt 가 필요 없다: 공개 필드 4개 + {@code payload.noteHash} 로 커밋 문자열을 다시 조립해
 * {@code commitHash} 와 대조하면 된다. 구독자는 한 겹 더 {@code keccak256(note ‖ salt) == noteHash} 를 확인할 수 있다.
 */
public record ProofResponse(
        long predictionId,
        Payload payload,
        String commitHash,
        String signature,
        String signerAddress,
        String signNonce,
        long chainId,
        Instant revealedAt,
        String salt,
        Anchor anchor,
        AnchorStatus anchorStatus,
        Settle settle) {

    /** 커밋 배치 상태 + "아직 배치에 안 들어감"(WAITING). 배치 상태 셋은 {@link AnchorBatch.Status} 와 이름이 같다. */
    public enum AnchorStatus {
        WAITING,
        PENDING,
        CONFIRMED,
        FAILED;

        public static AnchorStatus of(AnchorBatch batch) {
            return batch == null ? WAITING : valueOf(batch.getStatus().name());
        }
    }

    /**
     * 커밋 payload 의 구성 필드. 문자열 조립 규격은 {@code CommitPayload}(줄 구분, 결정 B2) — 여기서는 값만 내린다.
     * {@code noteHash} 는 {@code keccak256(note ‖ salt)}. 커밋 문자열의 마지막 줄이고 바깥 해시의 salt 역할이라 리빌 뒤 전원에게 간다.
     * {@code createdAt} 은 표시용이다 — 커밋 문자열에는 들어가지 않는다.
     */
    public record Payload(
            String stockCode,
            Prediction.Direction direction,
            BigDecimal targetPrice,
            short horizon,
            String noteHash,
            Instant createdAt) {}

    /**
     * 배치와 이 커밋의 자리. {@code merkleProof} 는 아래에서 위로 형제 해시(정렬 결합이라 좌우 정보 없음).
     * {@code contractAddress · chainId} 가 브라우저 체인 조회의 대상이다(결정 F1) — 재배포 뒤에도 이 배치는 <b>이 주소</b>의
     * 장부에서 찾아야 한다. 조회 키는 브라우저가 proof 를 접은 루트다(v3, ANT-CHAIN-13) — {@code batchId} 는 표시·링크용 DB 번호.
     */
    public record Anchor(
            long batchId,
            String merkleRoot,
            List<String> merkleProof,
            int leafIndex,
            int leafCount,
            AnchorBatch.Status status,
            String txHash,
            Long blockNumber,
            Instant confirmedAt,
            String contractAddress,
            long chainId) {}

    /**
     * 판정 결과. HIT/MISS 뒤에만 채운다. {@code sourceUrl} 은 공공데이터포털의 같은 종목·날짜 조회 주소 — 인증키는 붙이지 않으니
     * 사용자가 자기 키로 열어 종가를 대조한다(④).
     *
     * <p>{@code settleDate} 는 <b>실제로 판정에 쓴 거래일</b>(settled_on)이다. 만기일이 휴장이면 그 뒤 첫 거래일이라 만기일과
     * 다를 수 있고, {@code sourceUrl} 의 basDt 와 늘 같다(ANT-CHAIN-19). 옛 행(settled_on 없음)만 만기일로 물러선다.
     */
    public record Settle(
            Prediction.Status status,
            LocalDate settleDate,
            BigDecimal settlePrice,
            BigDecimal basePrice,
            BigDecimal errorRate,
            String sourceUrl) {}
}
