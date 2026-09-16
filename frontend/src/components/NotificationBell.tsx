/* 상단바 종 — H-02 [ANT-FE-NOTIFICATION]

   종과 팝업을 한 컴포넌트가 들고 있다. 여는 상태와 목록을 셸(Layout)이 들면
   셸이 알림의 사정을 알게 되는데, 셸은 화면 뼈대만 아는 편이 낫다.

   ── 목록은 **열 때** 읽는다 ─────────────────────────────
   상단바는 모든 화면에 있어서, 켤 때마다 읽으면 화면을 옮길 때마다 한 번씩
   부르게 된다. 뱃지 숫자도 목록과 같은 응답에서 온다 — 뱃지만 따로 읽는 API 를
   만들면 목록과 숫자가 어긋나는 순간이 생긴다.

   그래서 **처음 한 번은 뱃지를 위해 읽는다.** 안 읽은 알림이 있는지는 팝업을 열기
   전에도 보여야 하기 때문이다.

   ── STOMP 는 아직 없다 ─────────────────────────────────
   설계 제약의 `/user/queue/notifications` 가 붙을 자리가 여기다. 소켓이 오면
   **뱃지만 +1** 하고 목록은 다시 읽는다(api/notifications.ts 머리말). 서버에
   notification 도메인이 아직 없어 지금은 자리만 적어 둔다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getNotifications, markAllRead, markRead,
} from '../api/notifications'
import type { Notification } from '../api/notifications'
import NotificationPopover from './NotificationPopover'
import '../styles/notification.css'

/** 두 자리를 넘으면 폭이 들쭉날쭉해진다. 정확한 수보다 "많다" 가 중요한 자리다 */
const BADGE_MAX = 99

export default function NotificationBell() {
  /* Escape 로 닫았을 때 초점을 돌려놓을 자리. 안 돌려놓으면 초점이 body 로
     떨어져, 키보드만 쓰는 사람은 Tab 을 처음부터 다시 눌러야 한다. */
  const bellRef = useRef<HTMLButtonElement>(null)
  const [open, setOpen] = useState(false)
  const [items, setItems] = useState<Notification[]>([])
  const [unread, setUnread] = useState(0)
  /* 첫 읽기가 이미 돌고 있으므로 true 로 시작한다. **effect 안에서 동기로
     setState 하지 않으려는 것**이다 — 그러면 렌더가 한 번 더 돈다.
     useAsync 가 같은 문제를 같은 방법으로 푼다(api/useAsync.ts 머리말):
     loading 을 세우는 것은 사용자 조작에서만 한다. */
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  const load = useCallback(() => getNotifications()
    .then((res) => { setItems(res.items); setUnread(res.unread); setError(false) })
    .catch(() => setError(true))
    .finally(() => setLoading(false)), [])

  /* 처음 한 번. 뱃지는 팝업을 열기 전에도 보여야 한다 */
  useEffect(() => { void load() }, [load])

  /* 여기서부터는 사용자 조작이라 loading 을 세워도 된다 */
  const reload = useCallback(() => { setLoading(true); void load() }, [load])

  const toggle = useCallback(() => {
    setOpen((was) => {
      /* 열 때마다 다시 읽는다 — 닫아 둔 사이에 온 것이 있다 */
      if (!was) reload()
      return !was
    })
  }, [reload])

  /* 읽음은 **화면을 먼저 바꾸고** 서버에 알린다. 알림을 누르면 곧바로 그 자리로
     가는데, 응답을 기다리면 떠나는 순간까지 안 읽은 채로 보인다.
     실패하면 다음에 열 때 서버 값으로 돌아온다 — 되돌리는 코드를 두지 않는 이유다. */
  const read = useCallback((id: number) => {
    setItems((list) => list.map((n) => (n.id === id ? { ...n, read: true } : n)))
    setUnread((n) => Math.max(0, n - 1))
    void markRead(id).catch(() => {})
  }, [])

  const close = useCallback((returnFocus = false) => {
    setOpen(false)
    if (returnFocus) bellRef.current?.focus()
  }, [])

  const readAll = useCallback(() => {
    setItems((list) => list.map((n) => ({ ...n, read: true })))
    setUnread(0)
    void markAllRead().catch(() => {})
  }, [])

  return (
    <div className="nt-wrap">
      <button
        type="button"
        ref={bellRef}
        className="iconbtn"
        data-bell
        aria-label={unread > 0 ? `알림 ${unread}건` : '알림'}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={toggle}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M18 8a6 6 0 1 0-12 0c0 6-3 7-3 7h18s-3-1-3-7" />
          <path d="M13.7 21a2 2 0 0 1-3.4 0" />
        </svg>
        {/* 숫자는 aria-label 이 이미 읽어 준다 — 여기서 또 읽으면 두 번 들린다 */}
        {unread > 0 && (
          <span className="nt-badge" aria-hidden="true">
            {unread > BADGE_MAX ? `${BADGE_MAX}+` : unread}
          </span>
        )}
      </button>

      {open && (
        <NotificationPopover
          items={items}
          loading={loading}
          error={error}
          onClose={close}
          onRead={read}
          onReadAll={readAll}
          onRetry={reload}
        />
      )}
    </div>
  )
}
