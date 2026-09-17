/* 천하제일 단타대회 — 대회 조회·배팅 등록. API 명세서 v0.70 §대회·배팅.

   GET  /events                       목록 (배너·탭 진입)
   GET  /events/{eventId}             홈 — 종목 카드·풀·배당률·내 배팅
   POST /events/{eventId}/entries     묶음 등록 (Idempotency-Key 필수, 202)
   GET  /events/{eventId}/me          내 참가 내역
   GET  /events/{eventId}/leaderboard 순위

   ── 이 모듈이 지키는 것 셋 ──

   ① 종목 목록은 서버가 준다. 화면에 상수로 두지 않는다.
      idx 가 온체인 커밋 keccak(eventId, idx, side, noteHash) 의 재료라, 목록이 하나만
      어긋나도 배팅이 엉뚱한 종목에 걸린다. 순서까지 서버를 따른다.

   ② 회차는 slug 로 찾는다. 숫자 eventId 를 상수로 박으면 회차가 만들어진 뒤에야
      그 값을 알 수 있고, 운영 점검용 회차가 생기면 "목록 첫 번째" 도 못 쓴다.

   ③ null 은 "아직 정해지지 않았다" 다. 키는 늘 있고 값만 null 이다 —
      정산 전 paid·net·rank·avgError, 관리자 입력 전 basePrice, 안 건 종목의 mine.
      0 으로 읽으면 "본전"·"꼴찌"·"기준가 0원" 이 된다. */
import { api } from './client'
import { releaseIdempotencyKey } from './idempotency'
import { formatTargetPrice, noteHash } from './predictions'
import type { WalletNonce } from './wallet'

/** 서버 PredictionEvent.Status 여섯 갈래. 화면은 보통 phase 를 본다. */
export const EVENT_STATUSES = [
  'SCHEDULED', 'OPEN', 'SEALED', 'REVEALED', 'RESOLVED', 'SETTLED',
] as const
export type EventStatus = (typeof EVENT_STATUSES)[number]

/**
 * 화면용 단계. 서버가 status 여섯을 넷으로 접어 준다.
 *
 * REVEALED·RESOLVED 는 정산 진행 중의 내부 단계라 사용자에게는 SEALED 와 같은 그림이다.
 * 이 값만 보면 온체인 진행 순서를 몰라도 된다.
 */
export const EVENT_PHASES = ['BEFORE', 'ENTRY', 'SEALED', 'SETTLED'] as const
export type EventPhase = (typeof EVENT_PHASES)[number]

/** 서버 PredictionEventBet.Status. FAILED 는 체인에 못 올라간 것 — 집계 밖이다. */
export const BET_STATUSES = [
  'PENDING', 'ACTIVE', 'REVEALED', 'SETTLED', 'REFUNDED', 'FAILED',
] as const
export type BetStatus = (typeof BET_STATUSES)[number]

export type BetDirection = 'UP' | 'DOWN'
/** 종목 판정. TIE 는 환불이다. */
export type StockWinner = 'UP' | 'DOWN' | 'TIE'

export type EventListItem = {
  eventId: number
  /** 화면 주소에 쓰는 이름. 운영 점검용 회차는 null 일 수 있다 */
  slug: string | null
  title: string
  status: EventStatus
  phase: EventPhase
  opensAt: string
  sealsAt: string
  baseDate: string
  settleDate: string
}

/** 회차마다 달라질 수 있어 서버가 응답에 싣는다 — 500·2000·5 를 화면에 하드코딩하지 않는다. */
export type EventRules = {
  minStake: number
  maxStake: number
  minStocks: number
  stockCount: number
}

/**
 * 종목별 배팅 집계. **PENDING·FAILED 는 빠져 있다** — 체인에 안 올라간 돈이라,
 * 넣으면 실패한 전송이 남의 배당률을 흔든다.
 *
 * oddsUp/oddsDown = (up+down) ÷ 해당 풀. **그 풀이 0 이면 null** 이다 —
 * 0 으로 나눌 수 없고, 0 배당은 "걸면 아무것도 못 받는다" 는 거짓이다. 대시로 그린다.
 */
export type EventPool = {
  up: number
  down: number
  upCount: number
  downCount: number
  oddsUp: number | null
  oddsDown: number | null
}

/**
 * **승자투표권(패리뮤추얼) 배당 — 이 규칙이 사는 곳은 여기 한 곳이다.**
 *
 *     받을 돈 = 건 돈 × (양쪽 풀 합) ÷ (내가 선 쪽 풀)
 *
 * 두 가지로 갈리는 이유는 **내 배팅이 이미 풀에 들어 있는가** 하나뿐이다.
 * 걸기 전 미리보기(C-01 배팅 칸)는 내가 들어가면 그 순간 풀이 커지므로 양쪽에 더한
 * 뒤 재야 한다 — 빼고 재면 실제보다 큰 금액이 나와 화면이 부풀린 숫자를 약속한다.
 * 이미 건 뒤(대회 카드)는 서버가 준 풀에 내 돈이 들어 있으므로 그대로 나눈다.
 *
 * 반대쪽이 비어 있으면 받을 돈이 건 돈과 같아진다(가져올 남의 돈이 없다). 0 이 아니라
 * 원금이 맞다 — 컨트랙트도 이긴 풀이 전부일 때 원금을 돌려준다.
 */
function share(total: number, side: number, stake: number) {
  /* 내 쪽 풀이 0 이면 나눌 수 없다. 걸었는데 0 이라는 것은 아직 체인에 안 올라갔다는
     뜻이다(PENDING·FAILED 는 집계에서 빠진다) — 그때는 값을 지어내지 않는다. */
  if (side <= 0) return null
  return Math.floor((stake * total) / side)
}

/** 이미 걸어 둔 배팅이 **맞으면** 받을 돈. 내 stake 는 pool 에 이미 들어 있다. */
export function payoutOfBet(pool: EventPool, direction: BetDirection, stake: number) {
  return share(pool.up + pool.down, direction === 'UP' ? pool.up : pool.down, stake)
}

/** 아직 걸기 전 미리보기. 내 stake 를 양쪽에 더한 뒤 잰다. */
export function payoutIfIBet(pool: EventPool, direction: BetDirection, stake: number) {
  const mine = direction === 'UP' ? pool.up : pool.down
  return share(pool.up + pool.down + stake, mine + stake, stake)
}

/** 내가 그 종목에 건 것. 남의 방향·목표가는 응답에 아예 없다(집계만 나온다). */
export type EventMyBet = {
  predictionId: number
  stake: number
  betStatus: BetStatus
  direction: BetDirection
  targetPrice: number
}

export type EventStockResult = {
  closePrice: number
  winner: StockWinner
  payout: number | null
}

export type EventStockCard = {
  /** 컨트랙트 stockIdx. 커밋의 재료라 서버가 준 값을 그대로 보낸다 */
  idx: number
  stockCode: string
  name: string
  /** 기준일 종가. 관리자가 넣기 전엔 null — 0 으로 그리면 등락률이 거짓이 된다 */
  basePrice: number | null
  /** 최신 종가. 표시용이고 판정에는 쓰지 않는다 */
  lastPrice: number | null
  pool: EventPool
  /** 안 걸었으면 null */
  mine: EventMyBet | null
  /** 판정 전엔 null */
  result: EventStockResult | null
}

/** 내 참가 요약. 정산 전에는 paid·net·rank 가 null 이다. */
export type EventMeSummary = {
  stockCount: number
  staked: number
  qualified: boolean
  remainingStocks: number
  paid: number | null
  net: number | null
  rank: number | null
}

export type EventHome = {
  eventId: number
  slug: string | null
  title: string
  status: EventStatus
  phase: EventPhase
  opensAt: string
  sealsAt: string
  baseDate: string
  settleDate: string
  rules: EventRules
  stocks: EventStockCard[]
  me: EventMeSummary
}

// ── 조회 ────────────────────────────────────────────────

export function fetchEvents() {
  return api.get<{ items: EventListItem[] }>('/events')
}

export function fetchEvent(eventId: number) {
  return api.get<EventHome>(`/events/${eventId}`)
}

/**
 * slug 로 회차를 찾는다. 없으면 null — 화면은 "준비 중" 으로 그린다.
 *
 * 목록을 한 번 부르고 거기서 eventId 를 푼다. 경로에 slug 를 받는 엔드포인트를 따로 두지
 * 않은 것은 같은 리소스에 문이 둘이 되기 때문이다(서버 판단, ANT-EVENT-08).
 */
export async function fetchEventBySlug(slug: string) {
  const { items } = await fetchEvents()
  return items.find((e) => e.slug === slug) ?? null
}

/**
 * 대회 화면(J-01)이 띄울 회차를 고른다. **운영에서는 slug 가 맞는 회차뿐이다.**
 *
 * ── 왜 폴백을 걷었나 (2026-09-17, ANT-FE-EVENT-LIFECYCLE) ────────────
 * 전에는 slug → 접수 중(ENTRY) → 가장 최근 회차 순으로 폴백했다. 리허설 회차가 slug 없이
 * 만들어져서 QA 때 화면을 띄우려는 것이었는데, 그 폴백 때문에 **끝난 리허설 회차가 배포본의
 * 대회 페이지와 랭킹 탭에 그대로 새어 나왔다**("리허설 3회차"). 회차가 하나라도 있으면 항상
 * 뭔가 잡히는 규칙이었다.
 *
 * 이제 운영 빌드는 ① 만 본다. 없으면 null — 화면은 "지금 진행 중인 대회가 없어요". 점검용
 * 회차는 slug 가 뭐든 사용자 화면에 안 잡히고, 다음 대회가 생기면 slug 상수가 바뀌어 지난
 * 대회는 저절로 빠진다. **판정이 끝난 본 대회는 그대로 잡힌다** — 순위를 보러 오는 길이
 * 남아야 해서다. 대회 페이지 쪽은 그때 랭킹 탭으로 보낸다(EventContest).
 *
 * 개발 빌드(`import.meta.env.DEV`)에서만 옛 폴백을 남긴다 — 리허설 회차로 화면을 확인하는
 * 길. `cardHref` 가 같은 스위치를 쓰고, 빌드 때 상수로 접혀 운영 번들에는 이 가지가 없다.
 *
 * 이 규칙의 대가: **본 대회 slug 가 `CONTEST.id`(scalping) 가 아니면 아무것도 안 보인다.**
 * 새 제약은 아니다 — StockDetail 이 배팅 요소를 띄우는 조건도 `?event=scalping` 이다.
 */
export async function fetchContestEvent(slug: string) {
  const summary = await fetchContestSummary(slug)
  return summary === null ? null : fetchEvent(summary.eventId)
}

/**
 * 같은 규칙으로 회차만 고르고 **상세는 읽지 않는다.** 목록 한 번으로 끝난다.
 *
 * 랭킹 화면(E-01)의 이벤트 탭 배지가 이것을 쓴다 — 배지에 필요한 건 phase 하나인데
 * 종목·내 배팅까지 딸려 오는 상세를 부를 이유가 없다. 고르는 규칙을 두 곳에 복사하면
 * 한쪽만 고쳐져 탭 배지와 실제 화면이 서로 다른 회차를 말하게 된다.
 */
export async function fetchContestSummary(slug: string): Promise<EventListItem | null> {
  const { items } = await fetchEvents()
  if (items.length === 0) return null

  const bySlug = items.find((e) => e.slug === slug) ?? null
  if (bySlug !== null || !import.meta.env.DEV) return bySlug

  /* 개발 빌드 전용 폴백 — 리허설 회차(slug 없음)로 화면을 확인하는 길.
     서버가 id 내림차순으로 준다(EventQueryService.list) — 첫 줄이 가장 최근이다 */
  return items.find((e) => e.phase === 'ENTRY') ?? items[0]
}

export type EventEntryResult = {
  closePrice: number
  winner: StockWinner
  payout: number | null
  errorRate: number | null
}

export type EventMyEntry = {
  predictionId: number
  idx: number
  stockCode: string
  name: string
  direction: BetDirection
  targetPrice: number
  stake: number
  betStatus: BetStatus
  /** 확정된 배팅의 온체인 tx. PENDING 이면 null */
  txHash: string | null
  /** 202 폴링용. GET /operations/{id} */
  operationId: string | null
  result: EventEntryResult | null
}

export type EventMe = {
  phase: EventPhase
  summary: {
    stockCount: number
    staked: number
    qualified: boolean
    paid: number | null
    net: number | null
    /** HIT 인 예측만의 평균 오차. 정산 전·HIT 없으면 null */
    avgError: number | null
    rank: number | null
  }
  entries: EventMyEntry[]
}

export function fetchMyEntries(eventId: number) {
  return api.get<EventMe>(`/events/${eventId}/me`)
}

export type LeaderboardItem = {
  rank: number | null
  userId: number
  nickname: string | null
  stockCount: number
  staked: number
  paid: number | null
  net: number | null
  /** net ÷ staked × 100. 서버가 계산한다 */
  profitRate: number | null
  hitCount: number
  doneCount: number
  avgError: number | null
  qualified: boolean
}

export type EventLeaderboard = {
  phase: EventPhase
  /** 순위가 아직 없을 때 화면이 그대로 띄우는 문구. 있으면 items 는 비어 있다 */
  notice: string | null
  items: LeaderboardItem[]
  /** 미참가·자격 미달이면 null. 자기 성적은 fetchMyEntries 가 준다 */
  me: LeaderboardItem | null
  nextCursor: string | null
}

/**
 * 순위. **정산이 끝나야 items 가 찬다.**
 *
 * 봉인 뒤에도 예상 순위를 내리지 않는다(09-16 결정) — 승패를 모르는데 화면에 순위로 뜨면
 * 사용자는 현재 등수로 읽는다. 그 전에는 notice 문구만 온다.
 */
export function fetchLeaderboard(eventId: number, cursor?: string | null, size?: number) {
  return api.get<EventLeaderboard>(`/events/${eventId}/leaderboard`, {
    query: { cursor: cursor ?? undefined, size },
  })
}

// ── 등록 ────────────────────────────────────────────────

export type EventEntryItem = {
  stockCode: string
  direction: BetDirection
  targetPrice: number
  /** 정수 ANT. minStake ≤ stake ≤ maxStake */
  stake: number
  /** 빈 문자열이어도 된다 — noteSalt 가 있어 해시는 여전히 난수다 */
  note: string
  /** 소문자 64 hex. newNoteSalt() 로 만든다 */
  noteSalt: string
  evidencePointIds?: number[]
}

export type EventEntryBody = {
  items: EventEntryItem[]
  signature: string
}

export type EventEntryAccepted = {
  eventId: number
  entries: {
    stockCode: string
    predictionId: number
    /** 앵커용 예측 커밋 해시 */
    commitHash: string
    /** 종목마다 하나. GET /operations/{id} 로 폴링한다 */
    operationId: string
  }[]
  me: Omit<EventMeSummary, 'paid' | 'net' | 'rank'>
}

/**
 * 멱등키 칸. **제출마다 나뉜다** — 회차 하나로 묶으면 두 번째 제출이 첫 제출의 키를
 * 물려받아, 서버가 첫 응답을 그대로 돌려준다(같은 키 = 같은 요청으로 본다).
 *
 * 화면이 종목마다 따로 등록하게 되면서(2026-09-18) 이게 실제 사고가 된다 — 두 번째
 * 종목을 걸면 첫 종목의 결과가 돌아온다. noteSalt 는 제출마다 새로 뽑히므로
 * (newNoteSalt) 첫 항목의 것만으로 제출이 갈린다. 재시도는 같은 items 를 다시 넘기니
 * 같은 키가 나가 중복 등록이 막힌다 — 멱등키의 본래 목적은 그대로다.
 */
export const eventEntryScope = (eventId: number, items: EventEntryItem[]) =>
  `event-entry:${eventId}:${items[0]?.noteSalt ?? ''}`

/**
 * 서명 대상 문자열. **서버 EventEntryService.Signed.signingPayload 와 바이트가 같아야 한다.**
 *
 * 한 바이트만 달라도 예외가 아니라 **엉뚱한 주소가 복원돼** 401 SIGNER_MISMATCH 다.
 * 원인이 로그에 안 남는다. 고정 벡터로 대조한다 —
 * backend/src/test/resources/event/event-entry-signature-fixture.json
 *
 *   antenna:event-entry:v1
 *   eventId=1
 *   005930/UP/93000.00/0x…/1000
 *   000660/DOWN/210000.00/0x…/500
 *   chainId=31221
 *   nonce=…
 *
 * - 항목 한 줄. **순서는 items 순서 그대로** — 서버가 정렬하지 않는다
 * - targetPrice 는 늘 소수 둘째 자리(formatTargetPrice). 93000 과 93000.00 이 갈리면 서명만 어긋난다
 * - noteHash 는 기존 예측 커밋과 같은 함수다(CommitHashes.noteHash)
 * - chainId 는 nonce 응답값을 쓴다. 지갑에서 eth_chainId 로 읽으면 서버 조립본과 어긋난다
 * - **온체인 커밋은 들어가지 않는다** — 서버가 같은 재료로 따로 만든다
 *
 * async 인 이유는 noteHash 가 ethers 를 동적으로 받아 오기 때문이다(chain/keccak.ts).
 * 예측 쪽 predictionSigningPayload 는 동기인데, 그쪽은 호출부가 커밋에도 noteHash 를
 * 써서 미리 계산한 값을 넘기기 때문이다. 대회는 온체인 커밋을 서버가 만들어 프론트가
 * noteHash 를 따로 쓸 일이 없으므로 여기서 한 번에 구한다.
 */
export async function eventEntrySigningPayload(
  eventId: number,
  items: EventEntryItem[],
  nonce: WalletNonce,
) {
  const hashes = await Promise.all(items.map((it) => noteHash(it.note, it.noteSalt)))
  return [
    'antenna:event-entry:v1',
    `eventId=${eventId}`,
    ...items.map((it, i) =>
      [it.stockCode, it.direction, formatTargetPrice(it.targetPrice), hashes[i], it.stake].join('/'),
    ),
    `chainId=${nonce.chainId}`,
    `nonce=${nonce.nonce}`,
  ].join('\n')
}

/**
 * 묶음 등록. 202 다 — 저장은 끝났고 온체인 전송이 확인 대상이라,
 * entries[].operationId 마다 useOperation 으로 폴링한다.
 *
 * 첫 참가는 rules.minStocks 개 이상이어야 한다. 두 번째 제출부터는 한 종목씩 더해도 된다.
 * 열 종목 중 하나가 전송에 실패해도 202 이고, 그 하나는 서버 재시도 크론이 봉인 전까지
 * 다시 보낸다 — 사용자가 할 일은 없다.
 */
export function registerEntries(eventId: number, body: EventEntryBody) {
  const scope = eventEntryScope(eventId, body.items)
  return api
    .post<EventEntryAccepted>(`/events/${eventId}/entries`, body, { idempotencyScope: scope })
    /* 확정된 뒤에야 키를 버린다 — 실패한 재시도는 같은 키로 가야 서버가 중복으로 세지
       않는다. 예측 등록(createPrediction)과 같은 규칙이다. 안 버리면 다음 제출이 이 키를
       물려받아 첫 응답을 그대로 돌려받는다. */
    .then((r) => { releaseIdempotencyKey(scope); return r })
}
