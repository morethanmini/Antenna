/* 지갑·토큰 도메인. API 명세서 §지갑·토큰.

   GET /wallet          { linked, walletAddress }
   GET /wallet/balance  { balance(정수 ANT), symbol, syncedAt }
   GET /wallet/ledger   { items: [{ delta, reason, txHash, createdAt }], nextCursor, hasNext }
   POST /wallet/nonce   { scope } → { nonce, chainId }
   POST /wallet/link    { address, signature } → { walletAddress, bonus } */
import { api } from './client'

export type WalletStatus = {
  linked: boolean
  walletAddress: string | null
}

export type WalletBalance = {
  /** 정수 ANT 문자열. 서버가 금액 필드를 문자열로 통일해 내린다 — number 로 좁히지 않는다 */
  balance: string
  symbol: string
  /**
   * 온체인 대사 시각 — 토큰 인덱서가 체인 끝까지 읽은 마지막 시각. 잔액 옆에 반드시 함께 보인다.
   * 서버가 막 떴거나(첫 5초) 인덱서가 꺼져 있으면 null 이다(ANT-TOKEN-04) — 그때는 "대사 전" 으로 그린다.
   */
  syncedAt: string | null
  /**
   * 최근 30일에 들어온 ANT. 다른 금액 필드와 같이 정수 문자열이다.
   *
   * **총유입이지 순증감이 아니다.** 배팅했다가 환불받으면 이 값과 spent30d 가 둘 다 오른다.
   * 사유를 골라 빼지 않는 이유는 "번 것 − 쓴 것 = 30일간 잔액 변화" 가 성립해야
   * 숫자끼리 검산이 되기 때문이다(서버 WalletBalanceResponse 에 같은 설명이 있다).
   */
  earned30d: string
  /** 최근 30일에 나간 ANT. **양수로 온다** — "-" 를 붙일지는 화면이 정한다 */
  spent30d: string
}

/* 필터로 고를 수 있는 사유는 서버 TokenReason 어휘 그대로다. 임의 사유를 만들지 않는다.
   대회 배팅 세 가지(EVENT_*)가 빠져 있어 단타대회 내역이 전부 "기타" 로 보였다(QA 09-21). */
export const LEDGER_REASONS = [
  'SIGNUP_BONUS',
  'SLOT_OVER',
  'SUBSCRIBE',
  'SUBSCRIBE_INCOME',
  'AD_PAY',
  'SEASON_ENTRY',
  'SEASON_REWARD',
  'EVENT_STAKE',
  'EVENT_PAYOUT',
  'EVENT_REFUND',
] as const

export type LedgerReason = (typeof LEDGER_REASONS)[number]

export type LedgerEntry = {
  /** 부호 있는 증감. 양수 획득 · 음수 사용 */
  delta: string
  /**
   * 보통은 LedgerReason 중 하나다. 필터 없이 받으면 옛 테스트·수동 tx 의 원문(예: MIGRATION)도 온다 —
   * 서버가 잔액 합과 목록을 맞추려고 숨기지 않는다(ANT-TOKEN-04). 그래서 string 으로 받고 reasonLabel 로 그린다.
   */
  reason: string
  txHash: string | null
  createdAt: string
}

export const REASON_LABEL: Record<LedgerReason, string> = {
  SIGNUP_BONUS: '가입 보너스',
  SLOT_OVER: '예측 슬롯 초과',
  SUBSCRIBE: '구독 결제',
  SUBSCRIBE_INCOME: '구독 수익',
  AD_PAY: '광고 비용',
  // 대회 모드는 접었다(09-11 팀 결정). 서버 사유 이름(SEASON_*)대로 시즌으로 부른다
  SEASON_ENTRY: '시즌 참가비',
  SEASON_REWARD: '시즌 보상',
  EVENT_STAKE: '대회 배팅',
  EVENT_PAYOUT: '대회 배당',
  EVENT_REFUND: '대회 환불',
}

/** 원장 한 줄의 사유 라벨. 어휘 밖의 원문이면 "기타" — 빈 칸으로 두면 무엇이 오갔는지 읽을 수 없다. */
export function reasonLabel(reason: string) {
  return (REASON_LABEL as Record<string, string>)[reason] ?? '기타'
}

/**
 * ANT 금액 표기. 잔액 · 원장 증감 · 구독료 · 광고비가 모두 이 함수를 쓴다.
 *
 * ANT 는 decimals 0 이다(ANT-CHAIN-03). 서버 금액 필드는 정수 ANT 를 문자열로 싣고
 * "1000" 이 곧 1,000 ANT 라, 10^18 로 나누지 않고 천 단위로만 끊는다(S15P21A507-225).
 * BigInt 로 받는 것은 numeric(30,0) 이 Number 안전 범위를 넘을 수 있어서다.
 *
 * 음수(원장 차감)는 "-" 가 붙어 나온다. "+" 는 붙이지 않는다 — 획득을 드러낼지는 호출부가 정한다.
 */
export function formatToken(amount: string) {
  const raw = amount.trim()
  /* 예상 밖 형식이면 원본을 그대로 둔다. BigInt 에 바로 넣으면 '' 를 0 으로, '0x10' 을 16 으로
     읽어 틀린 금액을 조용히 그린다 */
  if (!/^-?\d+$/.test(raw)) return amount
  return BigInt(raw).toLocaleString('ko-KR')
}

/** KST ISO-8601 을 화면 문구로. 대사 시각·내역 시각에 함께 쓴다. */
export function formatDateTime(iso: string) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

/* ── 지갑 연동 (M-01) ─────────────────────────────────────────
   POST /wallet/nonce  { scope }            → { nonce, chainId }
   POST /wallet/link   { address, signature } → { walletAddress, bonus } */

/** 서명이 붙는 요청의 종류. payload 첫 줄의 용도 태그와 서버 nonce 칸을 동시에 정한다. */
export const SIGNATURE_SCOPES = {
  WALLET_LINK: 'wallet-link',
  PREDICTION: 'prediction',
  PREDICTION_BURN: 'prediction-burn',
  SUBSCRIBE: 'subscribe',
  /* 대회 묶음 등록(ANT-EVENT-05). 한 서명에 5~10 종목이 함께 들어간다 — 종목마다 받으면
     지갑 팝업이 다섯 번 뜨고, 그 사이 하나만 거절해도 "5종목 이상" 규칙이 깨진 채 절반만
     등록된다. 조립은 api/events.ts eventEntrySigningPayload. */
  EVENT_ENTRY: 'event-entry',
  /* 리포트 발행(ANT-CHAIN-16). 태그가 report 가 아닌 이유 — 봉인 문자열 첫 줄이 이미
     antenna:report:v1 이다. 같게 두면 서명 payload 와 봉인 문자열이 첫 줄까지 같아져
     한쪽을 다른 쪽으로 오인·재사용할 여지가 생긴다(예측의 prediction/commit 과 같은 판단). */
  REPORT_PUBLISH: 'report-publish',
  AD: 'ad',
  SEASON_JOIN: 'season-join',
} as const

export type SignatureScope = keyof typeof SIGNATURE_SCOPES

export type WalletNonce = {
  nonce: string
  /** 지갑에서 eth_chainId 로 읽지 말고 이 값을 쓴다 — 서버 조립본과 어긋나면 401 */
  chainId: number
}

/**
 * 서버 WalletLinkRequest.signingPayload() 와 같은 문자열을 만든다.
 *
 * 서버는 본문 필드로 payload 를 다시 조립해 대조한다. 줄바꿈·순서·소문자
 * 중 하나만 어긋나도 복원 주소가 달라져 원인이 로그에 남지 않는 401 이 난다.
 * 그래서 이 함수는 서버 구현을 그대로 옮긴 것이고, 손대려면 양쪽을 같이 고쳐야 한다.
 */
export function signingPayload(scope: SignatureScope, address: string, nonce: WalletNonce) {
  return [
    `antenna:${SIGNATURE_SCOPES[scope]}:v1`,
    `address=${address.toLowerCase()}`,
    `chainId=${nonce.chainId}`,
    `nonce=${nonce.nonce}`,
  ].join('\n')
}

/** 1회성 nonce 발급(Redis TTL 5분). 같은 scope 에 재발급하면 그 칸의 이전 값만 죽는다. */
/** 잔액 한 줄. 홈 위젯(B-01)과 지갑 화면(H-01)이 같은 것을 읽는다. */
export function fetchWalletBalance() {
  return api.get<WalletBalance>('/wallet/balance')
}

export function requestNonce(scope: SignatureScope) {
  return api.post<WalletNonce>('/wallet/nonce', { scope })
}

/**
 * 연동 응답. 주소 등록과 함께 서버가 가입 보너스 mint 를 접수한다(ANT-TOKEN-07).
 *
 * 연동 자체는 요청 안에서 끝나 상태 코드는 200 그대로다 — 비동기인 것은 보너스 전송뿐이다.
 */
export type WalletLinkResult = {
  walletAddress: string
  /**
   * 가입 보너스 접수분. 서버 체인이 꺼져 있으면 null 이다.
   *
   * `?` 로 둔 것은 서버 MR 보다 이쪽이 먼저 머지될 수 있어서다. 필드가 아직 안 오는 동안
   * 타입이 거짓말을 하지 않게 두면, 화면은 줄을 안 그리는 쪽으로 알아서 흐른다.
   *
   * **전송이 실패해도 이 모양은 그대로다.** 성공과 구분할 단서가 응답에 없으므로 이것을 근거로
   * "지급됐다"고 말하지 않는다. 실패분은 서버 재시도 배치가 10분 안팎에 다시 보낸다.
   *
   * amount 는 다른 금액 필드와 같이 정수 ANT 문자열이다 — formatToken() 으로 그린다.
   */
  bonus?: { operationId: string; amount: string } | null
}

/** 서명 검증 → 주소 등록. nonce 는 본문에 넣지 않는다 — 서버가 userId 로 꺼내 쓴다. */
export function linkWallet(address: string, signature: string) {
  return api.post<WalletLinkResult>('/wallet/link', { address, signature })
}

/** 0x1234…cdef 로 줄인다. 주소 전체는 좁은 자리에 넣으면 줄바꿈으로 깨진다. */
export function shortAddress(address: string) {
  return address.length > 14 ? `${address.slice(0, 6)}…${address.slice(-4)}` : address
}
