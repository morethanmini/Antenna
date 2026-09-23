/* B-03 첫 번째 안쪽 페이지 — 종목을 파악하는 자리.

   배치 의도
   전폭 카드를 세로로 쌓으면 스크롤을 내려야 내용이 나온다. 그래서 12칸 격자에
   카드를 나란히 놓고, 관련 있는 원천을 한 카드로 묶어 줄 수를 줄였다.

     차트(5)          │ 투자 포인트(7)
     종목 정보(5)      │ 예측 현황(7)               ← 밸류 + 재무 + 개요 / 예측 분포
     같은 업종(12)
     공시 · 뉴스(12)

   재무가 종목 정보 안으로 들어온 것은 2026-09-17 이다. 밸류에이션 네 값만으로는
   카드 아래가 통째로 비어 옆 칸과 바닥이 어긋났고, 바로 옆에 선 재무 카드는
   같은 "이 회사의 숫자" 인데 카드가 갈려 두 번 찾아봐야 했다. 순서는 **밸류 →
   재무 → 회사 개요** 다 — 투자 판단에 먼저 쓰는 것이 위로 온다. 대표·설립·주소는
   한 번 보고 마는 값이라 맨 아래다.

   투자 포인트가 첫 줄로 올라온 것은 2026-09-16 이다. 그 자리에 있던 예측 현황은
   서비스 초기에 걸린 예측이 거의 없어, 화면에서 가장 먼저 눈에 들어오는 칸이
   빈 채로 남았다. 둘 다 span 7 이라 자리만 맞바꿨다.

   카드 안에서 스크롤하지 않는다. 펼친 카드는 내용을 다 보여준다 — 높이를 묶어
   두면 펼쳤는데도 잘려 보이고 막대가 카드마다 생긴다. 한 줄의 바닥은 CSS 의
   stretch 가 맞춰 주고, 짧은 카드는 아래가 빈다.

   카드는 모두 접힌다. 다만 **처음에는 전부 펼쳐 둔다**(DEFAULT_OPEN). 접어 두면
   아래 절반이 제목 줄만 남아 "아직 아무것도 없는 화면" 으로 읽혔다 — 접기는
   읽고 나서 치우는 수단이지 처음 만나는 상태가 아니다. 어느 것을 접었는지는
   이 브라우저에 남아, 다른 종목으로 옮겨도 접어 둔 대로 열린다.

   카드를 합쳤어도 원천마다 BlockState 를 따로 쓴다. /valuation 이 죽어도 같은
   카드의 기업 개요는 그대로 보인다 — "블록 단위로 로딩·실패를 독립 처리" (§4 B-03).

   투자 포인트에서 고른 id 는 두 번째 안쪽 페이지(예측 등록)의 근거로 넘어간다.
   이 인계에는 API 가 없어 부모가 상태로 들고 있다 — 그래서 선택 상태를 props 로 받는다. */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import CloseChart from '../CloseChart'
import Block, { BlockState, Panel } from './Block'
import PredictionDepth from './PredictionDepth'
import PointPicker from './PointPicker'
import { useBlock } from '../../api/useBlock'
import { useCursorList } from '../../api/useCursorList'
import {
  POINT_KINDS, POINTS_LOADING, SOURCE_LABEL,
  getFinancials, getPeers, getPoints,
  getPrices, getProfile, getValuation,
} from '../../api/stockDetail'
import type { StockDocument, StockSummary } from '../../api/stockDetail'

/* 차트 기간. 서버는 from·to 를 받으므로 여기서 from 을 만들어 넘긴다. */
const RANGES = [
  { key: '1M', label: '1개월', days: 30 },
  { key: '3M', label: '3개월', days: 92 },
  { key: '6M', label: '6개월', days: 183 },
  { key: '1Y', label: '1년', days: 365 },
] as const
type RangeKey = (typeof RANGES)[number]['key']

/* 같은 업종 한 쪽에 담는 줄 수. 서버가 같은 업종 전 종목을 한 번에 주는데
   (Pageable 이 없다) 업종에 따라 백 줄이 넘어 카드가 세로로 한없이 길어진다.
   옆에 선 재무 카드가 3행 고정이라 그 높이에 맞춘 값이다. */
const PEERS_PER_PAGE = 8

/* 이 수보다 예측이 적으면 비율을 흐리게 그린다. 5건은 한 명이 몇 번만 더 걸어도
   비율이 통째로 뒤집히는 구간이라, 또렷하게 보이면 없는 신호를 읽게 된다. */

/* 접었다 펼치는 칸들. 전부 펼친 채로 시작한다(2026-09-16).

   재무·같은 업종·공시 셋을 접어 두고 있었는데, 그 셋이 화면 아래 절반이라
   처음 들어온 사람에게는 제목 줄만 세 개 놓인 빈 화면으로 보였다. 접힌 칸은
   useBlock 의 enabled 가 꺼져 요청도 나가지 않으므로, 펼침이 기본이 되면
   첫 진입에 그만큼 요청이 더 나간다 — 의도한 값이다. */
const DEFAULT_OPEN = {
  chart: true,
  sentiment: true,
  profile: true,
  points: true,
  peers: true,
  documents: true,
}
type SectionKey = keyof typeof DEFAULT_OPEN

/* 접힘 상태는 이 브라우저에만 남긴다. 종목을 옮겨도 "내가 접어 둔 대로" 가
   유지돼야 접는 의미가 있다. 저장이 막힌 환경(사생활 보호 모드 등)에서는
   조용히 기본값으로 돌아간다 — 화면이 멈추지 않는 게 우선이다. */
/* 기본값을 바꿀 때 키도 함께 올린다. 안 올리면 한 번이라도 들른 사람의
   브라우저에 옛 false 가 남아 새 기본값이 영영 안 먹는다. */
const OPEN_STORE_KEY = 'antena.b03.sections.v3'

function readOpen(): Record<SectionKey, boolean> {
  try {
    const raw = localStorage.getItem(OPEN_STORE_KEY)
    if (!raw) return DEFAULT_OPEN
    return { ...DEFAULT_OPEN, ...(JSON.parse(raw) as Partial<Record<SectionKey, boolean>>) }
  } catch {
    return DEFAULT_OPEN
  }
}

const num = (n: number | null, unit = '', digits = 0) =>
  n === null
    ? '—'
    : `${n.toLocaleString('ko-KR', { minimumFractionDigits: digits, maximumFractionDigits: digits })}${unit}`

/* 서버는 금액을 **원 단위**로 준다(억원이 아니다). 원 그대로 찍으면 자릿수가
   열 자리를 넘어 표가 읽히지 않으므로 조·억으로 줄인다. */
const money = (n: number | null) => {
  if (n === null) return '—'
  const abs = Math.abs(n)
  if (abs >= 1e12) return `${(n / 1e12).toFixed(1)}조`
  if (abs >= 1e8) return `${Math.round(n / 1e8).toLocaleString('ko-KR')}억`
  return n.toLocaleString('ko-KR')
}

const day = (iso: string) => iso.slice(0, 10).replace(/-/g, '.').slice(2)

/* DART 의 hm_url 은 스킴 없이 온다("www.samsung.com/sec"). 그대로 href 에 넣으면 **상대 경로**가 되어
   /stocks/www.samsung.com/sec 로 간다 — 삼성전자는 메인으로, SK하이닉스는 "종목을 찾을 수 없음" 으로
   떨어졌다(QA 09-18). 스킴이 없으면 https 를 붙인다. javascript: 같은 다른 스킴은 링크로 쓰지 않는다. */
const homepageHref = (raw: string) => {
  const url = raw.trim()
  if (/^https?:\/\//i.test(url)) return url
  // 콜론 뒤가 숫자면 스킴이 아니라 포트다(host:8080)
  return /^[a-z][a-z0-9+.-]*:(?!\d)/i.test(url) ? undefined : `https://${url.replace(/^\/+/, '')}`
}

/* 종목 정보 카드의 용어 설명.

   PER·ROE 는 아는 사람에게는 군더더기라 카드 본문에 풀어 쓸 수 없고, 모르는
   사람에게는 카드 전체가 읽히지 않는 숫자 덩어리다. 그래서 접어 두고, 펼치면
   카드 위에 겹쳐 띄운다 — 카드 안에 밀어 넣으면 펼칠 때마다 아래 값들이
   저만치 밀려 내려가 방금 보던 자리를 잃는다. */
const TERMS: { term: string; body: string }[] = [
  { term: 'PER', body: '주가가 순이익의 몇 배인가. 낮을수록 이익에 비해 싸다는 뜻이지만, 이익이 곧 줄 것으로 보면 낮게 머문다.' },
  { term: 'PBR', body: '주가가 순자산의 몇 배인가. 1배면 회사를 지금 정리했을 때 받을 값과 주가가 같다는 뜻이다.' },
  { term: 'ROE', body: '자기자본으로 한 해에 얼마를 벌었나. 높을수록 밑천을 잘 굴린 것이다.' },
  { term: '부채비율', body: '자기자본 대비 빚의 비율. 높을수록 이자 부담이 크고 업황이 꺾일 때 흔들린다.' },
  { term: '매출액', body: '한 해에 판 금액 전부. 여기서 원가와 비용을 빼기 전 값이다.' },
  { term: '영업이익', body: '매출에서 원가와 판매·관리비를 뺀 것. 본업으로 얼마를 남겼는지를 본다.' },
  { term: '순이익', body: '영업이익에서 이자·세금까지 다 빼고 최종으로 남은 것.' },
]

/* 문장이 부를 이름의 우선순위. 화면에 뜬 것 중 앞의 둘을 부른다 — PER·ROE 가
   가장 자주 인용되는 둘이라 앞에 둔다. 이 종목에 그 둘이 없으면 다음 것으로
   내려간다(지표는 종목마다 빈다 — 값이 null 이면 화면에 "—" 로 찍힌다). */
const TERM_PRIORITY = ['PER', 'ROE', 'PBR', '부채비율', '매출액', '영업이익', '순이익']

/* 부를 이름으로 문장을 만든다. 둘이면 둘, 하나면 하나, 없으면 이름 없이.
   **이름을 고정으로 박아 두면 안 된다** — 그 지표가 없는 종목에서 "PER? ROE?" 라고
   물어 놓고 정작 화면에는 그 줄이 "—" 로 비어 있어, 어디를 보라는 말인지 못 찾는다. */
function saying(terms: string[]) {
  const pick = TERM_PRIORITY.filter((t) => terms.includes(t)).slice(0, 2)
  if (pick.length === 0) return '숫자가 낯설다면, 제가 한 줄씩 풀어 드릴게요.'
  return `${pick.map((t) => `${t}?`).join(' ')} 제가 한 줄씩 풀어 드릴게요.`
}

function TermsHelp({ terms }: { terms: string[] }) {
  const [shown, setShown] = useState(false)
  const btnRef = useRef<HTMLButtonElement>(null)
  const popRef = useRef<HTMLDivElement>(null)

  /* 열리면 팝업으로 초점을 옮기고, Esc·바깥 누름으로 닫는다. 닫을 때는 누른
     버튼으로 초점을 돌려준다 — 안 돌려주면 키보드 사용자가 문서 맨 위로 튄다. */
  useEffect(() => {
    if (!shown) return
    popRef.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setShown(false) }
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node
      if (!popRef.current?.contains(t) && !btnRef.current?.contains(t)) setShown(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onDown)
    }
  }, [shown])

  const close = () => { setShown(false); btnRef.current?.focus() }

  return (
    <div className="sd-terms">
      <div className="sd-terms-say">
        <img
          className="sd-terms-face"
          src="/assets/character/face_ant/face-smile.webp"
          alt="" aria-hidden="true" width="34" height="34"
        />
        <p>{saying(terms)}</p>
        <button
          ref={btnRef}
          type="button"
          className="sd-terms-btn"
          aria-expanded={shown}
          onClick={() => (shown ? close() : setShown(true))}
        >
          {shown ? '닫기' : '용어 보기'}
        </button>
      </div>

      {shown && (
        <div
          ref={popRef}
          className="sd-terms-pop"
          role="dialog"
          aria-label="용어 설명"
          tabIndex={-1}
        >
          <dl>
            {TERMS.map((t) => (
              <div key={t.term}>
                <dt>{t.term}</dt>
                <dd>{t.body}</dd>
              </div>
            ))}
          </dl>
          <p className="sd-terms-foot">숫자는 DART 공시에서 가져온 연간 값입니다.</p>
        </div>
      )}
    </div>
  )
}

type Props = {
  code: string
  summary: StockSummary | null
  /** 예측 근거로 고른 포인트 id */
  picked: number[]
  onPick: (id: number) => void
  /** 예측 등록 탭으로 넘어간다 */
  onGoPredict: () => void
}

export default function InfoTab({ code, summary, picked, onPick, onGoPredict }: Props) {
  const [range, setRange] = useState<RangeKey>('3M')

  /* from 은 range 가 바뀔 때만 새로 만든다. 매 렌더 새 문자열이면
     useBlock 의 deps 가 계속 달라져 끝없이 다시 부른다. */
  const from = useMemo(() => {
    const days = RANGES.find((r) => r.key === range)?.days ?? 92
    const d = new Date()
    d.setDate(d.getDate() - days)
    return d.toISOString().slice(0, 10)
  }, [range])

  const [open, setOpen] = useState(readOpen)
  const toggle = useCallback((key: SectionKey) => {
    setOpen((prev) => {
      const next = { ...prev, [key]: !prev[key] }
      try { localStorage.setItem(OPEN_STORE_KEY, JSON.stringify(next)) } catch { /* 저장 불가 */ }
      return next
    })
  }, [])

  /* 접힌 칸은 부르지 않는다 — 펼치는 순간 그때 부른다 */
  const prices = useBlock(() => getPrices(code, from), [code, from], open.chart)
  const points = useBlock(() => getPoints(code), [code], open.points)
  const profile = useBlock(() => getProfile(code), [code], open.profile)
  const financials = useBlock(() => getFinancials(code), [code], open.profile)
  const valuation = useBlock(() => getValuation(code), [code], open.profile)
  const peers = useBlock(() => getPeers(code), [code], open.peers)

  /* 같은 업종 쪽 번호. 종목을 옮기면 1쪽으로 돌린다 — 3쪽을 보던 채로 다른
     종목에 들어가면 아무것도 없는 쪽이 열린다(SeasonPlay 의 barsDay 와 같은 방식). */
  const [peerPage, setPeerPage] = useState(0)
  const [peerCode, setPeerCode] = useState(code)
  if (peerCode !== code) {
    setPeerCode(code)
    setPeerPage(0)
  }
  const peerItems = peers.data?.items ?? []
  /* 용어 안내가 부를 이름. **값이 있는 것만 센다** — null 은 화면에 "—" 로 찍히므로
     그 이름을 부르면 가리킬 곳이 없다. 재무는 한 해라도 값이 있으면 그 칸이 선다. */
  const shownTerms = useMemo(() => {
    const out: string[] = []
    const v = valuation.data
    if (v) {
      if (v.per !== null) out.push('PER')
      if (v.pbr !== null) out.push('PBR')
      if (v.roe !== null) out.push('ROE')
      if (v.debtRatio !== null) out.push('부채비율')
    }
    const fin = financials.data?.items ?? []
    if (fin.some((f) => f.revenue !== null)) out.push('매출액')
    if (fin.some((f) => f.operatingProfit !== null)) out.push('영업이익')
    if (fin.some((f) => f.netIncome !== null)) out.push('순이익')
    return out
  }, [valuation.data, financials.data])

  const peerPages = Math.max(1, Math.ceil(peerItems.length / PEERS_PER_PAGE))
  const peerShown = peerItems.slice(peerPage * PEERS_PER_PAGE, (peerPage + 1) * PEERS_PER_PAGE)

  /* 서버가 열마다 배열을 따로 내려주므로 화면이 kind 로 다시 나누지 않는다.
     한 열이 비어도 나머지 두 열은 그대로 그린다. */
  const pointCount = POINT_KINDS.reduce((n, k) => n + (points.data?.[k].length ?? 0), 0)

  return (
    <div className="sd-grid">
      {/* ── 종가 차트 ─────────────────────────────────────── */}
      <Block
        title="종가 추이"
        note={summary?.asOf ? `${summary.asOf.replace(/-/g, '.')} 기준` : undefined}
        span={5}
        open={open.chart}
        onToggle={() => toggle('chart')}
        skeleton={286}
        loading={prices.loading}
        error={prices.error}
        onRetry={prices.retry}
        isEmpty={(prices.data?.items.length ?? 0) < 2}
        empty="이 구간에는 종가가 없어요. 거래가 정지됐을 수 있어요."
        actions={
          <div className="sd-range" role="group" aria-label="차트 기간">
            {RANGES.map((r) => (
              <button
                key={r.key}
                type="button"
                className={r.key === range ? 'is-on' : undefined}
                aria-pressed={r.key === range}
                onClick={() => setRange(r.key)}
              >
                {r.label}
              </button>
            ))}
          </div>
        }
      >
        <CloseChart series={prices.data?.items ?? []} label={summary?.name ?? ''} height={286} />
      </Block>

      {/* ── 투자 포인트 ───────────────────────────────────── */}
      <Block
        title="투자 포인트"
        note="고른 항목이 예측의 근거로 넘어갑니다"
        span={7}
        open={open.points}
        onToggle={() => toggle('points')}
        skeleton={230}
        loadingText={POINTS_LOADING}
        loading={points.loading}
        error={points.error}
        onRetry={points.retry}
        isEmpty={points.data !== null && pointCount === 0}
        empty="오늘 기준 투자 포인트를 만들지 못했습니다. 잠시 뒤 다시 열면 한 번 더 만들어 봅니다."
      >
        {/* 격자는 PointPicker 가 그린다 — 예측 등록(C-01) ③이 같은 것을 쓴다 */}
        <PointPicker points={points.data} picked={picked} onPick={onPick} />

        <p className="sd-point-foot">
          <span className="num">{`${picked.length}개 선택됨`}</span>
          <button type="button" className="sd-cta" onClick={onGoPredict}>
            이 근거로 예측하기
          </button>
        </p>
      </Block>

      {/* ── 종목 정보(밸류 · 재무 · 개요) ──────────────────
          원천이 셋이다. 하나가 죽어도 나머지 둘은 그대로 보인다 */}
      <Panel
        title="종목 정보"
        span={5}
        open={open.profile}
        onToggle={() => toggle('profile')}
      >
        {/* 숫자를 읽기 전에 말을 한 번 걸어 준다. PER·ROE 는 아는 사람에게는
            군더더기라 본문에 풀어 쓸 수 없고, 모르는 사람에게는 이 카드 전체가
            읽히지 않는다 — 접어 둔 설명을 개미가 들고 있게 했다 */}
        <TermsHelp terms={shownTerms} />

        {/* 업종 평균 PER/PBR · EPS · 배당수익률은 두지 않는다(§9.2) —
            /valuation 에 없고 /peers 는 나열이지 평균이 아니다 */}
        <h3 className="sd-sub">
          {'밸류에이션 '}
          {valuation.data?.basedOn.fiscal && (
            <span>{`${valuation.data.basedOn.fiscal} 연간${
              valuation.data.basedOn.fsDiv === 'OFS' ? ' 별도' : ' 연결'} 기준`}</span>
          )}
        </h3>
        <BlockState
          loading={valuation.loading}
          error={valuation.error}
          onRetry={valuation.retry}
          skeleton={54}
        >
          {valuation.data && (
            <>
              <dl className="sd-metrics">
                <div><dt>PER</dt><dd className="num">{num(valuation.data.per, '배', 1)}</dd></div>
                <div><dt>PBR</dt><dd className="num">{num(valuation.data.pbr, '배', 2)}</dd></div>
                <div><dt>ROE</dt><dd className="num">{num(valuation.data.roe, '%', 1)}</dd></div>
                <div><dt>부채비율</dt><dd className="num">{num(valuation.data.debtRatio, '%', 1)}</dd></div>
              </dl>
              {/* 값이 빈 이유를 서버가 문장으로 준다. 빈 칸만 남기면 고장으로 읽힌다 */}
              {valuation.data.basedOn.note && (
                <p className="sd-note-why">{valuation.data.basedOn.note}</p>
              )}
            </>
          )}
        </BlockState>

        <hr className="sd-rule" />

        <h3 className="sd-sub">
          {'재무 '}
          <span>{financials.data?.items[0]?.fsDiv === 'OFS' ? '별도 기준' : '연결 기준'}</span>
        </h3>
        <BlockState
          loading={financials.loading}
          error={financials.error}
          onRetry={financials.retry}
          skeleton={140}
          isEmpty={financials.data?.items.length === 0}
          empty="이 종목의 재무는 아직 수집되지 않았습니다."
        >
          <div className="sd-table-wrap">
            <table className="sd-table">
              <thead>
                <tr>
                  <th scope="col">기수</th>
                  <th scope="col">매출액</th>
                  <th scope="col">영업이익</th>
                  <th scope="col">순이익</th>
                </tr>
              </thead>
              <tbody>
                {financials.data?.items.map((f) => (
                  <tr key={`${f.year}-${f.quarter}`}>
                    {/* 4분기는 연간 보고서다. 그 밖은 분기를 밝혀야 반기·분기가 구분된다 */}
                    <th scope="row" className="num">
                      {f.quarter === 4 ? `${f.year}` : `${f.year} ${f.quarter}Q`}
                    </th>
                    <td className="num">{money(f.revenue)}</td>
                    <td className="num">{money(f.operatingProfit)}</td>
                    <td className="num">{money(f.netIncome)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </BlockState>

        <hr className="sd-rule" />

        {/* 서버에 소개 문단과 임직원 수가 없다. listedAt 은 DART 기업개황에 상장일이
            없어 항상 null 이라 자리를 만들지 않는다 — 영영 "—" 일 칸은 두지 않는다 */}
        <h3 className="sd-sub">기업 개요</h3>
        <BlockState
          loading={profile.loading}
          error={profile.error}
          onRetry={profile.retry}
          skeleton={120}
        >
          {profile.data && (
            <dl className="sd-facts">
              <div><dt>대표</dt><dd>{profile.data.ceo ?? '—'}</dd></div>
              <div><dt>설립</dt><dd className="num">{profile.data.establishedAt?.replace(/-/g, '.') ?? '—'}</dd></div>
              <div><dt>업종</dt><dd>{profile.data.industry ?? '—'}</dd></div>
              <div>
                <dt>홈페이지</dt>
                <dd>
                  {profile.data.homepage
                    ? <a href={homepageHref(profile.data.homepage)} target="_blank" rel="noreferrer noopener">바로가기</a>
                    : '—'}
                </dd>
              </div>
              {profile.data.address && (
                <div className="is-wide"><dt>주소</dt><dd>{profile.data.address}</dd></div>
              )}
            </dl>
          )}
        </BlockState>
      </Panel>

      {/* ── 예측 현황 ─────────────────────────────────────
          예측 탭 왼쪽의 호가창과 같은 표다(PredictionDepth). 서버에 없는
          GET /stocks/{code}/sentiment 를 기다리는 대신, 이미 있는 예측 분포
          (GET /stocks/{code}/predictions/distribution)를 그대로 쓴다.
          빈 상태(예측 0건 · 기준가 없음)를 어떻게 그릴지는 PredictionDepth 가 정한다. */}
      <PredictionDepth
        code={code}
        span={7}
        title="예측 현황"
        open={open.sentiment}
        onToggle={() => toggle('sentiment')}
      />

      {/* ── 같은 업종 ──────────────────────────────────────
          줄 수가 적어 높이를 고정하지 않는다. 고정하면 두 종목뿐인 업종에서
          카드 아래가 통째로 빈다. 재무가 종목 정보로 들어가면서 옆에 설 카드가
          없어져 전폭이 됐다(2026-09-17). */}
      <Block
        title="같은 업종"
        note={summary?.sector ?? undefined}
        span={12}
        open={open.peers}
        onToggle={() => toggle('peers')}
        skeleton={140}
        loading={peers.loading}
        error={peers.error}
        onRetry={peers.retry}
        isEmpty={peers.data?.items.length === 0}
        empty="비교할 같은 업종 종목이 없습니다."
      >
        <div className="sd-table-wrap">
          <table className="sd-table">
            <thead>
              {/* 시가총액은 응답에 있지만 그리지 않는다 — §9.2 의 "두지 않는 것" 이다 */}
              <tr>
                <th scope="col">종목</th>
                <th scope="col">전일 종가</th>
                <th scope="col">PER</th>
                <th scope="col">PBR</th>
              </tr>
            </thead>
            <tbody>
              {peerShown.map((p) => (
                <tr key={p.code}>
                  <th scope="row">
                    <Link to={`/stocks/${p.code}`}>{p.name}</Link>
                  </th>
                  <td className="num">{p.prevClose === null ? '—' : `${p.prevClose.toLocaleString('ko-KR')}원`}</td>
                  <td className="num">{num(p.per, '배', 1)}</td>
                  <td className="num">{num(p.pbr, '배', 2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* 쪽이 하나뿐이면 조작부를 그리지 않는다 — 누를 데 없는 화살표만 남는다 */}
        {peerPages > 1 && (
          <div className="sd-pager">
            <button
              type="button" aria-label="이전 쪽"
              disabled={peerPage === 0}
              onClick={() => setPeerPage((n) => Math.max(0, n - 1))}
            >
              ‹
            </button>
            <span className="num">{`${peerPage + 1} / ${peerPages}`}</span>
            <button
              type="button" aria-label="다음 쪽"
              disabled={peerPage >= peerPages - 1}
              onClick={() => setPeerPage((n) => Math.min(peerPages - 1, n + 1))}
            >
              ›
            </button>
          </div>
        )}
      </Block>

      {/* ── 공시·뉴스 ───────────────────────────────────────
          목록을 딴 컴포넌트로 뺀 이유는 커서 페이징 훅(useCursorList)에는 접힘
          스위치가 없어서다. Panel 은 접혀 있으면 children 을 그리지 않으므로,
          훅을 그 안쪽에 두면 펼칠 때 비로소 처음 부른다. */}
      <Panel
        title="공시 · 뉴스"
        note={`${summary?.name ?? '이 종목'} 관련 최신 소식을 한눈에 확인하세요`}
        span={12}
        open={open.documents}
        onToggle={() => toggle('documents')}
      >
        <DocumentList code={code} name={summary?.name ?? '이 종목'} />
      </Panel>
    </div>
  )
}

/* 언론사 이름. **서버가 안 준다** — 네이버 검색 API(news) 응답에 언론사 칸이
   없어(title·originallink·link·description·pubDate 다섯이 전부) 저장할 것도 없다.
   그래서 원문 주소의 호스트에서 되짚는다.

   표에 없는 곳은 호스트를 그대로 적는다. "언론사 미상" 같은 말보다 낫다 —
   zdnet.co.kr 이라고 적혀 있으면 어디서 온 글인지 알아볼 수는 있다. */
const PRESS: Record<string, string> = {
  'yna.co.kr': '연합뉴스', 'yonhapnewstv.co.kr': '연합뉴스TV',
  'hankyung.com': '한국경제', 'mk.co.kr': '매일경제', 'mt.co.kr': '머니투데이',
  'edaily.co.kr': '이데일리', 'sedaily.com': '서울경제', 'fnnews.com': '파이낸셜뉴스',
  'asiae.co.kr': '아시아경제', 'heraldcorp.com': '헤럴드경제', 'ajunews.com': '아주경제',
  'news1.kr': '뉴스1', 'newsis.com': '뉴시스', 'businesspost.co.kr': '비즈니스포스트',
  'chosun.com': '조선일보', 'joongang.co.kr': '중앙일보', 'donga.com': '동아일보',
  'hani.co.kr': '한겨레', 'khan.co.kr': '경향신문', 'kmib.co.kr': '국민일보',
  'seoul.co.kr': '서울신문', 'hankookilbo.com': '한국일보', 'segye.com': '세계일보',
  'etnews.com': '전자신문', 'zdnet.co.kr': '지디넷코리아', 'inews24.com': '아이뉴스24',
  'dt.co.kr': '디지털타임스', 'ddaily.co.kr': '디지털데일리', 'thelec.kr': '디일렉',
  'ytn.co.kr': 'YTN', 'sbs.co.kr': 'SBS', 'imbc.com': 'MBC', 'kbs.co.kr': 'KBS',
  'mbn.co.kr': 'MBN', 'wowtv.co.kr': '한국경제TV',
  'dart.fss.or.kr': '금융감독원 DART', 'fss.or.kr': '금융감독원',
}

function pressName(url: string): string | null {
  try {
    const host = new URL(url).hostname.replace(/^www\./, '')
    /* 뒤에서부터 맞춰 본다 — biz.chosun.com 도 chosun.com 으로 잡힌다 */
    for (const key of Object.keys(PRESS)) {
      if (host === key || host.endsWith(`.${key}`)) return PRESS[key]
    }
    return host
  } catch {
    return null
  }
}

/* 공시·뉴스의 두 갈래.

   서버는 한 목록에 섞어서 준다(최신순 커서). 화면에서 갈라 두 무리로 세우는
   이유는, 뉴스와 공시가 읽는 방식이 다르기 때문이다 — 뉴스는 훑어보는 것이고
   공시는 찾아보는 것이라, 한 줄로 섞으면 공시가 뉴스 사이에 묻힌다.

   갈래 인자(source)는 쓰지 않는다. 갈래마다 따로 부르면 커서를 둘 들고 있어야
   하고, 한쪽을 더 받는 동안 다른 쪽이 비어 보인다. 한 번 받아 화면에서 나눈다.

   IR 은 공시 쪽에 붙인다. 회사가 스스로 낸 자료라는 점에서 DART 와 같은 무리고,
   건수가 적어 제 칸을 주면 대개 비어 있다.

   **그림이 없다.** 네이버 검색 API 응답에 이미지 칸이 없어 수집·저장·응답 어디에도
   기사 사진이 없다(research_documents 에 컬럼 자체가 없다). 종목 로고를 대신
   넣어 봤지만 기사마다 같은 그림이 네 번 반복될 뿐이라 걷어냈다(2026-09-17).
   빈자리는 언론사 이름과 발췌로 채운다. */
const DOC_GROUPS = [
  {
    key: 'news' as const,
    label: '뉴스',
    /* 조사를 붙이지 않는다 — "삼성물산와" 처럼 받침 있는 이름에서 틀린다(09-21 QA) */
    desc: (name: string) => `${name} 관련 주요 뉴스를 모았어요.`,
    empty: '아직 들어온 뉴스가 없습니다.',
    icon: (
      <>
        <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4h9A1.5 1.5 0 0 1 16 5.5v13H5.5A1.5 1.5 0 0 1 4 17z" />
        <path d="M16 9h2.5A1.5 1.5 0 0 1 20 10.5v6a2 2 0 0 1-4 0" />
        <path d="M7 8h6M7 11.5h6M7 15h3.5" />
      </>
    ),
  },
  {
    key: 'filing' as const,
    label: '공시',
    desc: (name: string) => `${name}의 주요 공시와 투자자 안내를 모았어요.`,
    empty: '아직 들어온 공시가 없습니다.',
    icon: (
      <>
        <path d="M4 20h16M6 20V5a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v15M14 9h3a1 1 0 0 1 1 1v10" />
        <path d="M8.5 7.5h3M8.5 11h3M8.5 14.5h3" />
      </>
    ),
  },
]

/**
 * 공시·뉴스. 첫 쪽만 보여주던 것을 이어 받게 바꾸고(2026-09-16), 뉴스·공시 두
 * 무리의 가로 띠로 다시 짰다(2026-09-17).
 *
 * 서버는 처음부터 커서 페이징이었는데(cursor·size) 화면이 인자를 안 보내 최신
 * 20건에서 멈춰 있었다.
 */
function DocumentList({ code, name }: { code: string; name: string }) {
  /* 경로를 그대로 넘긴다 — 다른 목록 화면(커밋 원장 · 리포트 피드 · 지갑 원장)과
     같은 방식이고, cursor·size 를 훅이 알아서 실어 보낸다. */
  const list = useCursorList<StockDocument>(`/stocks/${code}/documents`)

  const grouped = {
    news: list.items.filter((d) => d.source === 'NEWS'),
    filing: list.items.filter((d) => d.source !== 'NEWS'),
  }

  return (
    <BlockState
      loading={list.loading && list.items.length === 0}
      error={list.error}
      onRetry={list.reload}
      skeleton={220}
      isEmpty={!list.loading && list.items.length === 0}
    >
      <div className="sd-doc-groups">
        {DOC_GROUPS.map((g) => (
          <DocumentRail
            key={g.key}
            group={g}
            name={name}
            items={grouped[g.key]}
            hasNext={list.hasNext}
            loading={list.loading}
            onMore={list.loadMore}
          />
        ))}
      </div>
    </BlockState>
  )
}

/**
 * 한 갈래의 가로 띠.
 *
 * **직접 만든 슬라이더가 아니라 그냥 가로 스크롤이다.** 칸을 transform 으로 밀면
 * 손가락·트랙패드로 밀 수 없고 탭 이동도 끊긴다. scroll-snap 을 얹으면 화살표로
 * 넘겨도 칸에 딱 맞게 서고, 그 밖의 조작은 브라우저가 알아서 한다.
 *
 * 점과 화살표는 스크롤 값에서 되읽는다 — 화면이 쪽 번호를 따로 들면, 손으로 민
 * 뒤에 그 값이 실제와 어긋난다.
 */
function DocumentRail({
  group, name, items, hasNext, loading, onMore,
}: {
  group: (typeof DOC_GROUPS)[number]
  name: string
  items: StockDocument[]
  hasNext: boolean
  loading: boolean
  onMore: () => void
}) {
  const railRef = useRef<HTMLUListElement>(null)
  const [page, setPage] = useState(0)
  const [pages, setPages] = useState(1)

  const measure = useCallback(() => {
    const el = railRef.current
    if (!el) return
    const per = Math.max(1, el.clientWidth)
    setPages(Math.max(1, Math.round(el.scrollWidth / per)))
    setPage(Math.round(el.scrollLeft / per))
  }, [])

  useEffect(() => {
    measure()
    const el = railRef.current
    if (!el) return
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [measure, items.length])

  const atEnd = page >= pages - 1
  const step = (dir: -1 | 1) => {
    const el = railRef.current
    if (!el) return
    /* 마지막 쪽에서 더 밀면 다음 쪽을 불러온다 — 화살표가 회색으로 죽어 있으면
       서버에 더 있다는 것을 알 길이 없다 */
    if (dir === 1 && atEnd) {
      if (hasNext) onMore()
      return
    }
    el.scrollBy({ left: dir * el.clientWidth, behavior: 'smooth' })
  }

  return (
    <section className={`sd-doc-group is-${group.key}`}>
      <header>
        <i className="sd-doc-badge" aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            {group.icon}
          </svg>
        </i>
        <div className="sd-doc-group-text">
          <h3>{group.label}</h3>
          <p>{group.desc(name)}</p>
        </div>
        {hasNext && (
          <button type="button" className="sd-doc-more" onClick={onMore} disabled={loading}>
            {loading ? '…' : '더보기'}
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="m9 6 6 6-6 6" />
            </svg>
          </button>
        )}
      </header>

      {items.length === 0 ? (
        <p className="sd-doc-none">{group.empty}</p>
      ) : (
        <div className="sd-doc-rail-wrap">
          {/* 원문 본문을 저장하지 않으므로 전문을 그리지 않는다(§4 B-03).
              칸을 눌러 원문으로 나간다 — 새 탭이라 이 화면의 작성 중 내용이 날아가지 않는다 */}
          <ul className="sd-doc-rail" ref={railRef} onScroll={measure}>
            {items.map((d) => <DocCard key={d.id} doc={d} />)}
          </ul>

          {(pages > 1 || hasNext) && (
            <div className="sd-doc-nav">
              <button
                type="button" aria-label={`${group.label} 이전`}
                disabled={page === 0}
                onClick={() => step(-1)}
              >
                &lsaquo;
              </button>
              <span className="sd-doc-dots" aria-hidden="true">
                {Array.from({ length: pages }, (_, i) => (
                  <i key={i} className={i === page ? 'is-on' : undefined} />
                ))}
              </span>
              <button
                type="button" aria-label={`${group.label} 다음`}
                disabled={atEnd && !hasNext}
                onClick={() => step(1)}
              >
                &rsaquo;
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  )
}

function DocCard({ doc }: { doc: StockDocument }) {
  const press = pressName(doc.originUrl)
  return (
    <li>
      <a className="sd-doc-card" href={doc.originUrl} target="_blank" rel="noreferrer noopener">
        <span className="sd-doc-card-head">
          <span className={`sd-doc-kind is-${doc.source.toLowerCase()}`}>{SOURCE_LABEL[doc.source]}</span>
          {/* 언론사 이름은 서버에 없다 — 원문 주소의 호스트에서 되짚은 값이다 */}
          {press && <b className="sd-doc-press">{press}</b>}
        </span>
        <strong>{doc.title}</strong>
        {/* 공시는 발췌가 없어 제목만 보여준다 */}
        {doc.snippet && <span className="sd-doc-snip">{doc.snippet}</span>}
        <span className="sd-doc-meta num">{day(doc.publishedAt)}</span>
      </a>
    </li>
  )
}
