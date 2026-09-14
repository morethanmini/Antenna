/* 종목 히트맵 — B-02 종목 탐색의 기본 보기.
   한국경제 marketmap 을 참고했다(2026-09-11). 시장 전체를 한 판에 깔고
   **자리는 섹터 · 크기는 규모 · 색은 등락률** 로 읽는다.

   첫 화면은 전 종목을 섹터별로 묶어 한 판에 깐다. 칸이 작아 이름이 못 들어가는
   종목이 많은데, 그럴 때는 색만 남는다 — 300 개가 넘는 종목에 이름을 억지로
   다 적으면 어느 것도 안 읽힌다. 섹터 이름줄을 누르면 그 섹터만 남아 칸이
   커지고 거의 모든 종목에 이름이 붙는다.

   누를 곳이 둘이고 뜻이 다르다 — **칸은 종목 상세로, 섹터 이름줄은 그 섹터
   펼치기로** 간다. 자세한 까닭은 SectorBlocks 주석에 적었다.

   목록의 "더 보기" 와 달리 전 종목을 한 번에 받는다(fetchAllStocks) — 일부만
   그리면 면적 비율이 거짓말이 된다.

   크기 기준은 시가총액이다(= 종가 × 상장주식수, 서버가 GET /stocks 로 내린다).
   정비례로 둔다 — 까닭은 아래 AREA_OF 주석에 적었다.

   색은 국내 관례다 — 오르면 빨강, 내리면 파랑. ±3% 에서 가장 진하다.
   색만으로 전하지 않는다: 칸에는 등락률 숫자가 함께 있고, 숫자가 안 들어갈 만큼
   작은 칸은 title 과 aria-label 로 읽을 수 있다. */
import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchAllStocks } from '../../api/stocks'
import type { StockFilter, StockListItem } from '../../api/stocks'
import { useBlock } from '../../api/useBlock'
import EmptyState from '../state/EmptyState'
import ErrorState from '../state/ErrorState'
import { squarify } from './treemap'
/* Tile 은 이 파일의 컴포넌트 이름으로 쓰므로 배치 결과 타입은 Laid 로 받는다 */
import type { Rect, Tile as Laid } from './treemap'
import '../../styles/screens/market-map.css'

/** 섹터 덩어리의 머리글 높이(px). CSS .mm-head 의 height 와 같아야 한다 */
const HEAD_H = 22

/** 색이 가장 진해지는 등락률(%). 한경 marketmap 의 범례와 같은 폭이다 */
const CAP = 3

/** 범례에 세우는 눈금(%). 양 끝이 CAP 과 같아야 "여기서 색이 멈춘다" 가 맞는 말이 된다 */
const LEGEND_STEPS = [-CAP, -2, -1, 0, 1, 2, CAP]

/** 섹터가 비어 있는 종목이 모이는 칸 */
const ETC = '기타'

/** 아직 안 왔을 때의 빈 목록. 참조가 고정돼야 useMemo 가 헛돌지 않는다 */
const NO_ITEMS: StockListItem[] = []

const RGB_FLAT: readonly number[] = [62, 67, 82]
const RGB_UP: readonly number[] = [222, 30, 40]
const RGB_DOWN: readonly number[] = [22, 98, 222]
const RGB_HALT: readonly number[] = [58, 62, 74]

/**
 * 등락률 → 칸 배경.
 *
 * 세기를 t^0.6 으로 눕힌다. 정비례로 두면 ±0.5% 쯤의 흔한 움직임이 거의 무채색이라
 * 판 대부분이 회색으로 죽는다. 거래정지(null)는 어느 쪽 색도 주지 않는다.
 */
function heatOf(rate: number | null): string {
  if (rate === null) return `rgb(${RGB_HALT.join(' ')})`
  const t = Math.pow(Math.min(Math.abs(rate) / CAP, 1), 0.6)
  const to = rate > 0 ? RGB_UP : RGB_DOWN
  const mix = RGB_FLAT.map((c, i) => Math.round(c + (to[i] - c) * t))
  return `rgb(${mix.join(' ')})`
}

/** 0 에는 부호를 붙이지 않는다 — Stocks·Watchlist 와 같은 규칙이다 */
const signed = (n: number) => `${n > 0 ? '+' : ''}${n.toFixed(2)}%`
const won = (n: number) => n.toLocaleString('ko-KR')

/** 0 은 색도 부호도 주지 않는다 */
const toneOf = (rate: number) => (rate > 0 ? ' up' : rate < 0 ? ' down' : '')

/**
 * 색 범례. 판이 아니라 **페이지 툴바**에 선다(Stocks.tsx) — 판 위에 따로 줄을
 * 두면 왼쪽이 빈 띠가 되어, 히트맵과 토글 사이에 쓸데없는 여백이 생긴다.
 *
 * 그라데이션 띠에서 단계 칸으로 바꿨다(2026-09-11). 띠는 양 끝만 읽히고 가운데가
 * 몇 %인지 셈해야 하는데, 칸마다 숫자를 박으면 판에서 본 색을 그대로 짚어 볼 수 있다.
 *
 * **색은 heatOf 로 뽑는다.** 여기에 색값을 따로 적어 두면 판의 색을 고칠 때
 * 범례만 옛 색으로 남아, 안내가 틀린 말을 하게 된다.
 */
export function HeatLegend() {
  return (
    <div className="mm-legend" aria-hidden="true">
      <span className="mm-legend-label">변동률</span>
      <ul className="mm-scale">
        {LEGEND_STEPS.map((v) => (
          <li key={v} style={{ background: heatOf(v) }}>{v}%</li>
        ))}
      </ul>
    </div>
  )
}

/** 칸 넓이의 기준 */
type Weigh = (s: StockListItem) => number

/**
 * 시가총액 → 칸 넓이. **정비례다.**
 *
 * 한때 제곱근으로 눌러 봤다가 되돌렸다(2026-09-11). 눌러 두면 작은 종목이 잘
 * 보이는 대신, 넓이를 그대로 규모로 읽을 수 없게 된다 — 넓이가 두 배인데
 * 시가총액은 네 배인 판은 "한눈에 보는 시장" 이라는 말과 맞지 않는다.
 * 한경 marketmap 도 정비례다.
 *
 * 그래서 이 판에서는 몇 종목이 크게 나온다. 시장이 실제로 그렇게 생겼다.
 * 작은 종목을 크게 보고 싶으면 섹터 바를 눌러 그 섹터만 펼친다.
 */
const AREA_OF = (v: number) => v

/**
 * 크기 기준을 **목록 전체를 보고 한 번에** 고른다.
 *
 * 시가총액이 하나라도 있으면 그것으로 잰다. 하나도 없으면(서버가 못 내려 주는
 * 상황) 종목마다 1 로 세어 섹터 덩어리가 종목 수에 비례한다 — 크기로는 아무 말도
 * 안 하는 상태이고, 판 아래 각주가 그 사실을 밝힌다.
 *
 * 거래가 정지된 종목은 종가가 없어 시가총액도 없다. 그런 종목에는 **목록에서
 * 가장 작은 시가총액**을 준다. 0 을 주면 폭 0 짜리 칸이 되어 판에서 아예
 * 사라지는데, 탐색 화면에서 종목이 소리 없이 빠지는 편이 더 나쁘다. 대신 색은
 * 회색(거래정지)이고 title 에 그렇게 적힌다.
 */
function makeWeigh(items: StockListItem[]): { weigh: Weigh; byCap: boolean } {
  const caps = items
    .map((s) => s.marketCap)
    .filter((v): v is number => typeof v === 'number' && v > 0)
  if (caps.length === 0) return { weigh: () => 1, byCap: false }

  const floor = Math.min(...caps)
  return {
    weigh: (s) =>
      AREA_OF(typeof s.marketCap === 'number' && s.marketCap > 0 ? s.marketCap : floor),
    byCap: true,
  }
}

type Group = { sector: string; items: StockListItem[]; weight: number; rate: number }

/** 섹터로 묶고 덩어리가 큰 순으로 세운다 */
function groupBySector(items: StockListItem[], weightOf: Weigh): Group[] {
  const by = new Map<string, StockListItem[]>()
  for (const s of items) {
    const key = s.sector ?? ETC
    const bucket = by.get(key)
    if (bucket) bucket.push(s)
    else by.set(key, [s])
  }

  return [...by.entries()]
    .map(([sector, list]) => {
      const weight = list.reduce((sum, s) => sum + weightOf(s), 0)
      /* 섹터 등락률은 **단순 평균**이다. 시가총액으로 가중하는 편이 지수에 가깝지만,
         목록 탭의 섹터 칩이 GET /stocks/sectors 의 단순 평균을 그대로 띄운다 —
         같은 앱의 두 화면이 같은 섹터를 다른 숫자로 말하면 어느 쪽도 못 믿는다.
         거래정지 종목은 셈에서 뺀다(등락률이 없다). */
      const live = list.filter((s) => s.changeRate !== null)
      const rate = live.length === 0
        ? 0
        : live.reduce((sum, s) => sum + (s.changeRate as number), 0) / live.length
      return { sector, items: list, weight, rate }
    })
    .sort((a, b) => b.weight - a.weight || b.items.length - a.items.length)
}

/** 판의 실제 픽셀 크기. 트리맵은 정사각형에 가깝게 자르려고 종횡비를 쓴다 */
function useBoxSize() {
  const ref = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState<{ w: number; h: number }>({ w: 0, h: 0 })

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(([entry]) => {
      /* 소수점까지 따라가면 스크롤바 하나가 생겼다 사라질 때마다
         배치가 통째로 다시 계산된다 */
      const w = Math.round(entry.contentRect.width)
      const h = Math.round(entry.contentRect.height)
      setSize((prev) => (prev.w === w && prev.h === h ? prev : { w, h }))
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  return { ref, size }
}

/** 칸 하나가 감당할 수 있는 글자. 작은 칸에 억지로 넣으면 다 잘린다 */
function fitOf(w: number, h: number) {
  return {
    name: w >= 52 && h >= 26,
    rate: w >= 52 && h >= 40,
    font: Math.max(9.5, Math.min(15, Math.round(Math.min(w / 5.4, h / 3.4)))),
  }
}

/** 칸에 다 들어가지 못한 값. 스크린리더가 읽는 한 줄이다 */
function hoverText(s: StockListItem) {
  const rate = s.changeRate === null ? '거래정지' : signed(s.changeRate)
  const close = s.prevClose === null ? '' : ` · 종가 ${won(s.prevClose)}원`
  return `${s.name} ${rate}${close}`
}

/**
 * 시가총액을 읽을 수 있는 단위로. 원 단위 그대로 적으면 자릿수가 열둘이라
 * 1,572,600,000,000 을 눈으로 세게 된다.
 */
function capText(v: number) {
  if (v >= 1e12) return `${(v / 1e12).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}조원`
  if (v >= 1e8) return `${Math.round(v / 1e8).toLocaleString('ko-KR')}억원`
  return `${won(v)}원`
}

/**
 * 말풍선 크기(px). 판 모서리에서 반대쪽으로 접을 자리를 계산할 때 쓴다.
 *
 * 폭은 CSS 의 .mm-tip width 와 같은 값이고, 높이는 **실제로 재서** 넣었다
 * (제목 + 네 줄 = 162px). 줄 수가 고정이라 상수로 둔다 — 줄을 더하거나 빼면
 * 여기도 같이 고쳐야 한다. 작게 잡아 두면 판 아래쪽에서 말풍선이 그만큼
 * 삐져나가 잘린다.
 */
const TIP_W = 214
const TIP_H = 162

/** 커서에서 띄우는 거리. 붙여 두면 말풍선이 커서 밑의 칸을 가린다 */
const TIP_GAP = 14

/**
 * 칸 설명 말풍선.
 *
 * 브라우저 기본 title 을 쓰다가 직접 그린다(2026-09-11) — 기본 툴팁은 한 줄짜리
 * 회색 상자라 값이 여럿이면 읽기 어렵고, 뜨기까지 1 초 넘게 걸린다.
 *
 * 거래량은 넣지 않았다. GET /stocks 가 안 내려 준다(daily_quotes.volume 은 있지만
 * StockListItemResponse 에 없다). 대신 등락률을 넣는다 — 지도의 주된 값인데
 * 작은 칸에는 안 적히므로 여기서 채워 주는 편이 낫다.
 */
function Tip({ s, at, box, baseDate }: {
  s: StockListItem
  at: { x: number; y: number }
  box: Rect
  baseDate: string | null
}) {
  /* 오른쪽·아래로 펼치되, 판 밖으로 나갈 것 같으면 반대쪽으로 접는다.
     잘려 나간 말풍선은 값이 안 보이므로 위치를 바꾸는 편이 낫다. */
  const right = at.x + TIP_GAP + TIP_W > box.w
  const below = at.y + TIP_GAP + TIP_H > box.h
  const left = right ? at.x - TIP_GAP - TIP_W : at.x + TIP_GAP
  const top = below ? at.y - TIP_GAP - TIP_H : at.y + TIP_GAP

  return (
    <div
      className="mm-tip"
      /* 커서를 따라다니므로 말풍선이 마우스를 가로채면 안 된다 — CSS 의
         pointer-events:none 과 짝이다. 읽어 주는 것은 칸의 aria-label 이다 */
      aria-hidden="true"
      style={{
        left: Math.max(0, Math.min(left, box.w - TIP_W)),
        top: Math.max(0, Math.min(top, box.h - TIP_H)),
      }}
    >
      <p className="mm-tip-name">{s.name}</p>
      <dl className="mm-tip-rows">
        <div>
          <dt>종가</dt>
          <dd className="num">{s.prevClose === null ? '거래정지' : `${won(s.prevClose)}원`}</dd>
        </div>
        <div>
          <dt>등락률</dt>
          <dd className={`num${s.changeRate === null ? '' : toneOf(s.changeRate)}`}>
            {s.changeRate === null ? '—' : signed(s.changeRate)}
          </dd>
        </div>
        <div>
          <dt>시가총액</dt>
          <dd className="num">{s.marketCap === null ? '—' : capText(s.marketCap)}</dd>
        </div>
        <div>
          <dt>기준일</dt>
          <dd className="num">{baseDate ?? '—'}</dd>
        </div>
      </dl>
    </div>
  )
}

/**
 * 종목 칸 하나. **두 단계가 같은 칸을 쓴다** — 첫 판의 잔 칸이든 펼친 섹터의 큰
 * 칸이든 누르면 그 종목의 상세로 간다. 크기만 다르고 뜻은 같으므로 한 곳에 둔다.
 *
 * 이름과 등락률은 들어갈 자리가 있을 때만 적는다(fitOf). 자리가 없어 글자를 못
 * 넣은 칸도 title·aria-label 로는 무엇인지 읽을 수 있다.
 */
function Tile({ t }: { t: Laid<StockListItem> }) {
  const fit = fitOf(t.w, t.h)
  const s = t.item
  return (
    <Link
      className="mm-tile"
      to={`/stocks/${s.code}`}
      /* 브라우저 기본 title 은 달지 않는다 — 직접 그린 말풍선과 겹쳐 두 개가 뜬다.
         data-code 는 판 하나에 건 이벤트가 어느 종목인지 되찾는 열쇠다. 칸마다
         onMouseEnter 를 달면 305 개의 핸들러가 생기고, 그 핸들러들이 렌더마다
         새로 만들어져 memo 가 깨진다. */
      data-code={s.code}
      aria-label={hoverText(s)}
      style={{
        left: t.x, top: t.y, width: t.w, height: t.h,
        background: heatOf(s.changeRate),
        fontSize: `${fit.font}px`,
      }}
    >
      {fit.name && <b className="mm-tile-name">{s.name}</b>}
      {fit.rate && (
        <span className="mm-tile-rate num">
          {s.changeRate === null ? '—' : signed(s.changeRate)}
        </span>
      )}
    </Link>
  )
}

/**
 * 펼친 섹터 — 칸이 커져 이름이 붙는다.
 *
 * memo 로 감싼 이유 — 말풍선이 커서를 따라다니느라 MarketMap 이 마우스가
 * 움직일 때마다 다시 그려진다. 감싸 두지 않으면 그때마다 칸 305 개가 통째로
 * 다시 그려져 지도가 끈적해진다. 넘기는 값(items · box · weigh)은 모두
 * useMemo 로 고정돼 있어 실제로 바뀔 때만 다시 그린다.
 */
const SectorView = memo(function SectorView({ items, box, weigh }: {
  items: StockListItem[]
  box: Rect
  weigh: Weigh
}) {
  const tiles = useMemo(
    () => squarify(items.map((item) => ({ item, weight: weigh(item) })), box),
    [items, box, weigh],
  )

  return <>{tiles.map((t) => <Tile key={t.item.code} t={t} />)}</>
})

/**
 * 첫 화면 — 섹터 덩어리.
 *
 * 누를 곳이 둘이고 뜻이 다르다(2026-09-11).
 * - **칸**을 누르면 그 종목의 상세로 간다. 지도에서 종목이 보이는데 눌러도 종목이
 *   안 나오면 한 번 헛걸음을 하게 된다.
 * - **섹터 이름줄**을 누르면 그 섹터만 펼친다.
 *
 * 그래서 덩어리 자체는 더 이상 버튼이 아니다. 버튼 안에 링크를 넣을 수도 없다
 * (대화형 요소는 겹칠 수 없다) — 이름줄이 버튼, 칸이 링크, 덩어리는 그 둘을 담는
 * 자리다.
 *
 * memo 로 감싼다 — SectorView 와 같은 이유다(말풍선이 커서를 따라다녀 부모가
 * 자주 다시 그려진다).
 */
const SectorBlocks = memo(function SectorBlocks({ groups, box, weigh, onPick }: {
  groups: Group[]
  box: Rect
  weigh: Weigh
  onPick: (sector: string) => void
}) {
  const blocks = useMemo(
    () => squarify(groups.map((item) => ({ item, weight: item.weight })), box),
    [groups, box],
  )

  return (
    <>
      {blocks.map((b) => {
        /* 이름줄을 달지 말지.

           이름줄이 **섹터를 펼치는 유일한 버튼**이라, 없는 덩어리는 펼칠 길이
           없어진다. 그래서 문턱을 낮게 잡는다 — 이름이 "전기·…" 로 잘려도 누를
           수 있는 줄이 있는 편이, 이름이 온전한 대신 못 누르는 것보다 낫다.
           전체 이름은 title 과 aria-label 이 말한다.

           그래도 바닥은 있다. 폭 46px 은 손가락이 짚을 수 있는 최소이고, 높이는
           이름줄 22px 을 뺀 뒤 종목 칸이 남을 만큼이어야 한다 — 34px 밑으로
           내려가면 이름줄이 덩어리를 거의 다 먹어 색이 안 보인다.

           문턱 아래인 덩어리(대개 종목 두세 개짜리 섹터)는 이름줄 없이 그 자리까지
           종목 색으로 채운다. 22px 을 비워 두면 죽은 회색 사각형이 되어 시장이
           거기서 끊긴 것처럼 보인다. 그 섹터를 통째로 보고 싶으면 목록 탭의
           섹터 칩을 쓴다 — 거기에는 23 개가 다 있다. */
        const head = b.w >= 46 && b.h >= 34
        const inner: Rect = head
          ? { x: 0, y: HEAD_H, w: b.w, h: b.h - HEAD_H }
          : { x: 0, y: 0, w: b.w, h: b.h }
        const grains = inner.h >= 6 && inner.w >= 6
          ? squarify(b.item.items.map((item) => ({ item, weight: weigh(item) })), inner)
          : []

        const n = b.item.items.length
        return (
          <div
            key={b.item.sector}
            className="mm-block"
            style={{ left: b.x, top: b.y, width: b.w, height: b.h }}
          >
            {head && (
              <button
                type="button"
                className="mm-head"
                onClick={() => onPick(b.item.sector)}
                title={`${b.item.sector} ${n}개 · ${signed(b.item.rate)} — 눌러서 펼치기`}
                aria-label={`${b.item.sector} ${n}개 종목, ${signed(b.item.rate)}. 눌러서 이 섹터만 펼치기`}
              >
                <b className="mm-head-name">{b.item.sector}</b>
                {/* 좁은 덩어리에서는 이름이 먼저다. 등락률은 자리가 남을 때만 */}
                {b.w >= 118 && (
                  <em className={`mm-head-rate num${toneOf(b.item.rate)}`}>{signed(b.item.rate)}</em>
                )}
              </button>
            )}
            {/* 첫 판에서도 **들어갈 만한 칸에는 이름을 적는다**. 안 적으면 시가총액
                비례에서 판의 절반을 차지하는 종목이 이름 없는 회색 덩어리가 되어,
                지도를 보고도 그게 무엇인지 알 수 없다(한경 marketmap 도 첫 화면부터
                이름을 적는다). 작아서 이름이 안 들어가는 칸은 그냥 색이다. */}
            {grains.map((g) => <Tile key={g.item.code} t={g} />)}
          </div>
        )
      })}
    </>
  )
})

type Props = {
  /** 시장·관심 여부만 쓴다. 섹터는 판 자체가 고르는 축이라 넘기지 않는다 */
  filter: StockFilter
}

export default function MarketMap({ filter }: Props) {
  const { ref, size } = useBoxSize()
  const q = useBlock(() => fetchAllStocks(filter), [filter])

  /** 파고든 섹터. null 이면 전체 */
  const [sector, setSector] = useState<string | null>(null)

  /* 시장을 바꾸면 펼쳐 둔 섹터를 접는다. 안 접으면 코스피에서 펼친 섹터를
     코스닥에서도 찾다가 빈 판이 나온다. effect 로 지우면 렌더가 한 번 더 돌아
     빈 화면이 한 프레임 보이므로 렌더 중에 판단한다(SettledToday 와 같은 규칙). */
  const [seen, setSeen] = useState(filter)
  if (seen !== filter) { setSeen(filter); setSector(null) }

  /* 빈 배열을 여기서 [] 로 적으면 매 렌더 새 참조가 되어, 아직 불러오는 중에도
     아래 useMemo 가 계속 다시 돈다. 모듈 상수 하나를 돌려쓴다 */
  const items = q.data?.items ?? NO_ITEMS

  /** 말풍선이 가리킬 종목과 커서 자리(판 왼쪽 위 기준) */
  const [hover, setHover] = useState<{ s: StockListItem; x: number; y: number } | null>(null)

  /** data-code 로 종목을 되찾는 표. 이벤트는 판 하나에만 걸려 있다 */
  const byCode = useMemo(() => new Map(items.map((s) => [s.code, s])), [items])

  /* 칸마다 핸들러를 달지 않고 판 하나에 걸어 위임한다 — 305 개를 다는 것보다
     싸고, 칸이 다시 그려질 이유도 없앤다. 커서 밑에 칸이 없으면(섹터 이름줄,
     칸 사이 틈) 말풍선을 닫는다. */
  const onMove = useCallback((e: React.MouseEvent) => {
    const el = (e.target as HTMLElement).closest<HTMLElement>('.mm-tile')
    const s = el?.dataset.code ? byCode.get(el.dataset.code) : undefined
    const canvas = e.currentTarget.getBoundingClientRect()
    setHover(s ? { s, x: e.clientX - canvas.left, y: e.clientY - canvas.top } : null)
  }, [byCode])

  /* 키보드로 옮겨 다닐 때도 같은 말풍선을 띄운다. 커서가 없으므로 칸 한가운데에
     맞춘다. React 의 onFocus 는 위로 전해지므로 여기 한 번만 걸면 된다. */
  const onFocusIn = useCallback((e: React.FocusEvent) => {
    const el = (e.target as HTMLElement).closest<HTMLElement>('.mm-tile')
    const s = el?.dataset.code ? byCode.get(el.dataset.code) : undefined
    if (!s || !el) { setHover(null); return }
    const r = el.getBoundingClientRect()
    const canvas = e.currentTarget.getBoundingClientRect()
    setHover({ s, x: r.left - canvas.left + r.width / 2, y: r.top - canvas.top + r.height / 2 })
  }, [byCode])

  const { weigh, byCap } = useMemo(() => makeWeigh(items), [items])
  const groups = useMemo(() => groupBySector(items, weigh), [items, weigh])
  const box = useMemo<Rect>(() => ({ x: 0, y: 0, w: size.w, h: size.h }), [size.w, size.h])

  const picked = sector === null ? null : groups.find((g) => g.sector === sector) ?? null

  if (q.error) return <ErrorState error={q.error} onRetry={q.retry} />
  if (!q.loading && items.length === 0) {
    return (
      <EmptyState
        title="조건에 맞는 종목이 없습니다"
        hint="시장을 바꾸거나 관심 종목만 보기를 꺼 보세요."
      />
    )
  }

  return (
    <div className="mm">
      {/* 판 위 줄은 **섹터를 펼쳤을 때만** 낸다(2026-09-11). 전에는 범례를 담느라
          늘 서 있었는데, 첫 화면에서는 왼쪽이 통째로 비어 보기 흉한 띠가 됐다.
          범례는 페이지 툴바로 올라갔다(Stocks.tsx 의 HeatLegend). */}
      {picked && (
        <div className="mm-bar">
          <button type="button" className="mm-back" onClick={() => setSector(null)}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M15 5 8 12l7 7" />
            </svg>
            전체 섹터
          </button>
          <p className="mm-where">
            <b>{picked.sector}</b>
            <em className="num">{picked.items.length}개</em>
            <span className={`num${toneOf(picked.rate)}`}>{signed(picked.rate)}</span>
          </p>
        </div>
      )}

      {/* 크기를 재야 배치가 나오므로 판은 늘 그린다 — 로딩 중이라고 비워 두면
          그동안 폭이 0 이라, 데이터가 온 뒤에도 한 프레임은 빈 판이 보인다 */}
      <div
        className="mm-canvas" ref={ref}
        onMouseMove={onMove}
        onMouseLeave={() => setHover(null)}
        onFocus={onFocusIn}
        onBlur={() => setHover(null)}
      >
        {/* 불러오는 동안은 **판 안에** 적는다. 판 위에 한 줄을 얹었다가 옮겼다
            (2026-09-11) — 다 불러오면 그 줄이 사라지면서 아래 판이 통째로 위로
            뛴다. 판 안에 두면 자리가 흔들리지 않는다.

            이미 그린 칸이 있으면(다시 불러오는 중) 덮지 않는다 — 멀쩡히 읽고
            있던 판을 글자 한 줄로 가릴 이유가 없다. */}
        {q.loading && items.length === 0 && (
          <p className="mm-loading">시장 전체를 불러오는 중입니다…</p>
        )}
        {size.w > 0 && size.h > 0 && items.length > 0 && (
          picked
            ? <SectorView items={picked.items} box={box} weigh={weigh} />
            : <SectorBlocks groups={groups} box={box} weigh={weigh} onPick={setSector} />
        )}

        {hover && (
          <Tip s={hover.s} at={hover} box={box} baseDate={q.data?.baseDate ?? null} />
        )}
      </div>

      {/* 크기가 무엇을 뜻하는지 밝힌다. 재 보지 않은 것을 잰 것처럼 두지 않는다.

          아직 불러오는 중이면 크기 이야기를 꺼내지 않는다 — 목록이 비어 있을 때는
          byCap 이 거짓이라, 그대로 두면 "서버가 시가총액을 안 내려 준다" 는 말을
          확인도 하기 전에 띄우게 된다. 그렇다고 통째로 감추면 값이 온 순간 한 줄이
          생겨 아래가 밀리므로, 늘 참인 문장 한 줄로 자리를 지킨다. */}
      <p className="mm-foot">
        {items.length === 0
          ? '칸 색은 직전 영업일 등락률입니다.'
          : byCap
            ? '칸 색은 직전 영업일 등락률, 칸 크기는 시가총액입니다. 칸을 누르면 종목 상세로, 섹터 이름줄을 누르면 그 섹터만 펼쳐집니다.'
            : '칸 색은 직전 영업일 등락률입니다. 서버가 아직 시가총액을 내려 주지 않아 칸 크기는 종목 수로 잡았습니다 — 시가총액이 붙으면 규모 비례로 바뀝니다.'}
      </p>
    </div>
  )
}
