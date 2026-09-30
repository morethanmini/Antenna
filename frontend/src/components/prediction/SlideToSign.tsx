/* 밀어서 등록 — C-01 마지막 발판.

   왜 그냥 버튼이 아닌가
   예측은 등록하면 수정도 삭제도 안 된다. 한 번의 오클릭으로
   되돌릴 수 없는 일이 벌어지지 않게, 손잡이를 끝까지 밀어야 넘어가게 한다.

   **키보드로도 되어야 한다.** 끌기만 되는 컨트롤은 마우스가 없으면 못 쓴다.
   손잡이는 진짜 <button> 이라 Enter·Space 로도 넘어간다 — 그쪽에는 밀기라는 관문이
   없지만, 되돌릴 수 없다는 고지가 바로 위에 함께 떠 있다.

   비활성일 때는 끌리지도 눌리지도 않는다. 무엇이 모자란지는 이 컨트롤이 아니라
   위쪽 안내가 말한다 — 발판에 오류 문구까지 얹으면 읽을 것이 두 곳으로 갈린다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import '../../styles/screens/slide-to-sign.css'

type Props = {
  /** 끝까지 밀었을 때. 키보드로 눌렀을 때도 같은 것을 부른다 */
  onConfirm: () => void
  /** 필수 항목이 덜 찼다 */
  disabled?: boolean
  /** 이미 서명 흐름에 들어갔다. 손잡이 대신 진행 문구를 보여 준다 */
  busy?: boolean
  label?: string
  busyLabel?: string
}

/** 이만큼 밀어야 넘어간다. **끌던 중이 아니라 손을 뗀 자리를 본다**(2026-09-22
    정정) — 끌던 중에 이 문턱을 넘기자마자 확정했더니, 맨 끝까지 가지 않고
    근처에서 멈춰도(심지어 계속 끄는 중이어도) 서명으로 넘어가 버렸다. 1 로
    두면 손을 뗀 지점이 트랙 폭 1px 오차로도 어긋나 거의 안 넘어가므로, 0.97 로
    "거의 끝" 을 표시하되 판정은 항상 손을 뗀 순간에만 한다. */
const THRESHOLD = 0.97

export default function SlideToSign({
  onConfirm, disabled = false, busy = false,
  label = '밀어서 등록하기', busyLabel = '서명중입니다…',
}: Props) {
  const trackRef = useRef<HTMLDivElement>(null)
  const knobRef = useRef<HTMLButtonElement>(null)
  /** 0~1. 손잡이가 얼마나 갔나 */
  const [at, setAt] = useState(0)
  /** at 의 최신값을 손을 뗀 그 순간 그대로 읽으려는 참조다 — pointerup 핸들러가
      클로저로 쥔 상태값에 의존하면, 리렌더가 그 사이 못 따라왔을 때 한 틱 전
      값을 볼 수 있다(끌기처럼 이벤트가 빠르게 겹치는 곳에서는 실제로 발생했다). */
  const atRef = useRef(0)
  const dragging = useRef(false)
  /** 끌고 있는 동안만 참. 참일 때는 전환을 끈다 — 손가락을 그대로 따라가야 한다 */
  const [held, setHeld] = useState(false)
  /** 손잡이 왼쪽 끝에서 얼마나 안쪽을 잡았나(px). 이걸 빼야 잡은 지점이 손가락
      밑에 그대로 머문다. 없으면 누르는 순간 손잡이가 커서 가운데로 튄다. */
  const grabDx = useRef(0)
  /** 다 민 뒤 제자리로 돌려놓는 타이머 */
  const homeTimer = useRef<number | undefined>(undefined)
  /** 이번 끌기에서 이미 넘겼나 — 한 번의 끌기로 두 번 부르지 않게. 끌기마다 풀린다 */
  const fired = useRef(false)
  /** 방금 끌어서 넘겼나 — 그 직후 따라오는 합성 click 하나만 막는다.
      이 값을 안 두고 fired 로 막으면, 서명이 실패해 돌아온 뒤 키보드로 다시
      누를 때도 막힌다(fired 는 다음 끌기 전까지 서 있으므로). */
  const afterDrag = useRef(false)

  const locked = disabled || busy

  /**
   * 손잡이가 실제로 움직이는 거리. **CSS 와 같은 수여야 한다.**
   *
   * 손잡이는 translateX(at * (100cqw - 100%)) 로 움직인다. cqw 는 컨테이너의
   * **콘텐츠 상자** 기준이라 트랙의 padding 이 빠진 폭이다. 그런데 여기서
   * getBoundingClientRect().width 는 padding 을 포함한 테두리 상자라, 그대로
   * 쓰면 둘이 어긋난다 — 실측 408 - 64 = 344 인데 CSS 는 336 만 민다.
   * 8px 차이만큼 손잡이가 손가락보다 뒤처졌다(코치 QA 2026-09-21).
   */
  const geom = () => {
    const track = trackRef.current, knob = knobRef.current
    if (!track || !knob) return null
    const t = track.getBoundingClientRect()
    const cs = getComputedStyle(track)
    const padL = parseFloat(cs.paddingLeft) || 0
    const padR = parseFloat(cs.paddingRight) || 0
    const w = knob.getBoundingClientRect().width
    return { left: t.left + padL, span: Math.max(1, t.width - padL - padR - w) }
  }

  /* 끌던 중에는 위치만 따라간다 — 확정은 하지 않는다(아래 endDrag 가 한다) */
  const move = useCallback((clientX: number) => {
    const g = geom()
    if (!g) return
    /* 잡은 지점을 손가락 밑에 붙들어 둔다 */
    const next = Math.min(1, Math.max(0, (clientX - g.left - grabDx.current) / g.span))
    atRef.current = next
    setAt(next)
  }, [])

  /** 손을 뗀 자리가 문턱을 넘었을 때만 확정한다. endDrag 가 부른다 */
  const confirm = useCallback(() => {
    fired.current = true
    afterDrag.current = true
    setAt(1)
    onConfirm()
    /* 붙은 것을 보여 준 뒤 제자리로 돌린다.

       바로 busy 로 넘어가면 발판 자리를 진행 문구가 덮으므로 이 타이머는 보이지
       않는다. 문제는 **넘어가지 않는 갈래**다 — 지갑이 없을 때 submit 은 연동
       창만 띄우고 돌아온다(PredictTab). 그때 손잡이가 끝에 붙은 채 남으면,
       창을 닫은 사람은 이미 등록된 것처럼 읽는다. */
    window.clearTimeout(homeTimer.current)
    homeTimer.current = window.setTimeout(() => setAt(0), 380)
  }, [onConfirm])

  useEffect(() => () => window.clearTimeout(homeTimer.current), [])

  const onPointerDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (locked) return
    dragging.current = true
    fired.current = false
    atRef.current = 0
    grabDx.current = e.clientX - e.currentTarget.getBoundingClientRect().left
    window.clearTimeout(homeTimer.current)
    setHeld(true)
    e.currentTarget.setPointerCapture(e.pointerId)
  }
  const onPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (!dragging.current || locked) return
    move(e.clientX)
  }
  const endDrag = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (!dragging.current) return
    dragging.current = false
    setHeld(false)
    try { e.currentTarget.releasePointerCapture(e.pointerId) } catch { /* 이미 놓였다 */ }
    /* 손을 뗀 그 자리로 판정한다 — atRef 가 move() 가 마지막으로 적어 둔 값이다.
       문턱을 넘겨 손을 뗐을 때만 확정하고, 그 못 미쳐 뗐으면(끝까지 안 밀었으면)
       되돌아온다. held 가 풀린 뒤라 CSS 전환을 타고 미끄러져 돌아간다 —
       예전에는 그 자리에서 사라지듯 튀었다. */
    if (atRef.current >= THRESHOLD && !fired.current) confirm()
    else if (!fired.current) setAt(0)
  }

  if (busy) {
    return (
      <div className="sts sts-busy" role="status">
        <span className="sts-spin" aria-hidden="true" />
        {busyLabel}
      </div>
    )
  }

  return (
    <div
      className={`sts${disabled ? ' is-off' : ''}${held ? ' is-held' : ''}`}
      ref={trackRef}
      /* 채워진 만큼 배경을 물들인다. 손잡이만 움직이면 얼마나 갔는지 잘 안 보인다 */
      style={{ ['--sts-at' as string]: String(at) }}
    >
      <span className="sts-label">{label}</span>
      <button
        type="button"
        ref={knobRef}
        className="sts-knob"
        style={{ transform: `translateX(calc(${at} * (100cqw - 100%)))` }}
        disabled={disabled}
        aria-label={`${label} — 오른쪽 끝까지 밀거나 Enter 를 누르세요`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onClick={(e) => {
          /* 끌어서 넘긴 직후에 click 이 한 번 더 온다 — 그 하나만 삼킨다. */
          if (afterDrag.current) { afterDrag.current = false; e.preventDefault(); return }
          /* **그냥 누른 클릭은 통과시키지 않는다**(2026-09-22, 되돌릴 수 없는
             동작이 한 번의 오클릭으로 넘어가던 문제). 버튼이라 마우스로 그냥
             누르기만 해도 이 click 이 온다 — 끝까지 밀지 않았는데 넘어가면
             파일 머리말의 "한 번의 오클릭으로 되돌릴 수 없는 일이 벌어지지
             않게" 라는 설계 목적이 깨진다.
             **키보드(Enter·Space)만 통과시킨다.** 그 click 은 detail 이 0 이다
             — 마우스·터치 클릭은 1 이상이라 여기서 가른다(MDN, UIEvent.detail). */
          if (e.detail === 0 && !locked) onConfirm()
        }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M5 6l6 6-6 6M13 6l6 6-6 6" />
        </svg>
      </button>
    </div>
  )
}
