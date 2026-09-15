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
import { useId } from 'react'

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

  /* 손잡이가 서로를 지나치지 못하게 막는다. 지나치게 두면 min > max 가 되어
     서버가 400 을 준다(StockService 가 perMin 으로 거절한다). */
  const setLo = (v: number) => onChange({
    min: v <= from ? undefined : Math.min(v, hi),
    max: value.max,
  })
  const setHi = (v: number) => onChange({
    min: value.min,
    max: v >= to ? undefined : Math.max(v, lo),
  })

  /* 지금 고른 범위를 말로 적는다 — 손잡이 위치만으로는 끝이 "제한 없음" 인지
     "딱 그 값" 인지 구별되지 않는다 */
  const text = value.min === undefined && value.max === undefined
    ? '전체'
    : value.max === undefined
      ? `${fmt(lo)}${unit} 이상`
      : value.min === undefined
        ? `${fmt(hi)}${unit} 이하`
        : `${fmt(lo)} ~ ${fmt(hi)}${unit}`

  return (
    <div className="st-slider">
      <div className="st-slider-head">
        <span className="st-slider-label">{label}</span>
        <b className="num">{text}</b>
      </div>

      <div className="st-slider-body">
        {/* 눈금과 고른 구간. 마우스는 손잡이만 받으므로 여기는 그림일 뿐이다 */}
        <span className="st-slider-track" aria-hidden="true" />
        <span
          className="st-slider-fill" aria-hidden="true"
          style={{ left: `${pct(lo)}%`, right: `${100 - pct(hi)}%` }}
        />
        <input
          type="range" className="st-slider-input"
          id={`${id}-lo`} min={from} max={to} step={step} value={lo}
          aria-label={`${label} 하한`}
          aria-valuetext={value.min === undefined ? '제한 없음' : `${fmt(lo)}${unit}`}
          onChange={(e) => setLo(Number(e.target.value))}
        />
        <input
          type="range" className="st-slider-input"
          id={`${id}-hi`} min={from} max={to} step={step} value={hi}
          aria-label={`${label} 상한`}
          aria-valuetext={value.max === undefined ? '제한 없음' : `${fmt(hi)}${unit}`}
          onChange={(e) => setHi(Number(e.target.value))}
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
