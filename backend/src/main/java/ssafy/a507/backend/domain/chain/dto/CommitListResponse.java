package ssafy.a507.backend.domain.chain.dto;

import java.util.List;

/**
 * GET /api/v1/commits 200 응답. 최신(prediction id 큰) 순 고정이라 커서는 id 하나다 — 프론트 {@code useCursorList} 계약.
 *
 * <p>앵커 목록과 달리 <b>정렬이 내림차순</b>이다. 배치 상세({@code GET /anchors/id})의 리프가 오름차순인 것은 머클 트리를
 * 접는 순서여서이고, 여기는 검증 재료가 아니라 "최근에 무엇이 봉인됐나" 를 보는 목록이다.
 */
public record CommitListResponse(List<CommitItemResponse> items, Long nextCursor, boolean hasNext) {}
