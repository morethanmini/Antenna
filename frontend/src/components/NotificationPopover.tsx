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
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { TYPE_LABEL } from '../api/notifications'
import type { Notification } from '../api/notifications'
import NotificationTypeIcon from './NotificationTypeIcon'
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
  /** 한 건 지운다. 되돌릴 수 없지만 확인창은 두지 않는다 — 알림 하나는 그만큼 가볍다 */
  onDelete: (id: number) => void
  /**
   * 모두 지운다. 확인 창은 **부르는 쪽(NotificationBell)** 이 띄운다 — 이 팝업은 바깥을
   * 누르면 닫히는데, 확인 창을 여기 안에 그리면 그 창을 누르는 순간 팝업째 사라진다.
   */
  onDeleteAll: () => void
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
  items, loading, error, onClose, onRead, onReadAll, onDelete, onDeleteAll, onRetry,
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

  /* ── 본문 한 줄 · [더보기] ────────────────────────────
     전에는 두 줄까지 보이고 그 뒤를 잘랐다(-webkit-line-clamp:2). 줄 수가
     들쭉날쭉해 목록이 층계처럼 보였고, 두 줄째가 잘린 알림은 어차피 다 못 읽는다.
     한 줄로 고정하고 넘치는 것만 펼칠 수 있게 한다.

     **넘치는지는 재서 안다.** 글자 수로 어림하면 팝업 폭(380px)·글꼴·언어가
     바뀔 때마다 어긋난다. 그린 뒤 scrollWidth 와 clientWidth 를 비교한다 —
     한 줄로 잘려 있을 때만 뜻이 있는 값이라, **펼쳐 둔 것은 재지 않고**
     이미 길다고 판정된 것을 그대로 들고 간다. */
  const listRef = useRef<HTMLUListElement>(null)
  const [longIds, setLongIds] = useState<number[]>([])
  const [openIds, setOpenIds] = useState<number[]>([])

  useLayoutEffect(() => {
    const el = listRef.current
    if (!el) return
    const next: number[] = []
    el.querySelectorAll<HTMLElement>('.nt-body').forEach((b) => {
      const id = Number(b.dataset.id)
      if (!Number.isFinite(id)) return
      /* 펼쳐 둔 줄은 잴 수 없다 — 판정을 그대로 잇는다 */
      if (openIds.includes(id)) { if (longIds.includes(id)) next.push(id); return }
      if (b.scrollWidth > b.clientWidth + 1) next.push(id)
    })
    /* 같은 값이면 새 배열을 넣지 않는다 — 넣으면 이 효과가 스스로를 다시 부른다 */
    setLongIds((prev) =>
      prev.length === next.length && prev.every((v, i) => v === next[i]) ? prev : next)
  }, [items, openIds, longIds])

  const toggleMore = useCallback((id: number) => {
    setOpenIds((prev) => (prev.includes(id) ? prev.filter((v) => v !== id) : [...prev, id]))
  }, [])

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
        <div className="nt-head-actions">
          {/* 읽을 것이 없으면 "전체 읽음" 은 누를 데가 없는 버튼이다. 지울 것이 없을 때의 "모두 삭제" 도 같다 */}
          {unread > 0 && (
            <button type="button" className="nt-readall" onClick={onReadAll}>
              전체 읽음
            </button>
          )}
          {items.length > 0 && (
            <button type="button" className="nt-clear" onClick={onDeleteAll}>
              모두 삭제
            </button>
          )}
        </div>
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
        <ul className="nt-list" ref={listRef}>
          {items.map((n) => (
            /* ── 줄 하나 (2026-09-22 구조 바꿈) ─────────────────────
               전에는 줄 전체가 <button> 이고 지우기만 그 바깥에 얹혀 있었다.
               [더보기] 를 본문 바로 뒤에 붙이려니 그 구조로는 안 됐다 — 버튼 안에
               버튼을 넣을 수 없어 본문 밖에 띄워 놓을 수밖에 없었고, 그러면 글자와
               떨어져 "…" 와 [더보기] 사이가 벌어진다.

               그래서 줄을 판(li)으로 두고, **누를 자리는 덮개 버튼(.nt-main)이**
               inset:0 으로 깔아 준다. 글자는 제자리에 흐르므로 본문과 [더보기] 를
               한 줄(flex)에 나란히 놓을 수 있다. 줄 아무 데나 눌러도 알림이 열리는
               것은 그대로다 — 덮개가 줄 전체를 덮기 때문이다.

               덮개를 **맨 앞**에 둔다. Tab 순서를 줄 → 더보기 → 삭제 로 맞추기
               위해서다. 그리는 순서는 DOM 순서와 무관하다 — 자리를 잡은(positioned)
               덮개가 흐르는 글자보다 늘 위에 그려지므로, 글자를 가리지 않으면서도
               클릭을 받는다. */
            <li
              key={n.id}
              className={`nt-row${n.read ? '' : ' is-unread'}${openIds.includes(n.id) ? ' is-open' : ''}`}
            >
              {/* 줄 전체를 덮는 누름판. 보이는 글자는 따로 흐르므로, 읽어 주는
                  기기를 위해 이름을 여기 모아 준다 — 종류 · 제목 · 본문 · 온 때,
                  그리고 안 읽었으면 그 말까지. 점을 뺀 뒤로 안 읽음을 알리는 것이
                  바탕색 하나뿐이라, 색을 못 보는 사람에게는 이 말이 유일한 단서다. */}
              <button
                type="button"
                className={`nt-main${n.linkPath ? '' : ' is-flat'}`}
                aria-label={[
                  TYPE_LABEL[n.type] ?? n.type,
                  n.title,
                  n.body,
                  ago(n.createdAt),
                  n.read ? '' : '읽지 않음',
                ].filter(Boolean).join(' · ')}
                onClick={() => open(n)}
                /* 갈 곳이 없으면 읽음 처리만 한다. 눌러도 아무 데도 안 가는 것이
                   기본값이라, 커서까지 손가락으로 바꾸지는 않는다(.is-flat) */
              />

              {/* **종류 글자를 뺐다**(2026-09-22, 디자인). 남은 것은 동그라미 안의
                  아이콘 하나다. 종류 이름은 마우스에 title 로, 읽어 주는 기기에는
                  위 덮개의 aria-label 로 남는다 — 화면에서 지운 것이지 없앤 것이
                  아니다. 표에 없는 새 종류도 그 두 곳에는 그대로 실린다. */}
              <span className="nt-type" title={TYPE_LABEL[n.type] ?? n.type} aria-hidden="true">
                <NotificationTypeIcon type={n.type} />
              </span>
              <b className="nt-title" aria-hidden="true">{n.title}</b>
              <span className="nt-when num" aria-hidden="true">{ago(n.createdAt)}</span>

              {/* 본문과 [더보기] 가 한 줄이다. 본문이 줄어들며 잘리고 그 **바로 뒤에**
                  버튼이 선다 — "…미적중 · 오차 3 더보기" 처럼 이어 읽힌다.
                  글자 쪽은 누름을 덮개로 흘려보내고(.nt-bodyline pointer-events:none)
                  버튼만 다시 받는다. 안 그러면 본문을 눌렀을 때 알림이 안 열린다. */}
              <span className="nt-bodyline" aria-hidden="true">
                <span className="nt-body" data-id={n.id}>{n.body}</span>
                {longIds.includes(n.id) && (
                  <button
                    type="button"
                    className="nt-more"
                    aria-hidden="false"
                    aria-expanded={openIds.includes(n.id)}
                    onClick={() => toggleMore(n.id)}
                  >
                    {openIds.includes(n.id) ? '접기' : '더보기'}
                  </button>
                )}
              </span>

              {/* 덮개 **위에** 선다(z-index). 여기를 눌렀는데 알림이 열리면 안 된다 */}
              <button
                type="button"
                className="nt-del"
                aria-label={`알림 삭제: ${n.title}`}
                title="삭제"
                onClick={() => onDelete(n.id)}
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
