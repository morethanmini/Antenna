package ssafy.a507.backend.domain.ranking.repository;

import java.math.BigDecimal;

/**
 * 통합 검색의 예측가 칸 한 줄(ANT-DATA-05). 랭킹 스냅샷에 없는 회원은 {@code hitRate} 가 null 이다.
 *
 * @param hitRate 실전 전체 랭킹의 적중률(%, 0~100). 랭킹에 못 든 회원이면 null
 */
public record NicknameHit(Long userId, String nickname, BigDecimal hitRate) {}
