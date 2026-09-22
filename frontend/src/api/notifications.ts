/* 알림함 — H-02 [ANT-FE-NOTIFICATION]
   설계서 §3 H · §4 · §8 · API 명세서 §알림

   **화면은 페이지가 아니라 상단바 팝업이다**(2026-09-16 결정). 설계서 §3 H-02 는
   `/notifications` 라우트를 적어 두었지만, 알림은 보러 가는 것이 아니라 하던 일
   옆에서 확인하고 닫는 것이라 팝업 하나로 끝낸다. 그래서 라우트를 두지 않는다 —
   설계서 문서는 팀 공용이라 손대지 않았고, 이 주석이 그 차이를 적어 두는 자리다.

   ── 서버 (2026-09-16 연결) ─────────────────────────────
   GET    /notifications            { unreadCount, items[{ …, readAt }] } 최신 50건
   PATCH  /notifications/{id}/read  204
   PATCH  /notifications/read-all   204
   DELETE /notifications/{id}       204 · 남의 것은 404
   DELETE /notifications            204 · 내 것 전부

   응답의 readAt(시각 | null)을 화면용 read(불리언)로 바꿔 들고 있는다 — 화면이
   묻는 것은 "읽었나" 뿐이고, 언제 읽었는지는 어디에도 그리지 않는다.

   ── STOMP 는 아직 붙이지 않는다 ─────────────────────────
   설계 제약에 `/user/queue/notifications` 로 실시간 푸시가 있지만, 서버가 아직
   보내지 않는다. 붙일 자리는 markRead·loadNotifications 옆이 아니라 셸
   (Layout)의 생애주기다 — 소켓이 주는 레코드는 REST 와 같은 모양이므로, 소켓
   메시지로는 **뱃지만 +1** 하고 목록은 이 REST 로 다시 읽는다. 소켓만 믿고 목록을
   만들지 않는다(연결이 끊긴 동안의 알림이 비기 때문이다). */
import { api } from './client'

/**
 * 알림 종류. 값은 서버가 `notifications.type` 에 쓰는 문자열 그대로다 — 화면에서
 * 만든 이름을 두면 서버가 새 종류를 보냈을 때 조용히 빠진다(아래 TYPE_LABEL 참고).
 *
 * 서버가 보내는 것(2026-09-16): VERDICT · SUBSCRIBE · FEE_CHANGED · REPORT_PUBLISHED ·
 * PREDICTION_REGISTERED · REPORT_COMMENT · REPORT_LIKE. SEASON · RENEW_FAILED 는
 * 명세에는 있지만 아직 보내는 곳이 없다 — 라벨만 준비해 둔다.
 */
export const NOTIFICATION_TYPES = [
  'VERDICT', 'SEASON', 'SUBSCRIBE', 'FEE_CHANGED', 'RENEW_FAILED', 'REPORT_PUBLISHED',
  'PREDICTION_REGISTERED', 'REPORT_COMMENT', 'REPORT_LIKE',
  /* 관리자 처리 결과(2026-09-22): 광고 승인·반려는 신청자에게, 신고 처리는 신고자에게,
     가려짐은 그 글·댓글 작성자에게 */
  'AD_APPROVED', 'AD_REJECTED', 'REPORT_HANDLED', 'CONTENT_BLOCKED',
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
  /** 읽었나. 서버의 readAt 이 null 이 아니면 참 */
  read: boolean
  /** 생성 시각. KST ISO-8601(명세 §1 규칙 7) */
  createdAt: string
}

export type NotificationList = {
  items: Notification[]
  /** 안 읽은 건수. 상단바 뱃지가 이 값을 쓴다 */
  unread: number
}

/** 서버 응답 그대로. 화면에는 위 Notification 으로 바꿔 넘긴다 */
type ServerNotification = Omit<Notification, 'read'> & { readAt: string | null }
type ServerList = { unreadCount: number; items: ServerNotification[] }

/**
 * 종류별 이름표 — 설계 제약의 딥링크 표.
 *
 * 실제 주소는 `linkPath` 가 들고 있고 이 표는 **아이콘과 말투**를 고르는 데만
 * 쓴다. 주소를 여기서 만들지 않는 이유는 위 linkPath 주석과 같다.
 * 서버가 모르는 종류를 보내면 종류 문자열이 그대로 보인다(NotificationPopover).
 */
export const TYPE_LABEL: Record<NotificationType, string> = {
  VERDICT: '판정',
  SEASON: '모의투자',
  SUBSCRIBE: '구독',
  FEE_CHANGED: '구독료',
  RENEW_FAILED: '결제 실패',
  REPORT_PUBLISHED: '리포트',
  PREDICTION_REGISTERED: '예측',
  REPORT_COMMENT: '댓글',
  REPORT_LIKE: '좋아요',
  AD_APPROVED: '광고',
  AD_REJECTED: '광고',
  REPORT_HANDLED: '신고',
  CONTENT_BLOCKED: '신고',
}

/** 알림 목록. 페이지가 아니라 팝업이라 커서 페이징을 두지 않는다 —
 *  서버가 최신 50건만 주고, 더 필요해지면 그때 커서를 붙인다. */
export async function getNotifications(): Promise<NotificationList> {
  const res = await api.get<ServerList>('/notifications')
  return {
    unread: res.unreadCount,
    items: res.items.map(({ readAt, ...n }) => ({ ...n, read: readAt !== null })),
  }
}

/** 한 건 읽음 */
export function markRead(id: number) {
  return api.patch<void>(`/notifications/${id}/read`)
}

/** 전부 읽음 */
export function markAllRead() {
  return api.patch<void>('/notifications/read-all')
}

/** 한 건 삭제. 되돌릴 수 없지만 한 건이라 확인 없이 지운다 */
export function deleteNotification(id: number) {
  return api.delete<void>(`/notifications/${id}`)
}

/** 모두 삭제. 되돌릴 수 없어 화면이 확인 창을 먼저 띄운다(NotificationBell) */
export function deleteAllNotifications() {
  return api.delete<void>('/notifications')
}
