/* B-01 인사이트 홈 · /
   담당 스토리 [ANT-FE-HOME]

   남은 목업은 광고 하나뿐이다 — 등록된 배너가 아직 없다(2026-09-03 결정).

   실전 시세는 전일 종가만 제공된다(법적 제약 · 명세서 §7).
   그래서 이 화면 어디에도 "현재가" 라는 말을 쓰지 않는다.

   레이아웃은 좌(넓음)·우(좁음) 두 줄기로 세운다. 카드를 그리드 칸에 직접
   넣지 않고 칼럼 안에 쌓는 이유는, 카드 높이가 서로 달라도 좌우 경계가
   어긋나지 않게 하려는 것이다. */
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getMarketIndices, getWatchlist } from '../api/insight'
import { fetchActiveAds } from '../api/ads'
import { formatToken } from '../api/wallet'
import { useWalletBalance } from '../api/walletBalance'
import { fetchRankings, formatComputedAt, metric } from '../api/rankings'
import { getBriefings } from '../api/briefings'
import { useBriefingParam } from '../components/briefingParam'
import { useAsync } from '../api/useAsync'
import { DEFAULT_AVATAR, useAuth } from '../auth/context'
import Sparkline from '../components/Sparkline'
import { BANNER, BANNER_BASE, CONTEST_PATH, phaseAt } from '../event/contest'
import StockLogo from '../components/stocks/StockLogo'
import '../styles/night.css'
import '../styles/home.css'

const INDEX_LABEL: Record<string, string> = {
  KOSPI: '코스피', KOSDAQ: '코스닥', USDKRW: '환율 (USD/KRW)',
}

/* 자동 넘김 간격. 브리핑은 문장을 읽어야 해서 넉넉히 두고, 광고는 여러 장이
   돌고 있다는 게 보이도록 짧게 잡는다. */
const BRIEF_ROTATE_MS = 4500
const AD_ROTATE_MS = 2600

/* 광고마다 다른 인상을 주려고 화면 쪽에 둔 임시 프리셋이다.
   ActiveAd 스키마에는 이미지·링크뿐이라 문구와 색을 담을 자리가 없다.
   백엔드에 필드가 생기면 이 배열을 지우고 응답 값을 그대로 쓰면 된다. */
const AD_THEMES = [
  { tone: 'a', title: '내 예측을 더 많은 사람에게!', sub: 'ANT로 홍보하고 성과를 키우세요.', cta: '지금 홍보하기' },
  { tone: 'b', title: '검증된 예측가를 구독해 보세요', sub: '적중률 높은 분석을 놓치지 않게.', cta: '구독 둘러보기' },
  { tone: 'c', title: '모의투자 리그가 열렸습니다', sub: '실전에 앞서 전략을 시험해 보세요.', cta: '리그 참가하기' },
]

/* 홈은 요약이라 관심 종목을 몇 줄만 보여준다. 전체는 B-04 에서 본다. */
const WATCH_ROWS = 3

const won = (n: number) => n.toLocaleString('ko-KR')
/* 0 에는 부호를 붙이지 않는다. "+0.00%" 는 오른 것처럼 읽힌다.
   B-04(Watchlist.tsx)가 먼저 같은 판단을 했고 여기만 남아 있었다. */
const signed = (n: number) => `${n > 0 ? '+' : ''}${n.toFixed(2)}%`

/* 등락률의 색. 0 은 상승도 하락도 아니다 — 이유는 Watchlist.tsx(B-04)에 적어 뒀다.
   수집이 하루치뿐이면 서버가 changeRate 를 0 으로 내리므로(응답 규약 "점이 둘 미만이면 0")
   이 구분이 없으면 온 종목이 빨강이 되어 "다 올랐다" 로 읽힌다. */
const toneOf = (rate: number | null) =>
  rate === null || rate === 0 ? '' : rate > 0 ? 'up' : 'down'

/* 미니차트 색은 옆의 등락률을 따른다. 부호가 없을 때(0 · null)만 선의 양 끝을 쓴다. */
const risingOf = (rate: number | null, series: number[]) =>
  rate !== null && rate !== 0
    ? rate > 0
    : series.length < 2 || series[series.length - 1] >= series[0]

/* 등락률과 종가만 내려오므로 변동액은 여기서 되돌려 구한다.
   prev = close / (1 + rate/100) 이고, 변동액은 close - prev 다. */
function deltaOf(close: number, rate: number) {
  const prev = close / (1 + rate / 100)
  return close - prev
}
const signedAmt = (n: number, digits = 0) =>
  `${n === 0 ? '' : n > 0 ? '+' : '−'}${Math.abs(n).toLocaleString('ko-KR', {
    minimumFractionDigits: digits, maximumFractionDigits: digits,
  })}`

/* 한 번에 한 장만 보여주고 자동으로 넘긴다. 화면 자리를 아끼려는 장치라
   목록이 한 장뿐이면 타이머를 걸지 않는다.
   - 움직임을 줄이도록 설정한 사용자에게는 첫 장에서 멈춘다.
   - paused 는 광고 위에 마우스를 올렸을 때 쓴다. */
function useRotate(count: number, ms: number) {
  const [index, setIndex] = useState(0)
  const [paused, setPaused] = useState(false)

  useEffect(() => {
    if (count < 2 || paused) return
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
    const timer = setInterval(() => setIndex((v) => (v + 1) % count), ms)
    return () => clearInterval(timer)
  }, [count, ms, paused])

  /* 목록이 짧아져도 범위를 벗어나지 않게 나머지로 접는다 */
  return { index: count > 0 ? index % count : 0, setIndex, setPaused }
}

/* ── 인라인 아이콘 ─────────────────────────────────────────
   아이콘 패키지를 새로 들이지 않고 필요한 것만 직접 그린다.
   전부 장식이라 aria-hidden 이고, 뜻은 옆 텍스트가 전한다. */
const ico = { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', 'aria-hidden': true } as const

const StarIcon = () => (
  <svg {...ico} className="hm-ico">
    <path d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.2-4.1 5.8-.8z"
          fill="currentColor" />
  </svg>
)
const WalletIcon = () => (
  <svg {...ico} className="hm-ico">
    <rect x="3" y="6" width="18" height="13" rx="3" stroke="currentColor" strokeWidth="1.8" />
    <path d="M3 10h18" stroke="currentColor" strokeWidth="1.8" />
    <circle cx="16.5" cy="14.5" r="1.4" fill="currentColor" />
  </svg>
)
const TrophyIcon = () => (
  <svg {...ico} className="hm-ico">
    <path d="M7 4h10v4a5 5 0 01-10 0V4z" stroke="currentColor" strokeWidth="1.8"
          strokeLinejoin="round" />
    <path d="M7 6H4.8a3 3 0 003 3M17 6h2.2a3 3 0 01-3 3M12 13v3M9 20h6M10 20l.4-4h3.2l.4 4"
          stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)
const PenIcon = () => (
  <svg {...ico} className="hm-ico">
    <path d="M4 20h4.2L19 9.2a2.1 2.1 0 0 0-3-3L5 17.2V20z" stroke="currentColor"
          strokeWidth="1.8" strokeLinejoin="round" />
    <path d="M14.3 7.4l2.9 2.9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
  </svg>
)
const ChartIcon = () => (
  <svg {...ico} className="hm-ico">
    <path d="M4 19V5M4 19h16" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    <path d="M7.5 15l3.2-4.2 2.8 2.2L18 7" stroke="currentColor" strokeWidth="1.8"
          strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)
const ListIcon = () => (
  <svg {...ico} className="hm-ico">
    <path d="M9 6.5h11M9 12h11M9 17.5h11" stroke="currentColor" strokeWidth="1.8"
          strokeLinecap="round" />
    <circle cx="4.6" cy="6.5" r="1.4" fill="currentColor" />
    <circle cx="4.6" cy="12" r="1.4" fill="currentColor" />
    <circle cx="4.6" cy="17.5" r="1.4" fill="currentColor" />
  </svg>
)
const SparkIcon = () => (
  <svg {...ico} className="hm-ico">
    <path d="M12 3.5l1.7 4.3 4.3 1.7-4.3 1.7L12 15.5l-1.7-4.3L6 9.5l4.3-1.7z"
          fill="currentColor" />
    <circle cx="18" cy="17.5" r="1.6" fill="currentColor" opacity=".55" />
  </svg>
)

/** 섹션 안의 로딩·실패·빈 상태. A-04 [ANT-FE-ERROR] 가 공용 컴포넌트를 만들면 그걸로 교체한다. */
function State({ loading, error, empty, onRetry }: {
  loading: boolean; error: unknown; empty?: boolean; onRetry?: () => void
}) {
  if (loading) return <p className="hm-state">불러오는 중…</p>
  if (error) return (
    <p className="hm-state err">
      불러오지 못했습니다.
      {onRetry && <button type="button" className="hm-retry" onClick={onRetry}>다시 시도</button>}
    </p>
  )
  if (empty) return <p className="hm-state">아직 표시할 내용이 없습니다.</p>
  return null
}

/* 화면 맨 위 인사. 데이터를 쓰지 않는 정적 배너라 상태 처리가 없다.
   "실시간" 이라고 쓰지 않는 이유는 실전 시세가 전일 종가뿐이기 때문이다. */
function Hero() {
  const { user } = useAuth()

  return (
    <section className="hm-hero" aria-labelledby="hm-hero-h">
      <div className="hm-hero-text">
        <p className="hm-hello">
          {user?.nickname ? `${user.nickname}님, 환영합니다.` : '환영합니다.'}
        </p>
        {/* 페이지 제목 자리다. 위에 따로 머리글을 두지 않으므로 h1 이다. */}
        <h1 id="hm-hero-h">
          <em>AI</em>가 시장을 분석하고,<br /><em>현명한 투자 결정</em>을 도와드려요.
        </h1>
        <p className="hm-hero-sub">
          종가 데이터와 AI 인사이트로<br />투자의 방향을 제시합니다.
        </p>
        <Link className="hm-cta" to="/predict">
          예측 시작하기
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M5 12h13M13 6.5l5.5 5.5L13 17.5" stroke="currentColor" strokeWidth="2"
                  strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </Link>
      </div>
      {/* 망원경으로 시장을 살피는 개미(2026-09-22 밤 시안). 난간과 고양이까지 한 덩어리로
          그려진 누끼라 카드 아래쪽에 세우면 시안의 "난간 위에서 내려다본다" 가 그대로 선다.

          **차트판을 든 옛 그림(character/hero-ant.webp)은 지우지 않았다** — 밝은 판을
          되살릴 때 쓸 수 있고, 다른 화면이 쓰는지 확인하지 않고 지울 파일이 아니다.

          원본은 page_home 의 ChatGPT 시안이고, 웹용 webp 는 scripts/bake-home-night.mjs
          가 굽는다(1448x1086 -> 900px · 1.57MB -> 142KB). */}
      <img className="hm-hero-art is-scout" src="/assets/page_home/hero-scout.webp" alt=""
           aria-hidden="true" />
    </section>
  )
}

/* AI 브리핑은 카드를 따로 두지 않고 이 카드 아래쪽에 한 줄 띠로 붙인다.
   자리를 적게 쓰려고 한 번에 한 문장만 두고 자동으로 넘긴다. */
/* 모듈 스코프에 둔다 — useAsync 는 load 가 바뀌면 다시 읽으므로
   매 렌더 새 함수를 만들면 안 된다(useAsync 머리말) */
const marketBriefings = () => getBriefings({ scope: 'MARKET' })

function BriefingTicker() {
  const { data } = useAsync(marketBriefings)
  const lines = data?.items ?? []
  const { index } = useRotate(lines.length, BRIEF_ROTATE_MS)
  const line = lines[index]
  const { open } = useBriefingParam()

  /* 목업을 걷어 냈다. 생성 배치가 아직 안 돌아 목록이 비는데, 그때 이 띠가 통째로
     사라지면 "원래 없는 자리" 로 보인다 — 옆의 지수는 실제 값이라 더 그렇다.
     비는 대신 왜 없는지 한 줄로 적는다. 읽기만 하는 줄이라 버튼이 아니다. */
  if (!line) {
    return (
      <div className="hm-ticker">
        <span className="hm-ticker-tag"><SparkIcon />AI 브리핑</span>
        <p className="hm-ticker-line is-muted">
          오늘의 시장 브리핑이 아직 없습니다. 생성 기능이 준비되면 이 자리에 표시됩니다.
        </p>
      </div>
    )
  }

  return (
    <div className="hm-ticker">
      <span className="hm-ticker-tag"><SparkIcon />AI 브리핑</span>
      {/* 목록은 헤드라인만 준다 — 본문은 M-10 이 받는다. key 가 바뀌면 다시
          마운트되어 페이드가 다시 도는데, 그 자리를 그대로 버튼으로 바꿨다.
          지금 보이는 문장을 눌러야 그 문장이 열리기 때문이다. */}
      <button
        type="button" className="hm-ticker-line" key={line.id}
        onClick={() => open(line.id)}
        aria-hidden="true" tabIndex={-1}
      >
        {line.headline}
      </button>
      {lines.length > 1 && (
        <span className="hm-ticker-no num" aria-hidden="true">
          {index + 1}/{lines.length}
        </span>
      )}
      {/* 화면에서는 한 줄씩 돌려 보여주지만, 읽어 주는 기기에는 전부 한 번에 준다.
          돌아가는 줄 하나만 두면 나머지에 닿을 길이 없고, 4.5초마다 이름이 바뀌는
          버튼은 초점을 두기도 어렵다. 그래서 보이는 줄은 a11y 트리에서 빼고
          (aria-hidden + tabIndex -1) 조작은 이 목록이 맡는다 — 초점이 오면
          home.css 의 :focus-within 이 목록을 드러낸다. */}
      <ul className="hm-sr">
        {lines.map((b) => (
          <li key={b.id}>
            <button type="button" onClick={() => open(b.id)}>{b.headline}</button>
          </li>
        ))}
      </ul>
    </div>
  )
}

function MarketOverview() {
  const load = useCallback(() => getMarketIndices(30), [])
  const { data, loading, error, reload } = useAsync(load)

  return (
    <section className="card hm-card" aria-labelledby="hm-market">
      <header className="hm-head">
        <h2 id="hm-market">시장 Overview</h2>
        <Link to="/stocks">더보기 ›</Link>
      </header>
      <State loading={loading} error={error} onRetry={reload} />
      {data && (
        <ul className="hm-indices">
          {data.items.map((it) => {
            const tone = toneOf(it.changeRate)
            /* 지수는 소수 둘째 자리까지가 관례다 */
            const delta = deltaOf(it.close, it.changeRate)
            return (
              <li key={it.code}>
                <span className="hm-ix-name">{INDEX_LABEL[it.code] ?? it.code}</span>
                <b className="hm-ix-val num">{won(it.close)}</b>
                <span className={`hm-ix-delta num ${tone}`}>
                  {signedAmt(delta, 2)}
                  <em>({signed(it.changeRate)})</em>
                </span>
                <Sparkline series={it.series} up={risingOf(it.changeRate, it.series)} width={78} height={52} area grow />
              </li>
            )
          })}
        </ul>
      )}
      <BriefingTicker />
      <p className="hm-foot">최근 30일 종가 흐름 · 실전 시세는 전일 종가만 제공됩니다</p>
    </section>
  )
}

/* 지갑 화면(H-01)과 같은 응답을 읽는다 — api/wallet.ts 한 벌을 쓴다.
   전에는 api/insight.ts 가 같은 이름으로 다른 모양을 선언하고 있었다(balance 를 number 로,
   서버에 없는 30일 증감·원화 환산까지). 목업일 때는 드러나지 않다가 ANT-TOKEN-04 로 경로가
   생기자 undefined.toLocaleString() 에서 터져 홈 전체가 오류 화면이 됐다.

   **상단 칩과 같은 저장소를 읽는다**(2026-09-20, ANT-FE-WALLET-SYNC). 전에는 이 카드가 들어올
   때마다 따로 읽어서, 구독 직후 홈에 오면 여기는 새 값이고 칩은 옛 값이었다. */
function MyAssets() {
  const { data, loading, error, reload } = useWalletBalance()

  return (
    <section className="card hm-card hm-wallet" aria-labelledby="hm-assets">
      <header className="hm-head">
        <h2 id="hm-assets">내 자산 현황</h2>
        <Link to="/me/wallet">지갑 ›</Link>
      </header>
      <State loading={loading} error={error} onRetry={reload} />
      {data && (
        <>
          {/* 금액은 정수 ANT 문자열이다(decimals 0). formatToken 이 천 단위만 끊는다 */}
          <div className="hm-ant-box">
            <div>
              <p className="hm-ant-label"><WalletIcon />보유 ANT</p>
              <p className="hm-ant"><b className="num">{formatToken(data.balance)}</b> <em>ANT</em></p>
            </div>
            {/* 충전 버튼을 두지 않는다 — 토큰 유입은 가입 보너스와 성과 보상 mint 뿐이다(§H-01 설계 제약) */}
            <Link className="hm-ant-btn" to="/me/wallet">내역 보기</Link>
          </div>
          {/* 30일 흐름. 잔액 하나만 있으면 "그동안 뭘 했는지" 가 안 보인다.

              **원화 환산은 여전히 없다** — ANT 시세가 없어 환산 자체가 불가능하다. 화면설계서
              §482 가 "토큰 기간 집계 · KRW 환산" 을 한 줄로 묶어 둔 탓에 둘 다 못 하는 것으로
              읽혔는데, 집계는 서버가 세어 주면 되는 것이었다(2026-09-16 · ANT-TOKEN-04 확장).

              번 것에서 쓴 것을 빼면 30일간 잔액 변화가 된다. 둘 다 총량이라 환불이 양쪽에
              잡히지만, 그래야 이 등식이 성립해 나중에 숫자끼리 검산이 된다. */}
          <dl className="hm-flow">
            <div>
              <dt>30일간 번 ANT</dt>
              {/* **.up / .down 을 쓰지 않는다.** 이 앱에서 빨강·파랑은 주가 등락이라,
                  여기 쓰면 "번 ANT 가 올랐다" 로 읽힌다. 구분은 부호와 자리로 한다 */}
              <dd className="num is-in">+{formatToken(data.earned30d)}</dd>
            </div>
            <div>
              <dt>30일간 쓴 ANT</dt>
              {/* 서버가 양수로 준다. 부호는 여기서 붙인다 */}
              <dd className="num is-out">−{formatToken(data.spent30d)}</dd>
            </div>
          </dl>
          {/* 대사 시각을 적는다 — 숫자만 보이면 실시간 잔액으로 오해한다(§H-01) */}
          <p className="hm-foot">
            {data.syncedAt
              ? `온체인 대사 ${data.syncedAt.slice(0, 10)} 기준`
              : '아직 온체인 대사 전입니다'}
          </p>
        </>
      )}
    </section>
  )
}

function Watchlist() {
  const load = useCallback(() => getWatchlist(), [])
  const { data, loading, error, reload } = useAsync(load)

  return (
    <section className="card hm-card" aria-labelledby="hm-watch">
      <header className="hm-head">
        <h2 id="hm-watch"><StarIcon />관심 종목</h2>
        <Link to="/watchlist">더보기 ›</Link>
      </header>
      <State loading={loading} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && (
        <div className="hm-table-wrap">
          <table className="hm-table">
            <thead>
              <tr>
                <th scope="col">종목명</th>
                <th scope="col" className="r">전일 종가</th>
                <th scope="col" className="r">전일 대비</th>
                <th scope="col" className="c">최근 30일</th>
                <th scope="col" className="s"><span className="hm-sr">관심 여부</span></th>
              </tr>
            </thead>
            <tbody>
              {data.items.slice(0, WATCH_ROWS).map((s) => {
                const tone = toneOf(s.changeRate)
                // 시세가 없는 종목은 종가·변동액을 지어내지 않고 빈칸으로 둔다
                const delta = s.prevClose === null ? null : deltaOf(s.prevClose, s.changeRate)
                return (
                  <tr key={s.stockCode}>
                    <th scope="row">
                      <Link to={`/stocks/${s.stockCode}`}>
                        <StockLogo code={s.stockCode} name={s.name} className="hm-logo" />
                        {s.name} <small className="num">{s.stockCode}</small>
                      </Link>
                    </th>
                    <td className="r num">{s.prevClose === null ? '—' : `${won(s.prevClose)} 원`}</td>
                    <td className={`r num ${tone}`}>
                      {delta === null ? '—' : (
                        <>
                          {signedAmt(Math.round(delta))}
                          <em> ({signed(s.changeRate)})</em>
                        </>
                      )}
                    </td>
                    <td className="c"><Sparkline series={s.series} up={risingOf(s.changeRate, s.series)} /></td>
                    {/* 목록에 담긴 종목이라 채운 별이다. 담기·빼기는 B-04 의 몫이다. */}
                    <td className="s"><span className="hm-star"><StarIcon /></span></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="hm-foot">
        <Link to="/watchlist">관심 종목 관리 ›</Link>
      </p>
    </section>
  )
}

/* 한 명만 세운다. 순위표는 E-01 랭킹 화면의 몫이라 여기서 되풀이하지 않는다.

   랭킹 화면(E-01)과 같은 api/rankings.ts 를 쓴다 — 전에는 insight.ts 가 같은 응답을
   따로 선언했는데, 그쪽 타입이 지표 셋을 non-null 로 적어 두어 배치 전 응답(전부 null)에서
   toFixed 가 터졌다. */
function TopPredictor() {
  const load = useCallback(() => fetchRankings({ track: 'REAL', limit: 1 }), [])
  const { data, loading, error, reload } = useAsync(load)
  const lead = data?.items[0]

  return (
    <section className="card hm-card" aria-labelledby="hm-top">
      <header className="hm-head">
        <h2 id="hm-top"><TrophyIcon />주목할 예측가</h2>
        <Link to="/rankings">랭킹 ›</Link>
      </header>
      <State loading={loading} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {lead && (
        <>
          <div className="hm-lead">
            {/* 랭킹 1위의 프로필 사진(2026-09-15). 전에는 서버가 사진 칸을 안 내려 줘
                모두 기본 개미였다 — RankingItemResponse 에 avatarUrl 이 생겼다.
                안 올린 사람은 null 이라 기본 개미로 떨어진다. */}
            <img
              className="hm-avatar" alt="" aria-hidden="true"
              src={lead.avatarUrl ?? DEFAULT_AVATAR}
            />
            <div className="hm-lead-id">
              <Link className="hm-lead-name" to={`/channels/${lead.userId}`}>{lead.nickname}</Link>
            </div>
            {/* 이름 아래가 아니라 **카드 오른쪽 끝**에 세운다(2026-09-15). 이 카드가
                하려는 말이 "1위" 인데, 이름 밑의 작은 칩으로는 지나쳐 버렸다. */}
            <span className="hm-lead-rank">랭킹 {lead.rank}위</span>
            <dl className="hm-lead-stats">
              <div><dt>예측</dt><dd className="num">{lead.doneCount}건</dd></div>
              <div><dt>적중률</dt><dd className="num">{metric(lead.hitRate, 1, '%')}</dd></div>
              <div><dt>평균 오차</dt><dd className="num">{metric(lead.avgError, 1, '%p')}</dd></div>
            </dl>
            <Link className="hm-lead-btn" to={`/channels/${lead.userId}`}>프로필 보기</Link>
          </div>
          {/* 배치 전에는 computedAt 이 null 이다 — 그때는 기준 시각을 적지 않는다 */}
          {data?.computedAt && (
            <p className="hm-foot">매 영업일 배치 집계 · 기준 {formatComputedAt(data.computedAt)}</p>
          )}
        </>
      )}
    </section>
  )
}

/* 우측 맨 위 광고 배너. 어두운 면으로 두어 흰 카드 흐름과 구분한다.
   여러 장이 오면 한 장씩 자동으로 넘기고, 점을 눌러 곧바로 고를 수도 있다.
   문구는 ActiveAd 스키마에 없어(이미지·링크뿐) 장마다 같은 안내가 나간다. */
function Sponsored() {
  const load = useCallback(() => fetchActiveAds(), [])
  const { data, loading, error } = useAsync(load)
  const items = data?.items ?? []
  const { index, setIndex, setPaused } = useRotate(items.length, AD_ROTATE_MS)
  const ad = items[index]

  /* 걸린 배너가 없으면 **우리 배너**를 건다(2026-09-17). 목업을 걷어내고 나니 등록된
     광고가 0건이라 이 칸이 통째로 비었는데, 빈 칸을 접어 버리면 광고 등록(H-04)으로
     가는 길이 홈에서 사라진다 — 지금은 마이페이지 안쪽 메뉴뿐이다.

     목업이 아니다. 가짜 광고주를 지어내는 것이 아니라 "이 자리를 팝니다" 라고 우리가
     말하는 것이고, 광고가 한 건이라도 승인되면 그 배너에 자리를 내준다.

     실패(error)일 때는 걸지 않는다. 불러오지 못한 것과 비어 있는 것은 다른 상태다. */
  const house = !loading && !error && items.length === 0

  return (
    <section className="hm-showcase" aria-labelledby="hm-showcase-h"
             onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}>
      <header className="hm-showcase-head">
        <h2 id="hm-showcase-h" className="hm-badge">스폰서드</h2>
      </header>
      <State loading={loading} error={error} />

      {house && (
        <div className="hm-showcase-viewport">
          <div className="hm-showcase-track">
            <div className="hm-showcase-slide hm-t-a">
              <p className="hm-showcase-title">이 자리에 광고를 실어 보세요</p>
              <p className="hm-showcase-sub">홈 첫 화면에 배너가 걸립니다. ANT 로 결제합니다.</p>
              <Link className="hm-showcase-btn" to="/ads/new">
                광고 등록하기
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M5 12h13M13 6.5l5.5 5.5L13 17.5" stroke="currentColor"
                        strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </Link>
            </div>
          </div>
        </div>
      )}
      {ad && (
        <>
          {/* 창 하나를 두고 띠를 옆으로 민다. 보이지 않는 장은 읽기·탭 대상에서 뺀다. */}
          <div className="hm-showcase-viewport">
            <div className="hm-showcase-track"
                 style={{ transform: `translateX(-${index * 100}%)` }}>
              {items.map((it, i) => {
                const t = AD_THEMES[i % AD_THEMES.length]
                /* 색 이름에 hm- 를 붙인다(2026-09-14). 전에는 `t-b` 였는데 모의투자
                   연습 화면(sim-practice.css)에 범위 없는 `.t-b{background:#e8f1fe}` 가
                   있어, 화면별 CSS 가 전역으로 함께 올라가며 이 슬라이드를 덮었다 —
                   두 번째 광고만 배경이 연하늘색이 되고 흰 글씨가 안 읽혔다. */
                return (
                  <div className={`hm-showcase-slide hm-t-${t.tone}`} key={it.id} aria-hidden={i !== index}>
                    <p className="hm-showcase-title">{t.title}</p>
                    <p className="hm-showcase-sub">{t.sub}</p>
                    <Link className="hm-showcase-btn" to={it.linkUrl}
                          tabIndex={i === index ? undefined : -1}>
                      {t.cta}
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <path d="M5 12h13M13 6.5l5.5 5.5L13 17.5" stroke="currentColor"
                              strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    </Link>
                    {/* 그림을 빼 뒀다(2026-09-17). 되살리려면 이 한 줄이다:
                          <img className="hm-showcase-art" src={it.imageUrl} alt="" aria-hidden="true" />

                        **여기가 광고에서 유일하게 진짜인 자리였다.** /ads/active 가 주는 것은
                        imageUrl 과 linkUrl 뿐이고 제목·설명·버튼 글자는 화면이 들고 있는
                        AD_THEMES 프리셋이다. 그림까지 빼면 이 카드에 광고주의 것은 링크만
                        남는다 — 문구 필드가 생기거나 라벨을 고치기 전까지 알고 있어야 한다. */}
                  </div>
                )
              })}
            </div>
          </div>
          {items.length > 1 && (
            <span className="hm-showcase-dots">
              {items.map((d, i) => (
                <button key={d.id} type="button" className={i === index ? 'on' : undefined}
                        onClick={() => setIndex(i)}
                        aria-label={`${i + 1}번째 광고 보기`}
                        aria-current={i === index || undefined} />
              ))}
            </span>
          )}
        </>
      )}
    </section>
  )
}

/* J-01 대회로 가는 배너. **대회 페이지로 들어오는 길이 이것과 링크 둘뿐**이라
   (사이드바에 메뉴를 만들지 않았다) 홈에서 눈에 띄어야 한다. 그래서 좌우 한 칸씩
   차지하지 않고 두 칸을 통째로 가로지른다.

   대회가 끝나면 지운다. 날짜가 지났다고 저절로 사라지게 하지 않은 것은,
   결과 공개 뒤에도 순위를 보러 오는 길이 남아야 하기 때문이다. */
function ContestBanner() {
  const art = BANNER[phaseAt()]

  /* 배너는 **겹을 쌓아** 그린다. 완성본 한 장을 쓰지 않는 이유는 그 그림이
     4.69:1 이고 이 자리는 9:1 이라, 한 장으로는 자르거나 홈을 밀어내는
     수밖에 없기 때문이다(event/contest.ts 머리말).

     **글자는 전부 그림 안에 있다.** 그래서 겹은 모두 장식으로 두고(alt=""),
     읽을 말은 링크의 aria-label 하나로 모은다 — 그림 조각마다 alt 를 나눠
     달면 스크린리더가 토막 난 문장을 읽는다. */
  return (
      <Link className="hm-event is-art" to={CONTEST_PATH} aria-label={art.alt}>
        {/* 띠 안쪽 — 배경·차트·색종이는 여기서 잘린다 */}
        <span className="hm-ev-clip" aria-hidden="true">
          <img className="hm-ev-bg" src={BANNER_BASE.bg} alt="" />
          <img className="hm-ev-chart" src={BANNER_BASE.chart} alt="" />
          <img className="hm-ev-confetti is-a" src={BANNER_BASE.confetti} alt="" />
          <img className="hm-ev-confetti is-b" src={BANNER_BASE.confetti} alt="" />
        </span>

        {/* 왼쪽 글 묶음 — 캡슐 · (제목+왕관) · 문구 */}
        <span className="hm-ev-lead" aria-hidden="true">
          <img className="hm-ev-pill" src={art.pill} alt="" />
          <span className="hm-ev-words">
            <span className="hm-ev-titlebox">
              <img className="hm-ev-title" src={art.title} alt="" />
              <img className="hm-ev-crown" src={BANNER_BASE.crown} alt="" />
            </span>
            <img className="hm-ev-copy" src={art.copy} alt="" />
          </span>
        </span>

        <img className="hm-ev-sticker" src={BANNER_BASE.sticker} alt="" aria-hidden="true" />
        <img className="hm-ev-mascot" src={BANNER_BASE.mascot} alt="" aria-hidden="true" />
      </Link>
  )
}

/* 배너가 히어로보다 짧게 끝나 남는 자리를 메운다. 데이터를 부르지 않는
   지름길 모음이라 로딩·실패 상태가 없다.

   ── 첫 칸이 "예측 등록" 이 아닌 이유 (2026-09-18) ──────
   /predict 는 화면이 아니라 넘겨보내는 자리다(PredictNew). 종목 코드가 없으면
   /stocks 로 보내므로, 홈에서 누르면 바로 옆 "종목 탐색" 과 **같은 화면**에
   떨어졌다 — 지름길 셋 중 둘이 한 곳을 가리키고 있었다.

   그 자리를 리포트 작성이 받는다. 예측과 함께 이 서비스의 두 가지 쓰기 행위
   중 하나인데, 지금은 리포트 피드 안으로 들어가야만 닿는다. */
function QuickLinks() {
  return (
    <nav className="card hm-quick" aria-label="바로가기">
      <Link to="/reports/new"><PenIcon />리포트 쓰기</Link>
      <Link to="/stocks"><ChartIcon />종목 탐색</Link>
      <Link to="/me/predictions"><ListIcon />내 예측</Link>
    </nav>
  )
}

export default function Home() {
  /* M-09 온보딩 튜토리얼은 셸(Layout)이 띄운다. 홈에만 걸면 딥링크로 막혔다가
     가입한 회원이 못 본다 — 그 사람은 홈이 아니라 그 경로로 착지한다. */
  return (
    <main className="main">
      <div className="main-inner">
        {/* 카드를 그리드에 바로 놓아 같은 행끼리 짝을 짓는다. 행마다 높이가
            자동으로 맞으므로 카드 아래끝이 창 크기와 무관하게 나란히 선다.
            순서가 곧 배치다 — 좌·우, 좌·우, 좌·우. */}
        <div className="hm-grid">
          <Hero />
          {/* 배너는 제 높이만 쓰고, 남는 자리는 바로가기가 받는다 */}
          <div className="hm-stack">
            <Sponsored />
            <QuickLinks />
          </div>
          {/* 두 칸을 가로지른다 — 아래 카드 줄을 밀지 않고 한 줄만 쓴다 */}
          <ContestBanner />
          {/* 관심 종목을 시장 Overview 보다 위에 둔다 — 홈에 들어와 가장 먼저
              보는 것이 "내가 담은 것" 이라는 코치 QA(2026-09-21). 시장 지표는
              누구에게나 같은 값이라 한 줄 아래여도 손해가 없다. */}
          <Watchlist />
          <MyAssets />
          <MarketOverview />
          <TopPredictor />
        </div>
      </div>
    </main>
  )
}
