/* ⋯ 메뉴 → 신고(M-06). 채널 프로필(E-02) · 리포트 상세(F-02) · 리포트 댓글 세 곳이 같이 쓴다.

   ── 왜 컴포넌트로 뺐나 ─────────────────────────────────
   처음에는 채널 프로필에만 있어서 화면 안에 적었다. 리포트와 댓글에도 붙으면서 같은
   "버튼 + 곁창 + 닫기 규칙 + 모달" 이 세 벌이 됐고, 댓글은 목록 줄마다 열리고 닫혀야 해서
   상태를 줄 단위로 따로 가져야 한다. 한 벌로 두면 각 자리는 대상만 넘기면 된다.

   ── 신고 여부를 기억하지 않는다 ─────────────────────────
   이미 신고했어도 항목은 늘 "신고" 다. 중복은 신고하기를 누를 때 서버가 409 로 막고
   모달이 "이미 신고한 …입니다" 로 알린다(AbuseReportModal). 신고 여부는 서버만 정확히 안다 —
   관리자가 처리하면 다시 신고할 수 있게 풀린다.

   ── 자기 것에는 그리지 않는다 ───────────────────────────
   그 판단은 부르는 쪽이 한다(작성자 id 를 아는 것은 그쪽이다). 자기 신고는 서버가 400 으로
   막지만, 누를 수 있게 두면 그 400 을 보려고 누르는 사람이 생긴다. */
import { useCallback, useRef, useState } from 'react'
import type { AbuseTargetType } from '../../api/abuse'
import { useDismiss } from '../useDismiss'
import AbuseReportModal from './AbuseReportModal'
import '../../styles/screens/abuse-report.css'

type Props = {
  targetType: AbuseTargetType
  targetId: number
  /** 모달 본문 맨 위 한 줄 — 잘못 누른 사람이 여기서 알아챈다 */
  targetLabel: string
  /** ⋯ 버튼의 스크린 리더 이름. "채널 메뉴" · "리포트 메뉴" · "댓글 메뉴" */
  menuLabel: string
  /** 자리 잡기(여백·정렬)는 부르는 쪽 레이아웃 몫이라 바깥 상자에 클래스를 얹게 한다 */
  className?: string
  /** 댓글 줄처럼 좁은 자리에서 버튼을 한 단계 작게 */
  small?: boolean
}

export default function AbuseMenu({
  targetType, targetId, targetLabel, menuLabel, className, small = false,
}: Props) {
  const [open, setOpen] = useState(false)
  const [reporting, setReporting] = useState(false)
  const panelRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)

  const close = useCallback((returnFocus?: boolean) => {
    setOpen(false)
    if (returnFocus) triggerRef.current?.focus()
  }, [])

  useDismiss({ open, panel: panelRef, trigger: triggerRef, onClose: close })

  return (
    <div className={`ab-menu-wrap${className ? ` ${className}` : ''}`}>
      <button
        type="button" className={`ab-menu-btn${small ? ' is-small' : ''}`} ref={triggerRef}
        aria-haspopup="menu" aria-expanded={open} aria-label={menuLabel}
        onClick={() => setOpen((v) => !v)}
      >
        <svg width={small ? 15 : 18} height={small ? 15 : 18} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" />
        </svg>
      </button>

      {open && (
        <div className="ab-menu" role="menu" ref={panelRef}>
          <button
            type="button" className="ab-menu-item" role="menuitem"
            onClick={() => { setOpen(false); setReporting(true) }}
          >
            신고
          </button>
        </div>
      )}

      {reporting && (
        <AbuseReportModal
          targetType={targetType}
          targetId={targetId}
          targetLabel={targetLabel}
          onClose={() => setReporting(false)}
        />
      )}
    </div>
  )
}
