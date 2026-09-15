package ssafy.a507.backend.domain.chain.anchor;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.repository.ReportRepository;
import ssafy.a507.backend.domain.prediction.entity.PredictionCommit;
import ssafy.a507.backend.domain.prediction.repository.PredictionCommitRepository;

/**
 * 한 배치의 리프 목록을 만드는 <b>유일한 곳</b> (ANT-CHAIN-14).
 *
 * <p>왜 클래스를 따로 두는가 — 리프 목록을 만드는 코드가 세 곳에 있고, <b>셋이 한 글자라도 어긋나면 루트가 달라진다.</b>
 * <ol>
 *   <li>{@link AnchorBatchService#openBatch} — 배치를 만들 때 (여기서 나온 루트가 체인에 박힌다)
 *   <li>{@link AnchorBatchService#payloadOf} — 재전송할 때
 *   <li>{@code ProofService.anchorOf} — 검산 화면에 머클 증명을 내릴 때
 * </ol>
 * 3번이 어긋나면 {@code ProofService} 의 방어선("접은 루트가 저장된 루트와 다르면 500")이 걸려 <b>모든 예측 검산이
 * 죽는다.</b> 리포트를 트리에 넣으면서 실제로 걸릴 뻔한 자리라 순서 규칙을 이 한 곳에 못박는다.
 *
 * <h2>순서 규칙</h2>
 *
 * <pre>
 * ① 예측 커밋 — prediction_id 오름차순
 * ② 리포트    — report_id 오름차순
 * </pre>
 *
 * <p><b>예측을 앞에 두는 것이 핵심이다.</b> 이미 체인에 박힌 배치들은 리포트가 0건이라, 이 규칙에서도 리프 순서가
 * 예전과 완전히 같다 — 옛 루트가 그대로 재현된다. 두 종류를 id 로 섞어 정렬했다면 그 보장이 깨진다
 * (예측 id 와 리포트 id 는 다른 시퀀스라 섞을 기준도 없다).
 *
 * <p>리프 종류를 트리 안에서 구분하지 않는다. {@code MerkleTree.build} 가 리프마다 한 겹 더 해시하고
 * ({@code 도메인 분리 리프}), 바깥 해시의 머리글이 {@code antenna:commit:v1} · {@code antenna:report:v1} 로 갈려
 * 예측 해시를 리포트 해시로 오인·재사용할 수 없다.
 */
@Component
@RequiredArgsConstructor
public class AnchorLeaves {

    /** 리프 한 줄. {@code hash} 가 트리에 들어가는 값이고, 나머지는 "이 리프가 무엇인지" 다. */
    public record Leaf(Kind kind, long id, String hash) {

        public byte[] bytes() {
            return Numeric.hexStringToByteArray(hash);
        }
    }

    /** 리프 종류. 원장·배치 상세가 줄마다 이 값을 내린다. */
    public enum Kind {
        PREDICTION,
        REPORT
    }

    private final PredictionCommitRepository commits;
    private final ReportRepository reports;

    /**
     * 아직 배치에 안 묶인 것 전부. 이 목록이 다음 배치가 된다.
     *
     * <p>리포트는 {@code body_hash} 가 있는 것만 고른다 — 이 기능 이전에 발행된 리포트는 봉인이 없어서
     * 리프를 만들 수 없다. 뒤늦게 해시를 만들어 올리면 "그때 있었다" 가 아니라 "지금 만들었다" 가 되므로
     * 소급 봉인은 하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<Leaf> pending() {
        List<Leaf> leaves = new ArrayList<>();
        for (PredictionCommit c : commits.findByAnchorBatchIsNullOrderByPredictionIdAsc()) {
            leaves.add(new Leaf(Kind.PREDICTION, c.getPredictionId(), c.getCommitHash()));
        }
        for (Report r : reports.findSealedWithoutBatch()) {
            leaves.add(new Leaf(Kind.REPORT, r.getId(), r.getReportHash()));
        }
        return leaves;
    }

    /** 이 배치에 묶인 것 전부. {@link #pending()} 이 만들었던 것과 같은 순서로 돌려준다. */
    @Transactional(readOnly = true)
    public List<Leaf> of(AnchorBatch batch) {
        List<Leaf> leaves = new ArrayList<>();
        for (PredictionCommit c : commits.findByAnchorBatchOrderByPredictionIdAsc(batch)) {
            leaves.add(new Leaf(Kind.PREDICTION, c.getPredictionId(), c.getCommitHash()));
        }
        for (Report r : reports.findByAnchorBatchOrderByIdAsc(batch)) {
            leaves.add(new Leaf(Kind.REPORT, r.getId(), r.getReportHash()));
        }
        return leaves;
    }

    /** 트리에 넣을 바이트 목록. 순서는 위 규칙 그대로다. */
    public static List<byte[]> bytesOf(List<Leaf> leaves) {
        List<byte[]> bytes = new ArrayList<>(leaves.size());
        for (Leaf leaf : leaves) {
            bytes.add(leaf.bytes());
        }
        return bytes;
    }
}
