package ssafy.a507.backend.domain.chain.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import ssafy.a507.backend.domain.chain.anchor.AnchorLeaves;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;

/**
 * GET /api/v1/anchors/{id} 200 응답 (ANT-CHAIN-06, 화면 D-02).
 *
 * <p>{@code commits} 는 <b>리프 순서(prediction id 오름차순)</b> 그대로다 — 이 순서로 트리를 다시 접으면
 * {@code merkleRoot} 가 나와야 한다. 페이징이 없는 것도 같은 이유다(리프 전량이 있어야 검산이 된다).
 * {@code sentAt · attempts · lastError} 는 운영 가시성용(결정 A5 선택 컬럼).
 *
 * <p><b>predictionId 를 싣는다 — 결정 F2 를 뒤집었다 (ANT-CHAIN-09, 2026-09-07).</b>
 * F2 는 "predictionId 가 붙으면 미판정 예측의 존재가 비구독자에게 샌다" 를 근거로 해시만 내렸는데,
 * 명세 §예측 공개 규칙과 {@link ssafy.a507.backend.domain.prediction.entity.Prediction} javadoc 이 이미
 * "BASE/OPEN 은 비구독자에게 <b>존재와 커밋만</b> 보인다" 로 정하고 있다 — 존재는 원래 공개 대상이고
 * 가리는 것은 내용이다. 여기 실리는 것도 번호와 해시뿐이라 작성자·내용은 드러나지 않고,
 * 내용 게이팅은 {@code GET /predictions/{id}/proof} 가 그대로 막는다(미판정이면 작성자·유효 구독자만).
 * 반대로 F2 를 지키면 D-02 에서 D-03({@code /ledger/verify/:predictionId})으로 넘어갈 길이 없어
 * 온체인 검증 기능의 진입 동선이 통째로 끊긴다. 잃는 것이 얻는 것보다 컸다.
 */
public record AnchorDetailResponse(
        long id,
        LocalDate businessDate,
        String merkleRoot,
        int commitCount,
        AnchorBatch.Status status,
        String txHash,
        Long blockNumber,
        Instant sentAt,
        Instant confirmedAt,
        int attempts,
        String lastError,
        String contractAddress,
        long chainId,
        List<Leaf> commits) {

    /**
     * 리프 하나. {@code commitHash} 는 머클 트리에 들어간 값 그대로이고, {@code predictionId} 는 그 커밋의 주인
     * 예측이다 — 프론트가 이 번호로 3단계 검산 화면(D-03)에 들어간다.
     *
     * <p><b>종목·기간을 함께 싣는다 (S15P21A507-237).</b> 해시만으로는 무엇이 봉인됐는지 알 수 없어 원장이
     * 읽히지 않았다. 둘 다 판정 전에도 공개 대상이다 — {@code ChannelPredictionItemResponse} 가 잠긴 건에도
     * 종목과 {@code horizon} 을 그대로 내리고, 가리는 것은 방향·목표가뿐이다.
     *
     * <p><b>{@code direction}·{@code targetPrice} 는 싣지 않는다.</b> 조인하면 딸려 오지만 판정 전이면
     * 작성자·구독자만 봐야 하는 값이라({@code PredictionViewService.canRead}), 원장이 내리면 E-02·C-03 의
     * 게이팅을 우회하게 된다. 작성자·근거도 같은 이유로 없다 — 원장은 "무엇이 언제 봉인됐나" 까지다.
     *
     * <p>{@code stockCode}·{@code stockName} 은 <b>null 일 수 있다</b>. 종목이 지워진 예측이 있고,
     * 그 커밋도 원장에서 빠지면 안 되기 때문이다(리프 전량이 있어야 루트가 재현된다).
     *
     * <p><b>리프가 두 종류다 (ANT-CHAIN-14).</b> {@code kind} 로 갈린다.
     * <ul>
     *   <li>{@code PREDICTION} — {@code predictionId}·{@code stockCode}·{@code stockName}·{@code horizon} 이 차고
     *       {@code reportId}·{@code title} 이 null. {@code commitHash} 는 예측 커밋 해시다.
     *   <li>{@code REPORT} — {@code reportId}·{@code title} 이 차고 나머지가 null.
     *       {@code commitHash} 는 리포트 해시({@code ReportSeal.reportHash})다.
     * </ul>
     * 필드 이름 {@code commitHash} 를 그대로 둔 이유: 이 값의 뜻이 "머클 트리에 들어간 리프" 이고 두 종류가 같다.
     * 이름을 바꾸면 프론트·명세·컨트랙트 이벤트에서 같은 값이 세 이름으로 불린다.
     *
     * <p>리포트 줄에 <b>본문도 {@code bodyHash} 도 싣지 않는다.</b> 구독자 전용 리포트가 섞여 있고, 원장은
     * "무엇이 언제 봉인됐나" 까지다 — 예측 줄에서 방향·목표가를 뺀 것과 같은 규칙이다. 제목은 목록이
     * 미구독자에게도 보여 주는 값이라(설계 §5) 싣는다.
     */
    public record Leaf(
            AnchorLeaves.Kind kind,
            Long predictionId,
            Long reportId,
            String commitHash,
            String stockCode,
            String stockName,
            Short horizon,
            String title) {

        public static Leaf prediction(
                long predictionId, String commitHash, String stockCode, String stockName, short horizon) {
            return new Leaf(
                    AnchorLeaves.Kind.PREDICTION,
                    predictionId,
                    null,
                    commitHash,
                    stockCode,
                    stockName,
                    horizon,
                    null);
        }

        public static Leaf report(long reportId, String reportHash, String title) {
            return new Leaf(AnchorLeaves.Kind.REPORT, null, reportId, reportHash, null, null, null, title);
        }
    }

    public static AnchorDetailResponse of(AnchorBatch b, List<Leaf> commits) {
        return new AnchorDetailResponse(
                b.getId(),
                b.getBusinessDate(),
                b.getMerkleRoot(),
                b.getCommitCount(),
                b.getStatus(),
                b.getTxHash(),
                b.getBlockNumber(),
                b.getSentAt(),
                b.getConfirmedAt(),
                b.getAttempts(),
                b.getLastError(),
                b.getContractAddress(),
                b.getChainId(),
                commits);
    }
}
