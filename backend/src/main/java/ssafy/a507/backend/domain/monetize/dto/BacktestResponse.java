package ssafy.a507.backend.domain.monetize.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * GET /api/v1/channels/{userId}/backtest 200 응답 (ANT-TOKEN-03, 화면 E-02).
 *
 * <p>"이 채널을 따라 샀다면 얼마였나". 적중률보다 이쪽이 설득력이 크고, <b>작게 여러 번 맞고 크게 한 번
 * 틀리는</b> 예측가를 걸러낸다 — 적중률은 둘을 구분하지 못한다.
 *
 * <p><b>파라미터가 고정이다</b>(설계 제약) — 3개월 · 상수 원금 · 수수료 0. 사용자 입력 칸이 없고 무료라
 * 잠그지도 않는다. 고정값을 화면에 박지 않고 응답에 싣는 이유는, 기간이 6개월로 바뀌면 화면 문구와
 * 계산 근거가 조용히 어긋나서다.
 *
 * <p>필드 이름은 프론트 {@code api/channels.ts} 의 {@code Backtest} 타입에 맞췄다. 명세 v0.64 까지는
 * {@code benchmarkReturn} · {@code maxDrawdown} · {@code trades[]} 로 적혀 있었는데, 화면은
 * {@code benchmarkRate} 를 쓰고 뒤의 둘은 그리지 않는다(E-02 는 숫자 세 개와 조건 한 줄이 전부다).
 */
public record BacktestResponse(
        /** 기간 개월 수. 지금은 3 고정 */
        int months,
        /** 시작 원금(원) */
        long principal,
        /** 누적 수익률 %. 소수 둘째 자리 */
        BigDecimal returnRate,
        /**
         * 같은 기간 KOSPI 수익률 %. 비교 기준이 없으면 숫자를 읽을 수 없다.
         *
         * <p>지수 값이 구간 양 끝에 없으면 null 이다 — 0 으로 내리면 "코스피는 제자리였다"는 거짓이 된다.
         */
        BigDecimal benchmarkRate,
        /** 계산에 쓴 판정 완료 예측 수. <b>0 이면 화면이 결과를 그리지 않는다</b> — 0% 는 "손실 없음"으로 읽힌다 */
        int sampleCount,
        Instant computedAt) {}
