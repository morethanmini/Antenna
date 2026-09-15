package ssafy.a507.backend.domain.prediction.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Limit;
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

    /**
     * 커밋 원장 목록(화면 D-01) — 최신 봉인부터 한 장.
     *
     * <p><b>정렬 기준이 {@code predictionId} 에서 {@code prediction.createdAt} 으로 바뀌었다 (ANT-CHAIN-15).</b>
     * 원장이 예측 커밋과 리포트를 한 목록으로 내리게 되면서, 두 표를 섞을 공통 기준이 등록 시각밖에 없다 —
     * 두 id 는 다른 시퀀스라 크기를 비교할 뜻이 없다. 같은 시각 안에서는 예전처럼 id 내림차순이다.
     *
     * <p>커서는 {@code CommitQueryService.LedgerCursor} 가 만든 {@code (createdAt, kind, id)} 셋이다.
     *
     * <p><b>파라미터에 null 을 넣지 않는다.</b> 전에는 {@code :at is null} 로 "커서 없음" 을 표현했는데
     * PostgreSQL 이 <b>null 타임스탬프 파라미터의 타입을 정하지 못해</b> 질의를 통째로 거절했다
     * ({@code ERROR: could not determine data type of parameter $1}). H2 는 그대로 통과시켜서
     * 로컬 테스트 전부가 초록이었고 운영에서만 500 이 났다. 그래서 경계값을 서비스가 정해 넣는다 —
     * 첫 장이면 {@code at} 이 먼 미래라 첫 항이 늘 참이고, 커서가 리포트를 가리키면 {@code cursorId} 가
     * {@code Long.MIN_VALUE} 라 같은 시각 예측이 자연히 빠진다(그 건들은 앞 장에서 이미 나갔다).
     *
     * <p>연관 셋을 모두 fetch 한다 — 한 장에 20건이면 fetch 없이 60번의 추가 질의가 난다.
     * {@code stock} 과 {@code anchorBatch} 는 <b>left</b> 다: 종목이 지워진 커밋과 아직 배치에 안 묶인 커밋도
     * 원장에 남아야 한다(빠지면 "봉인했는데 원장에 없다" 가 된다).
     */
    @Query("""
            select c from PredictionCommit c
             join fetch c.prediction p
             left join fetch p.stock
             left join fetch c.anchorBatch
             where p.createdAt < :at
                or (p.createdAt = :at and c.predictionId < :cursorId)
             order by p.createdAt desc, c.predictionId desc
            """)
    List<PredictionCommit> findLedgerPage(
            @Param("at") Instant at, @Param("cursorId") Long cursorId, Limit limit);

    /** 리빌 대상 — 판정이 끝났는데 salt 가 아직 비공개인 커밋 (결정 A9: 판정 기준). */
    @Query(
            "select c from PredictionCommit c join c.prediction p"
                    + " where c.revealedAt is null and p.status in :statuses order by c.predictionId")
    List<PredictionCommit> findUnrevealedByPredictionStatusIn(
            @Param("statuses") Collection<Prediction.Status> statuses);
}
