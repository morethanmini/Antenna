/* 리포트 검산 (D-04) — GET /reports/{reportId}/proof
   설계서 docs/화면설계서.md §3 D · 지라 S15P21A507-248

   서버는 재료만 준다. 같은지 다른지는 이 파일의 함수와 화면이 계산한다 —
   서버가 "검증됨" 이라고 말하면 증명이 아니다(D-03 이 세운 원칙). */
import { api } from './client'
import { keccak256Utf8 } from '../chain/keccak'
import type { ProofAnchor } from './proof'

/**
 * 봉인 문자열의 머리글. 서버 `ReportSeal.HEADER` 와 같아야 한다.
 *
 * 서명 payload 의 첫 줄(`antenna:report-publish:v1`)과 다른 값이다 — 서명이 봉인으로,
 * 봉인이 서명으로 오인·재사용되지 않게 갈라 둔 것이다.
 */
export const REPORT_SEAL_HEADER = 'antenna:report:v1'

export type ReportProof = {
  reportId: number
  title: string
  visibility: boolean
  authorId: number
  publishedAt: string
  /** keccak256(본문 ‖ salt). 잠금과 무관하게 늘 온다 */
  bodyHash: string
  /** 머클 리프. 늘 온다 */
  reportHash: string
  /**
   * 공개 리포트는 누구에게나, 구독자 전용은 작성자·구독자에게만.
   *
   * 없으면 ①의 **본문 대조만** 잠긴다 — `reportHash` 대조와 ②·③은 그대로 돈다.
   * 바깥 해시는 `bodyHash` 만 있으면 다시 조립되기 때문이다.
   */
  salt?: string
  /** 서명 이전에 발행된 리포트는 셋 다 없다. 실패가 아니라 그 기능 이전 글이다 */
  signature?: string
  signerAddress?: string
  signNonce?: string
  chainId?: number
  /** 아직 배치에 안 묶였으면 없다. anchorStatus 가 WAITING 이다 */
  anchor?: ProofAnchor
  anchorStatus: 'WAITING' | 'PENDING' | 'CONFIRMED' | 'FAILED'
}

export function fetchReportProof(reportId: string) {
  return api.get<ReportProof>(`/reports/${reportId}/proof`)
}

/**
 * 봉인 문자열을 다시 조립한다. 서버 `ReportSeal.canonical` 과 같아야 한다.
 *
 * **본문이 없어도 된다** — `bodyHash` 로만 들어가기 때문이다. 그래서 비구독자도
 * 구독자 전용 리포트가 수정되지 않았음을 검증할 수 있다(명세 v0.59 가 약속한 그것).
 */
export function sealPreimage(p: Pick<ReportProof, 'authorId' | 'title' | 'visibility' | 'bodyHash'>) {
  return [
    REPORT_SEAL_HEADER,
    `authorId=${p.authorId}`,
    `title=${p.title}`,
    `visibility=${p.visibility}`,
    `bodyHash=${p.bodyHash}`,
  ].join('\n')
}

/** ①-1 안쪽 해시 — keccak256(본문 ‖ salt). salt 를 못 받았으면 부를 수 없다. */
export function recomputeBodyHash(body: string, salt: string) {
  return keccak256Utf8(body + salt)
}

/** ①-2 바깥 해시 — 리프. */
export function recomputeReportHash(preimage: string) {
  return keccak256Utf8(preimage)
}

/**
 * ③ 서명 payload 를 다시 조립한다. 서버 `ReportCreateRequest.signingPayload` 와 같아야 한다.
 *
 * 본문이 아니라 **salt 없는** `keccak256(body)` 에 서명했다는 것이 봉인과 다른 점이다 —
 * salt 는 서버가 만들어서 서명 시점에 없었다. 그래서 이 단계는 본문이 있어야 한다.
 */
export async function signingPreimage(
  p: Pick<ReportProof, 'title' | 'visibility' | 'signNonce' | 'chainId'>,
  body: string,
) {
  return [
    'antenna:report-publish:v1',
    `title=${p.title}`,
    `visibility=${p.visibility}`,
    `bodyHash=${await keccak256Utf8(body)}`,
    `chainId=${p.chainId}`,
    `nonce=${p.signNonce}`,
  ].join('\n')
}

/* 서명자 복원은 chain/keccak.ts 한 곳에 있다 — 예측 검산(D-03)도 같은 함수를 쓴다.
   여기서 다시 내보내는 것은 이 파일만 읽는 화면이 import 를 두 군데서 끌어오지 않게 하려는 것이다. */
export { recoverSigner } from '../chain/keccak' 
