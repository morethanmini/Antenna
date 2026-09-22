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
import { useId, useRef } from 'react'

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

  /* 끌던 손잡이가 상대를 지나가면 **둘의 역할이 바뀐다.**
     끌던 것이 상한이 되고 서 있던 것이 하한으로 남는다(반대도 같다). 그래서
     끌던 손은 지나간 뒤에도 끊기지 않고 가던 방향으로 계속 간다.

     전에는 맞닿은 자리에 세웠다. 그러면 겹친 순간 **양쪽 다 잠겼다** — 겹치면
     위에 깔린 상한 손잡이만 잡히는데 그것은 하한 밑으로 못 내려가고, 밑에 깔린
     하한은 잡을 길이 없었다. 벌리는 쪽으로만 움직였다.

     지나가게 두어도 min > max 는 생기지 않는다. 두 값을 크기순으로 세워 넣기
     때문이다(서버는 perMin > perMax 를 400 으로 거절한다). 두 손잡이가 겹쳐
     보일 때 어느 input 이 잡히든 아래 두 함수가 같은 결과를 내므로, 위아래
     순서를 손보는 z-index 꼼수도 필요 없다.

     **상대 값은 잡는 순간 못 박는다.** 지나간 뒤로는 끌던 input 이 작은 쪽을
     들게 되는데, 상대를 그때그때 다시 읽으면 서 있어야 할 손잡이가 끌려온다
     (40 에서 겹친 뒤 25 까지 끌면 상대가 40 이 아니라 35 로 따라왔다). */
  const held = useRef<number | null>(null)
  const commit = (a: number, b: number) => {
    const min = Math.min(a, b)
    const max = Math.max(a, b)
    onChange({
      min: min <= from ? undefined : min,
      max: max >= to ? undefined : max,
    })
  }
  const setLo = (v: number) => commit(v, held.current ?? hi)
  const setHi = (v: number) => commit(v, held.current ?? lo)
  /* 끌기가 끝나면 푼다 — 화살표키로 만질 때는 상대를 그때그때 읽는 게 맞다.
     푸는 것은 창에 건다. 손잡이 밖에서 손을 떼면 input 은 pointerup 을 못 받는다. */
  const grab = (partner: number) => () => {
    held.current = partner
    window.addEventListener('pointerup', () => { held.current = null }, { once: true })
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
          onPointerDown={grab(hi)}
          onChange={(e) => setLo(Number(e.target.value))}
        />
        <input
          type="range" className="st-slider-input"
          id={`${id}-hi`} min={from} max={to} step={step} value={hi}
          aria-label={`${label} 상한`}
          aria-valuetext={value.max === undefined ? '제한 없음' : `${fmt(hi)}${unit}`}
          onPointerDown={grab(lo)}
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
