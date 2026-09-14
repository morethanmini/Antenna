/* 종목 도메인. API 명세서 §종목 · 설계서 §3 B · §4 B-02.

   GET /stocks?sector=&market=&sentiment=&perMin=&perMax=
              &hasOpenPrediction=&watchedOnly=&sort=&cursor=&size=
   GET /stocks/sectors            섹터별 종목 수·평균 등락률 (요약 칩)
   GET    /watchlist              관심 종목 목록 (B-04)
   POST   /watchlist              { stockCode }
   DELETE /watchlist/{stockCode}

   전부 실제 호출이다(명세서 v0.19). perMin/perMax · sort=PER · CHANGE_RATE 와 per · pbr 은
   동작한다. 서버가 아직 재료가 없어 무시하는 것 — sentiment · hasOpenPrediction 과 예측
   정렬 3종은 보내도 결과가 안 바뀌고(코드순), upRatio 는 null, predictionCount 는 0 으로
   온다. predictions 가 생기면 서버만 바뀌고 이 파일은 그대로다. */
import { api } from './client'
import type { CursorList } from './types'

/** 서버 Stock.Market 과 짝이다. */
export const MARKETS = ['KOSPI', 'KOSDAQ', 'KONEX'] as const
export type Market = (typeof MARKETS)[number]

export const MARKET_LABEL: Record<Market, string> = {
  KOSPI: 'KOSPI',
  KOSDAQ: 'KOSDAQ',
  KONEX: 'KONEX',
}

/* 정렬은 5종 고정이다(§4 B-02). 종목명·종가 정렬은 두지 않는다 — 어휘에 없다. */
export const STOCK_SORTS = [
  'PREDICTION_COUNT', 'UP_RATIO', 'DOWN_RATIO', 'CHANGE_RATE', 'PER',
] as const
export type StockSort = (typeof STOCK_SORTS)[number]

export const SORT_LABEL: Record<StockSort, string> = {
  PREDICTION_COUNT: '예측 많은 순',
  UP_RATIO: '상승 의견 순',
  DOWN_RATIO: '하락 의견 순',
  CHANGE_RATE: '등락률 순',
  PER: 'PER 낮은 순',
}

/** 예측 심리 필터. 행별 집계의 우세 방향으로 거른다. */
export const SENTIMENTS = ['UP', 'DOWN'] as const
export type Sentiment = (typeof SENTIMENTS)[number]

/**
 * 목록 한 줄.
 *
 * 실전 시세에 "현재가" 는 없다 — 실시간 시세는 법적 제약이라 종가만 내린다(명세 §1 · §7).
 * prevClose 는 그날 거래가 정지됐던 종목이면 null 이고, 0 으로 바꿔 그리지 않는다.
 * per · pbr 도 재무 미수집 종목은 null 이다. 역시 0 으로 그리지 않는다.
 *
 * upRatio · predictionCount 는 행별 예측 집계다. 이 값이 응답에 있어야 화면이
 * 행마다 /sentiment 를 부르지 않는다(§4 B-02).
 */
export type StockListItem = {
  code: string
  name: string
  sector: string | null
  market: Market | null
  prevClose: number | null
  /** 전일 대비 등락률 %. 거래정지면 null */
  changeRate: number | null
  per: number | null
  pbr: number | null
  /**
   * 시가총액(원) = 종가 × 상장주식수. 히트맵의 **칸 크기 기준**이다(MarketMap).
   *
   * 거래가 정지됐거나 상장주식수를 못 받은 종목은 null 이다. 0 이 아니다 —
   * 0 으로 오면 폭 0 짜리 칸이 되어 종목이 판에서 사라진다.
   */
  marketCap: number | null
  /** 판정 대기 중인 예측 건수 */
  predictionCount: number
  /** 그중 UP 비율 0~100. 예측이 없으면 null */
  upRatio: number | null
  watched: boolean
}

/** 목록 전체에 한 번만 해당하는 값. useCursorList 의 meta 로 온다. */
export type StockListMeta = {
  /** 목록의 종가가 기준하는 영업일 YYYY-MM-DD. 수집된 시세가 없으면 null */
  baseDate: string | null
}

export type StockList = CursorList<StockListItem> & StockListMeta

/** 상단 요약 칩 한 칸. code 가 null 인 행이 "전체 종목" 이다. */
export type SectorSummary = {
  /** 섹터명. null 이면 전체 */
  sector: string | null
  count: number
  /** 섹터 평균 등락률 % */
  changeRate: number
}

export type StockFilter = {
  sector?: string
  /**
   * 상장 시장. **화면에 이 값을 거는 컨트롤은 없다**(2026-09-11) — 이 서비스는
   * 코스피만 다뤄 고를 것이 하나뿐이다. 서버가 받는 파라미터라 자리는 남겨 둔다.
   */
  market?: Market
  sentiment?: Sentiment
  perMin?: number
  perMax?: number
  hasOpenPrediction?: boolean
  watchedOnly?: boolean
  sort: StockSort
}

/** 한 화면에 담는 줄 수. 서버 기본값이 20, 상한이 100 이다. */
export const STOCK_PAGE_SIZE = 10

/** 빈 필터. "필터 초기화" 가 이 값으로 되돌린다. */
export const EMPTY_FILTER: StockFilter = { sort: 'PREDICTION_COUNT' }

/* 목록은 useCursorList 가 커서를 관리하므로 함수를 그대로 넘긴다.
   백엔드가 붙으면 이 함수 대신 '/stocks' 경로 문자열을 주면 된다. */
export function fetchStocks(filter: StockFilter) {
  return (query: Record<string, string | number | boolean | undefined>) => {
    const q = { ...filter, size: STOCK_PAGE_SIZE, ...query }
    return api.get<StockList>('/stocks', { query: q })
  }
}

/** 서버가 한 번에 내려 주는 상한. 명세 §종목 — 기본 20, 최대 100. */
const MAX_SIZE = 100

/** 커서를 따라가는 횟수 뚜껑. 서버가 hasNext 를 계속 참으로 주면 여기서 멈춘다 */
const MAX_PAGES = 12

/**
 * 전 종목을 한 번에. 히트맵(B-02)이 쓴다 — 트리맵은 일부만 그리면 면적 비율이
 * 거짓말이 되어, "더 보기" 로 이어 붙일 수가 없다.
 *
 * size 상한이 100 이라 코스피 536 종목이면 여섯 번을 이어 부른다. 한 번에 내려
 * 주는 엔드포인트를 백엔드에 요청해 두었고, 생기면 이 함수만 바뀐다.
 */
export async function fetchAllStocks(filter: StockFilter): Promise<StockList> {
  const items: StockListItem[] = []
  let cursor: string | number | undefined
  let baseDate: string | null = null

  for (let page = 0; page < MAX_PAGES; page += 1) {
    const res = await api.get<StockList>('/stocks', {
      query: { ...filter, size: MAX_SIZE, cursor },
    })
    items.push(...res.items)
    /* 기준 영업일은 목록 전체에 하나다. 첫 쪽이 비어 null 로 올 수 있어 덮어쓴다 */
    baseDate = res.baseDate ?? baseDate
    if (!res.hasNext || res.nextCursor == null) break
    cursor = res.nextCursor
  }

  return { items, nextCursor: null, hasNext: false, baseDate }
}

export function getSectorSummary() {
  return api.get<{ items: SectorSummary[] }>('/stocks/sectors')
}

/* ── 관심 종목 (B-04) ─────────────────────────────────────
   서버 WatchlistItemResponse 와 짝을 이룬다. 명세 §홈·시세의 다섯 필드다.

   **커서 페이징이 없다.** 담는 수가 화면 한 장 안이라 서버가 전량을 한 번에
   내린다 — 그래서 useCursorList 가 아니라 useAsync 를 쓴다.

   행별 예측 심리 배지를 두지 않는다(§9.2). 이 응답에 집계가 없고, 심리는
   B-02 · B-03 에서만 보여준다 — 없는 값을 만들어 채우지 않는다. */
export type WatchlistItem = {
  stockCode: string
  name: string
  /** 마지막 영업일 종가. 수집 범위(KOSPI 300) 밖으로 밀리면 null */
  prevClose: number | null
  /** 전일 대비 등락률 %. 점이 둘 미만이면 서버가 0 으로 준다 */
  changeRate: number | null
  /** 미니차트용 최근 30 영업일 종가. 오래된 것이 먼저, 마지막이 prevClose 와 같다 */
  series: number[]
}

/** 최근 담은 순으로 온다. 화면이 다시 정렬하지 않는다 */
export function getWatchlist() {
  return api.get<{ items: WatchlistItem[] }>('/watchlist')
}

/** 관심 담기·빼기. 화면은 낙관적으로 먼저 바꾸고 실패하면 되돌린다(§4 B-02). */
export function addWatch(stockCode: string) {
  return api.post<void>('/watchlist', { stockCode })
}

export function removeWatch(stockCode: string) {
  return api.delete<void>(`/watchlist/${stockCode}`)
}
