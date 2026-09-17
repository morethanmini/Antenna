/* 네비바에 매달리는 곁창(검색줄 · 프로필 메뉴)의 닫기 규칙 한 벌.
   같은 규칙을 두 곳이 쓰므로 한 곳에 둔다 — 한쪽만 고쳐져 동작이 갈리는 것을 막는다.

   알림 팝업(NotificationPopover)이 먼저 같은 판단을 했고 그 이유가 거기 주석에 있다.
   그쪽은 목록·읽음 같은 제 일이 붙어 있어 합치지 않고, 규칙만 여기로 옮겨 적었다.

   닫는 길을 셋 둔다 — Escape · 바깥 누르기 · 여는 버튼 다시 누르기.
   곁창은 실수로 열기 쉬워서, 닫는 법을 못 찾으면 화면이 잠긴 것처럼 느껴진다. */
import { useEffect } from 'react'
import type { RefObject } from 'react'

type Options = {
  /** 열려 있나. 닫혀 있으면 아무것도 듣지 않는다 */
  open: boolean
  /** 곁창 상자 */
  panel: RefObject<HTMLElement | null>
  /** 여는 버튼. 이 버튼 위의 누름은 바깥으로 치지 않는다 */
  trigger: RefObject<HTMLElement | null>
  /**
   * 닫는다. `returnFocus` 가 참이면 부르는 쪽이 초점을 여는 버튼으로 돌려놓는다 —
   * **Escape 로 닫을 때만** 참이다. 바깥을 눌러 닫은 사람은 이미 다른 곳을 보고 있다.
   */
  onClose: (returnFocus?: boolean) => void
}

export function useDismiss({ open, panel, trigger, onClose }: Options) {
  useEffect(() => {
    if (!open) return

    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(true) }

    /* **mousedown 이지 click 이 아니다.** click 으로 잡으면 곁창 안의 링크를 누를 때
       먼저 닫히면서 그 클릭이 사라진다. 바깥인지 아닌지는 누른 순간에 정해진다. */
    const onDown = (e: MouseEvent) => {
      const el = e.target as Node
      if (panel.current?.contains(el)) return
      /* 여는 버튼 자체를 누른 것은 여기서 닫지 않는다 — 그 버튼의 onClick 이 토글하므로
         둘이 같이 돌면 닫고 곧바로 다시 연다 */
      if (trigger.current?.contains(el)) return
      onClose()
    }

    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
  }, [open, panel, trigger, onClose])
}
