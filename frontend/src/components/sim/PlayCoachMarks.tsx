/* G-04 진행 화면 안내 — 화면 위에 그대로 얹는 안내 (라우트 없음)
   담당 스토리 [ANT-FE-SEASON-PLAY]

   왜 또 만드는가 — G-00(SimOnboardingTour)과 무엇이 다른가
   G-00 은 모의투자에 처음 들어온 사람에게 "여기가 무엇을 하는 곳인지" 를 말한다.
   그림과 글로 된 카드라, 다 읽고 나서 진행 화면에 들어오면 **어디를 눌러야 하는지**
   는 여전히 모른다. 이 화면은 칸이 일곱이고 그중 셋(차트·주문·다음 영업일)이 순서대로
   맞물려야 한 게임일이 넘어간다 — 그 순서는 글로 설명하는 것보다 그 자리를 밝혀서
   보여 주는 쪽이 짧다.

   그래서 이건 카드가 아니라 **음영과 구멍**이다. 화면을 어둡게 덮고 말하는 칸만
   구멍을 내서, 읽는 동안 그 자리가 어디인지 눈이 먼저 안다.

   ── 지키는 것 ────────────────────────────────────────────
   1. 여기 적는 것은 이 화면에 **실제로 있는 것**뿐이다. 없는 기능을 적으면 안내가
      아니라 낚시가 된다(G-00 과 같은 규칙).
   2. 시대 단서를 만들지 않는다. 날짜·연도·사건명을 쓰지 않고 DAY 로만 말한다.
   3. 투자 조언을 하지 않는다. "언제 사라" 는 말은 어디에도 없다 — 어디를 누르면
      무슨 일이 일어나는지까지만 적는다.
   4. 칸을 못 찾으면 그 걸음을 건너뛴다. 마지막 게임일에는 "다음 영업일" 버튼이
      "결과 보기" 로 바뀌는 식으로 화면이 달라지므로, 없는 자리를 비워 두고 말하면
      엉뚱한 곳을 가리키게 된다.

   ── 자리를 매 프레임 다시 잰다 ────────────────────────────
   구멍의 좌표는 상태로 들고 있지 않고 rAF 로 매 프레임 재서 달라졌을 때만 넣는다.
   스크롤·창 크기 변경 이벤트만 듣게 하면 부드러운 스크롤이 도는 동안 구멍이 뒤처져
   따라오고, 차트가 봉을 그리며 높이가 바뀌는 순간에는 아예 어긋난다. */
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { markPlayCoachClosed } from '../../sim/onboardingSeen'
import '../../styles/screens/sim-coach.css'

type Place = 'bottom' | 'top' | 'right' | 'left'

type Step = {
  key: string
  /** 밝힐 칸. 없으면 그 걸음은 건너뛴다 */
  sel: string
  title: string
  body: string
  /** 말풍선을 놓아 볼 순서. 앞에서부터 들어가는 자리를 고른다 */
  place?: Place[]
  /** 구멍을 칸보다 얼마나 넓게 낼지. 기본 8 */
  pad?: number
}

/** 시즌마다 다른 값. 안내에 그대로 적는다 — "정해진 일수" 보다 "44일" 이 답이다 */
type Facts = {
  /** 이 시즌의 게임일 수(GET /seasons/{id} 의 lengthDays) */
  lengthDays?: number
}

/* 걸음은 화면을 위에서 아래로 훑는다. 왔다 갔다 하면 스크롤이 튀어 어디를 봤는지
   놓친다 — 순서를 "읽는 순서" 로 짜 두면 안내가 끝난 자리가 곧 다음에 할 일이다.

   시즌 값이 들어가는 문구가 있어 배열이 아니라 함수다. 값이 아직 없으면(시즌을 못
   받았을 때) 숫자 없는 문장으로 떨어진다 — "undefined일" 이라고 적는 것보다 낫다. */
const buildSteps = ({ lengthDays }: Facts): Step[] => [
  {
    key: 'day',
    sel: '.sp-day',
    title: '하루씩 넘기며 진행해요',
    body:
      (lengthDays ? `이 시즌은 ${lengthDays} 영업일짜리예요. ` : '시즌은 정해진 영업일만큼 이어져요. ')
      + '하루씩 직접 넘겨 마지막 날까지 가면 성적표가 나옵니다. 지금 며칠째이고 얼마나 남았는지는 여기서 봐요.',
    place: ['bottom', 'left'],
  },
  {
    /* 머리줄의 개미 말풍선. 좁은 머리줄에서는 CSS 가 통째로 감추므로(@container),
       안 보이면 이 걸음도 함께 빠진다 — 아래 probe 가 보이는 칸만 센다. */
    key: 'help',
    sel: '.sp-help',
    title: '차트 보는 법은 여기에',
    body: '지표가 무슨 뜻인지, 선이 왜 중간부터 그려지는지, 이 가격이 어디서 온 것인지 — 더보기를 누르면 적혀 있어요.',
    place: ['bottom'],
  },
  {
    key: 'stats',
    sel: '.sp-stats',
    title: '내 돈은 이 네 칸이 전부예요',
    body: '쓸 수 있는 현금, 주식까지 더한 총 자산, 지금 들고 있는 주식의 값, 그리고 처음 대비 손익.',
    place: ['bottom'],
  },
  {
    key: 'pie',
    sel: '.sp-pie',
    title: '산 종목은 여기에 쌓여요',
    body: '무엇에 얼마나 들어가 있는지 비중으로 보여줍니다. 언제 얼마에 샀는지는 매매일지에 남아요.',
    place: ['right', 'bottom'],
  },
  {
    key: 'chart',
    sel: '.sp-chart-card',
    title: '실제로 있었던 주가예요',
    body: '지어낸 값이 아니라 그때 그 종목의 일봉 그대로입니다. 오른쪽 끝이 오늘이고 그 앞은 아직 가려져 있어요. 종목 이름을 누르면 다른 종목으로 바꾸고, 지표 버튼으로 이동평균·볼린저·RSI를 켭니다. 왼쪽으로 끌면 시즌이 시작되기 전 흐름도 볼 수 있어요.',
    place: ['right', 'left', 'bottom'],
    pad: 6,
  },
  {
    key: 'order',
    sel: '.sp-order',
    title: '사고파는 곳',
    body: '매수·매도와 수량만 고르세요. 가격은 그 게임일 종가로 정해져요.',
    place: ['left', 'bottom'],
    pad: 6,
  },
  {
    key: 'advance',
    sel: '.sp-advance',
    title: '누르면 다음 날로 넘어가요',
    body: '넣어 둔 주문을 이때 한 번에 체결해요. 되돌릴 수 없으니 주문을 마친 뒤 눌러 주세요. 마지막 날에는 결과 보기로 바뀌어요. 그전에 끝내려면 아래의 건너뛰고 종료를 누르세요.',
    place: ['left', 'top'],
  },
  {
    key: 'later',
    sel: '.sp-later',
    title: '무슨 일이 있었는지는 아래에',
    body: '그때 실제로 나온 기사와 공시가 그날까지만 열려 있어요. AI가 줄인 한 줄 요약도 함께 접혀 있고, 새 소식이 오면 저절로 펴집니다.',
    place: ['top'],
  },
]

/* 말풍선 크기. CSS 와 두 곳에 나눠 적으면 한쪽만 고쳤을 때 화면 밖으로 밀린다 —
   자리 계산이 이 값을 쓰므로 여기를 원본으로 두고 CSS 가 따라 쓴다. */
const CARD_W = 320
/** 세로 높이는 글에 따라 달라진다. 자리를 고를 때만 쓰는 어림값이다 */
const CARD_H = 168
/** 말풍선과 구멍 사이 · 화면 가장자리에서 띄우는 간격 */
const GAP = 14
const EDGE = 12

type Box = { top: number; left: number; width: number; height: number }

const same = (a: Box | null, b: Box | null) =>
  a === b ||
  (!!a && !!b &&
    Math.abs(a.top - b.top) < 0.5 && Math.abs(a.left - b.left) < 0.5 &&
    Math.abs(a.width - b.width) < 0.5 && Math.abs(a.height - b.height) < 0.5)

/** 말풍선을 어디에 놓을지. 들어가는 자리를 앞에서부터 고르고, 다 안 되면 아래에 붙인다 */
function placeCard(hole: Box, order: Place[], cardH: number) {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const fits: Record<Place, boolean> = {
    bottom: hole.top + hole.height + GAP + cardH <= vh - EDGE,
    top: hole.top - GAP - cardH >= EDGE,
    right: hole.left + hole.width + GAP + CARD_W <= vw - EDGE,
    left: hole.left - GAP - CARD_W >= EDGE,
  }
  const at = order.find((p) => fits[p]) ?? 'bottom'

  let top = hole.top + hole.height + GAP
  let left = hole.left
  if (at === 'top') top = hole.top - GAP - cardH
  if (at === 'right' || at === 'left') {
    /* 옆에 붙을 때는 칸의 가운데에 맞춘다. 위쪽에 맞추면 차트처럼 긴 칸에서
       말풍선만 저 위에 떠 어디를 가리키는지 흐려진다 */
    top = hole.top + hole.height / 2 - cardH / 2
    left = at === 'right' ? hole.left + hole.width + GAP : hole.left - GAP - CARD_W
  }

  // 화면 밖으로 나가지 않게 민다. 민 만큼 꼬리는 여전히 구멍을 향한다
  left = Math.min(Math.max(left, EDGE), Math.max(EDGE, vw - CARD_W - EDGE))
  top = Math.min(Math.max(top, EDGE), Math.max(EDGE, vh - cardH - EDGE))
  return { at, top, left }
}

/* 있고, 보이는 칸만 밝힌다. 좁은 화면에서 CSS 가 감춘 칸(display:none)은 DOM 에는
   남아 있어 querySelector 로는 잡히는데, 자리가 0×0 이라 구멍이 점 하나로 뚫린다. */
const visible = (s: Step) => {
  const el = document.querySelector(s.sel)
  return !!el && el.getClientRects().length > 0
}

export default function PlayCoachMarks({ onClose, lengthDays }: { onClose: () => void } & Facts) {
  const [at, setAt] = useState(0)
  const [hole, setHole] = useState<Box | null>(null)
  const [cardH, setCardH] = useState(CARD_H)
  const cardRef = useRef<HTMLDivElement>(null)

  /* 화면에 실제로 있는 걸음만 남긴다. 마지막 게임일에는 "다음 영업일" 이 "결과 보기"
     로 바뀌는 등 화면이 달라져, 없는 자리를 가리키면 구멍이 엉뚱한 데 뚫린다.

     **그리는 중이 아니라 한 프레임 뒤에 센다.** 띄우는 쪽이 같은 렌더에서 함께
     올리면 그 시점엔 칸들이 아직 DOM 에 없어 하나도 못 찾는다 — 그대로 "밝힐 곳이
     없다" 로 읽고 스스로 닫아 버린다. */
  const [steps, setSteps] = useState<Step[] | null>(null)
  useLayoutEffect(() => {
    /* rAF 가 아니라 타이머로 미룬다. 창이 뒤에 깔려 있으면 브라우저가 화면을 그리지
       않아 rAF 가 아예 돌지 않고, 그러면 안내가 끝내 뜨지 않는다. */
    const id = window.setTimeout(() => setSteps(buildSteps({ lengthDays }).filter(visible)), 0)
    return () => clearTimeout(id)
  }, [lengthDays])

  const step = steps?.[Math.min(at, steps.length - 1)]
  const last = !!steps && at >= steps.length - 1

  /* "다시 보지 않기". 체크하면 영영, 안 하면 이 탭 세션 동안만 조용하다.
     마지막 걸음에만 두지 않는다 — 방해라서 건너뛰는 사람이 가장 먼저 끄고 싶어 하는
     것이고, 그 사람은 마지막 걸음까지 가지 않는다. */
  const [never, setNever] = useState(false)
  const neverRef = useRef(never)
  neverRef.current = never

  /* close 는 Escape 리스너에도 물려 있어, 체크 상태를 의존성으로 넣으면 체크할 때마다
     리스너가 새로 붙는다. 값은 ref 로 읽는다 */
  const close = useCallback(() => { markPlayCoachClosed(neverRef.current); onClose() }, [onClose])
  const next = useCallback(() => setAt((i) => i + 1), [])
  const back = useCallback(() => setAt((i) => Math.max(0, i - 1)), [])

  // 걸음이 바뀌면 그 칸을 화면 가운데로 끌어온다. 안 보이는 자리를 밝혀 봐야 소용없다
  useEffect(() => {
    if (!step) return
    document.querySelector(step.sel)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [step])

  const measure = useCallback((s: Step) => {
    const el = document.querySelector(s.sel)
    if (!el) return
    const r = el.getBoundingClientRect()
    const p = s.pad ?? 8
    const box = { top: r.top - p, left: r.left - p, width: r.width + p * 2, height: r.height + p * 2 }
    setHole((prev) => (same(prev, box) ? prev : box))
  }, [])

  /* 걸음이 바뀌면 **그리기 전에** 한 번 잰다. rAF 에만 맡기면 글이 먼저 바뀌고 구멍은
     한 프레임 뒤에 옮겨 가는데, 창이 뒤에 깔려 rAF 가 멈춘 브라우저에서는 그 한
     프레임이 영영 오지 않는다 — 다음 칸을 설명하면서 이전 칸을 밝히고 있게 된다. */
  useLayoutEffect(() => { if (step) measure(step) }, [step, measure])

  /* 그다음부터는 매 프레임 다시 잰다(맨 위 주석). 값이 달라졌을 때만 상태에 넣으므로
     가만히 있는 동안에는 렌더가 돌지 않는다. */
  useEffect(() => {
    if (!step) return
    let raf = 0
    const tick = () => { measure(step); raf = requestAnimationFrame(tick) }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [step, measure])

  // 말풍선 실제 높이로 자리를 다시 잡는다. 어림값으로만 두면 긴 글에서 아래가 잘린다
  useLayoutEffect(() => {
    const h = cardRef.current?.offsetHeight
    if (h && Math.abs(h - cardH) > 1) setCardH(h)
  })

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
      else if (e.key === 'ArrowRight') { if (!last) next() }
      else if (e.key === 'ArrowLeft') back()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [close, next, back, last])

  useEffect(() => { cardRef.current?.focus() }, [])

  // 밝힐 칸이 하나도 없으면 안내 자체가 성립하지 않는다. 조용히 닫는다
  useEffect(() => { if (steps && steps.length === 0) close() }, [steps, close])
  if (!steps || !step || !hole) return null

  const spot = placeCard(hole, step.place ?? ['bottom', 'top', 'right', 'left'], cardH)

  return (
    <div className="spc" role="dialog" aria-modal="true" aria-labelledby="spc-title">
      {/* 뒤 화면을 눌리지 않게 막는 판. 음영은 구멍의 그림자가 내지만 그림자는
          클릭을 받지 않아서, 막는 일은 이 투명한 판이 따로 맡는다 */}
      <div className="spc-block" onClick={(e) => e.stopPropagation()} />

      {/* 구멍 — 테두리 하나와 바깥을 통째로 덮는 그림자 */}
      <div
        className="spc-hole"
        style={{ top: hole.top, left: hole.left, width: hole.width, height: hole.height }}
        aria-hidden="true"
      />

      <div
        className={`spc-card at-${spot.at}`}
        style={{ top: spot.top, left: spot.left, width: CARD_W }}
        ref={cardRef} tabIndex={-1}
      >
        <div className="spc-rail">
          <ol className="spc-dots" aria-label={`${steps.length}걸음 중 ${at + 1}번째`}>
            {steps.map((s, i) => (
              <li key={s.key} className={i === at ? 'is-on' : i < at ? 'is-done' : undefined} />
            ))}
          </ol>
          <b className="spc-count num">{at + 1} / {steps.length}</b>
        </div>

        <h2 className="spc-title" id="spc-title">{step.title}</h2>
        <p className="spc-body">{step.body}</p>

        <label className="spc-never">
          <input type="checkbox" checked={never} onChange={(e) => setNever(e.target.checked)} />
          다시 보지 않기
        </label>

        <div className="spc-actions">
          {/* 건너뛰기는 늘 남겨 둔다. 두 번째로 들어온 사람을 붙잡지 않는다 */}
          <button type="button" className="spc-skip" onClick={close}>건너뛰기</button>
          <div className="spc-nav">
            {/* 첫 걸음에서도 자리를 비우지 않는다 — 버튼이 생겼다 사라지면 옆 버튼이
                좌우로 움직여 연달아 누를 때 빗나간다 */}
            <button
              type="button" className="spc-btn ghost" onClick={back}
              disabled={at === 0} aria-hidden={at === 0} tabIndex={at === 0 ? -1 : undefined}
            >
              이전
            </button>
            {last ? (
              <button type="button" className="spc-btn solid" onClick={close}>시작하기</button>
            ) : (
              <button type="button" className="spc-btn solid" onClick={next}>다음</button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
