package ssafy.a507.backend.domain.chain.dto;

import java.math.BigDecimal;
import java.math.BigInteger;
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
        EventEntry eventEntry,
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
     * 대회(이벤트)로 등록한 예측일 때만 채운다. 단건 등록이면 <b>객체 자체가 null</b> 이다 — {@link Anchor} 와 같은 판단이다
     * (09-06 결정). 프론트는 "대회 예측인가" 를 이 한 칸의 null 여부로 묻는다.
     *
     * <p><b>왜 필요한가 (ANT-CHAIN-20).</b> 대회 등록은 서명 문자열이 다르다 — 첫 줄이 {@code antenna:event-entry:v1}
     * 이고 {@code eventId} 줄과 {@code 종목/방향/목표가/noteHash/stake} 줄이 들어간다({@code EventEntryService.Signed}).
     * 그런데 그 서명이 단건 등록과 <b>같은 컬럼</b>에 저장되므로, 서식을 구분할 재료가 없으면 화면은 단건 서식으로 조립한다.
     * ECDSA 는 틀린 메시지에도 실패하지 않고 <b>엉뚱한 주소를 뱉으므로</b>, 그 결과가 화면에 "서명자 불일치" 로 떴다.
     * 조작이 아니라 재료가 틀린 것이었다 — 이 세 칸이 그 재료다.
     *
     * @param stake 그 종목에 건 ANT. <b>서명 줄의 마지막 칸이라 검산에 반드시 필요하다.</b> 이 값이 공개되면서
     *     종목별 배팅액이 열린다(전에는 본인만 봤다 — 순위표는 총액만). ③을 살리려면 피할 수 없는 대가다.
     * @param itemCount 같은 서명을 공유하는 커밋 수. 1이면 브라우저가 문자열을 복원할 수 있고, 2 이상이면
     *     형제 종목 줄이 없어 복원이 불가능하다(그때 화면은 ③을 실패가 아니라 <b>잠김</b>으로 그린다).
     *     묶음은 리허설 회차에만 남아 있다 — 등록 화면이 2026-09-17 부터 종목마다 따로 서명한다.
     */
    public record EventEntry(long eventId, BigInteger stake, int itemCount) {}

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
     * {@code basedOn} 도 같은 규칙으로 <b>기준가를 실제로 딴 거래일</b>이다(ANT-PRED-09) — 기준가까지 원천과 대조할 때 이 날짜로 연다.
     */
    public record Settle(
            Prediction.Status status,
            LocalDate settleDate,
            BigDecimal settlePrice,
            BigDecimal basePrice,
            LocalDate basedOn,
            BigDecimal errorRate,
            String sourceUrl) {}
}
