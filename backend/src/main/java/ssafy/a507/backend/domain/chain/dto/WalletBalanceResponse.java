package ssafy.a507.backend.domain.chain.dto;

import java.time.Instant;

/**
 * GET /api/v1/wallet/balance 200 응답 (ANT-TOKEN-04).
 *
 * <p><b>30일 집계를 여기 싣는 이유</b> — 화면(B-01 내 자산 현황 · H-01)이 잔액 옆에 "30일간 번/쓴" 을 함께
 * 그린다. 원장 목록({@code GET /wallet/ledger})으로 프론트가 직접 셀 수도 있지만, 그 API 는 기간 필터가 없고 한 번에
 * 100건이 상한이라 30일에 100건을 넘기는 사용자는 <b>숫자가 조용히 잘린다.</b> 틀린 합을 잔액 옆에 놓느니 서버가 한 번에
 * 세어 내린다.
 *
 * <p><b>총유입·총유출이지 순증감이 아니다.</b> 배팅했다가 환불받으면 나간 것과 들어온 것이 둘 다 오른다. 사유를 골라
 * 빼지 않는 이유는 {@code earned30d - spent30d = 30일간 잔액 변화} 가 성립해야 나중에 숫자끼리 검산이 되기
 * 때문이다. 한쪽을 빼면 그 등식이 깨지고, 안 맞는 이유를 누군가 다시 추적하게 된다.
 *
 * @param balance 정수 ANT 문자열. 원장(token_ledger) 합 — 광고·슬롯 초과가 결제 판단에 쓰는 것과 같은 숫자다.
 *     금액 필드는 문자열로 통일한다
 * @param symbol 토큰 이름. 컨트랙트가 정한 값이라 늘 "ANT"
 * @param syncedAt 원장이 체인을 여기까지 읽었다는 시각(토큰 인덱서가 체인 끝까지 훑은 마지막 회차). 인덱서가 꺼졌거나
 *     아직이면 null
 * @param earned30d 최근 30일에 들어온 ANT 합(양수 delta 의 합). 없으면 "0"
 * @param spent30d 최근 30일에 나간 ANT 합. <b>양수로 내린다</b> — 부호를 붙일지는 화면이 정한다
 * @param adHeld 심사 중(PENDING) 광고 게재료 합. 없으면 "0". 승인제(ANT-ADMIN-03)에서는 신청 때 돈이 안 나가고
 *     승인 때 소각되므로 그 사이엔 원장에 아무 줄도 없다 — 그래서 "광고 지불 내역이 사라졌다" 로 보였다(09-21 QA).
 *     {@code balance} 에서 빼지 않은 값이다: 원장 합이 잔액이라는 규칙을 지키고, 묶여 있다는 사실만 따로 알린다.
 *     가용잔액({@code AvailableBalanceService})은 이 돈을 이미 뺀다
 */
public record WalletBalanceResponse(
        String balance, String symbol, Instant syncedAt, String earned30d, String spent30d, String adHeld) {}
