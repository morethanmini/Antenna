/* 알림 팝업 — H-02 [ANT-FE-NOTIFICATION]

   **페이지가 아니라 팝업이다.** 알림은 보러 가는 것이 아니라 하던 일 옆에서
   확인하고 닫는 것이다. 페이지로 만들면 보던 화면을 떠났다가 뒤로가기로 돌아와야
   하고, 그 사이 스크롤 위치와 폼에 적던 것이 날아간다.

   그래서 "전체 보기" 도 두지 않는다(2026-09-16 결정). 링크를 하나 두면 결국
   페이지를 만들어야 하고, 같은 목록을 두 벌 관리하게 된다.

   ── 닫는 길을 셋 둔다 ───────────────────────────────────
   Escape · 바깥 누르기 · 종 다시 누르기. 팝업은 실수로 열기 쉬워서, 닫는 법을
   못 찾으면 화면이 잠긴 것처럼 느껴진다.

   ── 초점을 가두지 않는다 ────────────────────────────────
   ConfirmDialog 와 같은 판단이다. 이건 길을 막는 창이 아니라 곁창이라, Tab 이
   새어 나가면 그대로 페이지를 계속 쓰면 된다. 대신 열 때 목록으로 초점을 옮겨
   키보드만 쓰는 사람도 바로 읽을 수 있게 한다. */
import { useCallback, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { TYPE_LABEL } from '../api/notifications'
import type { Notification } from '../api/notifications'
import '../styles/notification.css'

type Props = {
  items: Notification[]
  loading: boolean
  error: boolean
  /**
   * 닫는다. `returnFocus` 가 참이면 부르는 쪽이 초점을 종으로 돌려놓는다 —
   * **Escape 로 닫을 때만** 참이다. 바깥을 눌러 닫은 사람은 이미 다른 곳을
   * 보고 있고, 알림을 눌러 옮겨 갈 때는 새 화면이 초점을 가져가야 한다.
   */
  onClose: (returnFocus?: boolean) => void
  onRead: (id: number) => void
  onReadAll: () => void
  onRetry: () => void
}

/** "3분 전" · "어제" 처럼. 알림은 **언제 왔는지가 순서**라 시각보다 간격이 쓸모 있다 */
function ago(iso: string) {
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return ''
  const min = Math.floor((Date.now() - t) / 60_000)
  if (min < 1) return '방금'
  if (min < 60) return `${min}분 전`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}시간 전`
  const day = Math.floor(hour / 24)
  if (day === 1) return '어제'
  if (day < 7) return `${day}일 전`
  return new Date(t).toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul', month: 'numeric', day: 'numeric',
  })
}

export default function NotificationPopover({
  items, loading, error, onClose, onRead, onReadAll, onRetry,
}: Props) {
  const navigate = useNavigate()
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(true) }
    /* **mousedown 이지 click 이 아니다.** click 으로 잡으면 목록 안의 링크를 누를 때
       먼저 닫히면서 그 클릭이 사라진다. 바깥인지 아닌지는 누른 순간에 정해진다. */
    const onDown = (e: MouseEvent) => {
      const el = e.target as Node
      if (boxRef.current?.contains(el)) return
      /* 종 자체를 누른 것은 여기서 닫지 않는다 — 종의 onClick 이 토글하므로
         둘이 같이 돌면 닫고 곧바로 다시 연다 */
      if ((el as HTMLElement).closest?.('[data-bell]')) return
      onClose()
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
  }, [onClose])

  useEffect(() => { boxRef.current?.focus() }, [])

  /* 누르면 읽음으로 바꾸고 그 자리로 간다. 팝업은 닫는다 — 목적지로 가면서
     알림 목록이 화면에 남아 있을 이유가 없다. */
  const open = useCallback((n: Notification) => {
    if (!n.read) onRead(n.id)
    if (n.linkPath) { onClose(); navigate(n.linkPath) }
  }, [navigate, onClose, onRead])

  const unread = items.filter((n) => !n.read).length

  return (
    <div
      className="nt-pop"
      ref={boxRef}
      tabIndex={-1}
      role="dialog"
      aria-label="알림"
    >
      <header className="nt-head">
        <h2>알림</h2>
        {/* 읽을 것이 없으면 "전체 읽음" 은 누를 데가 없는 버튼이다 */}
        {unread > 0 && (
          <button type="button" className="nt-readall" onClick={onReadAll}>
            전체 읽음
          </button>
        )}
      </header>

      {loading && (
        <ul className="nt-list" aria-hidden="true">
          {[0, 1, 2].map((i) => <li key={i} className="nt-item is-skel" />)}
        </ul>
      )}

      {error && (
        <p className="nt-state">
          알림을 불러오지 못했어요.{' '}
          <button type="button" className="nt-retry" onClick={onRetry}>다시 시도</button>
        </p>
      )}

      {!loading && !error && items.length === 0 && (
        <p className="nt-state is-quiet">아직 온 알림이 없어요.</p>
      )}

      {!loading && !error && items.length > 0 && (
        <ul className="nt-list">
          {items.map((n) => (
            <li key={n.id}>
              <button
                type="button"
                className={`nt-item${n.read ? '' : ' is-unread'}${n.linkPath ? '' : ' is-flat'}`}
                onClick={() => open(n)}
                /* 갈 곳이 없으면 읽음 처리만 한다. 눌러도 아무 데도 안 가는 것이
                   기본값이라, 커서까지 손가락으로 바꾸지는 않는다(.is-flat) */
              >
                <span className="nt-type">{TYPE_LABEL[n.type]}</span>
                <b className="nt-title">{n.title}</b>
                <span className="nt-body">{n.body}</span>
                <span className="nt-when num">{ago(n.createdAt)}</span>
                {/* 안 읽은 표시는 점 하나다. 목록에서 색만으로 나누면
                    색을 못 보는 사람에게는 같은 줄이 된다 */}
                {!n.read && <i className="nt-dot" aria-label="읽지 않음" />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
