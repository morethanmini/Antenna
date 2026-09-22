/* E-03 내 구독. API 명세서 §채널·구독 · 설계서 §3 E · §4 E-03.

   GET    /subscriptions/me            내 구독 목록
   DELETE /subscriptions/{id}/renewal  자동 갱신 해지 (204)

   타입은 명세서가 준 스키마 그대로다 —
     GET  200 { items: [{ id, publisher, fee, status, expiresAt, renewEnabled }] }
           publisher = { userId, nickname, avatarUrl, interests, latestPredictionAt }  (S15P21A507-273)
           item 에 startedAt 추가 — 구독 시점
     DELETE 204
   서버는 ANT-TOKEN-05 에서 열었다(MySubscriptionController).

   ── 이 화면이 다루는 것이 "구독" 이 아니라 "갱신 예약" 이다 ──
   DELETE .../renewal 은 구독을 끊지 않는다. renew_enabled 를 false 로 만들 뿐이고
   만료일까지는 ACTIVE 그대로다. 라벨을 "구독 취소" 로 쓰면 사용자는 지금 접근이
   끊긴다고 읽는다 — 설계서 §4 E-03 이 못 박은 제약이다. */
import { api } from './client'

/**
 * 구독 상태. 서버 Subscription.Status 와 짝이다.
 *
 * PENDING 은 결제가 블록 확정을 기다리는 중이라 **아직 열람 권한이 없다.**
 * ACTIVE 와 같게 그리면 잠긴 예측을 보고 고장으로 읽는다(E-02 와 같은 판단).
 * EXPIRED 는 이용 기간이 끝난 것이다 — 있었다가 끝났다.
 * FAILED 는 결제가 못 나갔거나 체인에서 되돌아간 것이다 — 시작된 적이 없다. 토큰도
 * 빠져나가지 않았다. 2026-09-19 전에는 서버가 이 경우도 EXPIRED 로 내려 "만료됨" 으로
 * 보였다(QA 09-18: 구독한 적 없는데 만료).
 */
export const SUBSCRIPTION_STATUSES = ['PENDING', 'ACTIVE', 'EXPIRED', 'FAILED'] as const
export type SubscriptionStatus = (typeof SUBSCRIPTION_STATUSES)[number]

/** 구독 대상 채널. 목록에서 채널로 나가는 링크에 쓴다 */
export type Publisher = {
  userId: string
  nickname: string
  /** 프로필 사진 주소. 안 올렸으면 null — 화면이 DEFAULT_AVATAR 를 쓴다 (S15P21A507-273) */
  avatarUrl: string | null
  /** 관심 섹터. 담은 순서대로. 안 고른 사람은 빈 배열 (S15P21A507-273) */
  interests: string[]
  /**
   * 가장 최근 예측 시각(REAL 만). 한 건도 없으면 null.
   * 구독은 앞으로 올 예측을 사는 것이라, 마지막 활동이 갱신을 정하는 근거다.
   */
  latestPredictionAt: string | null
}

/**
 * 내 구독 한 줄. 명세 응답의 여섯 필드 그대로다.
 *
 * fee 는 **결제 시점에 박제된 값**이라 채널의 현재 구독료와 다를 수 있다(E-02 참고).
 * 그래서 이 화면은 채널의 현재 가격을 함께 보여주지 않는다 — 두 숫자를 나란히 두면
 * 어느 쪽이 내가 내는 값인지 흐려진다.
 */
export type Subscription = {
  id: number
  publisher: Publisher
  /** 정수 ANT 문자열. 결제 시점 박제값 */
  fee: string
  status: SubscriptionStatus
  /** 구독이 개시된 시각. PENDING 은 아직 개시 전이라 null 이다 */
  startedAt: string | null
  /** 개시 +30일. PENDING 은 아직 개시 전이라 null 이다 */
  expiresAt: string | null
  /** 만료일에 배치 B4 가 재결제할지. 해지하면 false */
  renewEnabled: boolean
  /** 결제 tx 해시. 확정 전(PENDING)은 null. 구독은 커밋 원장에 안 오르므로 온체인 흔적은 이것뿐이다 */
  txHash: string | null
}

export function fetchMySubscriptions(): Promise<{ items: Subscription[] }> {
  return api.get<{ items: Subscription[] }>('/subscriptions/me')
}

/** 자동 갱신 해지. 204 라 돌려줄 것이 없다. 되살리기는 resumeRenewal */
export function cancelRenewal(subscriptionId: number): Promise<void> {
  return api.delete<void>(`/subscriptions/${subscriptionId}/renewal`)
}

/**
 * 자동 갱신 다시 켜기 — 해지 번복(09-21 QA). 204. 만료 전 ACTIVE 구독만 되고,
 * 끝난 구독은 409 SUBSCRIPTION_NOT_RENEWABLE 이다(새로 구독해야 한다).
 */
export function resumeRenewal(subscriptionId: number): Promise<void> {
  return api.put<void>(`/subscriptions/${subscriptionId}/renewal`)
}

export const STATUS_LABEL: Record<SubscriptionStatus, string> = {
  PENDING: '결제 확인 중',
  ACTIVE: '구독 중',
  EXPIRED: '만료됨',
  FAILED: '결제 실패',
}
