package ssafy.a507.backend.domain.chain.dto;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/v1/wallet/ledger 200 응답 (ANT-TOKEN-04). 최신(id 큰) 순 고정이라 커서는 id 하나다 — 프론트 {@code useCursorList} 계약.
 */
public record WalletLedgerResponse(List<Item> items, Long nextCursor, boolean hasNext) {

    /**
     * @param delta  부호 있는 정수 ANT 문자열. 획득은 양수, 사용은 음수
     * @param reason {@code TokenReason} 이름. 옛 행·수동 tx 는 그 밖의 원문일 수 있다(인덱서가 원문 그대로 적는다)
     * @param txHash 근거 온체인 tx. 이벤트 없이 들어간 행이면 null
     */
    public record Item(String delta, String reason, String txHash, Instant createdAt) {}
}
