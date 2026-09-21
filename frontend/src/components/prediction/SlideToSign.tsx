/* 밀어서 등록 — C-01 마지막 발판.

   왜 그냥 버튼이 아닌가
   예측은 등록하면 수정도 삭제도 안 되고 슬롯도 돌아오지 않는다. 한 번의 오클릭으로
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

/** 이만큼 밀면 넘어간다. 1 로 두면 끝을 정확히 맞춰야 해서 잘 안 넘어간다 */
const THRESHOLD = 0.9
/** 넘긴 뒤 손잡이가 끝까지 미끄러져 닿는 시간. CSS transform 전환과 같다 */
const SNAP_MS = 180
/** 끝에 닿은 손잡이를 제자리로 돌려놓기까지. busy 화면이 안 덮는 경우(지갑 연동 창 등)에 쓰인다 */
const RETURN_MS = 700

export default function SlideToSign({
  onConfirm, disabled = false, busy = false,
  label = '밀어서 등록하기', busyLabel = '서명중입니다…',
}: Props) {
  const trackRef = useRef<HTMLDivElement>(null)
  const knobRef = useRef<HTMLButtonElement>(null)
  /** 0~1. 손잡이가 얼마나 갔나 */
  const [at, setAt] = useState(0)
  /** 끄는 중이면 누른 자리. 끄는 동안은 전환을 꺼 손잡이가 손가락에 붙어 다닌다 */
  const [grab, setGrab] = useState<number | null>(null)
  const timers = useRef<number[]>([])
  useEffect(() => () => timers.current.forEach((id) => window.clearTimeout(id)), [])
  /** 이번 끌기에서 이미 넘겼나 — 한 번의 끌기로 두 번 부르지 않게. 끌기마다 풀린다 */
  const fired = useRef(false)
  /** 방금 끌어서 넘겼나 — 그 직후 따라오는 합성 click 하나만 막는다.
      이 값을 안 두고 fired 로 막으면, 서명이 실패해 돌아온 뒤 키보드로 다시
      누를 때도 막힌다(fired 는 다음 끌기 전까지 서 있으므로). */
  const afterDrag = useRef(false)
  /** 넘긴 뒤 잠시 잠근다. 부모의 busy 는 onConfirm 안에서 지갑 확인 등을 거친 뒤에야 서므로
      그 사이 손잡이를 다시 밀거나 누르면(연타 포함) onConfirm 이 두 번 불린다 — 예측 이중 등록·
      이중 소각·서명 창 두 개. ref 라 다시 그려지기 전에도 바로 막힌다. */
  const settling = useRef(false)
  const lockFor = (ms: number) => {
    settling.current = true
    timers.current.push(window.setTimeout(() => { settling.current = false }, ms))
  }

  const locked = disabled || busy

  /* 이동 거리는 CSS 와 같은 식으로 잰다 — 손잡이는 translateX(at × (100cqw − 100%))
     로 움직이고 cqw 는 padding 을 뺀 트랙 안쪽 폭이다. 테두리 상자 폭으로 재면
     padding 만큼 손가락과 손잡이가 어긋난다. 누른 자리에서 얼마나 움직였나로 셈해
     첫 움직임에 손잡이 가운데가 손가락으로 튀지 않게 한다. */
  const move = useCallback((clientX: number, from: number) => {
    const track = trackRef.current, knob = knobRef.current
    if (!track || !knob) return
    const cs = getComputedStyle(track)
    const inner = track.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)
    const span = Math.max(1, inner - knob.offsetWidth)
    const next = Math.min(1, Math.max(0, (clientX - from) / span))
    setAt(next)
    if (next >= THRESHOLD && !fired.current) {
      fired.current = true
      afterDrag.current = true
      lockFor(SNAP_MS + RETURN_MS)
      setGrab(null)
      /* 끝까지 미끄러져 닿은 뒤 넘긴다. 넘어가면 대개 busy 화면이 덮지만, 지갑 연동
         창처럼 안 덮는 경우엔 잠시 뒤 제자리로 미끄러져 돌아온다 — 다 밀린 채로
         남으면 이미 등록된 것처럼 보인다. */
      setAt(1)
      timers.current.push(
        window.setTimeout(onConfirm, SNAP_MS),
        window.setTimeout(() => setAt(0), SNAP_MS + RETURN_MS),
      )
    }
  }, [onConfirm])

  const onPointerDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (locked || settling.current) return
    fired.current = false
    setGrab(e.clientX)
    e.currentTarget.setPointerCapture(e.pointerId)
  }
  const onPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (grab === null || locked || fired.current) return
    move(e.clientX, grab)
  }
  const endDrag = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (grab === null) return
    setGrab(null)
    try { e.currentTarget.releasePointerCapture(e.pointerId) } catch { /* 이미 놓였다 */ }
    // 끝까지 못 밀었으면 되돌아온다(전환이 다시 켜져 미끄러져 돌아온다)
    if (!fired.current) setAt(0)
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
      className={`sts${disabled ? ' is-off' : ''}${grab !== null ? ' is-dragging' : ''}`}
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
          /* 끌어서 넘긴 직후에 click 이 한 번 더 온다 — 그 하나만 삼킨다.
             끌지 않고 그냥 누른 것(키보드·탭)은 그대로 통과시킨다. */
          if (afterDrag.current) { afterDrag.current = false; e.preventDefault(); return }
          if (locked || settling.current) return
          lockFor(RETURN_MS)
          onConfirm()
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
