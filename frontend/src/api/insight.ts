/* B-01 인사이트 홈 · A-03 통합 검색이 쓰는 조회 API.

   타입은 API 명세서의 응답 스키마를 그대로 옮겼다.

   ── 남은 목업 하나 ────────────────────────────────────────
   MOCK 이 걸린 함수는 이제 하나뿐이다. 나머지는 실 API 다.

     /ads/active  등록된 배너가 없어 화면이 비어 보인다 — 시연 동안 유지(2026-09-03 결정)

   실제 호출부는 명세서 경로·쿼리대로 적어 두었다. 광고가 붙으면 그 함수의
   `if (MOCK)` 한 줄과 mock/insight.ts 의 짝을 지우고, MOCK 상수와 import 까지 지운다.
   ─────────────────────────────────────────────────────── */
import { api } from './client'
import * as mock from './mock/insight'

const MOCK = true

/* ── 시장 Overview · GET /market/indices ─────────────────── */

/** 지수 한 종목. series 는 미니차트용 최근 N일 종가. */
export type IndexQuote = {
  /** KOSPI · KOSDAQ · USDKRW */
  code: string
  close: number
  changeRate: number
  series: number[]
}

/* 지수는 실제 API 를 탄다.

   잠깐 목업으로 되돌렸다가(d203f1a) 다시 되돌린 자리다. items 가 늘 비어 있는 걸
   보고 "수집이 아직 안 돌았다" 고 읽었는데, **원인은 백엔드가 아니라 우리 쪽
   재기동이었다.** application.yaml 이 .env 를 optional:file 로 기동할 때 한 번만
   읽는데, 그날 백엔드가 09:08 에 뜨고 .env 에 키가 09:59 에 들어왔다. 재기동하니
   같은 코드가 지수 3,278행을 받아 왔다.

   **다음에 "수집이 안 된다" 싶으면 .env 시각과 기동 시각부터 견주어 볼 것.**

   USDKRW 는 아직 안 온다 — KOREAEXIM_AUTH_KEY 가 .env 에 없어 환율만 건너뛴다.
   그래서 카드에 지수 셋이 아니라 둘이 뜬다. 빈 자리를 만들지 않고 온 것만 그린다 —
   화면이 개수를 정하지 않고 서버가 준 만큼 그린다. */
export function getMarketIndices(days = 30) {
  return api.get<{ items: IndexQuote[] }>('/market/indices', { query: { days } })
}

/* AI 브리핑은 여기 없다. 홈 띠(B-01) · 종목 상세(B-03) · 상세 모달(M-10)이
   같은 응답을 읽으므로 계약을 api/briefings.ts 한 곳에 뒀다. */

/* ── 관심 종목 · GET /watchlist ───────────────────────────── */

/** 실전 시세는 전일 종가만이다(§7). "현재가" 를 만들지 않는다. */
export type WatchlistRow = {
  stockCode: string
  name: string
  /** 수집 범위(KOSPI 300) 밖으로 밀려 시세가 없으면 null */
  prevClose: number | null
  /** 점이 둘 미만이면 0 */
  changeRate: number
  /** 최근 30 영업일 종가, 오래된 순. 시세가 없으면 빈 배열 */
  series: number[]
}

export function getWatchlist() {
  return api.get<{ items: WatchlistRow[] }>('/watchlist')
}

/* 주목할 예측가(GET /rankings)는 여기 없다 — 랭킹 화면(E-01)과 같은 응답이라
   api/rankings.ts 의 fetchRankings 한 벌을 홈도 같이 쓴다. 전에는 여기에도
   같은 타입이 있었는데, 지표 셋을 non-null 로 적어 두어 배치 전 응답과 어긋났다. */

/* ── 스폰서드 · GET /ads/active ───────────────────────────── */

export type ActiveAd = { id: number; imageUrl: string; linkUrl: string }

/* 광고는 등록된 배너가 아직 없어 화면이 비어 보인다 — 시연 동안 목업을 둔다(2026-09-03 결정). */
export function getActiveAds() {
  if (MOCK) return mock.activeAds()
  return api.get<{ items: ActiveAd[] }>('/ads/active')
}

/* 내 자산(GET /wallet/balance)은 여기 없다 — 지갑 화면(H-01)과 같은 응답이라 api/wallet.ts
   한 벌을 홈도 같이 쓴다. 전에는 여기에도 같은 이름의 타입이 있었는데 모양이 아예 달랐다
   (balance 를 number 로, 서버에 없는 spent30d·earned30d·valuationKrw 를 넣어 두었다).
   목업일 때는 그 넷이 다 있었고 서버 경로가 없을 때는 호출이 실패해 안 그렸는데,
   ANT-TOKEN-04 로 경로가 생기자 200 으로 다른 모양이 와서 홈이 통째로 터졌다. */

/* ── 통합 검색 · GET /search ──────────────────────────────── */

export type SearchResult = {
  /** 활성 종목만 온다 — 종가는 그 종목의 마지막 거래일 값이라 비지 않는다. 섹터는 없을 수 있다 */
  stocks: { code: string; name: string; sector: string | null; prevClose: number; watching: boolean }[]
  /** 실전 전체 랭킹에 든 예측가만. hitRate 는 % (0~100) — 랭킹 행과 같이 비어 올 수 있다 */
  channels: { userId: number; nickname: string; hitRate: number | null }[]
  reports: { id: number; title: string; author: string }[]
}

/** 타입별 상위 N건만 온다. 필터·정렬·페이징은 전용 목록의 책임이다(명세서 §검색). */
export function search(q: string, limit = 5) {
  return api.get<SearchResult>('/search', { query: { q, limit } })
}
