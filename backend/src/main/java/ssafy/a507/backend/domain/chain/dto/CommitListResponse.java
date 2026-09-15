package ssafy.a507.backend.domain.chain.dto;

import java.util.List;

/**
 * GET /api/v1/commits 200 응답. 최신 발행 순 고정 — 프론트 {@code useCursorList} 계약.
 *
 * <p><b>{@code nextCursor} 가 문자열이다 (ANT-CHAIN-15).</b> 한 목록이 예측 커밋과 리포트 두 표에서 오므로
 * id 하나로는 경계를 정할 수 없다 — {@code "<epochMicros>:<kind>:<id>"} 세 조각이고, 클라이언트는 받은 값을
 * 그대로 되돌려주기만 한다. 조립·해석 규칙은 {@code CommitQueryService.LedgerCursor} 한 곳에 있다.
 *
 * <p>앵커 목록과 달리 <b>정렬이 내림차순</b>이다. 배치 상세({@code GET /anchors/id})의 리프가 오름차순인 것은 머클 트리를
 * 접는 순서여서이고, 여기는 검증 재료가 아니라 "최근에 무엇이 봉인됐나" 를 보는 목록이다.
 */
public record CommitListResponse(List<CommitItemResponse> items, String nextCursor, boolean hasNext) {}
