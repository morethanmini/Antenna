/* 알림함 — H-02 [ANT-FE-NOTIFICATION]
   설계서 §3 H · §4 · §8

   **화면은 페이지가 아니라 상단바 팝업이다**(2026-09-16 결정). 설계서 §3 H-02 는
   `/notifications` 라우트를 적어 두었지만, 알림은 보러 가는 것이 아니라 하던 일
   옆에서 확인하고 닫는 것이라 팝업 하나로 끝낸다. 그래서 라우트를 두지 않는다 —
   설계서 문서는 팀 공용이라 손대지 않았고, 이 주석이 그 차이를 적어 두는 자리다.

   ── 서버가 아직 없다 ────────────────────────────────────
   backend 에 notification 도메인 자체가 없다(2026-09-16 확인). 명세서에만 계약이
   적혀 있어, 그 계약대로 타입과 함수를 먼저 만들고 목업으로 화면을 완성한다.

   지우는 절차 — api/channels.ts · api/insight.ts 와 같다
     1. 아래 MOCK 을 false 로 바꾼다
     2. mock/notifications.ts 와 이 파일의 `if (MOCK)` 세 줄, import 한 줄을 지운다
   화면 코드는 손대지 않는다.

   ── STOMP 는 아직 붙이지 않는다 ─────────────────────────
   설계 제약에 `/user/queue/notifications` 로 실시간 푸시가 있지만, 보낼 서버가
   없어 붙일 데가 없다. 붙일 자리는 markRead·loadNotifications 옆이 아니라 셸
   (Layout)의 생애주기다 — 소켓이 주는 레코드는 REST 와 같은 모양이므로, 소켓
   메시지로는 **뱃지만 +1** 하고 목록은 이 REST 로 다시 읽는다. 소켓만 믿고 목록을
   만들지 않는다(연결이 끊긴 동안의 알림이 비기 때문이다). */
import { api } from './client'
import * as mock from './mock/notifications'

/** 서버가 생기면 false 로 바꾼다 */
const MOCK = true

/**
 * 알림 종류 여섯.
 *
 * 값은 서버 enum 을 그대로 쓴다 — 화면에서 만든 이름을 두면 서버가 새 종류를
 * 보냈을 때 조용히 빠진다(아래 LINK 의 기본값 참고).
 */
export const NOTIFICATION_TYPES = [
  'VERDICT', 'SEASON', 'SUBSCRIBE', 'FEE_CHANGED', 'RENEW_FAILED', 'REPORT_PUBLISHED',
] as const
export type NotificationType = (typeof NOTIFICATION_TYPES)[number]

export type Notification = {
  id: number
  type: NotificationType
  title: string
  /** 한 줄 설명. 목록에서 두 줄까지만 보인다 */
  body: string
  /**
   * 눌렀을 때 갈 곳. **서버가 준 값을 그대로 쓴다** — 화면이 종류로 주소를
   * 조립하면 대상 id 를 알 수 없다(예측 id · 시즌 id 는 알림마다 다르다).
   * 서버가 비워 보내면 누를 수 없는 알림으로 그린다.
   */
  linkPath: string | null
  /** 읽었나 */
  read: boolean
  /** 생성 시각. KST ISO-8601(명세 §1 규칙 7) */
  createdAt: string
}

export type NotificationList = {
  items: Notification[]
  /** 안 읽은 건수. 상단바 뱃지가 이 값을 쓴다 */
  unread: number
}

/**
 * 종류별로 어디로 가는가 — 설계 제약의 딥링크 표.
 *
 * 실제 주소는 `linkPath` 가 들고 있고 이 표는 **아이콘과 말투**를 고르는 데만
 * 쓴다. 주소를 여기서 만들지 않는 이유는 위 linkPath 주석과 같다.
 */
export const TYPE_LABEL: Record<NotificationType, string> = {
  VERDICT: '판정',
  SEASON: '모의투자',
  SUBSCRIBE: '구독',
  FEE_CHANGED: '구독료',
  RENEW_FAILED: '결제 실패',
  REPORT_PUBLISHED: '리포트',
}

/** 알림 목록. 페이지가 아니라 팝업이라 커서 페이징을 두지 않는다 —
 *  한 번에 보이는 만큼만 받고, 더 필요해지면 그때 커서를 붙인다. */
export function getNotifications() {
  if (MOCK) return mock.list()
  return api.get<NotificationList>('/notifications')
}

/** 한 건 읽음 */
export function markRead(id: number) {
  if (MOCK) return mock.markRead(id)
  return api.patch<void>(`/notifications/${id}/read`)
}

/** 전부 읽음 */
export function markAllRead() {
  if (MOCK) return mock.markAllRead()
  return api.patch<void>('/notifications/read-all')
}
