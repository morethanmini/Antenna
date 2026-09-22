/* 종목 목록 보기 — B-02 의 둘째 탭. 2026-09-11 에 Stocks.tsx 에서 떼어 왔다.

   마켓맵이 첫 화면이 되면서 목록은 탭 하나로 내려갔다. 파일을 나눈 것은 줄 수
   때문이 아니라 **훅 때문**이다 — 한 파일에 두면 마켓맵을 보는 동안에도 목록의
   useCursorList 가 돌아 쓰지도 않을 첫 쪽을 불러온다. 탭이 꺼지면 이 컴포넌트가
   통째로 빠지므로 그 요청이 아예 나가지 않는다.

   컨트롤은 API 파라미터와 거의 1:1 이다(§4 B-02) — q · sector · perMin/perMax ·
   pbrMin/pbrMax · hasOpenPrediction · watchedOnly · sort. 서버가 받지만 컨트롤을
   두지 않는 것이 둘 있다: market 은 코스피만 다뤄 고를 것이 하나뿐이고,
   sentiment 는 정렬과 같은 축이라 뺐다(자세한 까닭은 그 자리 주석에).

   실전 시세는 전일 종가만이다(§7). "현재가" 라는 말을 쓰지 않고, 기준 영업일은
   행마다 반복하지 않고 머리글에 한 번 찍는다(응답 baseDate).

   행별 UP/DOWN 집계는 응답에 실려 온다 — 행마다 /sentiment 를 부르지 않는다.
   페이지 번호는 두지 않는다(커서 페이징이라 성립하지 않는다 · §9.2).
   행을 누르면 B-03 종목 상세로 간다. */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  addWatch, EMPTY_FILTER, fetchStocks, getSectorSummary, MARKET_LABEL, removeWatch,
  SORT_LABEL, STOCK_SORTS,
} from '../../api/stocks'
import type {
  SectorSummary, StockFilter, StockListItem, StockListMeta, StockSort,
} from '../../api/stocks'
import { useAsync } from '../../api/useAsync'
import { useCursorList } from '../../api/useCursorList'
import EmptyState from '../state/EmptyState'
import ErrorState from '../state/ErrorState'
import RangeSlider from './RangeSlider'
import StockLogo from './StockLogo'

const won = (n: number) => n.toLocaleString('ko-KR')
/* 0 에는 부호를 붙이지 않고 색도 주지 않는다 — 근거는 Watchlist.tsx(B-04). */
const signed = (n: number) => `${n > 0 ? '+' : ''}${n.toFixed(2)}%`
const toneOf = (rate: number | null) =>
  rate === null || rate === 0 ? '' : rate > 0 ? 'up' : 'down'
const dotted = (iso: string) => iso.replaceAll('-', '.')
/** 값이 없는 칸. 0 으로 그리면 "PER 0 배" 로 읽힌다 */
const DASH = '—'

/* 예측 심리 막대. UP 과 DOWN 이 한 줄을 나눠 갖는다 — 두 개를 따로 그리면
   합이 100 이라는 게 보이지 않는다. 비율만으로 방향을 전달하지 않도록
   우세 쪽 라벨과 퍼센트를 함께 적는다. */
function SentimentBar({ upRatio, count }: { upRatio: number | null; count: number }) {
  /* 예측이 없어도 **같은 모양**으로 그린다(2026-09-18) — 글자 한 줄만 두었더니 열 줄 중
     예닐곱이 그 줄이라 칸이 비어 보였고, 막대가 있는 줄만 튀어 목록이 들쭉날쭉했다.
     회색 막대는 "아직 아무도 안 걸었다" 를 자리로 말한다. */
  if (upRatio === null || count === 0) {
    return (
      <span className="st-senti is-none">
        <span className="st-senti-head"><b>예측 없음</b></span>
        <span className="st-senti-track" aria-hidden="true"><i className="st-senti-empty" /></span>
      </span>
    )
  }
  const up = upRatio >= 50
  return (
    <span className="st-senti">
      <span className="st-senti-head">
        <b className={up ? 'up' : 'down'}>
          {up ? 'UP' : 'DOWN'} {Math.round(up ? upRatio : 100 - upRatio)}%
        </b>
        <em className="num">{count}건</em>
      </span>
      <span className="st-senti-track" aria-hidden="true">
        <i className="st-senti-up" style={{ width: `${upRatio}%` }} />
        <i className="st-senti-down" style={{ width: `${100 - upRatio}%` }} />
      </span>
    </span>
  )
}

/** 칩 하나의 폭(px). CSS .st-sector-chip 의 flex-basis 와 같아야 한다 —
 *  한 쪽 넘길 거리를 셀 때 "한 칸은 남긴다" 는 계산에 쓴다 */
const CHIP_W = 148

/** 스크롤 끝 판정 여유(px). 소수점 오차로 끝에 닿고도 1px 이 남는 일이 있다 */
const EDGE_SLACK = 1

/** 이만큼 넘게 움직였으면 "끌었다" 로 본다(px). 누를 때 손이 조금 떨리는 것까지
 *  끌기로 치면, 칩을 고르려던 클릭이 삼켜져 아무 일도 안 일어난 것처럼 보인다 */
const DRAG_SLOP = 4
/** 가로 휠이 멈췄다고 보는 간격(ms). 그 뒤에 스냅을 되켜 가까운 칩에 붙인다 */
const WHEEL_IDLE_MS = 150

/** 슬라이더에서 손을 멈췄다고 볼 시간(ms). 짧으면 끄는 도중에 요청이 나가고,
 *  길면 놓고 나서도 목록이 안 바뀌어 고장 난 줄 안다 */
const SETTLE_MS = 250

/** PER·PBR 슬라이더의 오른쪽 끝. 이 값에 손잡이를 두면 "위로 제한 없음" 이다.
 *  실제 분포에서 90% 선을 조금 넘겨 잡았다 — 근거는 쓰는 자리 주석에 적었다 */
const PER_CAP = 80
const PBR_CAP = 8

/** 값이 오기 전에 깔아 두는 자리 칸 수. 1440px 화면에서 트랙에 들어가는 만큼이다 */
const SKELETON_CHIPS = 8

/**
 * 상단 섹터 요약 칩. 누르면 섹터 필터가 즉시 바뀐다.
 *
 * **한 줄로 눕히고 좌우 버튼으로 넘긴다**(2026-09-14). 섹터가 23 개 + 전체 = 24 칸
 * 이라 접어 두면 세 줄이 되는데, 그 띠가 목록 위를 덮어 정작 종목이 화면 밖으로
 * 밀려났다. 섹터는 훑다가 하나 고르는 값이지 한눈에 다 세는 값이 아니다.
 *
 * 넘기는 것은 브라우저 스크롤에 맡긴다(scrollBy + scroll-behavior:smooth) —
 * 직접 transform 으로 옮기면 트랙패드 가로 스크롤과 키보드 포커스 이동이 제
 * 자리를 잃는다. 버튼은 그 스크롤을 대신 눌러 주는 것뿐이다.
 *
 * **끌어서도 넘긴다**(2026-09-16). 트랙패드는 두 손가락으로 밀면 되지만 마우스만
 * 쓰는 사람은 화살표를 여러 번 눌러야 했다. 여기서도 옮기는 것은 scrollLeft 뿐이라
 * 스크롤 위치·끝 판정·키보드 이동이 전부 그대로다. 손가락 입력은 건드리지 않는다 —
 * 브라우저가 이미 그 방향으로 스크롤해 준다.
 */
function SectorChips({ current = [], onPick }: {
  /** 고른 섹터들. 비면 "전체 종목" 이 켜진다 */
  current: string[] | undefined
  onPick: (sector: string | undefined) => void
}) {
  const load = useCallback(() => getSectorSummary(), [])
  const { data, error } = useAsync(load)

  const trackRef = useRef<HTMLDivElement>(null)
  /** 양쪽에 더 갈 데가 있는지. 없으면 그 버튼을 끈다 */
  const [more, setMore] = useState({ left: false, right: false })
  /* 끌기 상태. **state 가 아니라 ref 로 판정한다** — state 만 쓰면 누른 직후 첫
     pointermove 가 다시 그려지기 전에 들어와 "아직 안 끄는 중" 으로 읽히고, 그 한
     번을 놓치면 짧은 끌기가 통째로 씹힌다. state 는 커서·스냅을 바꾸는 클래스에만
     쓴다(그건 한 박자 늦어도 티가 안 난다). */
  const drag = useRef({ active: false, captured: false, x: 0, scroll: 0 })
  const [grabbing, setGrabbing] = useState(false)
  /** 방금 끌었나. 끌기 끝에 따라오는 click 하나를 삼켜 섹터가 바뀌는 사고를 막는다
      (SlideToSign 의 afterDrag 와 같은 장치) */
  const afterDrag = useRef(false)

  const measure = useCallback(() => {
    const el = trackRef.current
    if (!el) return
    const max = el.scrollWidth - el.clientWidth
    setMore((prev) => {
      const next = { left: el.scrollLeft > EDGE_SLACK, right: el.scrollLeft < max - EDGE_SLACK }
      return prev.left === next.left && prev.right === next.right ? prev : next
    })
  }, [])

  /* 폭이 바뀌면 넘길 데가 있는지도 바뀐다. data 를 의존성에 두는 것은 칩이
     생기기 전에 재면 늘 "넘길 데 없음" 이 나오기 때문이다. */
  useEffect(() => {
    const el = trackRef.current
    if (!el) return
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [measure, data])

  /* 마우스만 받는다. 손가락은 브라우저가 이미 스크롤해 주는데 여기서 한 번 더
     scrollLeft 를 쓰면 관성 스크롤과 싸워 끊긴다. 왼쪽 버튼이 아니면(가운데·오른쪽)
     붙잡지 않는다 — 가운데 버튼은 브라우저의 자동 스크롤 자리다. */
  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (e.pointerType !== 'mouse' || e.button !== 0) return
    const el = trackRef.current
    if (!el) return
    drag.current = { active: true, captured: false, x: e.clientX, scroll: el.scrollLeft }
    afterDrag.current = false
  }

  /* 포인터를 붙잡는 것은 **슬롭을 넘긴 뒤**다. 누르자마자 붙잡으면 그 뒤의
     pointerup·click 이 전부 트랙으로 배달돼, 칩을 그냥 누른 것도 칩에 닿지 않는다
     (섹터 선택이 통째로 죽는다). 넘긴 뒤에 붙잡으면 커서가 트랙 밖으로 나가도
     끌기가 이어지는 이득만 남는다. */
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const el = trackRef.current
    const d = drag.current
    if (!d.active || !el) return
    const dx = e.clientX - d.x
    if (!d.captured) {
      if (Math.abs(dx) <= DRAG_SLOP) return
      d.captured = true
      afterDrag.current = true
      el.setPointerCapture(e.pointerId)
      /* 부드러운 스크롤·스냅은 여기서 바로 끈다. .is-dragging 클래스는 state 라 다시
         그려질 때까지 한두 번의 scrollLeft 가 smooth 로 처리돼 끌기 첫머리가 굼떴다 */
      el.style.scrollBehavior = 'auto'
      el.style.scrollSnapType = 'none'
      setGrabbing(true)
    }
    el.scrollLeft = d.scroll - dx
  }

  const endDrag = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current
    if (!d.active) return
    d.active = false
    if (d.captured) {
      try { trackRef.current?.releasePointerCapture(e.pointerId) } catch { /* 이미 놓였다 */ }
      trackRef.current?.style.removeProperty('scroll-behavior')
      trackRef.current?.style.removeProperty('scroll-snap-type')
    }
    setGrabbing(false)
  }

  /* 가로 휠·트랙패드 가로 밀기를 띠에 연결한다.

     **세로 휠은 가로채지 않는다.** 가로 줄이 세로 휠을 먹으면 띠 위에 커서가
     있을 때 페이지가 안 내려가 갇힌 것처럼 된다 — 다른 코치가 지적한 "스크롤이
     잘 안 움직인다" 가 바로 그 증상이라, 고치려다 만들 수는 없다.
     shift+휠은 브라우저가 이미 deltaX 로 바꿔 주므로 여기로 들어온다.

     React 의 onWheel 은 수동(passive) 리스너라 preventDefault 가 안 먹는다.
     그래서 직접 붙인다.

     **즉시 옮기고, 미는 동안은 스냅을 끈다**(2026-09-22). scrollLeft 에 값을 넣으면
     트랙의 scroll-behavior:smooth 때문에 매번 애니메이션이 새로 시작되는데, 트랙패드는
     16ms 마다 몇 px 씩 보내서 한 칸도 가기 전에 다음 신호가 와 다시 제자리에서 출발했다 —
     6px × 60번을 보내도 0 에서 안 움직였다(화면에 보이는 탭 실측). instant 로 옮기면
     손가락을 1:1 로 따라간다. 스냅을 켜 둔 채 조금씩 옮기면 매번 가까운 칩으로 되돌려지므로
     미는 동안은 끄고, 150ms 입력이 없으면 되켜 한 번만 붙인다(브라우저 기본 스크롤과 같은 모양). */
  useEffect(() => {
    const el = trackRef.current
    if (!el) return
    let idle: number | undefined
    const onWheel = (e: WheelEvent) => {
      if (e.ctrlKey || !e.deltaX) return   // ctrl+휠은 확대/축소다
      const max = el.scrollWidth - el.clientWidth
      /* 끝에 닿았으면 넘기지 않는다 — 브라우저의 뒤로가기 제스처를 막지 않는다 */
      if ((e.deltaX < 0 && el.scrollLeft <= 0) || (e.deltaX > 0 && el.scrollLeft >= max)) return
      e.preventDefault()
      el.style.scrollSnapType = 'none'
      el.scrollBy({ left: e.deltaX, behavior: 'instant' })
      window.clearTimeout(idle)
      idle = window.setTimeout(() => el.style.removeProperty('scroll-snap-type'), WHEEL_IDLE_MS)
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      el.removeEventListener('wheel', onWheel)
      window.clearTimeout(idle)
    }
  }, [data])

  const page = useCallback((dir: -1 | 1) => {
    const el = trackRef.current
    if (!el) return
    /* 보이는 만큼 넘기되 **한 칸은 남긴다** — 방금 보던 끝 칸이 다음 쪽 처음에
       남아 있어야 어디까지 봤는지 이어진다. 좁은 화면에서 음수가 되지 않게 막는다. */
    el.scrollBy({ left: dir * Math.max(CHIP_W, el.clientWidth - CHIP_W), behavior: 'smooth' })
  }, [])

  if (error) return null
  /* 아직 안 왔으면 **같은 크기의 자리**를 그린다(2026-09-14).

     null 을 돌려주고 있었는데, 그러면 이 줄이 통째로 없다가 생기면서 아래가
     104px 밀린다. 옆의 필터 카드와 목록 패널은 정적 마크업이라 먼저 그려져
     있어, 유독 이 줄만 "가장 늦게 뜬다" 로 보였다. 재 보니 실제 지연은 140ms
     남짓이고 /stocks/sectors 가 /stocks 보다 느린 것도 아니다(둘 다 80~90ms) —
     자리를 안 잡아 둔 것이 원인이었다. */
  if (!data) return <SectorChipsSkeleton />

  return (
    <div className="st-sectors">
      <SectorNav dir={-1} disabled={!more.left} onClick={() => page(-1)} />
      {/* 스크롤 상자 자체에는 tabIndex 를 주지 않는다 — 안쪽 칩이 전부 버튼이라
          탭으로 닿고, 브라우저가 포커스 간 칩을 알아서 끌어온다. 탭 정거장을
          하나 더 만들면 키보드로 지나가기만 번거로워진다. */}
      {/* 가장자리를 흐리게 할지는 넘길 데가 있는지로 정한다 — 넘김 버튼의 켜짐과
          같은 값(more)을 쓰므로 둘이 어긋날 일이 없다. 끝에 닿으면 흐림도 걷힌다.
          잘린 칩을 그냥 두면 "잘렸다" 로 읽히는데, 흐려지면 "더 있다" 가 된다. */}
      <div
        className={[
          'st-sector-track',
          grabbing ? 'is-dragging' : '',
          more.left ? 'fade-l' : '',
          more.right ? 'fade-r' : '',
        ].filter(Boolean).join(' ')}
        ref={trackRef} onScroll={measure}
        role="group" aria-label="섹터별 보기"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        /* 끌기 끝의 click 은 칩까지 내려가기 전에 여기서 잡는다. 칩마다 가드를
           두면 나중에 칩을 하나 더 만들 때 그 하나만 빠진다. */
        onClickCapture={(e) => {
          if (!afterDrag.current) return
          afterDrag.current = false
          e.preventDefault()
          e.stopPropagation()
        }}
      >
        {data.items.map((s: SectorSummary) => {
          const on = s.sector === null ? current.length === 0 : current.includes(s.sector)
          return (
            <button key={s.sector ?? 'ALL'} type="button"
                    className={`st-sector-chip${on ? ' on' : ''}`}
                    aria-pressed={on}
                    onClick={() => onPick(s.sector ?? undefined)}>
              <span className="st-sector-name">{s.sector ?? '전체 종목'}</span>
              <span className="st-sector-meta">
                <em className="num">{s.count}개</em>
                <b className={`num ${toneOf(s.changeRate)}`}>{signed(s.changeRate)}</b>
              </span>
            </button>
          )
        })}
      </div>
      <SectorNav dir={1} disabled={!more.right} onClick={() => page(1)} />
    </div>
  )
}

/**
 * 값이 오기 전의 자리.
 *
 * 빈 상자만 두지 않고 **칩 모양을 옅게 깔아 둔다** — 같은 높이의 회색 띠만
 * 있으면 그것대로 고장 난 화면처럼 보인다. 칸이 여덟인 것은 1440px 기준으로
 * 트랙에 들어가는 수다. 모자라면 오른쪽이 조금 비고 넘치면 잘릴 뿐이라,
 * 화면 폭을 재서 맞출 만한 값이 아니다.
 *
 * 읽어 줄 내용이 없으므로 보조기기에서는 감춘다. 목록 쪽이 제 로딩을 알린다.
 */
function SectorChipsSkeleton() {
  return (
    <div className="st-sectors" aria-hidden="true">
      <SectorNav dir={-1} disabled />
      <div className="st-sector-track">
        {Array.from({ length: SKELETON_CHIPS }, (_, i) => (
          <div key={i} className="st-sector-chip st-sector-skel">
            <span className="st-skel st-skel-name" />
            <span className="st-skel st-skel-meta" />
          </div>
        ))}
      </div>
      <SectorNav dir={1} disabled />
    </div>
  )
}

/** 섹터 줄 좌우 넘김 버튼. 화살표는 MarketMap 의 "전체 섹터" 와 같은 모양이다.
 *  자리만 그릴 때는 누를 일이 없어 onClick 이 없다 */
function SectorNav({ dir, disabled, onClick }: {
  dir: -1 | 1
  disabled: boolean
  onClick?: () => void
}) {
  return (
    <button
      type="button" className="st-sector-nav" onClick={onClick} disabled={disabled}
      aria-label={dir === -1 ? '이전 섹터 보기' : '다음 섹터 보기'}
    >
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
           style={dir === 1 ? { transform: 'rotate(180deg)' } : undefined}>
        <path d="M15 5 8 12l7 7" />
      </svg>
    </button>
  )
}

type Props = {
  filter: StockFilter
  setFilter: React.Dispatch<React.SetStateAction<StockFilter>>
}

export default function StockTable({ filter, setFilter }: Props) {
  /* **고르는 즉시 반영된다**(2026-09-15). 전에는 좌측 패널만 "필터 적용" 을 눌러야
     넘어가고 상단 섹터 칩은 즉시 반영이라, 같은 화면에서 규칙이 둘로 갈려 있었다.
     버튼을 없애고 섹터 칩 쪽으로 통일했다.

     그래도 draft 는 남는다 — 슬라이더는 끄는 동안 값이 이어서 바뀌므로, 화면에
     보이는 값(draft)과 서버로 나간 값(filter)이 잠깐 어긋나는 구간이 있다. */
  const [draft, setDraft] = useState<StockFilter>(filter)

  /* filter 는 주소 쿼리에서 온다(stockFilterParams.ts). 이 화면의 컨트롤이 아닌 곳에서
     주소가 바뀌면 — 사이드바의 "종목 탐색" 을 다시 눌러 /stocks 로 돌아온 경우 —
     컨트롤도 따라가야 한다. 안 그러면 목록은 전체인데 슬라이더는 옛 범위를 가리킨다.
     apply 로 바꾼 직후에는 draft 와 같은 값이 돌아오므로 화면은 그대로다.
     effect 가 아니라 렌더 중에 맞춘다(MarketMap 의 seen 과 같은 방식). */
  const [seen, setSeen] = useState(filter)
  if (seen !== filter) { setSeen(filter); setDraft(filter) }

  /* 반영 시점을 조건 종류에 따라 나눈다.

     칩·체크박스·섹터처럼 **한 번 눌러 끝나는** 것은 기다릴 이유가 없어 바로 넘긴다.
     슬라이더는 손잡이를 끄는 동안 수십 번 바뀌어서, 그대로 흘리면 요청이 그만큼
     나가고 목록이 끌리는 내내 깜빡인다 — 손을 멈추고 나서 한 번만 보낸다.

     apply 가 먼저 타이머를 끄는 것은, 슬라이더를 놓자마자 칩을 누른 경우에
     뒤늦게 깨어난 타이머가 **칩을 누르기 전 값**으로 덮어쓰는 것을 막기 위해서다. */
  const settle = useRef<number | null>(null)
  useEffect(() => () => { if (settle.current) clearTimeout(settle.current) }, [])

  const apply = useCallback((next: StockFilter) => {
    if (settle.current) { clearTimeout(settle.current); settle.current = null }
    setDraft(next)
    setFilter(next)
  }, [setFilter])

  const applyWhenSettled = useCallback((next: StockFilter) => {
    setDraft(next)
    if (settle.current) clearTimeout(settle.current)
    settle.current = window.setTimeout(() => {
      settle.current = null
      setFilter(next)
    }, SETTLE_MS)
  }, [setFilter])

  /* 관심 토글은 낙관적으로 먼저 바꾼다(§4 B-02). 목록은 훅이 들고 있어 직접
     고칠 수 없으므로, 바뀐 종목만 여기 얹고 렌더에서 덮어쓴다. 실패하면 되돌린다.

     어느 필터의 결과에 얹은 보정인지 함께 들고 있다 — 필터가 바뀌면 목록이
     처음부터 다시 오므로 보정도 버려야 한다. effect 로 비우면 렌더가 한 번 더
     돌아서(useCursorList 주석의 규칙) 렌더 중에 판단한다. */
  const [fix, setFix] = useState<{ for: StockFilter; map: Record<string, boolean> }>(
    { for: filter, map: {} },
  )
  const watchFix = fix.for === filter ? fix.map : {}

  /* 최소·최대를 직접 적던 시절에는 역전(최소 > 최대)을 여기서 막아야 했다.
     슬라이더는 손잡이가 서로를 지나치지 못하게 잡으므로 역전이 만들어지지 않는다 —
     경고줄도 함께 걷었다(2026-09-15). */

  /* filter 가 바뀔 때만 새 조회 함수를 만든다. 매 렌더 새로 만들면 훅이
     끝없이 다시 읽는다. filter 는 주소 쿼리가 바뀔 때만 새 객체라 참조가 안정적이다. */
  const source = useMemo(() => fetchStocks(filter), [filter])
  const list = useCursorList<StockListItem, StockListMeta>(source)

  const baseDate = list.meta?.baseDate ?? null
  const isEmpty = !list.loading && !list.error && list.items.length === 0

  /* 섹터는 여러 개 고른다(09-21 QA). 누르면 켜고 다시 누르면 끈다. "전체 종목" 은 전부 푼다 */
  const pickSector = (sector: string | undefined) => {
    const cur = draft.sector ?? []
    const next = sector === undefined ? []
      : cur.includes(sector) ? cur.filter((s) => s !== sector)
      : [...cur, sector]
    apply({ ...draft, sector: next.length > 0 ? next : undefined })
  }

  /** 같은 필터에 얹혀 있던 보정만 이어받아 한 종목을 덮어쓴다 */
  const putFix = (code: string, on: boolean) =>
    setFix((s) => ({
      for: filter,
      map: { ...(s.for === filter ? s.map : {}), [code]: on },
    }))

  const toggleWatch = async (s: StockListItem) => {
    const now = watchFix[s.code] ?? s.watched
    putFix(s.code, !now)
    try {
      await (now ? removeWatch(s.code) : addWatch(s.code))
    } catch {
      /* 서버가 거절하면 되돌린다 — 눌린 채로 남으면 담긴 줄 알고 넘어간다 */
      putFix(s.code, now)
    }
  }

  return (
    <>
      <SectorChips current={filter.sector} onPick={pickSector} />

      <div className="st-body">
        {/* ── 좌측 상세 필터 ───────────────────────────── */}
        {/* 제출할 것이 없어 form 을 걷었다(2026-09-15) — 고르는 즉시 반영된다.
            fieldset·legend 는 form 밖에서도 쓸 수 있다. */}
        <div className="card st-filter">
          {/* 제목은 눈에서만 뺐다(2026-09-15) — 칸마다 제 라벨이 있어 화면에서는
              한 겹 더 얹을 필요가 없다. 다만 글자까지 지우면 스크린리더가 이 상자를
              "무엇을 하는 곳" 인지 말할 수 없으므로 st-sr 로 남긴다(재무 지표 범위
              legend 와 같은 방식). */}
          <h2 className="st-sr">상세 필터</h2>

          {/* 종목명 검색(2026-09-15).

              상단바에도 검색이 있지만 저쪽은 **종목 하나로 바로 건너뛰는** 길이다.
              여기 것은 목록을 좁히는 조건이라, 섹터·PER 같은 다른 조건과 함께
              걸린다 — "화학 섹터에서 이름에 '한' 이 들어가는 것" 이 되는 자리다.

              타자를 칠 때마다 요청을 보내면 한 글자마다 목록이 깜빡이므로,
              슬라이더와 같이 손을 멈춘 뒤 한 번만 보낸다. */}
          <fieldset className="st-field">
            <legend>종목 찾기</legend>
            <input
              type="search" className="st-search" placeholder="종목명 또는 코드"
              aria-label="종목명 또는 종목코드로 찾기"
              // 서버 StockService.MAX_QUERY_LENGTH 와 같은 값이다 — 넘기면 400 이다
              maxLength={50}
              value={draft.q ?? ''}
              onChange={(e) => {
                const v = e.target.value
                applyWhenSettled({ ...draft, q: v.trim() === '' ? undefined : v })
              }}
            />
          </fieldset>

          {/* 여기 있던 칸 둘은 뺐다.

              시장(전체·KOSPI·KOSDAQ) — 이 서비스는 코스피만 다뤄 고를 것이
              하나뿐이었다(2026-09-11). 행에 찍히는 "KOSPI" 표시는 남긴다:
              그건 거르는 조건이 아니라 종목을 설명하는 말이다.

              예측 방향(상승 우세·하락 우세) — 정렬의 "상승 의견 순"·"하락 의견 순"
              과 같은 축이라 한 화면에 둘 이유가 없었다(2026-09-15). 정렬 쪽이 낫다:
              거르면 경계에 걸친 종목이 아예 사라지는데, 줄 세우면 위아래로 다 보인다. */}

          {/* 눈금 끝값은 실제 분포를 보고 정했다(2026-09-15 기준 코스피 상장).
              PER 은 중앙값 15 · 90%가 64 아래인데 최대가 3228 이고, PBR 은 중앙값
              1.07 · 90%가 5.6 아래인데 최대가 31.9 다. 최대까지 눈금을 늘리면 거의
              모든 종목이 왼쪽 끝에 뭉쳐 고를 수가 없다. 90% 선에서 조금 여유를 둔
              자리에서 끊고, 그 위는 손잡이를 끝에 두는 것으로 다룬다. */}
          <fieldset className="st-field">
            <legend className="st-sr">재무 지표 범위</legend>
            <RangeSlider
              label="PER" from={0} to={PER_CAP} step={1} unit="배"
              value={{ min: draft.perMin, max: draft.perMax }}
              onChange={(r) => applyWhenSettled({ ...draft, perMin: r.min, perMax: r.max })}
            />
            <RangeSlider
              label="PBR" from={0} to={PBR_CAP} step={0.1} digits={1} unit="배"
              value={{ min: draft.pbrMin, max: draft.pbrMax }}
              onChange={(r) => applyWhenSettled({ ...draft, pbrMin: r.min, pbrMax: r.max })}
            />
          </fieldset>

          {/* 체크박스 둘은 한 벌로 읽히는 목록이라 다른 칸보다 촘촘하게 둔다
              (st-field-list) — .st-field 의 gap:9px 을 그대로 쓰면 PER·PBR 슬라이더
              사이만큼 벌어져, 두 줄이 따로 노는 항목처럼 보인다. */}
          <fieldset className="st-field st-field-list">
            <legend>추가 조건</legend>
            <label className="st-check">
              <input type="checkbox" checked={draft.hasOpenPrediction ?? false}
                     onChange={(e) => apply({
                       ...draft, hasOpenPrediction: e.target.checked || undefined,
                     })} />
              내가 진행 중인 예측 있음
            </label>
            <label className="st-check">
              <input type="checkbox" checked={draft.watchedOnly ?? false}
                     onChange={(e) => apply({
                       ...draft, watchedOnly: e.target.checked || undefined,
                     })} />
              관심 종목만 보기
            </label>
          </fieldset>

          {/* "필터 적용" 은 없앴다(2026-09-15) — 고르는 즉시 반영되므로 누를 것이
              없다. 초기화는 남긴다: 조건을 하나씩 되돌리는 것보다 한 번에 지우는
              길이 있어야 한다. 상자(st-actions)는 좁은 화면에서 이 버튼이 조건
              칸들과 나란히 한 칸을 차지하도록 남겨 둔다. */}
          <div className="st-actions">
            <button type="button" className="st-reset"
                    onClick={() => apply(EMPTY_FILTER)}>
              필터 초기화
            </button>
          </div>
        </div>

        {/* ── 우측 목록 ────────────────────────────────── */}
        <section className="card st-panel" aria-labelledby="st-list-h">
          <header className="st-panel-head">
            <h2 id="st-list-h">종목 리스트</h2>
            {list.meta && list.meta.total > 0 && (
              <span className="st-count num">{list.meta.total}개 종목</span>
            )}
            {baseDate && (
              <span className="st-panel-asof">
                직전 영업일 종가 · <b className="num">{dotted(baseDate)}</b>
              </span>
            )}
            <label className="st-sort">
              <span className="st-sr">정렬</span>
              <select value={filter.sort}
                      onChange={(e) => {
                        const sort = e.target.value as StockSort
                        apply({ ...draft, sort })
                      }}>
                {STOCK_SORTS.map((s) => (
                  <option key={s} value={s}>{SORT_LABEL[s]}</option>
                ))}
              </select>
            </label>
          </header>

          {list.error && <ErrorState error={list.error} onRetry={list.reload} inline />}

          {isEmpty && (
            <EmptyState
              title="조건에 맞는 종목이 없습니다"
              hint="필터를 줄이거나 초기화해 보세요."
            />
          )}

          {list.items.length > 0 && (
            <>
              {/* 표가 아니라 목록이다 — 행 전체가 상세로 가는 링크라야 하고
                  tr 은 링크로 감쌀 수 없다. 관심 버튼은 링크 밖 형제로 둔다. */}
              <div className="st-cols" aria-hidden="true">
                <span>종목</span>
                <span className="r">전일 종가</span>
                <span className="r">등락률</span>
                <span className="r">PER</span>
                <span className="r">PBR</span>
                {/* 앞의 넷과 같이 오른쪽에 붙인다(2026-09-18) — 숫자 칸이 다 오른쪽인데
                    여기만 왼쪽이면 줄 끝이 톱니처럼 어긋난다 */}
                <span className="r">예측 현황</span>
              </div>
              <ul className="st-list">
                {list.items.map((s) => {
                  const on = watchFix[s.code] ?? s.watched
                  return (
                    <li key={s.code} className="st-item">
                      {/* 별이 줄 맨 앞이다(2026-09-18). a 안에 button 을 둘 수 없어
                          형제로 두는 구조는 그대로고, DOM 순서만 앞으로 옮겼다 —
                          CSS order 로 옮기면 탭 순서가 보이는 것과 어긋난다. */}
                      <button type="button" className={`st-star${on ? ' on' : ''}`}
                              aria-pressed={on}
                              aria-label={`${s.name} 관심 ${on ? '해제' : '추가'}`}
                              onClick={() => void toggleWatch(s)}>
                        <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
                          <path d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.2-4.1 5.8-.8z"
                                fill={on ? 'currentColor' : 'none'}
                                stroke="currentColor" strokeWidth="1.6" />
                        </svg>
                      </button>
                      <Link className="st-link" to={`/stocks/${s.code}`}>
                        <span className="st-id">
                          <StockLogo code={s.code} name={s.name} className="st-logo" />
                          <span className="st-id-text">
                            <b className="st-name">{s.name}</b>
                            <small className="st-sub">
                              <span className="num">{s.code}</span>
                              {s.market && <> · {MARKET_LABEL[s.market]}</>}
                              {s.sector && <> · {s.sector}</>}
                            </small>
                          </span>
                        </span>
                        <span className="st-close num">
                          {s.prevClose === null ? DASH : won(s.prevClose)}
                        </span>
                        <span className={`st-rate num ${toneOf(s.changeRate)}`}>
                          {s.changeRate === null ? DASH : signed(s.changeRate)}
                        </span>
                        <span className="st-per num">
                          {s.per === null ? DASH : `${s.per.toFixed(1)}배`}
                        </span>
                        <span className="st-per num">
                          {s.pbr === null ? DASH : `${s.pbr.toFixed(2)}배`}
                        </span>
                        <SentimentBar upRatio={s.upRatio} count={s.predictionCount} />
                      </Link>
                    </li>
                  )
                })}
              </ul>
            </>
          )}

          {list.loading && <p className="st-more-hint">불러오는 중…</p>}

          {list.hasNext && !list.loading && (
            <button type="button" className="st-more" onClick={list.loadMore}>더 보기</button>
          )}
          {!list.hasNext && list.items.length > 0 && (
            <p className="st-foot">마지막 종목까지 다 보셨습니다</p>
          )}
        </section>
      </div>
    </>
  )
}
