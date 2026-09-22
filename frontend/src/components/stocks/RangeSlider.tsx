/* 두 손잡이 범위 슬라이더 — B-02 상세 필터의 PER·PBR 이 쓴다.
   전에는 최소·최대를 직접 적는 칸 두 개였다(2026-09-15에 바꿨다). 숫자를 적으려면
   "PER 이 보통 얼마인지" 를 이미 알아야 해서, 탐색하러 온 사람에게는 쓸 수 없는
   컨트롤이었다. 눈금 위에서 끌면 가능한 범위가 눈에 보인다.

   **input[type=range] 두 개를 겹쳐 쓴다.** 직접 div 로 그리면 키보드로 못 만지고
   보조기기가 값을 못 읽는다. 네이티브를 겹치면 화살표키·Home/End 가 그냥 되고,
   값도 스스로 읽힌다. 대신 두 가지를 손봐야 한다 —
     · 트랙은 아래 div 가 그린다(네이티브 트랙은 감춘다). 두 개가 겹쳐 있어
       네이티브 트랙을 두면 선이 두 겹으로 보인다.
     · 위에 깔린 input 이 아래 것의 손잡이를 덮으므로, input 자체는 마우스를
       받지 않고(pointer-events:none) **손잡이만** 받게 한다.

   ■ 끝 값의 뜻
   꼬리가 아주 긴 값이다(PER 중앙값 15인데 최대 3228). 눈금을 최대까지 늘리면
   대부분의 종목이 왼쪽 끝 몇 px 안에 뭉쳐 고를 수가 없다. 그래서 상한을 적당한
   데서 끊고, 손잡이가 **끝에 있으면 "그 위로는 제한 없음"** 으로 친다.
   같은 이유로 왼쪽 끝은 "아래로 제한 없음" 이다. 둘 다 끝이면 조건이 없는 것과 같다. */
import { useEffect, useId, useState } from 'react'

export type Range = { min?: number; max?: number }

type Props = {
  label: string
  /** 눈금 왼쪽 끝. 보통 0 */
  from: number
  /** 눈금 오른쪽 끝. 이 값에 놓으면 상한 없음으로 친다 */
  to: number
  step: number
  value: Range
  onChange: (next: Range) => void
  /** 값 옆에 붙는 말(배 · % 따위) */
  unit?: string
  /** 소수 자리. 0.1 단위 눈금이면 1 */
  digits?: number
}

export default function RangeSlider({
  label, from, to, step, value, onChange, unit = '', digits = 0,
}: Props) {
  const id = useId()
  /* 값이 없다(제한 없음) = 눈금의 끝에 놓는다 */
  const lo = value.min ?? from
  const hi = value.max ?? to
  const pct = (v: number) => ((v - from) / (to - from)) * 100
  const fmt = (v: number) => v.toFixed(digits)

  /* **두 input 은 자기 값을 직접 든다.** 부모가 주는 것은 정렬된 min·max 라,
     그걸 그대로 두 input 에 꽂으면 끌던 손잡이가 상대를 지나간 순간 제 값을
     빼앗긴다 — 끌던 쪽에는 늘 큰 값이 돌아오므로 **왼쪽으로 더 못 갔다.**
     겹쳐 서면 위에 깔린 상한만 잡히는데 그것이 하한 밑으로 못 내려가서,
     둘이 맞닿으면 벌리는 쪽으로만 움직이는 잠김이 됐다(2026-09-22에 고쳤다).

     그래서 여기서는 손잡이가 서로를 **지나가게 둔다.** a 가 b 보다 커지면
     역할이 그대로 뒤집힌다(a 가 상한, b 가 하한). 부모에게는 늘 크기순으로
     세워 보내므로 min > max 는 나가지 않는다(서버는 400 으로 거절한다). */
  const [a, setA] = useState(lo)
  const [b, setB] = useState(hi)
  /* 밖에서 값이 바뀌면(필터 초기화 · 주소로 들어온 조건) 다시 맞춘다.
     크기순으로 비교하는 까닭 — 지나간 뒤에는 a 가 max 를 들고 있을 수 있는데,
     그것은 부모와 어긋난 게 아니라 같은 범위를 뒤집어 든 것뿐이다. 그대로
     비교하면 끌 때마다 손잡이가 제자리로 튄다. */
  useEffect(() => {
    if (Math.min(a, b) !== lo || Math.max(a, b) !== hi) { setA(lo); setB(hi) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lo, hi])

  const commit = (x: number, y: number) => {
    const min = Math.min(x, y)
    const max = Math.max(x, y)
    onChange({
      min: min <= from ? undefined : min,
      max: max >= to ? undefined : max,
    })
  }

  /* 트랙을 눌러도 가까운 손잡이가 그 자리로 온다. 손잡이를 정확히 집어야만 움직이던
     것을 고친다 — 16px 짜리 점 둘을 겨냥하는 것이 이 컨트롤에서 가장 어려운 동작이었다.

     좌표를 값으로 되돌릴 때 양끝 8px(손잡이 반지름)을 뺀다. 네이티브 range 는 손잡이가
     트랙 밖으로 나가지 않도록 그만큼 안쪽에서만 움직이므로, 그 보정을 빼면 누른 자리와
     손잡이가 끝으로 갈수록 어긋난다.

     손잡이를 직접 집은 경우에는 손을 떼고 끌기에 맡긴다 — 그때는 target 이 input 이다. */
  const HALF_THUMB = 8

  const onTrackDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if ((e.target as HTMLElement).tagName === 'INPUT') return
    const box = e.currentTarget.getBoundingClientRect()
    const span = box.width - HALF_THUMB * 2
    if (span <= 0) return
    const ratio = Math.min(1, Math.max(0, (e.clientX - box.left - HALF_THUMB) / span))
    const raw = from + ratio * (to - from)
    const v = Math.min(to, Math.max(from, Math.round(raw / step) * step))
    /* 가까운 쪽을 옮긴다. 거리가 같으면(두 손잡이가 겹쳐 있을 때) a 를 옮긴다 */
    if (Math.abs(v - a) <= Math.abs(v - b)) { setA(v); commit(v, b) }
    else { setB(v); commit(a, v) }
    /* 옮긴 손잡이에 초점을 준다 — 누른 다음 화살표키로 이어 다듬을 수 있다 */
    const moved = Math.abs(v - a) <= Math.abs(v - b) ? `${id}-a` : `${id}-b`
    document.getElementById(moved)?.focus()
  }

  /* 지금 고른 범위를 말로 적는다 — 손잡이 위치만으로는 끝이 "제한 없음" 인지
     "딱 그 값" 인지 구별되지 않는다 */
  const text = value.min === undefined && value.max === undefined
    ? '전체'
    : value.max === undefined
      ? `${fmt(lo)}${unit} 이상`
      : value.min === undefined
        ? `${fmt(hi)}${unit} 이하`
        : `${fmt(lo)} ~ ${fmt(hi)}${unit}`

  /* 두 input 의 이름표는 값에 따라 붙인다. 지나간 뒤에는 a 가 상한이 된다 */
  const part = (self: number, other: number) => {
    const upper = self > other || (self === other && self === b)
    const bound = self >= to ? '제한 없음' : `${fmt(self)}${unit}`
    const lower = self <= from ? '제한 없음' : `${fmt(self)}${unit}`
    return {
      'aria-label': `${label} ${upper ? '상한' : '하한'}`,
      'aria-valuetext': upper ? bound : lower,
    }
  }

  return (
    <div className="st-slider">
      <div className="st-slider-head">
        <span className="st-slider-label">{label}</span>
        <b className="num">{text}</b>
      </div>

      <div className="st-slider-body" onPointerDown={onTrackDown}>
        {/* 눈금과 고른 구간. 마우스는 손잡이만 받으므로 여기는 그림일 뿐이다 */}
        <span className="st-slider-track" aria-hidden="true" />
        <span
          className="st-slider-fill" aria-hidden="true"
          style={{ left: `${pct(Math.min(a, b))}%`, right: `${100 - pct(Math.max(a, b))}%` }}
        />
        <input
          type="range" className="st-slider-input"
          id={`${id}-a`} min={from} max={to} step={step} value={a}
          {...part(a, b)}
          onChange={(e) => { const v = Number(e.target.value); setA(v); commit(v, b) }}
        />
        <input
          type="range" className="st-slider-input"
          id={`${id}-b`} min={from} max={to} step={step} value={b}
          {...part(b, a)}
          onChange={(e) => { const v = Number(e.target.value); setB(v); commit(a, v) }}
        />
      </div>

      {/* 양 끝 눈금값. 오른쪽 끝에 + 를 붙여 "이 위로는 다 들어온다" 를 알린다 */}
      <div className="st-slider-ends" aria-hidden="true">
        <span>{fmt(from)}</span>
        <span>{fmt(to)}+</span>
      </div>
    </div>
  )
}
