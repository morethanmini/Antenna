/* C-01 예측 등록 도메인. 설계서 §3 C · §4 C-01 · §5 · §9.2.

     GET  /predictions/slots         이번 주 슬롯 잔여
     POST /predictions               등록 — 201 슬롯 내 / 202 슬롯 초과(소각)
     GET  /predictions/me            내 예측 (C-02)
     GET  /predictions/{id}          예측 상세 (C-03)

   ── 목업이 남아 있지 않다 ──────────────────────────────────
   마지막 하나였던 예측 상세가 ANT-PRED-05 로 열려 실제 호출로 넘어갔다(2026-09-15).
   MOCK 상수와 mock/predictions.ts 를 함께 지웠다.

   ⚠ **여기 있던 "status 어휘에 JUDGED 가 없다" 는 사실이 아니다**(2026-09-15
   확인). 서버 MyPredictionQueryService.StatusFilter 에 JUDGED 가 있고
   status=JUDGED 로 부르면 200 이다. 그 문장이 딸려 설명하던 "C-02 를 적중·
   빗나감 두 칸으로 갈랐다" 도 지금 화면과 다르다 — C-02 는 서버 거르개를
   안 쓰고 전량을 받아 화면이 단계로 가른다. 자세한 것은 아래 MY_FILTERS.

   종목별 예측 목록(GET /stocks/{code}/predictions)은 이제 요청하지 않는다 —
   2026-09-10 설계 변경으로 종목 상세가 개인을 보여주지 않고 목표가 분포만 낸다.
   그 자리는 GET /stocks/{code}/predictions/distribution 이 채웠다.
   ─────────────────────────────────────────────────────── */
import { api } from './client'
import { releaseIdempotencyKey } from './idempotency'
import { keccak256Utf8 } from '../chain/keccak'
import type { ProofAnchorStatus } from './proof'
import type { CursorList, OperationRef } from './types'
import type { WalletNonce } from './wallet'

/** UP/DOWN 둘뿐이다. "보합" 을 두지 않는다(§4 C-01). */
export const DIRECTIONS = ['UP', 'DOWN'] as const
export type Direction = (typeof DIRECTIONS)[number]

/**
 * 3~90 사이 정수 일수. 눈금 네 개(7·14·30·90) 고정이었는데 2026-09-14 에 연속 구간으로
 * 넓혔다 — "2주쯤" 처럼 눈금 사이를 노리는 예측을 눈금에 맞춰 반올림하게 만들 이유가 없다.
 *
 * **거래일이 아니라 캘린더일이다**(결정 B5, 명세 v0.38). 만기일 = 기준일 + horizon 일로
 * 등록 즉시 확정된다 — 휴장일을 세지 않으므로 클라이언트가 그대로 계산할 수 있다.
 * DB CHECK(DB-01)·서버 검증이 같은 구간이라, 밖의 값을 보내면
 * 400 INVALID_REQUEST(horizon) 이다.
 */
export const HORIZON_MIN = 3
export const HORIZON_MAX = 90
export type Horizon = number

/** 고르개 눈금에만 쓰는 참조값. 값 자체는 이 넷에 묶이지 않는다. */
export const HORIZON_TICKS = [3, 30, 60, 90] as const

export const horizonLabel = (h: Horizon) => `${h}일`

/** 근거 본문 상한. 구독자 전용이며 만기 리빌 후에도 공개되지 않는다. */
export const NOTE_MAX = 5000

/** 슬롯은 **하루** 단위다(서버 app.prediction.slot.free-per-day). 주 단위가 아니다. */
export type SlotStatus = {
  /** 이 셈이 걸린 날(KST). 자정에 넘어간다 */
  date: string
  freeLimit: number
  used: number
  remaining: number
  /**
   * 슬롯을 넘겨 등록할 때 소각할 금액(정수 ANT 문자열). 서버 금액표
   * app.token.amounts.slot-over(ANT-TOKEN-08)에서 온다. 금액 필드는 문자열로 통일돼 있어
   * 숫자로 바꾸지 않는다 — 표시는 formatToken(api/wallet.ts) 으로 한다.
   */
  overCost: string
}

export type PredictionDraft = {
  stockCode: string
  direction: Direction
  targetPrice: number
  horizon: Horizon
  note: string
  /**
   * 근거를 봉인하는 난수. 클라이언트가 만들어 본문에 함께 보낸다(ANT-PRED-02).
   * 소문자 64 hex, `0x` 없음 — {@link newNoteSalt} 참고.
   */
  noteSalt: string
  /** B-03 투자 포인트에서 인계받은 id. 리포트·재무지표는 근거가 될 수 없다 */
  evidencePointIds: number[]
}

/** 201 응답. basePrice 는 배치 B2 가 다음 영업일 종가로 채운다 — 비어 있다. */
export type PredictionCreated = {
  id: number
  commitHash: string
  /** 등록 직후에는 항상 WAITING. 배치 B2 가 묶으면 넘어간다 */
  anchorStatus: ProofAnchorStatus
  status: 'BASE'
  /** 등록 시점의 다음 평일. 기준가는 이 날 종가로 배치가 채운다 */
  baseDate: string
  /** baseDate + horizon 캘린더일 — 등록 즉시 확정된다 */
  settleDate: string
}

/**
 * 슬롯을 넘겨 소각으로 넘어간 경우. M-02 가 operationId 를 폴링한다.
 *
 * **서버가 아직 이 응답을 내지 않는다.** 소각할 토큰(ANT-CHAIN-03)이 없어 슬롯 초과는
 * 409 PREDICTION_SLOT_EXCEEDED 로 끝난다(명세 v0.41). 규격에는 남아 있어 타입도 남기되,
 * 지금 이 갈래로 오는 응답은 없다.
 */
export type PredictionQueued = OperationRef

export type CreateResult =
  | { kind: 'created'; data: PredictionCreated }
  | { kind: 'queued'; data: PredictionQueued }

export function getSlots() {
  return api.get<SlotStatus>('/predictions/slots')
}

/* ── 예측 포트폴리오 · GET /predictions/me/portfolio ──────
   C-04 가 쓰는 서버 집계. 여덟 필드가 전부다(설계서 §4 C-04). 화면이 목록을
   전량 받아 세지 않는 이유는 예측이 늘어도 읽는 행이 늘지 않게 하려는 것이다. */

export type Portfolio = {
  total: number
  hit: number
  miss: number
  /** BASE 와 OPEN 을 합친 수. 화면이 둘을 가르지 않는다 */
  pending: number
  /** 적중률 % — 분모는 판정된 것뿐이다. 판정이 0건이면 null */
  hitRate: number | null
  /** 판정된 예측의 평균 오차 % · 판정이 0건이면 null */
  avgError: number | null
  /** 체인에 **박힌** 배치에 든 수 — 배치가 CONFIRMED 인 것만이다 */
  anchored: number
  /** 나머지 전부 — 커밋 전, 배치 대기, PENDING·FAILED 배치 */
  unanchored: number
}

/* ── 공개 규칙 (§5 개정, 2026-09-10) ──────────────────────
   판정 전후로 갈리고, 근거만 끝까지 잠긴다.

     필드          판정 전(BASE·OPEN)   판정 후(HIT·MISS)
     ─────────────────────────────────────────────────────
     어느 종목      전체 공개            전체 공개
     방향          구독자만             전체 공개
     목표가         구독자만             전체 공개
     근거 본문      구독자만             구독자만

   판정 후 방향을 가리지 않는 이유 — 목표가가 공개되면 기준가와 비교해 방향이
   그대로 드러난다. 가려도 가려지지 않으므로 잠그는 시늉만 남는다.

   근거가 판정 후에도 잠기는 것은 서버 결정 D6 과 같다(PredictionCommit 주석).

   타입에 null 을 그대로 적는다 — 화면이 잠긴 값을 그리려 하면 tsc 가 먼저 막는다.
   404 로 감추지 않는 것도 그대로다. 없는 것처럼 보이면 구독 유인이 사라진다. */

/** 상태 전이는 BASE → OPEN → HIT/MISS 다(서버 Prediction.Status). */
export const PREDICTION_STATUSES = ['BASE', 'OPEN', 'HIT', 'MISS'] as const
export type PredictionStatus = (typeof PREDICTION_STATUSES)[number]

/* 목록을 가르는 두 묶음. 잠금 경계와 정확히 같은 자리에서 갈린다 —
   PENDING 은 구독자만 볼 수 있고 JUDGED 는 전체 공개다(§5). */
export const PREDICTION_PHASES = ['PENDING', 'JUDGED'] as const
export type PredictionPhase = (typeof PREDICTION_PHASES)[number]

export const PHASE_LABEL: Record<PredictionPhase, string> = {
  PENDING: '판정 대기',
  JUDGED: '판정 완료',
}

export const phaseOf = (s: PredictionStatus): PredictionPhase =>
  (s === 'HIT' || s === 'MISS' ? 'JUDGED' : 'PENDING')

/* ── 내 예측 (C-02) ───────────────────────────────────────
   서버 MyPredictionItemResponse 와 짝을 이룬다(ANT-PRED-06). 명세가 못 박은
   아홉 필드에 stockName · anchorStatus 가 더해진 열한 개이고, **그 밖의 값을
   화면에서 만들어 내지 않는다.** 시안에 있는 현재가 · 확신도 · 진행률 · 정확도
   점수는 이 응답에 없다 — 그리려면 API 가 먼저 늘어야 한다.

   잠금이 없다. 내 예측이라 전부 보인다 — 남의 예측(StockPrediction)과 필드부터
   다르므로 타입을 합치지 않았다. */
export type MyPrediction = {
  /** 서버 PK(long). 커서도 이 값이라 문자열로 바꾸지 않는다 */
  id: number
  /* 종목이 지워진 예측이 있을 수 있어 서버가 둘 다 null 로 내린다
     (MyPredictionItemResponse: stock == null ? null : ...). 화면은 이름이
     없으면 코드로, 코드까지 없으면 그 자리를 비운다 — "null" 을 그리지 않는다. */
  stockCode: string | null
  stockName: string | null
  direction: Direction
  targetPrice: number
  horizon: Horizon
  status: PredictionStatus
  /** 만기까지 남은 일수. 판정이 끝난 건은 null — 0 으로 그리지 않는다 */
  dday: number | null
  /** 목표가 대비 오차 %. 판정 완료만 채워진다 */
  errorRate: number | null
  /** 만기 영업일 YYYY-MM-DD. 기준가 배치가 채우기 전에는 null 이다 */
  settleDate: string | null
  /* 이 예측이 블록에 올라갔는가. 서버가 행마다 함께 내린다
     (MyPredictionItemResponse.anchorStatus, 2026-09-09). 어휘는 proof 와 같은
     넷이라 C-03 과 같은 말로 그린다 — 커밋이 아직 없으면 WAITING 이다.

     앵커 배치는 **10분마다** 그때까지 쌓인 봉인을 묶는다(AnchorScheduler,
     주말·휴장 포함 — 2026-09-15 에 매일 00:05 에서 바뀌었다). WAITING 은
     고장이 아니라 정상 대기이고, 화면은 그 사실을 문장으로 말해 줘야 한다.
     문구는 AnchorStage 의 ANCHOR_BATCH_CYCLE 한 곳에서 온다. */
  anchorStatus: ProofAnchorStatus
}

/* 상태 필터 어휘(MY_FILTERS · MY_FILTER_LABEL · MyFilter)를 지웠다(2026-09-15).

   쓰는 화면이 하나도 없었다. C-02(PredictList)가 서버 거르개를 안 쓰는 쪽으로
   바뀌었기 때문이다 — 전량을 받아 화면이 STAGES(기록 예정 / 예측 진행 /
   결과 확인)로 가른다. 서버 어휘와 칸 이름조차 다르다.

   서버 status 파라미터 자체는 살아 있다(ALL · PENDING · HIT · MISS · JUDGED,
   명세 v0.29). 다시 거르개를 서버에 맡기게 되면 그때 어휘를 새로 옮기면 된다 —
   쓰지 않는 어휘를 들고 있는 편이 더 나쁘다. 이 자리에 있던 주석이 "JUDGED 가
   없다" 는 거짓을 주장하고 있었고, 실제로 그 문장을 믿고 잘못 판단한 일이 있었다. */

/** 목록 전체에 걸리는 값. 필터를 바꿔도 네 숫자는 그대로여야 한다 */
export type MyPredictionMeta = {
  total: number
  pendingCount: number
  judgedCount: number
  /** 판정 완료 중 적중 **비율(0~1)**. 판정 건이 없으면 null */
  hitRate: number | null
}

/* 서버가 hit/judged 를 소수 넷째 자리까지 내린다(0.6 · 0.6667). 퍼센트로
   바꾸는 자리를 여기 하나로 둔다 — 화면마다 100 을 곱하면 한 곳에서 빼먹는
   순간 "0.6%" 가 그려지고, 그게 틀렸다는 걸 아무도 눈치채지 못한다. */
export const hitRatePercent = (ratio: number) => Math.round(ratio * 1000) / 10

export type MyPredictionList = CursorList<MyPrediction> & MyPredictionMeta

export const MY_PREDICTION_PAGE_SIZE = 12

/**
 * useCursorList 가 커서를 관리하므로 함수를 넘긴다.
 *
 * status 를 붙이지 않는다 — 서버가 "전체" 를 기본으로 보고, C-02 는 전량을 받아
 * 화면에서 단계로 가른다. 서버에 거르개를 맡기게 되면 여기에 인자를 되살린다.
 */
export function fetchMyPredictions() {
  return (query: Record<string, string | number | boolean | undefined>) =>
    api.get<MyPredictionList>('/predictions/me', {
      query: { size: MY_PREDICTION_PAGE_SIZE, ...query },
    })
}

/* ── 예측 피드 (F-01 예측 탭) ──────────────────────────────
   서버 PredictionFeedItemResponse 와 짝이다(ANT-PRED-05).

   채널 예측 목록에 **작성자와 등록 시각**을 더한 모양이다. 채널 화면은 "이 사람의
   예측" 이라 작성자가 머리글에 한 번 나오면 되지만, 피드는 여러 채널이 섞이므로
   줄마다 누구 것인지 적어야 한다.

   게이팅은 채널 목록과 같다 — 종목·기간은 전체 공개, 판정 전이면 방향·목표가만
   작성자·구독자에게. 잠긴 건도 목록에서 빼지 않는다(빼면 피드가 실제보다 한가해
   보이고 구독 유인이 사라진다). 근거 본문은 여기 없다 — 상세(C-03)에서만 본다.

   scope 어휘는 리포트 피드와 같은 것을 쓴다(REPORT_SCOPES) — 한 화면의 두 탭이
   다른 말을 쓰면 안 된다. */
export type PredictionFeedItem = {
  id: number
  author: { userId: number; nickname: string }
  /** 종목이 지워졌으면 null */
  stockCode: string | null
  stockName: string | null
  /** 잠긴 건(판정 전 + 비구독)은 null */
  direction: Direction | null
  targetPrice: number | null
  horizon: number
  status: PredictionStatus
  /** 만기까지 남은 일수. 판정이 끝났거나 기준가 확정 전이면 null */
  dday: number | null
  errorRate: number | null
  /** 기준가 확정 전에는 null — 만기일 = 기준일 + horizon 이라서다 */
  settleDate: string | null
  createdAt: string
  locked: boolean
}

/* 조회는 useCursorList 가 경로로 직접 한다 — GET /predictions/feed?scope=&cursor=&size= */

/* ── 예측 상세 (C-03) ─────────────────────────────────────
   서버 PredictionDetailResponse 와 짝이다(ANT-PRED-05). 필드 이름·중첩을 그대로 옮겼다.

   잠금이 두 겹이다(§5 게이팅 표). **서버가 403 을 쓰지 않는다** — 누구에게나 200 이고
   가리는 것은 필드다.

     ① locked      판정 전 + 작성자·구독자 아님 → direction · targetPrice 가 null,
                   evidences 가 빈 배열
     ② noteLocked  구독자 아님 → note 가 null. **판정 후에도 풀리지 않는다**
                   (payload 에 noteHash 만 들어가므로 애초에 공개 대상이 아니다)

   무엇을 잠그지 않는가 — proof 의 커밋 해시 · 앵커 · 서명 주소는 **언제나 온다**.
   이 셋이 "조작하지 않았다" 를 스스로 증명하는 재료이고, 해시로는 내용을 알 수 없다.

   ⚠ **등록 직후에는 비어 있는 값이 많다.** baseDate · basePrice · settleDate 는
   배치 B2 가 다음 영업일에 채운다(엔티티 주석: "등록 시 NULL, 배치가 채운다").
   settleDate = baseDate + horizon 이라 기준일이 없으면 만기일도 없다 — 화면이
   이 셋을 비어 있는 채로 그릴 수 있어야 한다. */
export type PredictionDetail = {
  id: number
  author: { userId: number; nickname: string }
  /** 종목이 지워졌으면 null. 그 예측도 사라지지 않는다 — 커밋은 남아야 한다 */
  stockCode: string | null
  stockName: string | null
  status: PredictionStatus
  /** 3~90 캘린더일 */
  horizon: number
  createdAt: string

  /* ── 잠금 ① ─────────────────────────────────────────── */
  locked: boolean
  direction: Direction | null
  targetPrice: number | null

  /* ── 가격·날짜 ───────────────────────────────────────── */
  /** 등록 때 본 직전 종가 박제. 표시용이며 기준가가 아니다 */
  refClose: number | null
  /** 기준가 확정 영업일. 배치 B2 가 채운다 */
  baseDate: string | null
  /** 기준일 종가 = 판정 출발점. 등록 직후(BASE)는 비어 있다 */
  basePrice: number | null
  /** 만기일 = baseDate + horizon. 기준일이 없으면 이것도 없다 */
  settleDate: string | null
  /** 만기 종가. 판정 완료만 채워진다 */
  settlePrice: number | null
  /** 목표가 대비 오차 %. 판정 완료만 */
  errorRate: number | null
  /** 만기까지 남은 일수. 판정이 끝났거나 기준가 확정 전이면 null */
  dday: number | null
  /** 진행률을 그리는 재료. 실전 시세는 전일 종가뿐이라 그 거래일을 함께 받는다(§7 legal) */
  lastClose: { close: number; tradeDate: string } | null

  /* ── 잠금 ② ─────────────────────────────────────────── */
  noteLocked: boolean
  note: string | null
  /** C-01 에서 인계받아 커밋에 묶인 근거. 등록 후 바뀌지 않는다. locked 면 빈 배열 */
  evidences: { pointId: number; kind: 'POSITIVE' | 'RISK' | 'CHECK'; body: string }[]

  /* ── 항상 공개 ────────────────────────────────────────
     커밋이 아직 없으면(등록 직후 극히 짧은 창) 해시·주소가 null 이고 anchorStatus 는 WAITING. */
  proof: {
    signerAddress: string | null
    commitHash: string | null
    anchorStatus: ProofAnchorStatus
    anchorBlockNumber: number | null
    revealed: boolean
  }
}

export function getPredictionDetail(id: string) {
  return api.get<PredictionDetail>(`/predictions/${id}`)
}

/**
 * 목표까지 얼마나 왔는가. 기준가에서 목표가까지를 100 으로 본다.
 *
 * 서버가 주는 값이 아니라 기준가·목표가·전일 종가로 여기서 계산한다 — 명세가
 * 진행률을 화면 구성으로 못 박았고(§4 C-03), 셋이 다 응답에 있으므로 없는 값을
 * 만들어내는 것이 아니다. 셋 중 하나라도 비면 그릴 수 없어 null 이다.
 *
 * 100 을 넘길 수 있다 — 목표를 지나쳤다는 뜻이라 자르지 않는다. 음수도 그렇다.
 */
export function targetProgress(d: PredictionDetail): number | null {
  const { basePrice, targetPrice, lastClose } = d
  if (basePrice === null || targetPrice === null || lastClose === null) return null
  const span = targetPrice - basePrice
  /* 기준가와 목표가가 같으면 나눌 수 없다. 등록 자체가 막히지만 계약상 가능하다 */
  if (span === 0) return null
  return Math.round(((lastClose.close - basePrice) / span) * 1000) / 10
}

/* ── 이 종목의 예측 분포 (호가창) ────────────────────────
   설계 변경 2026-09-10. 종목 상세는 **누가 걸었는지를 보여주지 않는다** —
   목표가를 구간으로 잘라 각 구간에 몇 명이 걸었는지만 낸다. 개인은 작성자
   채널에서만 본다.

   그래서 이 응답에는 예측 id·작성자·목표가 원본이 없다. 있으면 구간을 되짚어
   개인을 복원할 수 있고, 그건 이 화면을 만든 이유를 무너뜨린다.

   구간은 **전일 종가 대비 %** 로 자른다. 주식 호가창과 달리 상·하한이 없어
   절대가로는 자를 수 없고, 비율이면 1,000원 종목과 100만원 종목이 같은 개수의
   구간으로 나뉜다. 기준가는 전일 종가다 — 실전 시세는 그것뿐이다(§7 legal). */

/** 한 구간. 아래에서 위로(싼 가격 → 비싼 가격) 온다 */
export type PredictionBucket = {
  /** 전일 종가 대비 하한 %(포함). 맨 아래 구간은 null — "그 이하 전부" */
  fromPct: number | null
  /** 상한 %(미포함). 맨 위 구간은 null — "그 이상 전부" */
  toPct: number | null
  /** 구간 경계의 실제 가격. 화면이 다시 계산하지 않게 서버가 함께 준다 */
  fromPrice: number | null
  toPrice: number | null
  /** 이 구간에 걸린 예측 수 */
  count: number
}

export type PredictionDistribution = {
  stockCode: string
  /** 구간을 나눈 기준. 전일 종가다 — "현재가"가 아니다(§7 legal) */
  basePrice: number
  /** 그 종가의 날짜 */
  asOf: string
  /** 구간 폭 % */
  stepPct: number
  /** 아래(싼 쪽)에서 위(비싼 쪽) 순서 */
  buckets: PredictionBucket[]
  /** 구간 합계. 화면이 더해서 쓰지 않는다 — 서버가 센 값과 어긋날 수 있다 */
  total: number
}

/* ── 오늘 판정된 예측 (B-03 헤더) ─────────────────────────
   판정 배치 B2 가 13:30 에 돌면서 그날 만기가 온 예측을 HIT/MISS 로 확정한다.
   그 결과만 종목 머리에 띄운다 — "이 종목에 걸었던 사람들이 오늘 어떻게 됐나".

   판정 완료는 전체 공개다(§5 개정). 작성자·적중 여부·오차율에 잠금이 없어
   locked 같은 칸이 필요 없다. */
export type SettledPrediction = {
  /** 서버가 숫자로 준다(Prediction.id) — 링크에 그대로 끼워 쓴다 */
  id: number
  /** 프로필 사진 주소. 안 올렸으면 null — 화면이 DEFAULT_AVATAR 를 쓴다 */
  author: { userId: number; nickname: string; avatarUrl: string | null }
  /** 판정 후라 방향·목표가에 잠금이 없다(§5 개정) */
  direction: Direction
  targetPrice: number
  /** 판정된 것만 오므로 BASE·OPEN 은 없다 */
  status: 'HIT' | 'MISS'
  /** 목표가 대비 오차 %. 판정 완료라 항상 채워진다 */
  errorRate: number
}

/**
 * 오늘 판정된 이 종목의 예측.
 *
 * 없으면 빈 목록이다 — 화면은 그때 아무것도 그리지 않는다. 장이 쉬는 날이나
 * 배치 전에는 늘 비어 있으므로, 빈 상태를 "없습니다" 로 알릴 일이 아니다.
 */
export function getSettledToday(code: string) {
  return api.get<{ items: SettledPrediction[] }>(`/stocks/${code}/predictions/settled-today`)
}

/**
 * 판정 대기 중인 예측의 분포.
 *
 * 판정이 끝난 예측은 세지 않는다 — 호가창은 지금 걸려 있는 물량을 보는 것이고,
 * 끝난 예측은 기록이다. 둘을 한 그림에 더하면 어느 쪽도 읽을 수 없다.
 *
 * 기준가는 응답의 basePrice 를 쓴다. 목업 때는 화면이 전일 종가를 인자로 건넸는데
 * (목업이 난수로 지어낸 기준가가 화면 머리와 어긋나서), 서버는 그 값을 받지 않는다.
 */
export function getPredictionDistribution(code: string) {
  return api.get<PredictionDistribution>(`/stocks/${code}/predictions/distribution`)
}

/**
 * 멱등 키 scope. 서버가 `Idempotency-Key` 를 **필수**로 받는다(없으면 400
 * IDEMPOTENCY_KEY_REQUIRED) — 예측에는 삭제 API 가 없고 슬롯도 되돌아오지 않아,
 * 타임아웃 뒤 한 번의 재시도가 예측 둘을 만들면 복구할 길이 없다.
 *
 * 봉인 재료가 바뀌면 다른 요청이므로 키도 갈라야 한다. noteSalt 가 등록마다 새로
 * 뽑히므로 그것만으로도 갈리지만, 무엇에 서명했는지가 키에 드러나도록 함께 적는다.
 */
export const createScope = (d: PredictionDraft) =>
  `prediction:${d.stockCode}:${d.noteSalt}`

/* 201 과 202 를 응답 본문 모양으로 가른다. api.post 는 상태 코드를 돌려주지 않는데,
   그것 하나 때문에 공용 client 를 고치면 다른 화면까지 영향이 간다. 두 응답은
   키가 겹치지 않아(operationId ↔ id) 모양만으로 확실히 갈린다.

   지금 서버는 202 를 내지 않는다 — 슬롯 초과는 409 다(위 PredictionQueued 주석). */
export function createPrediction(draft: PredictionDraft, signature: string) {
  const scope = createScope(draft)
  return api.post<PredictionCreated | PredictionQueued>(
    '/predictions', { ...draft, signature }, { idempotencyScope: scope },
  )
    .then((body): CreateResult => ('operationId' in body
      ? { kind: 'queued', data: body }
      : { kind: 'created', data: body }))
    /* 확정된 뒤에야 키를 버린다. 실패한 재시도는 같은 키로 가야 서버가 중복으로
       세지 않는다 — 성공 응답을 받은 다음 요청만 새 키를 받는다. */
    .then((r) => { releaseIdempotencyKey(scope); return r })
}

/* ── 커밋 봉인 (ANT-PRED-02) ──────────────────────────────
   규격 원본은 서버 CommitPayload · CommitHashes 이고, 기준값은
   backend/src/test/resources/commit/commit-cross-fixture.json 다섯 건이다.
   여기 조립 규칙은 그 다섯 건을 그대로 통과한다 — 고치면 다시 대조해야 한다.

   한 글자만 어긋나도 두 가지가 난다. 서명 문자열이 다르면 복원 주소가 달라져
   이유가 로그에 남지 않는 401(SIGNER_MISMATCH), 커밋 문자열이 다르면 D-03 ①단계가
   "불일치" 를 그린다 — 사용자에게는 "네 예측이 조작됐다" 로 읽힌다.

   ── 미리보기가 진짜 commitHash 를 보여 준다 ────────────────
   09-09 결정으로 커밋 salt 가 없어졌다. 유일한 난수인 noteSalt 를 클라이언트가
   만들고, noteHash 가 그 난수성을 물려받아 salt 역할을 겸한다. 그래서 등록 전
   미리보기가 원장에 남을 값과 **같은** commitHash 를 계산할 수 있다 — 서버 salt 가
   있을 때는 불가능했다. */

/** 커밋·서명 문자열에 함께 들어가는 다섯 값. 등록 폼(C-01)과 검산(D-03)이 나눠 쓴다. */
export type CommitFields = {
  stockCode: string
  direction: Direction
  targetPrice: number
  /** 서버가 short 로 받는다. proof 응답은 Horizon 어휘 밖 값도 낼 수 있어 number 다 */
  horizon: number
  /** keccak256(note ‖ noteSalt) — `0x` + 소문자 64 hex */
  noteHash: string
}

/**
 * 이 시스템의 **유일한 난수**. 32바이트 CSPRNG → 소문자 64 hex, `0x` 없음.
 *
 * 커밋 salt 가 없어져(09-09) 예측 전체의 비밀성이 이 값 하나에 걸린다. 약하면
 * 리빌 전에 남이 근거를 맞춰 볼 수 있다. crypto.getRandomValues 는 OS 엔트로피를
 * 쓰는 브라우저 내장 CSPRNG 이고, crypto.subtle 과 달리 비보안 컨텍스트(http)에서도
 * 돈다. Math.random() 은 암호용이 아니고 crypto.randomUUID() 는 122비트에 형식도
 * 달라 둘 다 쓰면 안 된다.
 *
 * 예측 한 건마다 새로 뽑는다 — 재사용하면 같은 근거를 쓴 두 예측의 noteHash 가 같아져
 * "같은 말을 두 번 했다" 가 원장에 그대로 드러난다.
 */
export function newNoteSalt() {
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * 근거 해시 `keccak256(utf8(note) ‖ utf8(noteSalt))`. 서버 CommitHashes.noteHash 와 짝이다.
 *
 * 본문은 받은 그대로 해시한다 — trim 도 `\r\n` → `\n` 정규화도 하지 않는다. 서버도
 * 받은 바이트를 그대로 저장하고 해시하므로, 여기서 손대면 두 값이 갈린다.
 * noteSalt 는 hex **문자열** 로 이어 붙인다(바이트로 디코드하지 않는다). 구분자 없음.
 */
export function noteHash(note: string, noteSalt: string) {
  return keccak256Utf8(note + noteSalt)
}

/**
 * 커밋·서명 문자열에 쓰는 목표가 표기. **항상 소수 둘째 자리** 다.
 *
 * 서버 CommitPayload.formatPrice 가 DB numeric(14,2) 모양으로 조립한다. 82000 과
 * 82000.00 이 갈리면 서명 복원 주소가 달라져 원인이 남지 않는 401 이 난다.
 */
export const formatTargetPrice = (price: number) => price.toFixed(2)

/**
 * 셋째 자리 이하가 있는가. 서버는 **반올림하지 않고** 400 으로 거절하므로
 * (CommitPayload.formatPrice, RoundingMode.UNNECESSARY) 등록 전에 폼에서 막는다.
 * 조용히 반올림하면 서명한 값과 서버가 해시한 값이 달라진다.
 */
export const targetPriceInScale = (price: number) => Number(price.toFixed(2)) === price

/**
 * 커밋 문자열의 필드 줄 다섯 개. 서버 CommitPayload.lines() 와 같다.
 *
 * 커밋과 서명이 이 다섯 줄을 **함께** 쓴다. 두 곳에서 따로 목표가를 문자열로 만들면
 * 서명은 통과하고 해시는 안 맞는 사고가 나므로, 포맷 규칙을 여기 한 곳에 둔다.
 */
function commitLines(f: CommitFields) {
  return [
    `stockCode=${f.stockCode}`,
    `direction=${f.direction}`,
    `targetPrice=${formatTargetPrice(f.targetPrice)}`,
    `horizon=${f.horizon}`,
    `noteHash=${f.noteHash}`,
  ]
}

/**
 * commitHash 의 대상 문자열. 서버 CommitPayload.canonical() 과 바이트가 같아야 한다.
 * 구분은 `\n` 하나, 마지막 줄 뒤 개행 없음, UTF-8.
 *
 * 없는 줄 셋과 그 이유
 * - `salt=`             커밋 salt 자체가 없다(09-09). noteHash 가 그 역할을 겸한다.
 * - `evidencePointIds=` 리서치 포인트 내부 id 는 외부 검증자에게 의미가 없고, 봉인 뒤
 *                       바꾸는 API 도 없다. 등록 **본문** 에는 그대로 들어간다.
 * - `createdAt=`        서버 시각이라 검증자가 재현할 수 없다. "이때 있었다" 는 앵커가
 *                       증명한다.
 *
 * 첫 줄이 서명 문자열(`antenna:prediction:v1`)과 다른 것도 규격이다 — 서명이 커밋으로,
 * 커밋이 서명으로 오인·재사용되지 않게 한다.
 */
export function commitPayload(f: CommitFields) {
  return ['antenna:commit:v1', ...commitLines(f)].join('\n')
}

/** 커밋 문자열의 해시. 등록 전 미리보기와 D-03 ①단계가 같은 함수를 쓴다. */
export const commitHash = (payload: string) => keccak256Utf8(payload)

/**
 * personal_sign 대상 문자열 (결정 B3 — 예측 내용에 서명한다).
 *
 * 지갑 연동용 문자열(wallet.ts signingPayload)에 서명하면 예측 내용이 서명에 들어가지
 * 않아 서버가 SIGNER_MISMATCH 로 거절한다. 여기서는 커밋과 같은 다섯 줄에 머리·꼬리만
 * 다르게 붙인다.
 *
 * chainId 는 POST /wallet/nonce 응답값을 그대로 쓴다 — 지갑에서 eth_chainId 로 읽으면
 * 서버 조립본과 어긋난다(wallet.ts WalletNonce 주석과 같은 이유).
 */
export function predictionSigningPayload(f: CommitFields, nonce: WalletNonce) {
  return [
    'antenna:prediction:v1',
    ...commitLines(f),
    `chainId=${nonce.chainId}`,
    `nonce=${nonce.nonce}`,
  ].join('\n')
}
