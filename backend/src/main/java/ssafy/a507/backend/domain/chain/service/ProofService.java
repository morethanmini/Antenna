package ssafy.a507.backend.domain.chain.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.chain.dto.ProofResponse;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.chain.merkle.MerkleTree;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.entity.PredictionCommit;
import ssafy.a507.backend.domain.prediction.repository.PredictionCommitRepository;
import ssafy.a507.backend.domain.prediction.repository.PredictionRepository;

/**
 * 3단계 검산 재료 조립 (ANT-CHAIN-06, 화면 D-03).
 *
 * <p>게이팅은 두 겹이다(plan §설계). ① <b>미판정 예측 자체</b> — BASE/OPEN 이면 작성자와 유효 구독자만 본다(명세 §예측 공개 규칙,
 * 그 외 403). 판정(HIT/MISS)이 끝나면 회원 전원. ② <b>근거 salt</b> — 근거 본문이 판정 후에도 구독자 전용이라 {@code salt}
 * 도 작성자·구독자에게만 준다(ANT-PRED-02 · 결정 D6). 비구독자의 ①단계 검산은 {@code payload.noteHash} 로 충분하다 —
 * 커밋 문자열에 salt 줄이 없고 noteHash 가 그 역할을 겸하기 때문이다.
 *
 * <p><b>salt 에는 리빌 조건을 걸지 않는다(2026-09-15).</b> 받을 자격은 구독이지 시간이 아니다. 리빌까지 기다리게 하면
 * 구독자가 돈을 내고도 "지금 보고 있는 근거가 봉인된 그것인가" 를 만기까지 확인할 수 없다 — 이 서비스가 파는 것이
 * 검증 가능성인데 정작 구독자의 검산만 반쪽이 된다. 비구독자 경계는 그대로다.
 *
 * <p>구독 판정은 monetize 의 {@link SubscriptionRepository#findSubscribedPublisherIds} 하나를 쓴다. "ACTIVE 상태" 가 아니라
 * "ACTIVE + 기간 유효" 가 근거라는 정의가 거기 박혀 있고, 정의를 둘로 만들지 않는다.
 *
 * <p>proof 는 저장하지 않고 매번 리프 전량에서 다시 접는다. 접은 루트가 저장된 루트와 다르면 DB 가 깨진 것이므로
 * 틀린 proof 를 내리느니 500 으로 실패한다 — 사용자 화면에서 ②가 "불일치" 로 보이면 우리 데이터를 의심할 수 없게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProofService {

    /** 공공데이터포털 주식시세 조회. 키는 붙이지 않는다 — 사용자가 자기 키로 연다(③ 대조는 브라우저 몫). */
    private static final String PUBLIC_DATA_PRICE_URL =
            "https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo"
                    + "?resultType=json&basDt=%s&likeSrtnCd=%s";
    private static final DateTimeFormatter BAS_DT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<Prediction.Status> SETTLED = List.of(Prediction.Status.HIT, Prediction.Status.MISS);

    private final PredictionRepository predictions;
    private final PredictionCommitRepository commits;
    private final SubscriptionRepository subscriptions;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public ProofResponse proof(long predictionId) {
        Long viewerId = currentUserProvider.currentUserId();
        Prediction prediction = predictions
                .findById(predictionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PREDICTION_NOT_FOUND, "predictionId"));

        boolean settled = SETTLED.contains(prediction.getStatus());
        boolean own = prediction.getUser().getId().equals(viewerId);
        // 구독 조회는 필요할 때만 — 작성자 본인이거나 판정 전이 아니면 물어볼 이유가 없다.
        boolean subscriber = !own && isSubscriber(viewerId, prediction.getUser().getId());
        if (!settled && !own && !subscriber) {
            throw new BusinessException(ErrorCode.PREDICTION_FORBIDDEN, "predictionId");
        }

        PredictionCommit commit = commits.findById(predictionId).orElse(null);
        AnchorBatch batch = commit == null ? null : commit.getAnchorBatch();

        ProofResponse.Payload payload = new ProofResponse.Payload(
                prediction.getStock() == null ? null : prediction.getStock().getCode(),
                prediction.getDirection(),
                prediction.getTargetPrice(),
                prediction.getHorizon(),
                commit == null ? null : commit.getNoteHash(),
                prediction.getCreatedAt());

        /* 겹 ②: 근거 salt 는 작성자·구독자만. **리빌 여부는 보지 않는다**(2026-09-15).
           전에는 revealed 까지 걸어서, 30일짜리 예측이면 구독자도 30일 뒤에야 salt 를 받았다 —
           그때까지는 서버가 준 note 가 봉인된 그 note 인지 확인할 길이 없어 구독자의 검산이 반쪽이었다.
           구독자는 이미 note 본문·방향·목표가를 전부 보므로 salt 를 더 준다고 새는 것이 없고,
           커밋 문자열에 salt= 줄이 없어(CommitPayload) commitHash 를 앞당겨 계산할 수 있게 되지도 않는다.
           작성자 쪽은 더 분명하다 — salt 는 클라이언트가 만든 값이라 원래 본인이 알고 있다.

           commit null 검사를 여기 둔다 — 전에는 revealed 가 그 검사를 겸했다(등록 직후 커밋 행이
           생기기 전 짧은 창). 빼면 작성자가 그 창에서 열 때 NPE 다. */
        boolean saltVisible = commit != null && (own || subscriber);

        return new ProofResponse(
                predictionId,
                payload,
                commit == null ? null : commit.getCommitHash(),
                commit == null ? null : commit.getSignature(),
                commit == null ? null : commit.getSignerAddress(),
                commit == null ? null : commit.getRevealedAt(),
                saltVisible ? commit.getSalt() : null,
                batch == null ? null : anchorOf(commit, batch),
                ProofResponse.AnchorStatus.of(batch),
                settled ? settleOf(prediction) : null);
    }

    private boolean isSubscriber(Long viewerId, Long publisherId) {
        return !subscriptions
                .findSubscribedPublisherIds(viewerId, List.of(publisherId), Subscription.Status.ACTIVE, Instant.now())
                .isEmpty();
    }

    /**
     * 리프 전량(prediction id 오름차순 — 배치가 트리를 만든 순서)에서 이 커밋의 proof 를 다시 접는다.
     * 배치가 PENDING/FAILED 여도 트리는 DB 만으로 결정되므로 proof 는 내린다 — 체인에 있는지는 {@code anchorStatus} 가 말한다.
     */
    private ProofResponse.Anchor anchorOf(PredictionCommit commit, AnchorBatch batch) {
        List<PredictionCommit> leaves = commits.findByAnchorBatchOrderByPredictionIdAsc(batch);
        int leafIndex = -1;
        for (int i = 0; i < leaves.size(); i++) {
            if (leaves.get(i).getPredictionId().equals(commit.getPredictionId())) {
                leafIndex = i;
                break;
            }
        }
        MerkleTree tree = MerkleTree.build(
                leaves.stream().map(c -> Numeric.hexStringToByteArray(c.getCommitHash())).toList());
        String root = Numeric.toHexString(tree.root());
        if (leafIndex < 0 || !root.equalsIgnoreCase(batch.getMerkleRoot())) {
            log.error(
                    "배치 {} 의 리프로 접은 루트 {} 가 저장된 루트 {} 와 다르다 (leafIndex {}). DB 가 깨졌다 — proof 를 내리지 않는다",
                    batch.getId(),
                    root,
                    batch.getMerkleRoot(),
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

    /**
     * ③단계(판정 종가 ↔ 공공데이터 원본 대조) 재료.
     *
     * <p>{@code sourceUrl} 의 날짜는 <b>실제로 판정에 쓴 거래일</b>({@code settled_on})이다. 만기일이 아니다 —
     * 만기일이 휴장이면 판정 배치가 그 이후 첫 거래일 종가를 쓰는데(ANT-PRED-04), 만기일로 포털을 열면 빈 응답이 와서
     * 사용자가 우리 종가를 대조할 방법이 없어진다. horizon 30 은 기준일이 목·금이면 만기가 주말이라 드문 일이 아니다.
     * 판정 전 데이터(settled_on 이 비어 있는 옛 행)는 만기일로 물러선다.
     */
    private static ProofResponse.Settle settleOf(Prediction p) {
        String sourceUrl = null;
        LocalDate pricedOn = p.getSettledOn() != null ? p.getSettledOn() : p.getSettleDate();
        if (pricedOn != null && p.getStock() != null) {
            sourceUrl = PUBLIC_DATA_PRICE_URL.formatted(BAS_DT.format(pricedOn), p.getStock().getCode());
        }
        return new ProofResponse.Settle(
                p.getStatus(), p.getSettleDate(), p.getSettlePrice(), p.getBasePrice(), p.getErrorRate(), sourceUrl);
    }
}
