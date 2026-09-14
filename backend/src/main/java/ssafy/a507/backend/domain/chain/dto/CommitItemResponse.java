package ssafy.a507.backend.domain.chain.dto;

import java.time.LocalDate;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.entity.PredictionCommit;

/**
 * GET /api/v1/commits 목록 한 줄 (화면 D-01 커밋 원장).
 *
 * <p>원장을 <b>배치가 아니라 커밋 단위</b>로 읽는 화면이다. 배치 줄에는 종목을 적을 수 없다 — 한 배치에 여러 종목의
 * 커밋이 함께 묶이기 때문이다. 그래서 봉인 한 건을 한 줄로 내린다.
 *
 * <p><b>게이팅이 없다</b> — 배치 목록·루트·커밋 해시와 같은 기준이다(결정 F2·F3). 종목과 {@code horizon} 은 판정 전에도
 * 공개 대상이고({@code ChannelPredictionItemResponse} 가 잠긴 건에도 그대로 내린다), 가리는 것은 방향·목표가다.
 *
 * <p><b>{@code direction}·{@code targetPrice} 는 싣지 않는다.</b> 조인하면 딸려 오지만 판정 전이면 작성자·구독자만
 * 봐야 하는 값이라({@code PredictionViewService.canRead}) 원장이 내리면 E-02·C-03 의 게이팅을 우회한다.
 * 작성자·근거도 같은 이유로 없다 — 원장은 "무엇이 언제 봉인됐나" 까지다.
 *
 * @param stockCode 종목이 지워진 예측이 있어 null 일 수 있다. 그 커밋도 원장에서 빠지지 않는다
 * @param anchorId 아직 배치에 묶이지 않았으면 null — 봉인은 됐고 다음 배치를 기다리는 중이다.
 *     {@code businessDate}·{@code anchorStatus} 도 함께 null 이다
 */
public record CommitItemResponse(
        long predictionId,
        String commitHash,
        String stockCode,
        String stockName,
        short horizon,
        Prediction.Status status,
        Long anchorId,
        LocalDate businessDate,
        AnchorBatch.Status anchorStatus) {

    public static CommitItemResponse of(PredictionCommit c) {
        Prediction p = c.getPrediction();
        Stock stock = p.getStock();
        AnchorBatch batch = c.getAnchorBatch();
        return new CommitItemResponse(
                c.getPredictionId(),
                c.getCommitHash(),
                stock == null ? null : stock.getCode(),
                stock == null ? null : stock.getName(),
                p.getHorizon(),
                p.getStatus(),
                batch == null ? null : batch.getId(),
                batch == null ? null : batch.getBusinessDate(),
                batch == null ? null : batch.getStatus());
    }
}
