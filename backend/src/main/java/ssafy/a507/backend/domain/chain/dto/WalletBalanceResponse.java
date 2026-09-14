package ssafy.a507.backend.domain.chain.dto;

import java.time.Instant;

/**
 * GET /api/v1/wallet/balance 200 응답 (ANT-TOKEN-04).
 *
 * @param balance  정수 ANT 문자열. 원장(token_ledger) 합 — 광고·슬롯 초과가 결제 판단에 쓰는 것과 같은 숫자다. 금액 필드는 문자열로 통일한다
 * @param symbol   토큰 이름. 컨트랙트가 정한 값이라 늘 "ANT"
 * @param syncedAt 원장이 체인을 여기까지 읽었다는 시각(토큰 인덱서가 체인 끝까지 훑은 마지막 회차). 인덱서가 꺼졌거나 아직이면 null
 */
public record WalletBalanceResponse(String balance, String symbol, Instant syncedAt) {}
