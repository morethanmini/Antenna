/* G-04 시즌 진행 · /sim/:id/play
   담당 스토리 [ANT-FE-SEASON-PLAY]
   설계서 docs/화면설계서.md §3 · §4 G-04 · API 명세 §모의투자

   배치는 프로토타입 4·5번 화면(리플레이 투자하기)을 따른다 — 머리줄, 지표 4칸,
   차트, 주문. 셸(상단바·사이드바)은 프로토타입이 아니라 우리 것을 쓴다.

   본문은 세 칸이다: 포트폴리오 · 차트 · 주문. 차트를 주문 바로 옆에 붙이고
   포트폴리오를 왼쪽에 세웠다 — 주문할 때 보는 것이 차트와 내 비중 둘이고,
   그 둘이 주문 패널과 붙어 있어야 눈이 멀리 가지 않는다.

   차트에 폭을 다 주지 않는다. 시즌이 30게임일이라 워밍업까지 합쳐도 봉이 60개라
   넓게 늘어놓으면 오히려 성기게 보인다.

   뉴스·AI 힌트는 아래 두 칸으로 내리고 접어 뒀다(2026-09-08). 주문할 때 먼저 보는
   것은 차트와 내 비중이고, 읽을거리는 그다음이다. 2026-09-14 에 GET /news 가 들어와
   접힌 줄에 그날의 한 줄 요약과 기사 건수가 뜬다 — 펴 보지 않아도 무엇이 있는지 안다.

   ── 프로토타입에서 일부러 뺀 것 ──────────────────────────
   1. 날짜 배지(2008.09.19 금). 시기를 알려주면 결과를 아는 사람이 유리해진다.
      DAY n 만 센다 — 시즌 가격에 실제 날짜 자체가 없다(ERD).
   2. 제목의 연도·사건명(2008 금융위기). 같은 이유다. 시즌의 성격만 쓴다.
   3. 종목코드(005930)와 로고. GET /tickers 응답에 없다.
   4. 목표가·손절가. 체결 규칙이 게임일 종가 단일가라 예약 주문이 없다(명세 §모의투자).
      자리를 만들어 두면 있는 기능처럼 보인다. 수수료·거래세는 2026-09-23 에 들어왔다 —
      주문 패널의 비용 줄과 api/seasonPlay 의 요율.
   5. 주봉·월봉 탭. 서버가 일봉만 준다.
   6. 초보자 모드 · 심급적용 토글. 아직 정의가 없다.

   ── 서버에서 오는 것 · 아직 안 오는 것 ────────────────────
   온다   GET /seasons/{id} · /tickers · /tickers/{tickerId}/prices · /news
          GET /seasons/{id}/me · /trades · POST /orders · /advance · /join

   주문·진행·투자현황은 useSeasonSim 이 서버를 부른다. 브라우저에서 굴리던 것을
   2026-09-08 에 서버로 옮겼다 — 그때 order·advance 를 Promise 로 만들어 둔 덕에
   이 화면은 await 두 줄 말고는 고치지 않았다. */
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/errors'
import {
  getPrices, getTickers, maxBuyQty, orderAmount, orderFee, orderSettled, orderTax, rate, signOf,
  type Candle, type SeasonNews, type Side, type Ticker,
} from '../api/seasonPlay'
import { useApiQuery } from '../api/useApiQuery'
import MascotSay from '../components/MascotSay'
import CandleChart from '../components/sim/CandleChart'
import IndicatorPane, { type PaneKind } from '../components/sim/IndicatorPane'
import PortfolioDonut, { type DonutSlice } from '../components/sim/PortfolioDonut'
import PlayCoachMarks from '../components/sim/PlayCoachMarks'
import TickerPickerModal from '../components/sim/TickerPickerModal'
import ErrorState from '../components/state/ErrorState'
import { playCoachMuted } from '../sim/onboardingSeen'
import { useSeasonSim } from '../sim/useSeasonSim'
import type { BarView } from '../sim/barView'
import type { IndicatorKind } from '../sim/indicators'
import '../styles/screens/sim-play.css'

/** GET /seasons/{id} 중 이 화면이 쓰는 것만. 시기는 오지 않는다 */
type SeasonHead = {
  id: number
  title: string
  lengthDays: number
  initialCash: number
  tickerCount: number
}

const won = (n: number) => `${Math.round(n).toLocaleString('ko-KR')}원`
/** 시고저는 "원" 을 붙이지 않는다 — 네 칸이 나란히 서는 자리라 단위가 반복되면 시끄럽다 */
const cut = (n: number) => Math.round(n).toLocaleString('ko-KR')
const QTY_STEPS = [10, 100, 1000]
/** 봉 그림의 기준 높이. 지금은 보조지표 칸을 켰을 때의 바닥을 정하는 데만 쓴다 */
const CHART_H = 200
/** IndicatorPane 이 차지하는 총 높이 — 여백·머리줄·그림 */
const PANE_H = 130

/* 봉 그림과 보조지표 칸이 함께 쓰는 세로. 원래 330 으로 못 박혀 있었는데, 그러면
   낮은 화면에서 아래 접이 카드 둘이 화면 밖으로 밀렸다 — 배율 100% 에서 제목부터
   거기까지가 한 화면에 들어와야 하는 자리다.

   REST 는 차트 **말고** 화면이 쓰는 세로다(상단바 · 제목줄 · 요약 네 칸 · 카드 여백 ·
   접이 카드 둘 · 위아래 여백). 1410x712 에서 실측해 맞춘 값이라, 위쪽 CSS 의 여백을
   고치면 이 숫자도 같이 재야 한다.

   이 값에서 나오는 높이는 가운데 줄의 다른 두 카드와도 맞물린다. 줄 높이는 셋 중
   가장 높은 것이 정하는데(주문 패널이 그 자리다), 차트가 그보다 커지면 차트가 줄을
   밀어 올려 아래가 화면 밖으로 나간다. 1410x712 에서 둘이 같은 높이로 서도록 잡았다.

   바닥 170 은 봉이 그림으로 읽히는 한계다. 여기까지 줄여도 모자라면 그때는 스크롤이
   조금 남는다 — 봉을 더 뭉개느니 그 편이 낫다.
   천장은 원래 값(330)에서 한 뼘 내린 320 이다. 330 이면 820~900 구간에서 차트가
   주문 패널보다 높아져 줄을 밀고, 그만큼 아래가 3px 넘쳤다. */
const CHART_REST = 440
const CHART_MIN = 170
const CHART_MAX = 320

function useChartTotal() {
  const read = () => Math.min(CHART_MAX, Math.max(CHART_MIN, window.innerHeight - CHART_REST))
  const [h, setH] = useState(read)
  useEffect(() => {
    const on = () => setH(read())
    window.addEventListener('resize', on)
    return () => window.removeEventListener('resize', on)
  }, [])
  return h
}


/* 켤 수 있는 지표.

   이평선을 5·15·30 으로 둔다. 워밍업이 30봉이라 셋 다 DAY 1 부터 값이 나온다 —
   HTS 관례값(5·20·60)을 그대로 쓰면 MA60 이 플레이 중반에야 나와서, 켜도 선이 안
   그려지는 버튼이 된다. 워밍업이 늘면 그때 관례값으로 되돌린다.

   MACD 는 뺐다(2026-09-08). RSI 는 축이 0~100 이라 캔들에 얹지 못하고 아래 패널로
   간다(IndicatorPane). */
const OVERLAYS: IndicatorKind[] = ['MA5', 'MA15', 'MA30', 'BOLL']
const PANES: PaneKind[] = ['RSI']
const INDICATORS: IndicatorKind[] = [...OVERLAYS, ...PANES]

/* ── 하루가 넘어가면 저절로 펴진다 ───────────────────────
   뉴스와 AI 요약은 주문 카드보다 아래에 있어, 차트 보고 주문하면 시선이 거기서
   끝나 아래로 안 내려간다. 그날 읽을 것이 생겼을 때 스스로 펴서 알린다.

   **효과(useEffect)를 쓰지 않는다.** 열림 여부를 상태로 들고 하루가 바뀔 때마다
   고쳐 넣으면 렌더가 한 번 더 돌고, 접었다가 다시 펴지는 깜빡임도 생긴다.
   "덮어쓴 적이 없으면 내용이 있을 때 펴 둔다" 는 규칙이면 계산만으로 끝난다.

   **접어 둔 것은 그 게임일 동안만 존중한다.** 오늘 안 볼래, 는 오늘의 뜻이지
   앞으로 영영 보지 않겠다는 뜻이 아니다 — 다음 날 새 소식이 오면 다시 편다.
   그래서 덮어쓴 값에 게임일을 함께 적어 둔다.

   점(dot)은 읽을 것이 있는데 접혀 있을 때만 찍는다. 펴면 곧바로 사라진다. */
type Override = { day: number; open: boolean }

function useDayPanel(day: number, hasContent: boolean) {
  const [override, setOverride] = useState<Override | null>(null)
  const mine = override?.day === day ? override : null
  /* **처음에는 접혀 있다(2026-09-22).** 한때 그날 읽을 것이 있으면 저절로 폈는데,
     두 상자가 펴진 채로 열리면 첫 화면이 그만큼 길어져 아래가 잘렸다. 알리는 일은
     아래 dot 이 한다 — 새 것이 있는데 접혀 있을 때만 켜진다. */
  const open = mine ? mine.open : false
  return {
    open,
    dot: hasContent && !open,
    toggle: (next: boolean) => setOverride({ day, open: next }),
  }
}

/* ── 보조지표가 무엇인지 ───────────────────────────────────
   차트에 선을 켜 놓고도 그게 무슨 선인지 모르면 켠 의미가 없다. 지표 버튼 바로
   아래에서 개미가 한 줄로 말해 주고, 더 보고 싶은 사람만 편다.

   **해석은 관례이지 정답이 아니다.** 마지막 줄에 그렇게 적는다 — 여기 적힌 것을
   매매 규칙으로 읽으면 안 된다. 우리는 투자 조언을 하지 않는다.

   INDICATORS 와 같은 순서로 적는다. 지표가 늘거나 빠지면 이 표도 같이 고친다 —
   코드에서 자동으로 만들 수 있는 값이 아니다(설명은 사람이 쓴다). */
const INDICATOR_HELP: { key: string; term: string; body: string }[] = [
  {
    key: 'MA',
    term: 'MA5 · MA15 · MA30',
    body: '최근 5·15·30일 평균 종가를 이은 선이에요. 위로 향하면 오름세, 아래로 향하면 내림세예요. 짧은 선이 긴 선을 뚫고 올라가면 흐름이 바뀌는 신호로 보기도 해요.',
  },
  {
    key: 'BOLL',
    term: '볼린저밴드',
    body: '평균선 위아래로 최근 변동폭만큼 띠를 두릅니다. 값은 보통 띠 안에서 움직이고, 띠가 좁아지면 곧 크게 움직일 수 있다는 뜻으로 읽습니다.',
  },
  {
    key: 'RSI',
    term: 'RSI',
    body: '최근 오른 힘과 내린 힘을 견줘 0~100 으로 나타냅니다. 70 위면 많이 올랐다, 30 아래면 많이 내렸다고 보는 편입니다. 축이 달라 차트 아래 칸에 따로 그립니다.',
  },
]

/* ── 차트가 왜 이렇게 생겼는지 ─────────────────────────────
   QA 에서 가장 많이 나온 물음 둘이다. "선이 왜 중간부터 그려지냐" 와 "이 가격은 진짜냐".
   둘 다 고장으로 오해받는 자리라, 지표 뜻 바로 아래에 답을 같이 둔다.

   **연도·실제 날짜는 쓰지 않는다.** "시세를 언제부터 모았다" 를 적으면 워밍업 봉 수와
   맞물려 이 시즌이 언제인지가 드러난다 — 화면 전체가 지키는 규칙이다(맨 위 주석 1번).
   대신 "모아 둔 시세의 맨 앞과 가까우면" 까지만 말한다.

   붙어 있는 워밍업 봉 수는 세어서 넣는다. 시즌마다 다르고(모아 둔 구간의 앞쪽일수록
   짧다), 숫자가 있어야 "왜 하필 내 화면에서 짧은지" 가 설명된다. */
function chartFacts(warmupBars?: number) {
  return [
    {
      key: 'warmup',
      term: '선이 중간부터 그려지는 이유',
      body:
        '지표는 봉이 일정 개수 모여야 첫 값이 나옵니다 — MA5 는 5개, 볼린저는 20개, MA30 은 30개. '
        + '그래서 긴 지표일수록 왼쪽이 비어 보입니다. '
        + (warmupBars
          ? `첫 게임일 앞에 과거 봉을 최대 120개 붙여요. 이 시즌에는 ${warmupBars}개가 있어요`
          : '시즌은 첫 게임일 앞에 과거 봉을 최대 120개 붙여 둡니다')
        + ' — 시작 지점이 시세의 맨 앞에 가까우면 붙일 봉이 적어서 긴 선일수록 늦게 시작해요.',
    },
    {
      key: 'data',
      term: '이 가격은 어디서 온 것인가',
      body:
        '실제로 있었던 일봉을 그대로 옮긴 것입니다. 지어낸 값이 아니라서 아래 기사·공시와 앞뒤가 맞습니다. '
        + '종목은 그 구간 첫날 시가총액이 큰 순서로 담았고 이름도 실제 이름입니다. '
        + '서버는 지금 진행일까지의 봉만 내려줍니다 — 그 뒤 봉은 화면에 아예 오지 않아 앞질러 볼 수 없어요. '
        + '지표는 이 화면에서 종가로 계산해요(볼린저 20·2 · RSI 14).',
    },
  ]
}

function IndicatorHelp({ warmupBars }: { warmupBars?: number }) {
  const [open, setOpen] = useState(false)
  return (
    <div className="sp-help">
      <MascotSay
        trailing={
          <button
            type="button" className="sp-help-more"
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
          >
            {open ? '접기' : '더보기'}
          </button>
        }
      >
        <p>
          <b>보조지표</b>는 캔들만으로 안 보이는 흐름을 선으로 그린 것입니다.
          오른쪽 <b>지표</b> 버튼에서 켜고 끌 수 있어요.
        </p>
      </MascotSay>

      {/* 펼친 내용은 줄 안에 넣지 않고 아래로 띄운다. 머리줄에 얹힌 자리라
          안에서 늘리면 종목명·가격이 아래로 밀려 화면이 통째로 움직인다.
          지표 드롭다운(.sp-indpop)과 같은 방식이다. */}
      {open && (
        <dl className="sp-help-pop">
          {[...INDICATOR_HELP, ...chartFacts(warmupBars)].map((h) => (
            <div key={h.key}>
              <dt>{h.term}</dt>
              <dd>{h.body}</dd>
            </div>
          ))}
          <p className="sp-help-note">
            모두 널리 쓰이는 해석일 뿐 맞는 답은 아닙니다. 판단은 직접 하세요.
          </p>
        </dl>
      )}
    </div>
  )
}

/* 서버가 막은 이유를 사람 말로 바꾼다. 훅이 오류 코드를 이 몇 가지로 좁혀 준다 */
const REJECT_TEXT: Record<string, string> = {
  CASH: '모의투자금이 부족해요',
  QTY: '수량을 확인해 주세요',
  NO_PRICE: '이 게임일의 가격이 아직 없습니다',
  NOT_JOINED: '참가하지 않은 시즌입니다',
  ENDED: '이미 끝난 회차입니다',
  LAST_DAY: '마지막 게임일입니다',
  REVIEW: 'AI 복기를 만들지 못해 종료하지 않았습니다. 다시 시도해 주세요',
  DAY_MISMATCH: '다른 창에서 진행됐습니다. 화면을 새로 맞췄습니다',
  STALE: '처리됐지만 바뀐 잔고를 불러오지 못했습니다. 새로고침해 주세요',
  /* 끊김·500 처럼 이유를 모르는 실패. 서버가 처리했을 수도 있어 "못 했다" 고 단정하지 않는다 —
     훅이 실패 뒤에도 현황을 다시 받으므로 지금 화면의 잔고·체결 내역이 답이다 */
  FAILED: '처리 결과를 확인하지 못했습니다. 잔고와 체결 내역을 확인한 뒤 다시 시도해 주세요',
}

const Ico = ({ size = 18, children }: { size?: number; children: ReactNode }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
)

/** 지표 4칸 중 하나. 라벨 · 값 · 아랫줄 · 오른쪽 아이콘 배지 */
function Stat({ tone, label, value, sub, icon }: {
  tone: string
  label: string
  value: string
  sub?: ReactNode
  icon: ReactNode
}) {
  return (
    <div className={`sp-stat t-${tone}`}>
      <div className="sp-stat-txt">
        <span className="sp-stat-label">{label}</span>
        <b className="sp-stat-val num">{value}</b>
        {sub && <span className="sp-stat-sub num">{sub}</span>}
      </div>
      <span className="sp-stat-ico" aria-hidden="true">{icon}</span>
    </div>
  )
}

export default function SeasonPlay() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const season = useApiQuery<SeasonHead>(`/seasons/${id}`)
  const seasonId = Number(id)
  /* 종료 확인창. 결과는 종료한 뒤에만 본다 — 종료가 성적표·복기를 만드는 순간이다 */
  const finishDialog = useRef<HTMLDialogElement>(null)

  const [tickers, setTickers] = useState<Ticker[]>([])
  const [tickerError, setTickerError] = useState<ApiError | null>(null)
  /** 종목 목록이 한 번이라도 돌아왔나. 비어 있는 것과 "아직 안 온" 것을 가른다 */
  const [tickersLoaded, setTickersLoaded] = useState(false)
  const [selected, setSelected] = useState<number | null>(null)
  const [picking, setPicking] = useState(false)

  /* 종목별 봉 저장소. 고른 종목만 받아 두고 다시 고르면 그대로 쓴다 —
     200종목을 한 번에 받으면 요청이 200번이다. 보유 종목의 평가금액도 여기서 나온다. */
  const [candles, setCandles] = useState<Record<number, Candle[]>>({})
  const [priceError, setPriceError] = useState<ApiError | null>(null)
  const [viewTicker, setViewTicker] = useState<number | null>(null)

  /* 캔들 차트가 보는 구간. **여기서 들고 있어야** 아래 지표 패널이 같은 구간을 그린다 —
     봉 하나가 위아래에서 다른 x 에 서면 두 그림을 나란히 읽을 수 없다.
     종목을 바꾸면 되돌린다. 봉 수가 종목마다 달라 남겨 두면 엉뚱한 구간이 열린다. */
  const [view, setView] = useState<BarView>(null)

  const [side, setSide] = useState<Side>('BUY')
  const [qtyInput, setQty] = useState(0)
  const [on, setOn] = useState<IndicatorKind[]>(['MA5', 'MA15', 'MA30'])
  const [indOpen, setIndOpen] = useState(false)
  const [filled, setFilled] = useState<string | null>(null)
  const indRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!seasonId) return
    getTickers(seasonId)
      .then((r) => {
        setTickers(r.items)
        setSelected((cur) => cur ?? r.items[0]?.tickerId ?? null)
      })
      .catch((e) => setTickerError(e instanceof ApiError ? e : null))
      .finally(() => setTickersLoaded(true))
  }, [seasonId])

  /* 고른 종목의 봉을 받는다. 이미 있으면 다시 부르지 않는다 —
     진행일이 바뀌면 위에서 통째로 버리므로 그때 다시 받는다.
     uptoDay 를 넘기지 않는다. 서버가 내 진행일까지만 준다(커닝 차단). */
  useEffect(() => {
    if (!seasonId || selected === null || candles[selected]) return
    getPrices(seasonId, selected)
      .then((r) => setCandles((prev) => ({ ...prev, [selected]: r.items })))
      .catch((e) => setPriceError(e instanceof ApiError ? e : null))
  }, [seasonId, selected, candles])

  /* 지표 목록은 접어 둔다. 열려 있는 동안 바깥을 누르거나 Escape 를 치면 닫는다 —
     팝오버가 열린 채 남으면 차트를 가린다. */
  useEffect(() => {
    if (!indOpen) return
    const onDown = (e: MouseEvent) => {
      if (!indRef.current?.contains(e.target as Node)) setIndOpen(false)
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setIndOpen(false) }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [indOpen])

  const candlesOf = useCallback((tickerId: number) => candles[tickerId], [candles])

  const sim = useSeasonSim({
    seasonId,
    initialCash: season.data?.initialCash ?? 0,
    lengthDays: season.data?.lengthDays ?? 0,
    tickers,
    candlesOf,
  })

  /* 진행일이 바뀌면 받아 둔 봉을 버린다.

     서버는 내 진행일까지만 봉을 준다(커닝 차단). 그래서 DAY 8 에 받아 둔 배열에는
     DAY 9 부터의 봉이 없다 — 다음 영업일로 넘겨도 캐시가 그대로면 차트가 어제에
     멈추고, 오늘 종가가 없어 <b>매수 가능이 0주가 되어 주문이 막힌다</b>.
     하루에 요청 한 번이면 되는 값이라 통째로 버리고 다시 받는다.

     렌더 중에 갈아끼운다(리액트가 권하는 방식이다. 효과로 하면 한 프레임 동안
     옛 봉이 그려진다). */
  const [barsDay, setBarsDay] = useState(sim.day)
  if (barsDay !== sim.day) {
    setBarsDay(sim.day)
    setCandles({})
    /* 보는 구간도 끝으로 되돌린다. 과거를 보던 채로 하루를 넘기면 새로 생긴 봉이
       화면 밖에 그려져 아무 일도 안 일어난 것처럼 보인다. 차트 안에서 비교하지
       않는 이유는 바로 위에서 봉을 버려 그 컴포넌트가 잠깐 사라지기 때문이다. */
    setView(null)
  }

  /* 종목을 바꾸면 보는 구간을 끝으로 되돌린다. 봉 수가 종목마다 다르므로 남겨 두면
     같은 번호가 다른 자리를 가리킨다(진행일이 바뀔 때는 바로 위 barsDay 블록이 되돌린다). */
  if (viewTicker !== selected) {
    setViewTicker(selected)
    setView(null)
  }

  /* 그날의 시장 요약(AI_HINT)과 고른 종목의 기사·공시. 훅이라 이른 return 보다 위에 둔다.
     서버가 진행일을 넘는 행을 내리지 않으므로 day 는 화면 표시용이다 — 넘겨 물어도 잘린다. */
  const hints = useApiQuery<{ items: SeasonNews[] }>(`/seasons/${id}/news`, { day: sim.day })
  const news = useApiQuery<{ items: SeasonNews[] }>(
    `/seasons/${id}/news`,
    { day: sim.day, tickerId: selected ?? undefined },
  )

  /* 두 칸이 그릴 것. **조기 return(시즌 로딩·오류·미참가)보다 위에 둔다** —
     바로 아래 useDayPanel 이 훅이라, return 아래로 내려가면 어떤 렌더에서는
     호출되고 어떤 렌더에서는 안 되어 훅 규칙이 깨진다. */
  const todayHint = hints.data?.items.find((n) => n.gameDay === sim.day) ?? null
  /* 펼쳤을 때 나올 줄 — body 의 주요 사건 세 줄. **비어 있으면 아예 펼치지 않는다** —
     접힌 줄의 한 문장 요약 말고는 보여 줄 것이 없다. */
  const hintLines = (todayHint?.body ?? '')
    .split('\n')
    .map((line) => line.replace(/^·\s*/, '').trim())
    .filter(Boolean)
  const newsItems = (news.data?.items ?? []).filter((n) => n.kind !== 'AI_HINT')

  /* 그날 읽을 것이 있으면 저절로 편다. 불러오는 중에는 아직 모르므로 펴지 않는다 —
     빈 상자가 열렸다 닫히면 화면이 한 번 튄다. */
  const hintPanel = useDayPanel(sim.day, !hints.loading && !!todayHint)
  const newsPanel = useDayPanel(sim.day, !news.loading && newsItems.length > 0)

  const heldQty = useMemo(
    () => Object.fromEntries(sim.positions.map((p) => [p.tickerId, p.qty])),
    [sim.positions],
  )

  /* 어제 종가로 다시 평가한 주식 금액. "전일 대비" 는 이것과의 차이다.

     오늘 산 주식도 어제 값으로 세므로 엄밀한 어제 잔고는 아니다. 이 줄이 답하는
     질문이 "어제 이후 얼마 벌었나" 가 아니라 "지금 들고 있는 것들이 오늘 얼마나
     움직였나" 여서, 매매를 섞지 않는 이 계산이 오히려 읽기 쉽다. */
  const prevStock = useMemo(
    () => sim.positions.reduce(
      (acc, p) => acc + p.qty * (sim.priceOf(p.tickerId, sim.day - 1) ?? p.avgPrice),
      0,
    ),
    [sim],
  )

  /* 봉 그림이 쓸 세로. **훅이라 아래 조기 return(로딩 · 오류 · 미참가)보다 위에 둔다** —
     한 번 아래에 뒀다가 화면이 통째로 죽었다. 어떤 렌더에서는 호출되고 어떤 렌더에서는
     안 되어 훅 순서가 어긋난다(2026-09-22). */
  const chartTotal = useChartTotal()

  /* 들어오자마자 다섯을 부른다 — 시즌 · 종목 · 내 상태 · 봉 · 뉴스. 그중 봉이 가장
     크다(워밍업 120 + 진행분). 시즌만 왔을 때 화면을 열면 지표 네 칸과 차트 자리가
     빈 채로 서서 "고장 났나" 로 읽히므로, 그릴 것이 다 올 때까지 한 장으로 붙잡는다.

     **첫 진입에만 붙잡는다.** 하루를 넘기면 봉을 통째로 버리고 다시 받는데(barsDay),
     그때도 이 게이트가 서면 넘길 때마다 화면이 통째로 사라졌다 돌아온다. 한 번 그린
     뒤에는 차트 자리의 "봉을 불러오는 중…" 한 줄로 충분하다. */
  const bars = selected === null ? undefined : candles[selected]
  /* 첫 게임일 앞에 붙어 있는 봉. 시즌마다 다르고(모아 둔 시세의 앞쪽일수록 짧다),
     "왜 MA30 이 끝에서야 시작하는지" 가 이 숫자로 설명된다 — 도움말에 그대로 쓴다. */
  const warmupBars = bars?.filter((c) => c.gameDay <= 0).length
  const pending =
    season.loading
    || !tickersLoaded
    || (selected !== null && bars === undefined && priceError === null)
  const [booted, setBooted] = useState(false)
  if (!booted && !pending) {
    setBooted(true)
  }

  /* ── 화면 안내 ─────────────────────────────────────────
     처음 이 화면에 온 사람에게 칸마다 구멍을 내어 무엇을 하는 자리인지 알린다.
     G-00 이 "모의투자가 무엇인지" 라면 이쪽은 "이 화면에서 무엇을 누르는지" 다.

     화면이 다 그려진 **뒤에** 연다(booted). 안내가 자리를 재서 구멍을 뚫는 방식이라,
     차트가 아직 자리를 안 잡았을 때 열면 엉뚱한 곳에 구멍이 난다.
     참가 전(joined===false)에는 열지 않는다 — 그 화면에는 밝힐 칸이 없다.

     한 번 닫으면 이 탭을 쓰는 동안은 다시 뜨지 않고, 안내 안에서 "다시 보지 않기" 를
     체크하고 닫았으면 그때부터 영영 뜨지 않는다(sim/onboardingSeen.ts). */
  const [coach, setCoach] = useState(false)
  useEffect(() => {
    if (booted && sim.joined !== false && !playCoachMuted()) setCoach(true)
  }, [booted, sim.joined])

  if (!booted && pending) {
    return (
      <main className="main">
        <div className="main-inner sim-play">
          <div className="placeholder tall">{'시즌 정보를 가져오는 중이에요…'}</div>
        </div>
      </main>
    )
  }

  if (season.error || !season.data) {
    return (
      <main className="main">
        <div className="main-inner sim-play">
          {season.error
            ? <ErrorState error={season.error} onRetry={season.reload} />
            : <div className="placeholder tall">{'시즌을 찾을 수 없습니다'}</div>}
        </div>
      </main>
    )
  }

  const s = season.data

  /* 참가하지 않았으면 진행할 것이 없다. 서버가 GET /me 에 SEASON_NOT_JOINED 를 주고
     훅이 joined=false 로 알려 준다 — 이때 차트만 띄워 두면 주문 버튼이 다 막힌
     화면이 되어 왜 안 되는지 알 수 없다. */
  if (sim.joined === false) {
    return (
      <main className="main">
        <div className="main-inner sim-play">
          <nav className="sp-crumb" aria-label="위치">
            {/* "연습" 칸이 있었다(2026-09-21 제거). 연습 허브를 홈에 합치면서 둘이
                같은 곳이 되어, 같은 링크가 나란히 두 번 서 있었다. */}
            <Link to="/sim">모의투자 홈</Link>
            <i aria-hidden="true">›</i>
            <span>진행</span>
          </nav>
          <section className="sp-card">
            <p className="sp-none">
              <b>{s.title}</b>
              아직 이 연습에 참가하지 않았습니다.
              <small>참가하면 예수금 {won(s.initialCash)}으로 DAY 1부터 시작해요.</small>
              <button type="button" className="sp-join" disabled={sim.pending}
                      onClick={() => { void sim.join() }}>
                {sim.pending ? '참가 중…' : '참가하기'}
              </button>
            </p>
          </section>
        </div>
      </main>
    )
  }


  const ticker = tickers.find((t) => t.tickerId === selected) ?? null
  const price = selected === null ? null : sim.priceOf(selected)
  const prev = selected === null ? null : sim.priceOf(selected, sim.day - 1)
  /** 오늘 봉. 진행일과 같은 game_day 를 찾는다 */
  const today = bars?.find((c) => c.gameDay === sim.day) ?? null
  const diff = price !== null && prev !== null ? price - prev : null
  const diffRate = diff !== null && prev ? (diff / prev) * 100 : null

  const held = sim.positions.find((p) => p.tickerId === selected)
  /* 끝난 회차는 0 — 수량 칸·단계 버튼·주문 버튼이 이 값 하나로 같이 잠긴다(QA 09-21: 건너뛰고 종료한
     회차에서 "매수 주문" 이 켜져 있었다. 서버는 409 로 막지만 누르기 전에 막아야 한다). */
  const maxQty =
    sim.isDone || price === null ? 0 : side === 'BUY' ? maxBuyQty(sim.cash, price) : held?.qty ?? 0
  /* 주문 수량은 **살 수 있는·가진 만큼을 넘지 않는다**(2026-09-19, QA 09-18). input 의 max 는 손으로
     치는 값을 막지 못하고 +N 버튼에도 상한이 없어서, 보유보다 많은 매도가 그대로 서버까지 갔다
     (409 SEASON_INSUFFICIENT_QTY). 21억을 넘기면 서버 Integer 에 담기지도 않아 엉뚱한 400 이 났다.
     넣을 때 자르고(changeQty), 그린 뒤에 상한이 줄어드는 경우 — 다음 날로 넘어가 종가가 올랐을 때 —
     를 위해 쓸 때도 한 번 더 자른다. */
  const qty = Math.min(qtyInput, maxQty)
  const changeQty = (n: number) => setQty(Math.min(maxQty, Math.max(0, Math.floor(n) || 0)))
  const amount = price === null ? 0 : orderAmount(price, qty)
  /* 수수료(양쪽)와 거래세(매도만). 여기 값은 어디까지나 예상이고 진짜는 주문 응답이 준다 —
     같은 요율·같은 절사라 어긋나지 않지만, 어긋나면 서버가 맞다.
     **둘을 따로 그린다**(2026-09-23) — 합쳐서 "수수료·세금 N원" 으로 두면 어느 쪽이
     얼마인지 알 수 없다. 매도에서는 거래세가 수수료의 열 배라 그 차이가 특히 크다. */
  const fee = orderFee(amount)
  const tax = orderTax(side, amount)
  /** 매수면 내는 총액, 매도면 받는 실수령액 */
  const settled = orderSettled(side, amount)
  const percent = s.lengthDays > 0 ? Math.round((sim.day / s.lengthDays) * 100) : 0
  /** 남은 게임일. 건너뛰고 종료 버튼과 확인창이 같은 수를 말한다 */
  const left = Math.max(0, s.lengthDays - sim.day)

  /* RSI 를 켜면 아래에 칸이 하나 더 붙는다. 그때만 카드가 길어지면 오른쪽 주문
     패널과의 높이 균형이 켤 때마다 달라져 화면이 뛴다. 차트가 그만큼 양보해서
     카드 높이를 같게 둔다 — 껐을 때는 봉이 커지므로 손해도 아니다. */
  const paneOn = PANES.some((k) => on.includes(k))
  /* 보조지표 칸을 켜면 그 몫을 봉 그림에서 뗀다 — 둘이 합쳐 같은 세로를 쓴다.
     칸을 켠 채로 화면이 낮으면 봉이 너무 얇아지므로 거기서는 바닥을 하나 더 둔다.
     (높이 자체는 위쪽 chartTotal 이 갖는다 — 훅이라 조기 return 보다 위에 있어야 한다.) */
  const chartH = paneOn ? Math.max(CHART_H * 0.7, chartTotal - PANE_H) : chartTotal

  const stockDiff = sim.stockValue - prevStock
  const stockRate = prevStock > 0 ? (stockDiff / prevStock) * 100 : null
  const prevTotal = sim.cash + prevStock
  const assetRate = prevTotal > 0 ? (stockDiff / prevTotal) * 100 : null

  /** 전일 대비 한 줄. 보유가 없으면 비교할 게 없어 자리만 지킨다 */
  const dayOver = (r: number | null) =>
    r === null ? <span className="flat">전일 대비 —</span>
      : <span className={signOf(stockDiff)}>전일 대비 {rate(r)}</span>

  /* 도넛 조각. **가진 종목을 전부 이름으로 넘긴다**(2026-09-19, QA 09-18). 전에는 큰 것 넷만
     남기고 나머지를 "기타 N종목" 한 줄로 접어서, 다섯째부터는 무엇을 얼마나 들고 있는지 이 화면
     어디에서도 볼 수 없었다. 원에서는 다섯째부터 같은 색으로 이어 붙어 여전히 한 덩어리로 읽히고
     (PortfolioDonut 의 colorOf), 목록은 "더 보기" 로 접혀 있어 카드가 길어지지도 않는다.
     현금은 늘 마지막이다 — 종목들 사이에 끼면 종목처럼 읽힌다. */
  const sorted = [...sim.positions].sort((a, b) => b.value - a.value)
  const slices: DonutSlice[] = [
    ...sorted.map((p) => ({
      key: `t${p.tickerId}`,
      label: p.displayName,
      value: p.value,
      tickerId: p.tickerId,
      detail: `${p.qty.toLocaleString('ko-KR')}주 · 평균 ${won(p.avgPrice)}`,
      pnl: p.pnl,
    })),
    { key: 'CASH', label: '현금', value: sim.cash },
  ]

  /* await 로 부른다. 지금은 훅 안에서 즉시 끝나지만 서버가 붙으면 진짜로 기다리게
     된다 — 그때 이 자리를 안 고치려고 지금부터 비동기로 쓴다. */
  async function submit() {
    if (selected === null) return
    const at = await sim.order(selected, side, qty)
    if (at !== null) {
      setFilled(`${side === 'BUY' ? '매수' : '매도'} ${qty.toLocaleString('ko-KR')}주 · ${won(at)} 체결`)
      setQty(0)
    }
  }

  /* 종료가 서버에 박힌 뒤에만 결과로 간다. 서버는 finish 에서 성적표와 AI 복기를 같이
     만들어 저장하고, 실패하면 아무것도 저장하지 않는다 — 그때는 창을 닫고 이유를 보인다. */
  async function finishAndGo() {
    /* 마지막 게임일 전에 이 창이 열렸다면 "건너뛰고 종료" 로 들어온 것이다 */
    if (await sim.finish(!sim.isLastDay)) navigate(`/sim/${seasonId}/result`)
    else finishDialog.current?.close()
  }

  return (
    <main className="main">
      <div className="main-inner sim-play">
        <nav className="sp-crumb" aria-label="위치">
          <Link to="/sim">모의투자 홈</Link>
          <i aria-hidden="true">›</i>
          <span>진행</span>
        </nav>

        {/* 게임일 헤더 — 날짜를 쓰지 않는다. 시즌 가격에 실제 날짜가 없다 */}
        <header className="sp-head">
          <div className="sp-title">
            <h1>{s.title}</h1>
            {/* 안내는 라우트가 없는 오버레이라 링크로 열 수 없다. 한 번 닫으면
                자동으로는 다시 뜨지 않으므로 여는 자리를 화면 안에 둔다 */}
            <p>
              연습 시즌 · 종목 {s.tickerCount}개
              {' '}
              <button type="button" className="sp-coach-again" onClick={() => setCoach(true)}>
                화면 안내 보기
              </button>
            </p>
          </div>
          {/* 제목과 진행 표시 사이의 빈 칸. 여기가 화면에서 가장 넓게 남는 자리다 —
              차트 카드 머리줄에 뒀더니 종목명·가격·지표 버튼과 폭을 다투다가
              좁은 칸에서 버튼 위로 겹쳤다. */}
          <IndicatorHelp warmupBars={warmupBars} />

          <div className="sp-day">
            <span className="sp-daychip num">
              <Ico size={15}><path d="m10 8.5 6 3.5-6 3.5z" /><circle cx="12" cy="12" r="9" /></Ico>
              DAY {sim.day}
            </span>
            <span className="sp-bar" role="progressbar" aria-label="진행률"
                  aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
              <i style={{ width: `${percent}%` }} />
            </span>
            <span className="sp-dayof num">{sim.day} / {s.lengthDays}일</span>
          </div>
        </header>

        {/* ── 지표 4칸 ─────────────────────────────────── */}
        <section className="sp-stats" aria-label="투자 현황">
          <Stat
            tone="cash"
            label="남은 모의투자금 (현금)"
            value={won(sim.cash)}
            icon={<Ico><rect x="3" y="6" width="18" height="13" rx="2.5" /><path d="M3 10h18M16.5 14.5h1.5" /></Ico>}
          />
          <Stat
            tone="asset"
            label="총 자산"
            value={won(sim.totalAsset)}
            sub={dayOver(assetRate)}
            icon={<Ico><path d="M4 16.5 9 11l3.5 3.5L20 7" /><path d="M15.5 7H20v4.5" /></Ico>}
          />
          <Stat
            tone="stock"
            label="주식 평가금액"
            value={won(sim.stockValue)}
            sub={dayOver(stockRate)}
            icon={<Ico><path d="M5 19V11M12 19V5M19 19v-5" /></Ico>}
          />
          <Stat
            tone={sim.pnl > 0 ? 'up' : sim.pnl < 0 ? 'down' : 'flat'}
            label="누적 손익"
            value={`${sim.pnl > 0 ? '+' : ''}${won(sim.pnl)}`}
            sub={<span className={signOf(sim.pnl)}>{rate(sim.pnlRate)}</span>}
            icon={<Ico><path d="M12 19V5M12 5l-5 5M12 5l5 5" /></Ico>}
          />
        </section>

        <div className="sp-body">
          {/* ── 왼쪽 · 포트폴리오 ────────────────────────
              접지 않는다. 도넛은 주문을 정하는 동안 늘 옆에 있어야 하는 그림이다 —
              "얼마나 들어가 있나" 를 보고 다음 주문을 정한다. 접어 두면 그때마다
              펴야 하고, 펴 두면 접이식일 이유가 없다. */}
          <section className="sp-card sp-pie" aria-label="포트폴리오">
            <h2>
              <span className="sp-h-ico t-pie" aria-hidden="true">
                <Ico size={15}><circle cx="12" cy="12" r="8" /><path d="M12 4v8h8" /></Ico>
              </span>
              포트폴리오
              {/* 도넛은 지금 비중만 말한다. 언제 얼마에 샀는지는 매매일지에 있다 */}
              <Link className="sp-h-link" to={`/sim/${seasonId}/trades`}>매매일지</Link>
            </h2>
            {/* 보유가 없어도 그린다. 전액 현금이면 회색 원 하나인데, 그것도 "아직
                아무것도 안 넣었다" 를 말하는 그림이다 — 글자 두 줄만 남기면 카드가
                비어 보이고, 사고 나서야 원이 생기면 화면이 튄다. */}
            {/* 좁은 칸이라 셋까지만 펴 둔다. 더 있으면 "더 보기" 로 넘긴다 —
                목록이 길어지면 이 카드가 줄 높이를 정해 버려 차트 아래가 빈다. */}
            <PortfolioDonut
              slices={slices}
              total={sim.totalAsset}
              onPick={setSelected}
              maxRows={3}
            />
            {sim.positions.length === 0 && (
              <p className="sp-empty">
                아직 보유한 종목이 없습니다.
                <small>종목을 사면 여기에 비중이 쌓입니다</small>
              </p>
            )}
          </section>

          {/* ── 가운데 · 차트. 주문 패널 바로 옆이다 ────── */}
          <section className="sp-chart-card" aria-label="시세">
            {/* 이름·가격·등락을 한 줄에 둔다. 프로토타입과 같은 자리다 —
                가격을 아랫줄로 내리면 종목을 바꿨을 때 눈이 두 번 움직인다. */}
            <div className="sp-ticker">
              <div className="sp-tname">
                {/* 종목 이름이 곧 고르기 버튼이다. 옆에 버튼을 따로 두면 차트 머리에
                    상자가 하나 더 서고, 주문 카드로 내리면 무엇을 보는 중인지와
                    무엇을 사는지가 갈라진다. HTS 들이 이름을 눌러 바꾼다. */}
                <button type="button" className="sp-tpick" onClick={() => setPicking(true)}>
                  <b>{ticker?.displayName ?? '종목을 고르세요'}</b>
                  <i aria-hidden="true">⌄</i>
                </button>
                {ticker?.sector && <span className="sp-sector">{ticker.sector}</span>}
                <strong className="num">{price === null ? '—' : won(price)}</strong>
                {diff !== null && diffRate !== null && (
                  <span className={`sp-diff num ${signOf(diff)}`}>
                    {diff > 0 ? '▲' : diff < 0 ? '▼' : ''}
                    {Math.abs(diff).toLocaleString('ko-KR')} ({rate(diffRate)})
                  </span>
                )}
              </div>
              <div className="sp-tools">
                {/* 지표를 접어 둔다. 버튼 다섯 개를 늘 펴 두면 차트 위 두 줄을 먹는데,
                    지표를 바꾸는 일은 종목을 바꾸는 것보다도 드물다. */}
                <div className="sp-ind-wrap" ref={indRef}>
                  <button
                    type="button"
                    className={indOpen ? 'sp-indbtn on' : 'sp-indbtn'}
                    aria-expanded={indOpen}
                    onClick={() => setIndOpen((v) => !v)}
                  >
                    지표
                    <span className="num">{on.length}</span>
                    <i aria-hidden="true">⌄</i>
                  </button>
                  {indOpen && (
                    <div className="sp-indpop" role="group" aria-label="보조지표">
                      {INDICATORS.map((k) => (
                        <button
                          key={k}
                          type="button"
                          aria-pressed={on.includes(k)}
                          className={on.includes(k) ? 'on' : undefined}
                          onClick={() =>
                            setOn((cur) =>
                              cur.includes(k) ? cur.filter((x) => x !== k) : [...cur, k])
                          }
                        >
                          <em aria-hidden="true" />
                          {k === 'BOLL' ? '볼린저밴드' : k}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </div>

            {/* 오늘 하루의 네 값. 종가만 보면 같은 53,900 원이라도 하루가 완전히 다르다 —
                55,400 에서 밀려 내려온 것과 53,200 에서 올라온 것은 다음 판단이 반대다.
                캔들에 마우스를 올려야만 보이던 걸 오늘 것만은 늘 보이게 둔다. */}
            {today && (
              <dl className="sp-ohlc num" aria-label="오늘 시세">
                <div><dt>시가</dt><dd>{today.open == null ? '—' : cut(today.open)}</dd></div>
                <div><dt>고가</dt><dd>{today.high == null ? '—' : cut(today.high)}</dd></div>
                <div><dt>저가</dt><dd>{today.low == null ? '—' : cut(today.low)}</dd></div>
                <div>
                  <dt>거래량</dt>
                  <dd>{today.volume == null ? '—' : today.volume.toLocaleString('ko-KR')}</dd>
                </div>
              </dl>
            )}

            {priceError && <ErrorState error={priceError} onRetry={() => setPriceError(null)} inline />}
            {!priceError && bars === undefined && <p className="sp-state">봉을 불러오는 중…</p>}
            {bars !== undefined && (
              <CandleChart
                candles={bars} currentDay={sim.day} indicators={on} height={chartH}
                view={view} onViewChange={setView}
              />
            )}

            {/* RSI 는 축이 0~100 이라 캔들 위에 얹지 못한다 — 아래에 칸을 따로 낸다.
                가로 좌표를 캔들과 공유하므로 같은 봉이 같은 x 에 선다. */}
            {bars !== undefined &&
              PANES.filter((k) => on.includes(k)).map((k) => (
                <IndicatorPane key={k} candles={bars} currentDay={sim.day} kind={k} view={view} />
              ))}
          </section>

          {/* ── 오른쪽 · 주문 ───────────────────────────── */}
          <div className="sp-side">
            <section className="sp-card sp-order" aria-label="주문">
              <div className="sp-sides" role="tablist">
                <button type="button" role="tab" aria-selected={side === 'BUY'}
                        className={side === 'BUY' ? 'buy on' : 'buy'}
                        onClick={() => { setSide('BUY'); setQty(0); sim.clearReject() }}>
                  매수
                </button>
                <button type="button" role="tab" aria-selected={side === 'SELL'}
                        className={side === 'SELL' ? 'sell on' : 'sell'}
                        onClick={() => { setSide('SELL'); setQty(0); sim.clearReject() }}>
                  매도
                </button>
              </div>

              {/* 체결 방식을 한 줄로 밝혀 둔다. 프로토타입에는 시장가·지정가 선택이
                  있었지만 이 게임의 체결은 게임일 종가 하나뿐이라 고를 게 없다 —
                  고를 수 없는 걸 버튼으로 두면 눌러 보게 된다. */}
              <div className="sp-fixed">
                <span>주문 유형</span>
                <b>종가 단일가</b>
              </div>

              <div className="sp-qty-row">
                <span>주문 수량</span>
                <div className="sp-stepper">
                  <button type="button" aria-label="1주 줄이기"
                          disabled={qty <= 0}
                          onClick={() => changeQty(qty - 1)}>−</button>
                  {/* type=number 를 쓰지 않는다 — 숫자가 아닌 글자를 치면 브라우저가 그것을
                      화면에 둔 채 value 로는 빈 문자열을 줘서, changeQty 가 한 번도 돌지
                      않고 글자가 쌓인다(환경 설정 구독료에서 실제로 나왔다). */}
                  <input
                    type="text"
                    inputMode="numeric"
                    maxLength={9}
                    value={qty || ''}
                    onChange={(e) => changeQty(Number(e.target.value.replace(/[^0-9]/g, '')))}
                    aria-label="주문 수량"
                  />
                  <i>주</i>
                  <button type="button" aria-label="1주 늘리기"
                          disabled={qty >= maxQty}
                          onClick={() => changeQty(qty + 1)}>+</button>
                </div>
              </div>

              <div className="sp-steps">
                {QTY_STEPS.map((n) => (
                  <button key={n} type="button" disabled={qty >= maxQty}
                          onClick={() => changeQty(qty + n)}>
                    +{n.toLocaleString('ko-KR')}
                  </button>
                ))}
                <button type="button" disabled={maxQty <= 0} onClick={() => setQty(maxQty)}>
                  최대
                </button>
                <button type="button" disabled={qty <= 0} onClick={() => setQty(0)}>
                  초기화
                </button>
              </div>

              {/* 예상 금액은 프론트 계산이다(명세 §모의투자). 종가 단일가라 곱하기 하나다.
                  비용 줄이 붙는다(2026-09-23) — 수수료 0.015%(양쪽) · 거래세 0.15%(매도만).
                  굵게 적는 마지막 줄을 대금이 아니라 **실제로 오가는 돈**으로 둔 이유: 매수에서
                  대금만 크게 보이면 그 값을 예수금에 맞춰 넣었을 때 수수료만큼 모자라 튕긴다.
                  "매수 가능" 수량도 수수료를 포함해 구한다(maxBuyQty). */}
              <dl className="sp-amount">
                <div>
                  <dt>{side === 'BUY' ? '매수 가능' : '보유 수량'}</dt>
                  <dd className="num">
                    {maxQty.toLocaleString('ko-KR')}주
                    {side === 'SELL' && held && <small>평균 {won(held.avgPrice)}</small>}
                  </dd>
                </div>
                <div>
                  <dt>주문 금액</dt>
                  <dd className="num">{won(amount)}</dd>
                </div>
                <div className="sp-fee">
                  <dt>수수료</dt>
                  <dd className="num">{won(fee)}</dd>
                </div>
                {/* 거래세는 매도에만 붙는다. 매수에서 "거래세 0원" 을 그리면 없는 비용을
                    있는 것처럼 세는 줄이 된다 */}
                {side === 'SELL' && (
                  <div className="sp-fee">
                    <dt>거래세</dt>
                    <dd className="num">{won(tax)}</dd>
                  </div>
                )}
                <div className="sp-total">
                  <dt>{side === 'BUY' ? '총 주문 금액' : '예상 수령액'}</dt>
                  <dd className="num">{won(settled)}</dd>
                </div>
              </dl>

              {sim.reject && (
                <p className="sp-reject" role="alert">{REJECT_TEXT[sim.reject]}</p>
              )}
              {filled && !sim.reject && (
                <p className="sp-filled" aria-live="polite">{filled}</p>
              )}

              <button
                type="button"
                className={`sp-submit ${side === 'BUY' ? 'buy' : 'sell'}`}
                disabled={qty <= 0 || price === null || sim.pending}
                onClick={() => { void submit() }}
              >
                {/* 꺼진 버튼은 왜 꺼졌는지 말해야 한다. "매수 주문" 이 회색으로 있으면
                    누를 수 없는 이유가 수량인지 가격인지 서버인지 알 수 없다. */}
                {sim.isDone
                  ? '끝난 시즌입니다'
                  : sim.pending
                  ? '주문 중…'
                  : price === null
                    ? '가격을 불러오는 중…'
                    : maxQty <= 0
                      /* 상한이 0 이면 "수량을 입력하세요" 는 할 수 없는 일을 시키는 말이다 —
                         못 넣는 이유를 말한다(2026-09-22 QA). 매수는 현금, 매도는 보유량이다. */
                      ? (side === 'BUY' ? '현금이 한 주 값보다 적습니다' : '팔 수 있는 수량이 없습니다')
                      : qty <= 0
                      ? '수량을 입력하세요'
                      : side === 'BUY' ? '매수 주문' : '매도 주문'}
              </button>

              {/* 마지막 날에는 버튼이 결과로 바뀐다. 전에는 "마지막 게임일입니다" 라고
                  꺼진 버튼만 남아 흐름이 거기서 끊겼다 — 다 돌린 사람에게 다음 걸음이
                  없으면 얼마 벌었는지도 못 본다.
                  링크가 아니라 확인창이다(2026-09-09). 결과를 보는 것은 곧 종료이고, 종료가
                  성적표와 AI 복기를 만들어 저장하는 순간이라 사람이 한 번 확인한다. */}
              {/* 이미 끝난 회차는 확인창을 거칠 것이 없다 — 성적표는 저장돼 있다 */}
              {sim.isDone ? (
                <Link className="sp-advance is-done" to={`/sim/${seasonId}/result`}>
                  결과 보기
                  <em aria-hidden="true">›</em>
                </Link>
              ) : sim.isLastDay ? (
                <button
                  type="button"
                  className="sp-advance is-done"
                  onClick={() => finishDialog.current?.showModal()}
                >
                  결과 보기
                  <em aria-hidden="true">›</em>
                </button>
              ) : (
                <button
                  type="button"
                  className="sp-advance"
                  disabled={sim.pending}
                  onClick={() => { void sim.advance(); setFilled(null) }}
                >
                  <em aria-hidden="true">▶</em>
                  {sim.pending ? '진행 중…' : '다음 영업일 진행'}
                </button>
              )}
              {/* 건너뛰고 종료(2026-09-19, QA 09-18 "30일을 일일이 다 눌러야 함"). 남은 날에 더 할
                  일이 없는 사람이 "다음 영업일" 을 수십 번 누르지 않고 끝낸다. 바로 끝내지 않고
                  마지막 날과 **같은 확인창**을 거친다 — 되돌릴 수 없고, 보유분이 어느 날 값으로
                  평가되는지 알고 눌러야 한다. 대회는 "다음 영업일" 과 마찬가지로 서버가 막는다(409). */}
              {!sim.isLastDay && !sim.isDone && (
                <button
                  type="button"
                  className="sp-skip"
                  disabled={sim.pending}
                  onClick={() => finishDialog.current?.showModal()}
                >
                  남은 {left}일 건너뛰고 종료
                </button>
              )}
              <p className="sp-note">
                {sim.isDone
                  ? '끝난 시즌입니다. 주문과 진행은 닫혔습니다.'
                  : sim.isLastDay
                  ? '마지막 게임일입니다. 진행은 여기까지입니다.'
                  : '주문은 그 게임일 종가로 한 번에 체결돼요.'}
              </p>
              {/* 요율은 **금액 줄이 아니라 여기** 적는다(2026-09-23). 위에서 알고 싶은 것은
                  "이번 주문에 얼마" 이고, 요율은 그 숫자가 어디서 나왔는지를 뒤에서 받쳐
                  주는 값이라 한 번만 조용히 있으면 된다 — 줄마다 붙이면 금액을 읽는 데
                  방해가 된다.

                  **이 요율은 우리가 정한 값이다.** 실제 증권사와 같지 않다(증권사는
                  회사·계좌마다 다르고 유관기관 제비용도 따로 붙는다). 시즌 참가 화면과
                  매매일지에도 같은 값이 적혀 있고, 서버는 application.yaml 의
                  app.season.fees 한 곳에서 읽는다 — 셋이 같은 수를 말해야 한다. */}
              {!sim.isDone && (
                <p className="sp-rates">
                  수수료 <b>0.015%</b> <span>매수·매도</span>
                  <i aria-hidden="true">·</i>
                  거래세 <b>0.15%</b> <span>매도만</span>
                </p>
              )}

              {/* 종료 확인. 네이티브 dialog — 포커스 가둠·Esc·배경막을 브라우저가 준다.
                  만드는 동안(pending)은 Esc 로도 못 닫는다 — 닫혀도 서버는 계속 돌아
                  결과가 화면 없이 굳어 버린다. */}
              <dialog
                className="sp-dialog"
                ref={finishDialog}
                aria-labelledby="sp-finish-title"
                onCancel={(e) => { if (sim.pending) e.preventDefault() }}
              >
                <h2 id="sp-finish-title">
                  {sim.isLastDay
                    ? '모의투자를 종료하고 결과를 볼까요?'
                    : `남은 ${left}일을 건너뛰고 종료할까요?`}
                </h2>
                {/* 건너뛸 때만 하는 말. 남은 날이 어떻게 처리되고 보유분이 어느 날 값으로
                    평가되는지 — 이걸 모르고 누르면 "오늘 값으로 끝나는 줄 알았다" 가 된다. */}
                {!sim.isLastDay && (
                  <p>
                    남은 게임일은 <b>주문 없이 지나가요.</b>
                    <br />
                    보유 종목은 <b>마지막 게임일 종가</b>로 평가해요.
                  </p>
                )}
                <p>
                  종료하면 더 이상 주문도 진행도 할 수 없습니다.
                  <br />
                  성적표와 AI 복기를 기록에 남겨요.
                </p>
                <small>AI 복기는 몇십 초 걸릴 수 있어요.</small>
                <div className="sp-dialog-actions">
                  <button
                    type="button" className="sp-dialog-btn" disabled={sim.pending}
                    onClick={() => finishDialog.current?.close()}
                  >
                    취소
                  </button>
                  <button
                    type="button" className="sp-dialog-btn solid" disabled={sim.pending}
                    onClick={() => { void finishAndGo() }}
                  >
                    {sim.pending ? (
                      <span className="waiting">
                        <span className="spinner" aria-hidden="true" />성적표·복기 만드는 중…
                      </span>
                    ) : '종료하고 결과 보기'}
                  </button>
                </div>
              </dialog>
            </section>
          </div>
        </div>

        {/* ── 아래 두 칸 (접이식) ──────────────────────────
            빈 상자 둘이 펴져 있으면 화면을 차지하기만 하므로, 읽을 것이 없으면
            접어 둔다 — 접힌 줄에 무엇이 올 자리인지 적어 두면 그것만으로 뜻이 전해진다.

            읽을 것이 생기면 저절로 편다(useDayPanel). 이 칸들이 주문 카드보다
            아래라, 차트 보고 주문하면 시선이 거기서 끝나 아래로 안 내려간다.
            접어 둔 사람에게는 그 게임일 동안 다시 펴지 않고 점만 찍는다. */}
        <div className="sp-later">
          {/* **펼칠 것이 있을 때만 접이로 둔다(2026-09-23).** 접힌 줄에는 한 문장
              요약, 펼치면 주요 사건 세 줄이다. body 가 비면 꺾쇠를 달아 봐야 빈 상자만
              열리므로, 펼칠 줄이 있으면 details, 없으면 같은 모양의 한 줄이다. */}
          {hintLines.length > 0 ? (
            <details
              className="sp-fold" open={hintPanel.open}
              onToggle={(e) => hintPanel.toggle((e.currentTarget as HTMLDetailsElement).open)}
            >
              <summary>
                {hintPanel.dot && <i className="sp-dot" aria-label="읽지 않은 요약" />}
                <span className="sp-h-ico t-ai" aria-hidden="true">
                  <Ico size={15}><path d="m12 4 1.6 3.6L17 9.2l-3.4 1.6L12 14.4l-1.6-3.6L7 9.2l3.4-1.6z" /><path d="M18 15.5 18.8 17l1.7.8-1.7.8-.8 1.7-.8-1.7-1.7-.8 1.7-.8z" /></Ico>
                </span>
                AI 한줄 요약
                <em>{todayHint?.title}</em>
                <i aria-hidden="true">⌄</i>
              </summary>
              <div className="sp-fold-body">
                <ul className="sp-hint">
                  {hintLines.map((line) => <li key={line}>{line}</li>)}
                </ul>
              </div>
            </details>
          ) : (
            <p className="sp-fold sp-fold-line">
              <span className="sp-h-ico t-ai" aria-hidden="true">
                <Ico size={15}><path d="m12 4 1.6 3.6L17 9.2l-3.4 1.6L12 14.4l-1.6-3.6L7 9.2l3.4-1.6z" /><path d="M18 15.5 18.8 17l1.7.8-1.7.8-.8 1.7-.8-1.7-1.7-.8 1.7-.8z" /></Ico>
              </span>
              AI 한줄 요약
              <em>{hints.loading ? '불러오는 중…' : (todayHint?.title ?? '요약 없음')}</em>
            </p>
          )}

          {/* 소식도 같은 규칙이다 — 기사가 없으면 펼쳐도 "여기까지 전해진 기사가
              없어요" 한 줄뿐이라, 접힌 줄의 "소식 없음" 과 같은 말을
              두 번 하는 셈이었다. */}
          {newsItems.length > 0 ? (
            <details
              className="sp-fold" open={newsPanel.open}
              onToggle={(e) => newsPanel.toggle((e.currentTarget as HTMLDetailsElement).open)}
            >
              <summary>
                {newsPanel.dot && <i className="sp-dot" aria-label="읽지 않은 소식" />}
                <span className="sp-h-ico t-news" aria-hidden="true">
                  <Ico size={15}><path d="M5 4h11v16H5z" /><path d="M16 8h3v9.5a2.5 2.5 0 0 1-5 0" /><path d="M8 8h5M8 11.5h5M8 15h3" /></Ico>
                </span>
                당시 주요 이슈
                <em>{`${ticker?.displayName ?? ''} 소식 ${newsItems.length}건`}</em>
                <i aria-hidden="true">⌄</i>
              </summary>
              <div className="sp-fold-body">
                <ul className="sp-news">
                  {newsItems.map((n) => (
                    <li key={n.link ?? `${n.gameDay}${n.title}`}>
                      <div className="sp-news-head">
                        <span className="sp-news-day">DAY {n.gameDay}</span>
                        {n.kind !== 'NEWS' && (
                          <span className="sp-news-kind">{n.kind === 'IR' ? '기업설명회' : '공시'}</span>
                        )}
                        {n.link ? (
                          <a href={n.link} target="_blank" rel="noreferrer noopener">{n.title}</a>
                        ) : (
                          <b>{n.title}</b>
                        )}
                      </div>
                      {n.body && <p>{n.body}</p>}
                    </li>
                  ))}
                </ul>
              </div>
            </details>
          ) : (
            <p className="sp-fold sp-fold-line">
              <span className="sp-h-ico t-news" aria-hidden="true">
                <Ico size={15}><path d="M5 4h11v16H5z" /><path d="M16 8h3v9.5a2.5 2.5 0 0 1-5 0" /><path d="M8 8h5M8 11.5h5M8 15h3" /></Ico>
              </span>
              당시 주요 이슈
              <em>{news.loading ? '불러오는 중…' : '소식 없음'}</em>
            </p>
          )}
        </div>

        {sim.loadError && <ErrorState error={sim.loadError} onRetry={() => { void sim.reload() }} inline />}
        {tickerError && <ErrorState error={tickerError} onRetry={() => setTickerError(null)} inline />}

        {picking && (
          <TickerPickerModal
            tickers={tickers}
            selectedId={selected}
            heldQty={heldQty}
            priceOf={sim.priceOf}
            onPick={setSelected}
            onClose={() => setPicking(false)}
          />
        )}

        {/* 화면 안내. 종목 고르기 창이 열려 있으면 띄우지 않는다 —
            그 창이 화면 절반을 덮어, 밝힐 칸이 그 아래로 숨는다 */}
        {coach && !picking && <PlayCoachMarks onClose={() => setCoach(false)} lengthDays={s.lengthDays} />}
      </div>
    </main>
  )
}
