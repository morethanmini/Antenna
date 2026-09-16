/* 알림 목업. backend 에 notification 도메인이 아직 없다(2026-09-16 확인).

   지우는 절차 — api/channels.ts · api/insight.ts 와 같다
   1. api/notifications.ts 의 MOCK 을 false 로 바꾼다
   2. 이 파일과 notifications.ts 의 `if (MOCK)` 세 줄, import 한 줄을 지운다
   화면 코드는 손대지 않는다.

   값은 지어냈지만 형태는 지어내지 않았다 — 설계서 §3 H-02 의 딥링크 표를 그대로
   따른다(VERDICT→C-03 · SEASON→G-08 · SUBSCRIBE·FEE_CHANGED→E-02 ·
   RENEW_FAILED→E-03 · REPORT_PUBLISHED→F-02).

   **여섯 종류를 한 번씩 넣었다.** 화면에서 종류마다 다른 아이콘·말투를 그리므로
   하나라도 빠지면 그 갈래를 못 본 채로 만들게 된다. 읽음·안 읽음도 섞어 두었다 —
   둘이 같아 보이면 뱃지가 무슨 뜻인지 알 수 없다.
   linkPath 가 null 인 건도 하나 둔다. 누를 수 없는 알림을 그릴 줄 알아야 한다. */
import type { Notification, NotificationList } from '../notifications'

/** 목업이 도는 동안 읽음 상태를 들고 있는 자리. 새로고침하면 처음으로 돌아간다 */
let rows: Notification[] = [
  {
    id: 6, type: 'VERDICT', read: false,
    title: '삼성전자 예측이 판정됐어요',
    body: '목표가 248,500원 · 적중 — 오차 1.2%p',
    linkPath: '/predictions/16',
    createdAt: '2026-09-16T13:32:00+09:00',
  },
  {
    id: 5, type: 'REPORT_PUBLISHED', read: false,
    title: '구독 중인 채널에 새 리포트',
    body: '레드와이어사지마라했다 · 반도체 업황, 지금 어디쯤인가',
    linkPath: '/reports/12',
    createdAt: '2026-09-16T11:05:00+09:00',
  },
  {
    id: 4, type: 'RENEW_FAILED', read: false,
    title: '구독 갱신에 실패했어요',
    body: 'ANT 잔액이 모자랍니다. 지갑을 확인해 주세요',
    linkPath: '/me/subscriptions',
    createdAt: '2026-09-15T09:10:00+09:00',
  },
  {
    id: 3, type: 'SUBSCRIBE', read: true,
    title: '새 구독자가 생겼어요',
    body: '차트보는개미 님이 내 채널을 구독했습니다',
    linkPath: '/me/channel',
    createdAt: '2026-09-14T18:44:00+09:00',
  },
  {
    id: 2, type: 'SEASON', read: true,
    title: '모의투자 시즌이 끝났어요',
    body: '2026 8월 리플레이 · 최종 12위 · AI 복기가 준비됐습니다',
    linkPath: '/sim/8/result',
    createdAt: '2026-09-12T16:00:00+09:00',
  },
  {
    id: 1, type: 'FEE_CHANGED', read: true,
    title: '구독료가 바뀌었어요',
    body: '존버는승리한다 채널 · 다음 갱신부터 300 ANT',
    /* 누를 수 없는 알림. 서버가 대상 주소를 못 만드는 경우가 있다 */
    linkPath: null,
    createdAt: '2026-09-10T08:20:00+09:00',
  },
]

const delay = <T,>(value: T) => new Promise<T>((r) => setTimeout(() => r(value), 220))

export function list(): Promise<NotificationList> {
  return delay({
    items: rows.map((r) => ({ ...r })),
    unread: rows.filter((r) => !r.read).length,
  })
}

export function markRead(id: number): Promise<void> {
  rows = rows.map((r) => (r.id === id ? { ...r, read: true } : r))
  return delay(undefined)
}

export function markAllRead(): Promise<void> {
  rows = rows.map((r) => ({ ...r, read: true }))
  return delay(undefined)
}
