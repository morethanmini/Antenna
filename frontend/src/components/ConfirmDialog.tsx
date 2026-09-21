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

   ── body 로 포털한다 ────────────────────────────────────
   **상단바가 유리라서다.** .navbar 에 backdrop-filter 가 걸려 있는데(app.css),
   filter 계열이 붙은 상자는 그 안의 position:fixed 자손에게 컨테이닝 블록이
   된다 — 화면이 아니라 **그 상자**에 맞춰진다는 뜻이다. 알림 종처럼 바 안에서
   이 창을 띄우면 se-backdrop 의 inset:0 이 뷰포트가 아니라 72px 짜리 바를
   가리켜, 창이 바 안에 갇힌 채 잘려 보였다(코치 QA 2026-09-21).

   재현해 실측한 값 — 같은 마크업에서 filter 하나로 갈린다.
     backdrop-filter 있음  배경막 295 x 72
     backdrop-filter 없음  배경막 295 x 734   (뷰포트 310 x 734)

   호출부마다 "바 밖에서 띄워라" 를 지키게 하는 대신 창이 스스로 body 로
   나간다. 어디서 부르든 화면 한가운데에 뜬다.

   ── 포커스 가둠(focus trap)은 없다 ───────────────────────
   SessionExpiredModal 과 같다. 버튼 둘뿐이라 Tab 이 새어 나가도 바로 돌아오고,
   Escape 로 언제나 닫힌다. */
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
