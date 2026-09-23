/* 시즌 진행(G-04) · 리서치(G-05) · 매매일지(G-06) 조회 API.

   타입은 API 명세서 v0.25 의 응답 스키마를 그대로 옮겼다. 담기지 않은 값은 만들지 않는다.

   실제 날짜·연도 필드를 타입에 두지 않는다. 축이 날짜가 아니라 gameDay 이기 때문이고,
   타입에 없으면 실수로 그릴 수도 없다.

   연습은 종목이 실명이라 시기가 사실상 드러난다 — 실명과 실제 주가가 함께 나가면 검색
   한 번에 날짜가 나온다. 그래도 날짜를 안 받는 이유는 위와 같다. 대회는 진짜로 가린다.

   담당 백엔드 티켓
   - GET /tickers · /prices — 됨(ANT-SEASON-10)
   - POST /join(restart 포함) · GET /me · POST /orders · GET /trades — 됨(ANT-SEASON-03, v0.36)
   - POST /advance · POST /finish · GET /result/me — 됨(ANT-SEASON-04, v0.37~39)
   - 대회 참가(참가비 소각 서명)는 501 — ANT-SEASON-06
   - GET /news — 됨(ANT-SEASON-07). 데이터는 seed/season-news.jsonl.gz 가 들어오면 채워진다
   - GET /indicators — MVP 에서 빼기로 했다. 절대값 하나로 구간이 특정된다

   Idempotency-Key 는 scope 하나에 키 하나가 물려 있다(api/idempotency). 성공한 뒤
   반납하지 않으면 다음 주문·참가가 같은 키로 나가 서버가 첫 응답을 되돌려 준다 —
   실제로는 아무것도 처리되지 않는다. 그래서 성공 직후 반납한다. */
import { api } from './client'
import { releaseIdempotencyKey } from './idempotency'

/* ── 참가 · POST /seasons/{id}/join ───────────────────────── */

export type JoinResult = { participantId: number; attemptNo: number; currentDay: number }

/**
 * 연습·시연 참가. restart 면 진행 중 회차를 버리고(ABANDONED) 새 회차로 시작한다 —
 * 화면의 "초기화하고 다시 시작" 확인 뒤에만 보낸다. 진행 중 회차가 있는데 restart 가
 * 아니면 409 SEASON_ALREADY_JOINED 다.
 */
export const join = (seasonId: number, restart = false) => {
  const scope = `join:${seasonId}${restart ? ':restart' : ''}`
  return api
    .post<JoinResult>(`/seasons/${seasonId}/join`, restart ? { restart: true } : undefined, {
      idempotencyScope: scope,
    })
    .then((r) => { releaseIdempotencyKey(scope); return r })
}

/* ── 시즌 종목 · GET /seasons/{id}/tickers ────────────────── */

/** 원본 종목코드는 오지 않는다. 모드와 무관하게 서버가 내리지 않는다. */
export type Ticker = {
  tickerId: number
  /**
   * 화면에 그리는 이름. 연습은 실제 종목명("삼성전자"), 대회는 가명("A사")이다.
   * 화면은 모드를 보지 않고 이 값을 그대로 쓴다.
   */
  displayName: string
  /** 섹터 힌트. 대회에서는 상위 분류로만 온다 */
  sector?: string
}

export const getTickers = (seasonId: number) =>
  api.get<{ items: Ticker[] }>(`/seasons/${seasonId}/tickers`)

/* ── 시즌 가격 · GET /seasons/{id}/tickers/{tickerId}/prices ── */

/** 한 게임일의 봉. 실제 날짜가 없고 gameDay 인덱스만 있다. */
export type Candle = {
  gameDay: number
  /** OHLC 는 원천에 없으면 null 이다 — 심지가 빠질 수 있다 */
  open: number | null
  high: number | null
  low: number | null
  /** 체결·판정·표시가 쓰는 유일한 가격. 항상 있다 */
  close: number
  volume: number | null
}

/**
 * uptoDay 를 넘기지 않으면 서버가 내 진행일까지만 준다. 진행일 초과분은 커닝이라
 * 서버가 아예 내리지 않는다 — 화면에서 자르는 것으로는 부족하다.
 */
export const getPrices = (seasonId: number, tickerId: number, uptoDay?: number) =>
  api.get<{ items: Candle[] }>(`/seasons/${seasonId}/tickers/${tickerId}/prices`, {
    query: { uptoDay },
  })

/* ── 내 현황·포트폴리오 · GET /seasons/{id}/me ────────────── */

export type Position = {
  tickerId: number
  displayName: string
  qty: number
  avgPrice: number
  /** 평가금액 */
  value: number
  /** 평가손익 */
  pnl: number
  /** 비중. 단위가 명세에 없어 화면은 weightOf() 를 쓴다 */
  weight: number
}

export type MyStatus = {
  /** 예수금 */
  cash: number
  totalAsset: number
  /** 주식 평가금액 */
  stockValue: number
  pnl: number
  pnlRate: number
  currentDay: number
  /**
   * 내 회차 상태. 주문·진행은 ONGOING 에서만 받는다(아니면 409). 진행일로는 끝났는지 알 수 없다 —
   * 건너뛰고 종료한 회차도, 마지막 날에 아직 종료하지 않은 회차도 진행일이 마지막 날이다.
   */
  status: 'ONGOING' | 'DONE' | 'ABANDONED'
  positions: Position[]
}

export const getMyStatus = (seasonId: number) =>
  api.get<MyStatus>(`/seasons/${seasonId}/me`)

/* ── 주문 · POST /seasons/{id}/orders ─────────────────────── */

export type Side = 'BUY' | 'SELL'

/** 게임일 종가 단일가로 체결된다. 부분 체결도 슬리피지도 없다. */
export type OrderResult = {
  tradeId: number
  /** 체결가 = 그 게임일 종가 */
  price: number
  gameDay: number
  /** 위탁수수료. 매수·매도 양쪽에 붙는다 */
  fee: number
  /** 증권거래세. 매도에만 붙고 매수는 0 이다 */
  tax: number
}

export const order = (seasonId: number, tickerId: number, side: Side, qty: number) => {
  const scope = `order:${seasonId}:${tickerId}:${side}:${qty}`
  return api
    .post<OrderResult>(`/seasons/${seasonId}/orders`, { tickerId, side, qty }, { idempotencyScope: scope })
    .then((r) => { releaseIdempotencyKey(scope); return r })
}

/* ── 게임일 진행 · POST /seasons/{id}/advance ─────────────── */

export type AdvanceResult = {
  currentDay: number
  /** 마지막 게임일에 닿았다. 화면은 결과로 넘긴다 */
  isLastDay: boolean
}

/**
 * expectedDay 는 낙관적 잠금이다. 내가 보고 있는 게임일을 함께 보내고, 서버의 현재
 * 게임일과 다르면 409 DAY_MISMATCH 로 전진하지 않는다 — 두 번 눌러도, 탭이 둘이어도
 * 하루만 넘어간다.
 *
 * Idempotency-Key 를 쓰지 않는다. 진행은 반복 호출이 정상인 연산이라 키를 발급할
 * 시점이 없다(명세 §1).
 */
export const advance = (seasonId: number, expectedDay: number) =>
  api.post<AdvanceResult>(`/seasons/${seasonId}/advance`, { expectedDay })

/* ── 회차 종료 · POST /seasons/{id}/finish ────────────────── */

/**
 * 성적표. 서버가 체결 내역을 처음부터 다시 돌려 계산하고 DB 에 굳힌다.
 *
 * <p>정의할 수 없는 값은 null 이다 — 매도가 없으면 승률·평균 보유일이 없고,
 * 손실이 하나도 없으면 손익비의 분모가 0 이다.
 */
export type SeasonFinishResult = {
  participantId: number
  /** 마지막 게임일 종가로 평가한 총자산 */
  finalAsset: number
  /** 시작 예수금 대비 % */
  returnRate: number
  /**
   * 등가중 벤치마크(%). 시즌 종목을 똑같이 나눠 사서 끝까지 들고 있었다면.
   * 코스피 지수가 아니라 <b>그 시즌 종목으로 만든 지수</b>다.
   */
  benchmarkReturn: number | null
  /**
   * 최대 낙폭(%). 최고점에서 가장 깊게 파인 곳까지다 —
   * 화면이 따로 세는 "최고점 대비 마감" 과 다르다. 그건 끝값이고 이건 도중의 바닥이다.
   */
  maxDrawdown: number
  /** 매도 건수 중 이익으로 끝난 비율(%) */
  winRate: number | null
  /** 이익 본 매도의 합 ÷ 손해 본 매도의 합. 1 미만이면 잃은 것이다 */
  profitFactor: number | null
  /** (판 날 − 처음 산 날) 의 평균. 단타였는지 길게 들었는지 */
  avgHoldingDays: number | null
}

/**
 * 이 회차를 끝낸다. <b>되돌릴 수 없다</b> — 회차가 DONE 이 되어 주문도 진행도 막힌다.
 * 마지막 게임일에서만 부를 수 있고(아니면 409 SEASON_NOT_LAST_DAY), 이미 끝난 회차면
 * 저장해 둔 같은 결과를 다시 준다.
 *
 * @param skipRemaining 건너뛰고 종료(v0.82). 마지막 게임일 전이어도 끝낸다 — 서버가 남은 날을
 *   주문 없이 넘긴 것으로 치고 **마지막 게임일 종가**로 정산한다. 하루씩 끝까지 넘긴 것과 같다.
 */
export const finish = (seasonId: number, skipRemaining = false) =>
  api.post<SeasonFinishResult>(
    `/seasons/${seasonId}/finish`, undefined,
    skipRemaining ? { query: { skipRemaining: true } } : undefined,
  )

/** season_results 전체 — 성적표 + 점수·등급·AI 복기. 점수·등급은 아직 null 이다 */
export type SeasonResultData = SeasonFinishResult & {
  score: number | null
  grade: string | null
  /** AI 복기. 잘한 판단 / 아쉬운 판단 / 개선 제안 세 단락. 서버에 키가 없던 회차는 null */
  review: string | null
  closedAt: string
}

/**
 * 내 마지막 회차의 결과. 끝나지 않았으면 404 SEASON_RESULT_NOT_FOUND —
 * 결과 화면이 이걸로 "이미 끝난 회차인가" 를 알고, 끝났으면 성적표·복기를 바로 그린다.
 */
export const getResult = (seasonId: number) =>
  api.get<SeasonResultData>(`/seasons/${seasonId}/result/me`)

/* ── 게임일 뉴스 · GET /seasons/{id}/news ─────────────────── */

export type NewsKind = 'NEWS' | 'DISCLOSURE' | 'IR' | 'EVENT' | 'AI_HINT'

/**
 * 제목·본문(앞부분 발췌)에서 날짜·연도·언론사를 지웠다. 종목은 실명이다.
 * link 는 원문 주소라 누르면 날짜가 보인다 — 연습 모드만 남아 그대로 준다.
 * AI_HINT 는 그 게임일 시장 전체 요약: title 이 한 줄 요약, body 가 주요 사건 세 줄(\n), link 는 null.
 * tickerId 를 빼면 AI_HINT, 넣으면 그 종목 기사다. 최근 게임일부터 30건, day 는 진행일에서 잘린다.
 */
export type SeasonNews = {
  kind: NewsKind
  title: string
  body: string | null
  link: string | null
  gameDay: number
}

export const getNews = (seasonId: number, day: number, tickerId?: number) =>
  api.get<{ items: SeasonNews[] }>(`/seasons/${seasonId}/news`, {
    query: { day, tickerId },
  })

/* ── 체결 내역 · GET /seasons/{id}/trades ─────────────────── */

export type Trade = {
  tradeId: number
  tickerId: number
  tickerName: string
  side: Side
  qty: number
  price: number
  /** 체결 대금. 비용을 빼기 전이다 — 실제로 오간 돈은 아래 fee·tax 를 더하거나 뺀 값이다 */
  amount: number
  /** 위탁수수료. 수수료가 없던 때(2026-09-23 이전)의 체결은 null 이다 */
  fee: number | null
  /** 증권거래세. 매도에만 붙는다. 옛 체결은 null */
  tax: number | null
  gameDay: number
  /** 매도에만 값이 있다. **왕복 비용을 뺀 순액**이다 */
  realizedPnl: number | null
}

/** 이 체결에 든 비용(수수료 + 거래세). 옛 체결의 null 은 0 으로 본다 */
export const tradeCost = (t: Pick<Trade, 'fee' | 'tax'>) => (t.fee ?? 0) + (t.tax ?? 0)

/** 이 체결로 예수금이 움직인 크기. 매수는 나간 돈, 매도는 들어온 돈 */
export const tradeSettled = (t: Pick<Trade, 'side' | 'amount' | 'fee' | 'tax'>) =>
  t.side === 'BUY' ? t.amount + tradeCost(t) : t.amount - tradeCost(t)

export type TradePage = { items: Trade[]; nextCursor: number | null; hasNext: boolean }

/**
 * 체결 내역. 최근 것이 먼저 온다.
 *
 * <p>커서는 체결 id 다(서버가 숫자로 준다). 종목·방향 필터도 서버가 받지만 G-06 은
 * 쓰지 않는다 — 한 회차 체결이 수십 건이라 전부 받아 두고 화면에서 거르는 쪽이
 * 필터를 바꿀 때마다 왕복하지 않아 빠르고, 요약 숫자도 정확해진다.
 * 건수가 커지면 그때 이 인자들을 쓰면 된다.
 */
export const getTrades = (
  seasonId: number,
  opts: { cursor?: number; size?: number; tickerId?: number; side?: Side } = {},
) =>
  api.get<TradePage>(`/seasons/${seasonId}/trades`, { query: { ...opts } })

/* ── 화면이 함께 쓰는 계산 ────────────────────────────────── */

/**
 * 비중(%). 응답의 weight 를 쓰지 않는 이유는 명세에 단위가 적혀 있지 않아서다.
 * 평가금액과 총자산에서 직접 구하면 도넛 조각과 옆에 적는 금액이 어긋날 수 없다.
 */
export const weightOf = (value: number, totalAsset: number) =>
  totalAsset > 0 ? (value / totalAsset) * 100 : 0

/* ── 매매 비용 ─────────────────────────────────────────────

   서버(application.yaml 의 app.season.fees)와 같은 값을 둔다 — 요율을 바꾸면 둘을 같이 고친다.
   API 로 받지 않는 이유: 요율은 설정이 아니라 정책이라 배포로만 바뀌고 BE·FE 가 같이 나간다. 화면은 "예상" 을 그리는 데만 쓰고 실제 금액은
   주문 응답의 fee·tax 다 — 둘이 어긋나면 서버가 맞다. 그래도 같은 값이어야 하는 이유는
   "최대" 수량이다: 화면이 수수료를 모르면 예수금을 꽉 채운 수량을 만들어 409 로 튕긴다. */

/** 요율은 분자·분모로 둔다 — 소수로 곱하면 1원이 어긋난다(floorRate). */
type Rate = { num: number; den: number }
/** 위탁수수료율 0.015%. 매수·매도 양쪽 */
const COMMISSION: Rate = { num: 15, den: 100_000 }
/** 증권거래세율 0.15%. 매도에만 */
const SELL_TAX: Rate = { num: 15, den: 10_000 }

/** 예상 주문 금액. 명세가 프론트 계산으로 지정한 값이다. 종가 단일가라 곱하기 하나다. */
export const orderAmount = (price: number, qty: number) => price * qty

/**
 * 원 단위 절사(서버의 SeasonFeeProperties 와 같은 방향). **요율을 그대로 곱하지 않는다** —
 * 800,000 × 0.00015 가 자바스크립트에서 119.999…라 절사하면 119 가 되는데, 서버는 BigDecimal
 * 이라 120 이다. 1원이 어긋나면 "예상 수령액이 실제와 다르다" 가 된다. 분자·분모로 나눠
 * 곱셈을 정수 안에 두면 그 차이가 생기지 않는다(800,000 × 15 = 12,000,000 은 정확하다).
 */
const floorRate = (amount: number, rate: Rate) => Math.floor((amount * rate.num) / rate.den)

/** 예상 위탁수수료 */
export const orderFee = (amount: number) => floorRate(amount, COMMISSION)

/** 예상 증권거래세. 매수는 0 */
export const orderTax = (side: Side, amount: number) =>
  side === 'SELL' ? floorRate(amount, SELL_TAX) : 0

/** 예상 비용 합계 */
export const orderCost = (side: Side, amount: number) => orderFee(amount) + orderTax(side, amount)

/** 실제로 오갈 돈. 매수는 내는 총액, 매도는 받는 실수령액 */
export const orderSettled = (side: Side, amount: number) =>
  side === 'BUY' ? amount + orderCost(side, amount) : amount - orderCost(side, amount)

/**
 * 이 가격에 최대 몇 주를 살 수 있는가. 수량 버튼의 "최대" 가 쓴다.
 *
 * 수수료까지 포함해 나눈다 — 대금만으로 나누면 예수금을 꽉 채운 수량이 나와 수수료만큼
 * 모자라고, "최대" 를 누르는 족족 409 INSUFFICIENT_BALANCE 다.
 *
 * 나눈 값을 그대로 쓰지 않고 양쪽으로 한 번씩 맞춘다. 나눗셈은 수수료를 <b>절사하기 전</b>
 * 요율로 하는데 서버는 원 단위로 버리므로, 실제로 낼 수수료가 조금 더 싸서 한두 주를 더 살 수
 * 있다(싼 종목일수록 크다 — 1,000원짜리를 만 주 살 때 한 주 차이가 난다). 내려가는 쪽은
 * 반대 방향의 경계를 막는다. 둘 다 서버가 받아 주는 수량(=orderSettled ≤ 예수금)만 남긴다.
 */
export const maxBuyQty = (cash: number, price: number) => {
  if (price <= 0) return 0
  const affordable = (q: number) => orderSettled('BUY', orderAmount(price, q)) <= cash
  let qty = Math.floor((cash * COMMISSION.den) / (price * (COMMISSION.den + COMMISSION.num)))
  while (qty > 0 && !affordable(qty)) qty -= 1
  while (affordable(qty + 1)) qty += 1
  return qty
}

/** 손익 부호. 색을 정하는 자리가 한 곳이어야 빨강·파랑이 화면마다 뒤집히지 않는다. */
export const signOf = (n: number): 'up' | 'down' | 'flat' =>
  n > 0 ? 'up' : n < 0 ? 'down' : 'flat'

/** 등락률 표기. 0 도 부호 없이 그대로 적는다 */
export const rate = (n: number) => `${n > 0 ? '+' : ''}${n.toFixed(2)}%`
