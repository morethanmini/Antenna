/* 검산 화면의 공용 조각 — D-03(예측)과 D-04(리포트)가 같이 쓴다.

   두 화면이 같은 모양이어야 하는 이유는 "검산" 이 이 서비스가 파는 것이라서다.
   예측은 체크가 초록인데 리포트는 다른 색·다른 아이콘이면, 사용자가 둘을 다른
   보증으로 읽는다. 복사해 두면 한쪽만 고쳐지므로 한 곳에 둔다.

   Verify.tsx 에서 옮겨 온 것이고 동작은 그대로다(ANT-CHAIN-17). */
import type React from 'react'

/* ── 검산 결과 한 줄 ──────────────────────────────────────
   pass·fail 을 색으로만 나누지 않는다(설계서 §7 과 같은 규칙). 흑백으로 캡쳐해도,
   색각 이상이 있어도 읽혀야 한다. wait 는 "아직" 이지 실패가 아니라 중립색을 쓴다.

   manual 은 나머지와 성격이 다르다 — 기계가 판정을 못 해서가 아니라 하면 안 되는
   자리다(③). 우리가 원본을 대신 불러다 "맞습니다" 라고 하면 다시 우리를 믿는 것이 되므로
   대조는 사용자가 한다. 그래서 체크도 시계도 아닌 별도 표시를 쓴다. */
export type Verdict = 'pass' | 'fail' | 'wait' | 'locked' | 'manual'

const VERDICT_ICON: Record<Verdict, React.ReactNode> = {
  pass: <path d="m5 12.5 4.5 4.5L19 7.5" />,
  fail: <><path d="M6 6l12 12" /><path d="M18 6 6 18" /></>,
  wait: <><circle cx="12" cy="12" r="9" /><path d="M12 7.5V12l3 1.8" /></>,
  locked: <><rect x="4.5" y="10.5" width="15" height="10" rx="2" /><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5" /></>,
  // 바깥으로 나가는 화살표 — 확인할 곳이 이 화면 밖이라는 뜻
  manual: <><path d="M14 5h5v5" /><path d="M19 5l-8 8" /><path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" /></>,
}

const VERDICT_SR: Record<Verdict, string> = {
  pass: '통과', fail: '불일치', wait: '대기', locked: '잠김', manual: '직접 확인',
}

export function CheckLine({ verdict, label, children }: {
  verdict: Verdict
  label: string
  children?: React.ReactNode
}) {
  return (
    <li className={`vf-check is-${verdict}`}>
      <span className="vf-check-mark" aria-hidden="true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
          {VERDICT_ICON[verdict]}
        </svg>
      </span>
      <span className="sr-only">{`${VERDICT_SR[verdict]} — `}</span>
      <span className="vf-check-body">
        <b>{label}</b>
        {children && <span className="vf-check-detail">{children}</span>}
      </span>
    </li>
  )
}

export function Step({ no, title, formula, verdict, children }: {
  no: number
  title: string
  formula?: string
  verdict: Verdict
  children: React.ReactNode
}) {
  return (
    <section className={`vf-step is-${verdict}`}>
      <header className="vf-step-head">
        <span className="vf-step-no num" aria-hidden="true">{no}</span>
        <div className="vf-step-title">
          <h2>{title}</h2>
          {formula && <code className="vf-formula">{formula}</code>}
        </div>
      </header>
      <div className="vf-step-body">{children}</div>
    </section>
  )
}
