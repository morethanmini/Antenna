package ssafy.a507.backend.domain.market.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import ssafy.a507.backend.domain.market.entity.Stock;

/**
 * GET /api/v1/stocks/{code} 요약 헤더 (ANT-DATA-05). 명세의 8개 필드에 종목 상세 헤더가 쓰는
 * market · asOf · per · pbr · predictionCount 를 더했다 — 화면이 이 응답 하나로 머리를 그린다.
 *
 * <p>종가는 전역 최신 거래일이 아니라 <b>그 종목의 마지막 거래일</b> 한 행에서 꺼낸다(valuation 과 같은
 * 규칙). 거래정지 종목도 머리가 비지 않고, asOf 가 그 종가의 날짜를 말한다.
 *
 * @param name KRX 종목명 — 탐색 목록과 같은 이름이다(DART 법인명 아님)
 * @param prevClose 마지막 거래일 종가 · 시세가 한 건도 없으면 null. 실전 시세에 현재가는 없다
 * @param asOf prevClose 의 거래일
 * @param change 그 전 거래일 종가와의 차 · 비교할 날이 없으면 null
 * @param changeRate 같은 비교의 등락률(%) · 소수 둘째 자리
 * @param tradingValue 거래대금 <b>근사</b> — 종가 × 거래량(원). daily_quotes 에 거래대금이 없다. 거래량이 없으면 null
 * @param per 배치가 stocks 에 써 둔 파생값 — 목록과 같은 숫자
 * @param predictionCount 판정 대기(BASE·OPEN) 예측 수 — 목록 행과 같은 집계
 * @param watching 내 관심 종목인가
 */
public record StockSummaryResponse(
        String code,
        String name,
        String sector,
        Stock.Market market,
        BigDecimal prevClose,
        LocalDate asOf,
        BigDecimal change,
        BigDecimal changeRate,
        BigDecimal tradingValue,
        BigDecimal per,
        BigDecimal pbr,
        int predictionCount,
        boolean watching) {}
