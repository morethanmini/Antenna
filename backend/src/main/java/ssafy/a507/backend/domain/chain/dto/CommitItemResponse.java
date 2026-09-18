package ssafy.a507.backend.domain.chain.dto;

import java.time.LocalDate;
import ssafy.a507.backend.domain.chain.anchor.AnchorLeaves;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.entity.PredictionCommit;

/**
 * GET /api/v1/commits 목록 한 줄 (화면 D-01 커밋 원장).
 *
 * <p>원장을 <b>배치가 아니라 봉인 한 건 단위</b>로 읽는 화면이다. 배치 줄에는 종목을 적을 수 없다 — 한 배치에 여러 종목의
 * 커밋이 함께 묶이기 때문이다.
 *
 * <p><b>줄이 두 종류다 (ANT-CHAIN-15).</b> {@code kind} 로 갈리고, 어휘는 배치 상세의 리프
 * ({@link AnchorDetailResponse.Leaf})에서 그대로 가져왔다 — 같은 값을 두 화면이 다른 이름으로 부르지 않게 한다.
 * <ul>
 *   <li>{@code PREDICTION} — {@code predictionId}·{@code stockCode}·{@code stockName}·{@code horizon}·{@code status}
 *       가 차고 {@code reportId}·{@code title}·{@code visibility} 가 null.
 *   <li>{@code REPORT} — {@code reportId}·{@code title}·{@code visibility} 가 차고 나머지가 null.
 *       {@code commitHash} 는 리포트 해시({@code ReportSeal.reportHash})다.
 * </ul>
 * 두 종류에 공통으로 차는 것은 {@code kind}·{@code commitHash} 와 배치 세 칸뿐이다.
 *
 * <p><b>게이팅이 없다</b> — 배치 목록·루트·커밋 해시와 같은 기준이다(결정 F2·F3). 남의 봉인까지 다 보여야
 * 머클루트를 다시 접어 확인할 수 있다.
 *
 * <p><b>예측 줄의 {@code direction}·{@code targetPrice} 는 싣지 않는다.</b> 조인하면 딸려 오지만 판정 전이면
 * 작성자·구독자만 봐야 하는 값이라({@code PredictionViewService.canRead}) 원장이 내리면 E-02·C-03 의 게이팅을 우회한다.
 * <b>리포트 줄의 본문·{@code bodyHash} 도 같은 이유로 없다</b> — 구독자 전용 리포트가 섞여 있고, 원장은
 * "무엇이 언제 봉인됐나" 까지다. 작성자도 두 종류 모두 싣지 않는다.
 *
 * <p>제목과 공개범위는 싣는다 — 리포트 목록이 미구독자에게도 보여 주는 값이다(설계 §5). 가리는 것은 본문이다.
 *
 * @param stockCode 종목이 지워진 예측이 있어 예측 줄에서도 null 일 수 있다. 그 커밋도 원장에서 빠지지 않는다
 * @param anchorId 아직 배치에 묶이지 않았으면 null — 봉인은 됐고 다음 배치를 기다리는 중이다.
 *     {@code businessDate}·{@code anchorStatus} 도 함께 null 이다
 */
public record CommitItemResponse(
        AnchorLeaves.Kind kind,
        String commitHash,
        Long predictionId,
        String stockCode,
        String stockName,
        Short horizon,
        Prediction.Status status,
        Long reportId,
        String title,
        Boolean visibility,
        Long anchorId,
        LocalDate businessDate,
        AnchorBatch.Status anchorStatus) {

    public static CommitItemResponse of(PredictionCommit c) {
        Prediction p = c.getPrediction();
        Stock stock = p.getStock();
        return new CommitItemResponse(
                AnchorLeaves.Kind.PREDICTION,
                c.getCommitHash(),
                c.getPredictionId(),
                stock == null ? null : stock.getCode(),
                stock == null ? null : stock.getName(),
                p.getHorizon(),
                p.getStatus(),
                null,
                null,
                null,
                batchId(c.getAnchorBatch()),
                businessDate(c.getAnchorBatch()),
                batchStatus(c.getAnchorBatch()));
    }

    /** 봉인된 리포트만 원장에 오른다 — {@code reportHash} 가 null 인 줄은 만들 수 없다(레포지토리가 걸러 준다). */
    public static CommitItemResponse of(Report r) {
        return new CommitItemResponse(
                AnchorLeaves.Kind.REPORT,
                r.getReportHash(),
                null,
                null,
                null,
                null,
                null,
                r.getId(),
                // 가려진 리포트는 제목 대신 자리표시 — 해시·배치는 그대로라 원장 증명은 선다(Report.listedTitle)
                r.listedTitle(),
                r.isPublic(),
                batchId(r.getAnchorBatch()),
                businessDate(r.getAnchorBatch()),
                batchStatus(r.getAnchorBatch()));
    }

    private static Long batchId(AnchorBatch batch) {
        return batch == null ? null : batch.getId();
    }

    private static LocalDate businessDate(AnchorBatch batch) {
        return batch == null ? null : batch.getBusinessDate();
    }

    private static AnchorBatch.Status batchStatus(AnchorBatch batch) {
        return batch == null ? null : batch.getStatus();
    }
}
