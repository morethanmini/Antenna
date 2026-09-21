/* 되돌리기 어려운 행동을 한 번 더 묻는 창. [ANT-FE-SETTINGS-LOGOUT]
   설계서 §6 (모달 규칙)

   ── 브라우저 confirm() 을 쓰지 않는 이유 ─────────────────
   테마 밖에서 그려져 도메인 색·서체가 안 먹고, 모바일에서는 문구가 잘린다.
   무엇보다 버튼 라벨을 "확인/취소" 밖으로 못 바꾼다 — 무엇이 일어나는지를
   버튼에 적을 수 없으면 확인 단계의 값이 절반으로 준다.

   ── 겉모습은 M-08 세션 만료 창을 그대로 쓴다 ─────────────
   styles/session-expired.css 의 se-* 를 재사용한다. 같은 자리에 같은 크기로 뜨는
   같은 종류의 창이라 스타일시트를 한 벌 더 두면 둘이 조금씩 어긋나기 시작한다.
   (그래서 그 파일은 M-08 전용이 아니다 — 확인 창 공용이다.)

   ── 포커스 가둠(focus trap)은 없다 ───────────────────────
   SessionExpiredModal 과 같다. 버튼 둘뿐이라 Tab 이 새어 나가도 바로 돌아오고,
   Escape 로 언제나 닫힌다.

   ── body 로 portal 한다 ──────────────────────────────────
   부르는 자리 안에서 그리면 조상의 transform · filter · backdrop-filter 가
   position:fixed 의 기준 상자가 된다. 상단바(backdrop-filter)의 알림 "모두 삭제"
   확인창이 화면이 아니라 72px 띠에 맞춰져 위가 잘렸다(09-21 QA). */
import { useCallback, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import '../styles/session-expired.css'

type Props = {
  title: string
  /** 무슨 일이 일어나는지. 버튼 라벨만으로 부족한 만큼만 적는다 */
  description: string
  /** 확인 버튼 글자. "확인" 이 아니라 <b>할 일</b>을 적는다 — "로그아웃" 처럼 */
  confirmLabel: string
  /** 되돌릴 수 없는 행동이면 확인 버튼을 빨강으로 */
  tone?: 'default' | 'danger'
  onConfirm: () => void
  onCancel: () => void
}

export default function ConfirmDialog({
  title, description, confirmLabel, tone = 'default', onConfirm, onCancel,
}: Props) {
  const dialogRef = useRef<HTMLDivElement>(null)

  const cancel = useCallback(() => { onCancel() }, [onCancel])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') cancel() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [cancel])

  /* 초점을 창 안으로 들인다. 안 그러면 키보드 사용자는 창이 떴다는 사실조차 모른 채
     뒤 화면을 계속 조작한다(SessionExpiredModal 과 같은 이유). */
  useEffect(() => { dialogRef.current?.focus() }, [])

  return createPortal(
    <div className="se-backdrop" onClick={cancel} role="presentation">
      <div
        className="se-modal" role="alertdialog" aria-modal="true"
        aria-labelledby="confirm-title" aria-describedby="confirm-desc"
        tabIndex={-1} ref={dialogRef} onClick={(e) => e.stopPropagation()}
      >
        <div className="se-body">
          <h2 id="confirm-title">{title}</h2>
          <p id="confirm-desc" className="se-sub">{description}</p>
        </div>

        <div className="se-actions">
          <button type="button" className="se-btn" onClick={cancel}>취소</button>
          <button
            type="button" className={`se-btn solid${tone === 'danger' ? ' danger' : ''}`}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
