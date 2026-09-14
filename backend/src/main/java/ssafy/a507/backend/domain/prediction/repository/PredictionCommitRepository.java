package ssafy.a507.backend.domain.prediction.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.entity.PredictionCommit;

public interface PredictionCommitRepository extends JpaRepository<PredictionCommit, Long> {

    /**
     * 앵커 대기 커밋. <b>prediction id 오름차순이 곧 리프 순서</b>다(ANT-CHAIN-02) — 재계산·복구 때
     * 같은 순서로 넣어야 같은 트리가 나온다. 배치가 이 순서로 커밋을 넣고 컨트랙트 이벤트도 이 순서로 남긴다.
     */
    List<PredictionCommit> findByAnchorBatchIsNullOrderByPredictionIdAsc();

    /** 실패 배치를 같은 내용으로 재전송할 때. 순서 규칙은 위와 같다. */
    List<PredictionCommit> findByAnchorBatchOrderByPredictionIdAsc(AnchorBatch anchorBatch);

    /**
     * 배치 리프 전량 + 예측·종목까지 한 번에 (D-02 가 종목명·기간을 함께 그린다, S15P21A507-237).
     *
     * <p>{@code prediction} 은 {@code @MapsId} 라 항상 있어 {@code join fetch} 지만, {@code stock} 은
     * <b>nullable 이라 left 다</b> — inner 로 걸면 종목이 지워진 커밋이 리프 목록에서 빠져 원장이 실제보다
     * 짧아지고, 그 목록으로는 머클루트를 재현할 수 없다.
     *
     * <p>두 연관 모두 {@code LAZY} 라 fetch 하지 않으면 리프 수(최대 500)만큼 N+1 이 난다.
     * 순서 규칙은 {@link #findByAnchorBatchOrderByPredictionIdAsc} 와 같다.
     */
    @Query("select c from PredictionCommit c"
            + " join fetch c.prediction p left join fetch p.stock"
            + " where c.anchorBatch = :batch order by c.predictionId asc")
    List<PredictionCommit> findLeavesWithPredictionByAnchorBatch(@Param("batch") AnchorBatch batch);

    /**
     * 목록 한 장의 커밋을 배치까지 한 번에 (ANT-PRED-06 anchorStatus, 프론트 요청 09-09). 항목마다 proof 를 따로 부르면
     * 한 페이지에 요청이 12번 나가서 목록 응답에 상태를 싣는다. 배치를 {@code join fetch} 하지 않으면 여기서 N+1 이 난다.
     */
    @Query("select c from PredictionCommit c left join fetch c.anchorBatch where c.predictionId in :ids")
    List<PredictionCommit> findWithBatchByPredictionIdIn(@Param("ids") Collection<Long> ids);

    /** 리빌 대상 — 판정이 끝났는데 salt 가 아직 비공개인 커밋 (결정 A9: 판정 기준). */
    @Query(
            "select c from PredictionCommit c join c.prediction p"
                    + " where c.revealedAt is null and p.status in :statuses order by c.predictionId")
    List<PredictionCommit> findUnrevealedByPredictionStatusIn(
            @Param("statuses") Collection<Prediction.Status> statuses);
}
