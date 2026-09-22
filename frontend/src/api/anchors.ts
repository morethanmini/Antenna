/* 온체인 검증 도메인. API 명세서 §온체인 검증 · 설계서 §3 D.

   GET /anchors?cursor=&size=  { items, nextCursor, hasNext }
   GET /commits?cursor=&size=  커밋 원장 목록 — D-01 [ANT-FE-LEDGER-LIST]
   GET /anchors/{id}           배치 상세 (리프 전량) — D-02 [ANT-FE-ANCHOR]
   GET /predictions/{id}/proof 4단계 검산            — D-03 [ANT-FE-VERIFY]

   백엔드 ANT-CHAIN-06 으로 셋 다 열려 있다. 목업이 아니다. */

import type { Horizon, PredictionStatus } from './predictions'

/** 배치가 체인에 올라간 단계. 서버 AnchorBatch.Status 와 짝이다. */
export const ANCHOR_STATUSES = ['PENDING', 'CONFIRMED', 'FAILED'] as const
export type AnchorStatus = (typeof ANCHOR_STATUSES)[number]

/**
 * 앵커 배치 한 줄. 서버 AnchorItemResponse 와 짝이다.
 *
 * txHash · blockNumber · confirmedAt 은 아직 체인에 안 올랐거나 실패하면 비어 있다
 * — PENDING 행에서 이 셋을 그리려 하면 안 된다.
 */
export type Anchor = {
  /** DB 배치 번호. v3(ANT-CHAIN-13)부터 체인 칸의 키는 merkleRoot 다 — 이 번호는 체인에 가지 않는다 */
  id: number
  /** 이 배치가 묶은 영업일 YYYY-MM-DD */
  businessDate: string
  /** 이 배치 커밋들의 머클 루트 */
  merkleRoot: string
  commitCount: number
  status: AnchorStatus
  txHash: string | null
  blockNumber: number | null
  confirmedAt: string | null
  /** 이 배치를 올린 장부 컨트랙트 주소 */
  contractAddress: string
  chainId: number
}

/** 리프 종류. 서버 AnchorLeaves.Kind 와 짝이다(ANT-CHAIN-14). */
export const LEAF_KINDS = ['PREDICTION', 'REPORT'] as const
export type LeafKind = (typeof LEAF_KINDS)[number]

/**
 * 배치에 담긴 리프 한 건. 서버 AnchorDetailResponse.Leaf 와 짝이다.
 *
 * predictionId 가 함께 오므로 목록에서 커밋 하나를 골라 D-03 검산으로 갈 수 있다
 * (ANT-CHAIN-09 에서 결정 F2 를 뒤집어 동봉하기로 바뀌었다).
 *
 * **리프가 두 종류다**(ANT-CHAIN-14). `kind` 로 갈리고, 반대편 필드는 전부 null 이다.
 *
 *   PREDICTION  predictionId · stockCode · stockName · horizon
 *   REPORT      reportId · title
 *
 * commitHash 이름을 그대로 둔 이유는 서버와 같다 — 이 값의 뜻이 "머클 트리에 들어간
 * 리프" 이고 두 종류가 같은 자리다. 이름을 바꾸면 같은 값이 세 군데서 세 이름이 된다.
 *
 * 리포트 줄에는 본문도 bodyHash 도 오지 않는다. 구독자 전용 리포트가 섞여 있고,
 * 원장은 "무엇이 언제 봉인됐나" 까지다 — 예측 줄에서 방향·목표가를 뺀 것과 같은 규칙이다.
 */
export type AnchorLeaf = {
  kind: LeafKind
  /** REPORT 면 null */
  predictionId: number | null
  /** PREDICTION 이면 null */
  reportId: number | null
  /** 리포트 제목. PREDICTION 이면 null */
  title: string | null
  commitHash: string
  /**
   * 무엇이 봉인됐는지 알려 주는 두 값(S15P21A507-237). 해시만으로는 읽히지 않는다.
   *
   * 둘 다 판정 전에도 공개다 — 서버 ChannelPredictionItemResponse 가 잠긴 건에도 종목과
   * horizon 을 그대로 내리고, 가리는 것은 방향·목표가뿐이다. 그 둘은 여기 오지 않는다.
   *
   * **종목이 지워진 예측이 있어 null 일 수 있다.** 그 커밋도 리프에서 빠지지 않는다 —
   * 전량이 있어야 머클루트가 재현된다.
   */
  stockCode: string | null
  stockName: string | null
  /** 예측 기간(3~90 영업일 — ANT-PRED-11 전의 예측은 캘린더일). REPORT 면 null */
  horizon: Horizon | null
}

/** 원장 한 줄의 공통부 — 무엇이 봉인됐든 해시와 배치 자리는 같다. */
type LedgerBase = {
  /** 머클 트리에 들어간 리프. 예측이면 커밋 해시, 리포트면 리포트 해시다 */
  commitHash: string
  /** 아직 배치에 안 묶였으면 셋 다 null — 봉인은 끝났고 다음 배치를 기다리는 중이다 */
  anchorId: number | null
  businessDate: string | null
  anchorStatus: AnchorStatus | null
}

/**
 * 커밋 원장의 한 줄. 서버 CommitItemResponse 와 짝이다 — GET /commits.
 *
 * 원장을 **배치가 아니라 봉인 한 건 단위**로 읽는다. 배치 줄에는 종목을 적을 수 없다 —
 * 한 배치에 여러 종목의 커밋이 함께 묶이기 때문이다.
 *
 * **줄이 두 종류다 (ANT-CHAIN-15).** kind 로 갈리는 판별 유니온이라, 화면이 kind 를
 * 좁히면 그쪽 필드는 null 검사 없이 쓸 수 있다. 배치 상세(AnchorLeaf)와 같은 어휘다.
 *
 * 방향·목표가·작성자는 두 종류 모두 오지 않는다. 판정 전이면 작성자·구독자만 봐야 하는
 * 값이라 원장이 내리면 E-02·C-03 의 게이팅을 우회한다(서버 DTO 주석). 리포트 줄의
 * 본문·bodyHash 도 같은 이유로 없다 — 제목과 공개범위까지다.
 */
export type LedgerCommit =
  | (LedgerBase & {
      kind: 'PREDICTION'
      predictionId: number
      /** 종목이 지워진 예측이 있어 null 일 수 있다. 그 커밋도 원장에서 빠지지 않는다 */
      stockCode: string | null
      stockName: string | null
      horizon: Horizon
      /** 결과 공개 예정일 YYYY-MM-DD (ANT-PRED-12). 만기일이 없으면 null */
      revealDate?: string | null
      status: PredictionStatus
      reportId: null
      title: null
      visibility: null
    })
  | (LedgerBase & {
      kind: 'REPORT'
      reportId: number
      title: string
      /** true 면 공개, false 면 구독자 전용. 본문이 아니라 범위만 온다 */
      visibility: boolean
      predictionId: null
      stockCode: null
      stockName: null
      horizon: null
      status: null
    })

/**
 * 앵커 배치 상세. 서버 AnchorDetailResponse 와 짝이다 — 목록에 없는 넷이 더 온다.
 *
 * commits 는 리프 순서(prediction id 오름차순) 그대로다. 이 순서로 트리를 다시
 * 접으면 merkleRoot 가 나와야 한다 — 순서가 곧 검증 재료라 화면이 정렬을 바꾸면 안 된다.
 */
export type AnchorDetail = Anchor & {
  /** 릴레이어가 트랜잭션을 보낸 시각. 확정 전에도 채워진다 */
  sentAt: string | null
  /** 전송 시도 횟수. 1보다 크면 재시도가 있었다는 뜻이다 */
  attempts: number
  /** FAILED 일 때 실패 사유. 그 외에는 null */
  lastError: string | null
  commits: AnchorLeaf[]
}

export const STATUS_LABEL: Record<AnchorStatus, string> = {
  PENDING: '대기',
  CONFIRMED: '확정',
  FAILED: '실패',
}

/**
 * 해시를 줄여 보여준다. 앞뒤를 남기는 이유는 두 해시를 눈으로 구별하기 위해서다
 * — 앞만 남기면 서로 다른 값이 같아 보인다.
 *
 * 줄여 놓더라도 전체 값을 복사할 수 있어야 한다(설계 제약). 그건 CopyHash 가 맡는다.
 */
export function shortHash(value: string, head = 10, tail = 8) {
  return value.length > head + tail + 1 ? `${value.slice(0, head)}…${value.slice(-tail)}` : value
}

/** 영업일. 배치가 어느 날 것인지만 알면 되므로 날짜까지만 적는다. */
export function formatBusinessDate(iso: string) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

/** 확정 시각. 체인에 언제 올랐는지가 중요해 분까지 적는다. */
export function formatConfirmedAt(iso: string) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

/**
 * 체인에 올라간 지 얼마나 됐나. D-01 원장의 배치 머리줄이 쓴다.
 *
 * 절대 시각(`formatConfirmedAt`)을 쓰면 방금 올라간 배치와 어제 것이 같아 보인다 —
 * 원장에서 중요한 것은 "언제인가" 가 아니라 "얼마나 됐나" 다. 하루가 넘으면
 * 간격이 더는 쓸모가 없어져 날짜로 돌아간다.
 *
 * 배치 상세(D-02)는 그대로 `formatConfirmedAt` 을 쓴다 — 거기서는 검증 재료라
 * 분 단위 절대값이어야 한다.
 */
export function formatAnchorAgo(iso: string) {
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return iso
  const min = Math.floor((Date.now() - t) / 60_000)
  if (min < 1) return '방금 확정'
  if (min < 60) return `${min}분 전 확정`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}시간 전 확정`
  return `${formatBusinessDate(iso)} 확정`
}
