package ssafy.a507.backend.domain.prediction.dto;

import java.math.BigDecimal;

/**
 * GET /api/v1/predictions/me/portfolio 200 응답 (ANT-PRED-06 · 화면 C-04).
 *
 * <p><b>여덟 필드가 전부다.</b> 설계서 §4 C-04 가 섹터별·기간별 적중률, 근거 사용 패턴, 확신도
 * 캘리브레이션, 누적 수익률 따위를 두지 않기로 못 박았다 — 재료가 없거나(수익률은 실전에 체결이
 * 없다) 표본이 모자라 숫자가 거짓말을 한다.
 *
 * @param total 내 예측 전부
 * @param hit 적중
 * @param miss 빗나감
 * @param pending 판정 대기 — BASE 와 OPEN 을 합친 수다. 화면이 둘을 가르지 않는다
 * @param hitRate 적중률 % · 판정된 것만 분모다({@code hit / (hit + miss)}) · <b>판정이 0건이면 null</b>
 * @param avgError 판정된 예측의 평균 오차 % · 판정이 0건이면 null
 * @param anchored 체인에 <b>박힌</b> 배치에 든 커밋 수 — 배치가 CONFIRMED 인 것만이다
 * @param unanchored 나머지 전부 — 커밋이 아직 없는 것, 배치에 안 묶인 것, 배치가 PENDING·FAILED 인 것
 */
public record PortfolioResponse(
        long total,
        long hit,
        long miss,
        long pending,
        BigDecimal hitRate,
        BigDecimal avgError,
        long anchored,
        long unanchored) {}
