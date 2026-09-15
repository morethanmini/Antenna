package ssafy.a507.backend.domain.chain.anchor;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.chain.dto.ProofResponse;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.chain.merkle.MerkleTree;

/**
 * 배치의 리프 전량에서 한 리프의 머클 증명을 다시 접는다 (ANT-CHAIN-06 · 17).
 *
 * <p>순서는 {@link AnchorLeaves} 가 정한다 — 배치가 트리를 만든 순서와 <b>같은 코드</b>여야 루트가 재현된다.
 * 리포트가 트리에 들어오면서 갈릴 뻔한 자리다(ANT-CHAIN-14).
 *
 * <p><b>proof 를 저장하지 않고 매번 다시 접는다.</b> 접은 루트가 저장된 루트와 다르면 DB 가 깨진 것이므로
 * 틀린 proof 를 내리느니 500 으로 실패한다 — 화면에서 "불일치" 로 보이면 사용자가 우리 데이터를 의심할
 * 수 없게 된다(자기 계산이 틀린 줄 안다).
 *
 * <p>예측 검산({@code ProofService})과 리포트 검산({@code ReportProofService})이 같은 것을 쓴다. 복사해
 * 두면 한쪽만 고쳐졌을 때 두 화면이 서로 다른 루트를 말하고, 그게 바로 이 서비스가 팔려는 것을 깎는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnchorProof {

    private final AnchorLeaves anchorLeaves;

    /**
     * @param kind 이 리프가 예측인지 리포트인지
     * @param id 그 종류 안에서의 id (예측이면 predictionId, 리포트면 reportId)
     * @throws BusinessException 배치에 그 리프가 없거나, 접은 루트가 저장된 루트와 다를 때 (500)
     */
    public ProofResponse.Anchor of(AnchorLeaves.Kind kind, long id, AnchorBatch batch) {
        List<AnchorLeaves.Leaf> leaves = anchorLeaves.of(batch);
        int leafIndex = indexOf(leaves, kind, id);

        MerkleTree tree = MerkleTree.build(AnchorLeaves.bytesOf(leaves));
        String root = Numeric.toHexString(tree.root());
        if (leafIndex < 0 || !root.equalsIgnoreCase(batch.getMerkleRoot())) {
            log.error(
                    "배치 {} 의 리프로 접은 루트 {} 가 저장된 루트 {} 와 다르다 ({} {} · leafIndex {})."
                            + " DB 가 깨졌다 — proof 를 내리지 않는다",
                    batch.getId(),
                    root,
                    batch.getMerkleRoot(),
                    kind,
                    id,
                    leafIndex);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        List<String> proof = tree.proof(leafIndex).stream().map(Numeric::toHexString).toList();
        return new ProofResponse.Anchor(
                batch.getId(),
                batch.getMerkleRoot(),
                proof,
                leafIndex,
                leaves.size(),
                batch.getStatus(),
                batch.getTxHash(),
                batch.getBlockNumber(),
                batch.getConfirmedAt(),
                batch.getContractAddress(),
                batch.getChainId());
    }

    /** 못 찾으면 -1. 호출부가 루트 불일치와 함께 한 번에 처리한다 — 둘 다 "DB 가 깨졌다" 는 같은 뜻이다. */
    private int indexOf(List<AnchorLeaves.Leaf> leaves, AnchorLeaves.Kind kind, long id) {
        for (int i = 0; i < leaves.size(); i++) {
            AnchorLeaves.Leaf leaf = leaves.get(i);
            if (leaf.kind() == kind && leaf.id() == id) {
                return i;
            }
        }
        return -1;
    }
}
