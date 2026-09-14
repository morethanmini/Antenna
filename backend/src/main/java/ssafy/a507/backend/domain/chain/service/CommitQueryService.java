package ssafy.a507.backend.domain.chain.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.chain.dto.CommitItemResponse;
import ssafy.a507.backend.domain.chain.dto.CommitListResponse;
import ssafy.a507.backend.domain.prediction.entity.PredictionCommit;
import ssafy.a507.backend.domain.prediction.repository.PredictionCommitRepository;

/**
 * 커밋 원장 목록 (화면 D-01).
 *
 * <p>{@link AnchorQueryService} 와 나눈 이유 — 저쪽은 <b>배치</b>를 다루고 여기는 <b>커밋</b>을 다룬다. 화면이 커밋 단위로
 * 바뀌면서 필요해졌다. 배치 상세({@code GET /anchors/{id}})는 그대로 남는다 — 머클루트를 재현하는 검증용 뷰라 성격이 다르다.
 *
 * <p>읽기만 한다. 게이팅이 없는 것도 앵커 조회와 같다(결정 F2·F3) — 해시·번호·종목·기간으로는 예측 내용을 알 수 없고,
 * 내용 공개 여부는 {@code GET /predictions/{id}/proof} 가 판단한다.
 */
@Service
@RequiredArgsConstructor
public class CommitQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PredictionCommitRepository commits;

    /**
     * 최신 커밋부터. 커서는 마지막으로 받은 {@code predictionId} 이고 그보다 작은 것을 다음 장으로 준다.
     *
     * <p>아직 배치에 묶이지 않은 커밋도 포함한다 — 봉인은 끝났고 다음 배치를 기다리는 중이라, 빼면 방금 등록한 사람에게
     * "봉인했는데 원장에 없다" 로 보인다. 그 줄은 배치 자리가 비어 온다.
     */
    @Transactional(readOnly = true)
    public CommitListResponse list(Long cursor, Integer size) {
        int pageSize = pageSize(size);
        List<PredictionCommit> found = commits.findLedgerPage(cursor, Limit.of(pageSize + 1));
        boolean hasNext = found.size() > pageSize;
        List<PredictionCommit> page = hasNext ? found.subList(0, pageSize) : found;
        if (page.isEmpty()) {
            return new CommitListResponse(List.of(), null, false);
        }
        List<CommitItemResponse> items = page.stream().map(CommitItemResponse::of).toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getPredictionId() : null;
        return new CommitListResponse(items, nextCursor, hasNext);
    }

    private int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
