/* G-01 모의투자 홈 · /sim
   담당 스토리 [ANT-FE-SEASON-HOME]
   설계서 docs/화면설계서.md §3 G-01 — "진행 중 시즌(이어하기) · 완료 기록 요약 · 시즌 목록"

   **2026-09-21 시안으로 갈아엎었다.** 본문 전체가 밤 도시다(상단바는 그대로 밝다). 왼쪽은 카피와 시작 버튼, 가운데는
   책상에 앉은 개미, 오른쪽은 "가상 투자 자금 · 진행 중인 연습" 두 칸이다. 그 아래로
   연습 주제 다섯 장이 가로로 늘어서고, 맨 밑은 연습이 어떤 것인지 말하는 사실 띠다.
   상단바·사이드바는 그대로 Layout 것을 쓴다.

   ── 시안에서 그리지 않은 것 ────────────────────────────
   1. 상단의 지수 전광판(KOSPI · NASDAQ · S&P 500 과 등락률). 실시간 시세는 이 화면이 받는
      값이 아니고, 숫자를 박아 두면 가짜 시세가 된다. 장식으로도 두지 않는다.
   2. 가상 투자 자금 옆의 연필(수정). 시작 자산은 시즌 속성이라 사용자가 바꾸는 값이 아니다.
   3. 주제 카드의 날짜·연도. 시대를 특정하는 말은 모의투자 전체에서 쓰지 않는다 — 카드에는
      서버가 준 제목(title)만 쓴다. 제목 자체가 사건을 말하는 것은 시더
      (PracticeSeasonSeeder)가 정한 일이라 여기서 손대지 않는다.
   4. 최근 연습 기록 목록. 이 시안은 그 자리를 주제 카드가 받았다. 기록은 "진행 중인 연습"
      칸의 링크로 G-09 기록 화면에서 본다.
   5. 제목 아래 소개 두 줄("실제와 닮은 시장에서…"). 손글씨 줄과 제목이 이미 같은 말을
      하고 있어 세 번째 줄은 읽히지 않고 자리만 먹었다(2026-09-21).

   ── 서버에서 받는 것 ────────────────────────────────────
     GET /seasons?mode=PRACTICE     → 주제 카드 · 시작 자산 · 주제 수
     GET /seasons/me?status=ONGOING → 진행 중인 연습 수 · 카드의 "이어서 하기"
   둘 다 커서 페이징이 없는 목록이라(§1 규칙 6 의 예외) useCursorList 를 쓰지 않는다.

   ── 그림은 어디서 오나 ──────────────────────────────────
   주제 카드의 배경 사진은 sim/seasonArt.ts 의 표(제목 → 그림)에서 온다. 표에 없는 주제는
   사진 없이 어두운 판만 깔린다. 개미 그림(ant-desk.webp)은 page_train 에 받은 시안 원본을
   여백만 잘라 webp 로 구운 것이다. */
import { useRef } from 'react'
import { Link } from 'react-router-dom'
import type { ApiError } from '../api/errors'
import {
  agreed, getMyRuns, getPracticeRuns, isJoinable, joinableFirst, manwon, recentFirst,
  type MyRun, type OpenRun,
} from '../api/seasons'
import { useAsync } from '../api/useAsync'
import ErrorState from '../components/state/ErrorState'
import { seasonArt } from '../sim/seasonArt'
import { openSimOnboarding } from '../sim/onboardingSeen'
import '../styles/screens/sim-home.css'

/* 401 을 그대로 보여준다.
   전에는 빈 상태로 뭉갰다 — 이 화면이 부르는 /seasons·/seasons/me 가 없던 때라 401 이
   "그 API 가 아직 없다" 는 뜻이었기 때문이다. 이제 둘 다 붙었으므로 401 은 로그인이
   안 됐다는 뜻이고, 그걸 빈 상태로 그리면 서버가 거부한 것을 참가한 것이 없는 것으로
   읽게 된다. errorText 가 UNAUTHENTICATED 를 "로그인이 필요합니다" 로 풀어 준다. */
const shown = (error: ApiError | null) => error

const Ico = ({ size = 22, children }: { size?: number; children: React.ReactNode }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
)

const PlayIcon = () => (
  <Ico size={24}><circle cx="12" cy="12" r="9" /><path d="m10 8.2 6 3.8-6 3.8z" fill="currentColor" /></Ico>
)
const ChevronIcon = () => <Ico size={18}><path d="m9 5 7 7-7 7" /></Ico>
const ArrowIcon = () => <Ico size={16}><path d="M5 12h14M13 6l6 6-6 6" /></Ico>
const CoinIcon = () => (
  <Ico size={18}><circle cx="12" cy="12" r="9" /><path d="M12 7v10M9.5 9.5h3.75a1.75 1.75 0 0 1 0 3.5H9.5m0 0h4a1.75 1.75 0 0 1 0 3.5H9.5" /></Ico>
)
const CheckRingIcon = () => (
  <Ico size={18}><circle cx="12" cy="12" r="9" /><path d="m8.5 12 2.5 2.5 4.5-5" /></Ico>
)
const GridIcon = () => (
  <Ico size={22}><rect x="3.5" y="3.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
    <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="13.5" width="7" height="7" rx="1.5" /></Ico>
)
const ClockIcon = () => (
  <Ico size={22}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3.5 2" /></Ico>
)
const CoinsIcon = () => (
  <Ico size={22}><ellipse cx="12" cy="6" rx="7.5" ry="3" /><path d="M4.5 6v5c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V6" />
    <path d="M4.5 11v5c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-5" /></Ico>
)
const BarsIcon = () => <Ico size={22}><path d="M6 20v-6M12 20V7M18 20v-9" /></Ico>

/* ── 히어로 ───────────────────────────────────────────────
   진행 중인 것이 있으면 시작 버튼이 그 회차로 이어진다 — 없으면 연습하기(주제 고르기)로. */
function Hero({ lead, practice, ongoing, done, startAt, myError, myLoading, onRetry }: {
  lead: MyRun | null
  practice: OpenRun[] | undefined
  ongoing: MyRun[]
  done: MyRun[]
  /** 바로 들어갈 수 있는 첫 주제. 목록이 비었으면 null — 그때는 권하지 않는다 */
  startAt: string | null
  myError: ApiError | null
  myLoading: boolean
  onRetry: () => void
}) {
  const cash = agreed(practice, 'initialCash')
  const total = practice?.length ?? 0
  const going = ongoing.length
  const percent = total > 0 ? Math.min(100, Math.round((going / total) * 100)) : 0
  const failure = shown(myError)
  /* 복기는 결과 화면(G-08)에만 있고 기록 목록(G-09)에는 없다 — 가장 최근에 끝낸
     판으로 곧장 보낸다. 끝낸 것이 없으면 볼 복기도 없어 기록으로 보낸다. */
  const lastDone = [...done].sort(recentFirst)[0]

  return (
    <section className="sh-hero is-dark" aria-labelledby="sh-hero-h">
      {/* 히어로 장식 — 빛줄기·별·촛대. 도시 야경은 페이지 바탕(sim-home.css 의 .main::before)이 깐다.
          전부 장식이라 레이어째 aria-hidden 이다. */}
      <div className="sh-backdrop" aria-hidden="true">
        <img className="sh-bd-wave" src="/assets/pageset/wave-thin.webp" alt="" />
        <img className="sh-bd-spark" src="/assets/pageset/sparks.webp" alt="" />
      </div>

      <div className="sh-hero-copy">
        <span className="sh-hand">가상으로 시작하는<br />진짜 투자 감각</span>

        {/* 연습 주제가 전부 사건 이름(코로나 · 전쟁 · 재당선 · 계엄)이다. 제목도 그
            말을 그대로 쓴다 — "투자 감각을 깨워보세요" 같은 문구는 어느 서비스에나
            붙일 수 있어 이 화면이 무엇을 하는 곳인지 말해 주지 못했다.
            어느 시장인지는 아래 카드 다섯 장이 이미 말하고 있어 제목에서 뺀다.
            강조는 "순간" 에 건다 — 이 화면이 파는 것은 기간이 아니라 그 며칠이다. */}
        <h1 className="sh-title" id="sh-hero-h">
          역사적인 <em>순간</em>을
          <br />
          연습해보세요
        </h1>

        <div className="sh-cta">
          {/* 시작 버튼은 **첫 주제로 바로 들어간다**(2026-09-21). 전에는 연습 허브
              (/sim/practice)로 보냈는데 그 화면을 이 화면에 합쳐서, 그대로 두면
              자기 자신을 가리키는 버튼이 된다. 고를 주제는 바로 아래 카드에 있으므로
              여기서는 "맨 앞 것부터" 로 연결한다.
              열린 주제가 하나도 없으면 버튼을 내린다 — 눌러도 갈 곳이 없다. */}
          {lead ? (
            <Link className="sh-btn solid" to={`/sim/${lead.seasonId}/play`}>
              <PlayIcon />
              이어서 하기
            </Link>
          ) : startAt ? (
            <Link className="sh-btn solid" to={startAt}>
              <PlayIcon />
              지금 시작하기
            </Link>
          ) : null}
          {/* G-00 안내는 처음 열었을 때 한 번만 자동으로 뜬다. 건너뛴 사람이 다시 볼 자리다.
              라우트가 없는 오버레이라 링크가 아니라 셸에 신호를 보낸다. */}
          <button type="button" className="sh-btn line" onClick={openSimOnboarding}>
            서비스 소개
            <ChevronIcon />
          </button>
        </div>

        {/* 무엇을 이어서 하는지 말한다(2026-09-19). 연습을 여러 개 걸쳐 둔 사람은
            눌러 봐야 알았다. */}
        {lead && (
          <p className="sh-topic">
            플레이 중인 주제 <b>{lead.title}</b>
          </p>
        )}
      </div>

      {/* 책상에 앉은 개미. 시안 원본(page_train)을 여백만 잘라 webp 로 구웠다.
          말풍선("오늘도 조금 더 성장!") 이 그림 안에 있어 따로 얹지 않는다. */}
      <div className="sh-stage" aria-hidden="true">
        <img className="sh-bubble" src="/assets/pageset/bubble.webp" alt="" />
        <img className="sh-hero-art" src="/assets/page_train/ant-desk.webp" alt="" />
      </div>

      {/* 오른쪽 두 칸. 시작 자산은 연습 목록이 한 값으로 모일 때만 그린다(agreed) —
          시즌마다 다르면 아무 값이나 대표로 세우는 셈이라 칸을 통째로 감춘다. */}
      <aside className="sh-side" aria-label="연습 요약">
        {cash !== undefined && (
          <dl className="sh-glass">
            <dt><CoinIcon />가상 투자 자금</dt>
            <dd className="num">{cash.toLocaleString('ko-KR')}<small>원</small></dd>
          </dl>
        )}
        <dl className="sh-glass">
          <dt><CheckRingIcon />진행 중인 연습</dt>
          {failure ? (
            <dd className="sh-glass-err"><ErrorState error={failure} onRetry={onRetry} inline /></dd>
          ) : (
            <>
              <dd className="num">
                {myLoading ? '…' : going}<small> / {total}</small>
              </dd>
              <span className="sh-bar" role="progressbar" aria-label="진행 중인 연습 비율"
                    aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
                <i style={{ width: `${percent}%` }} />
              </span>
              {/* 연습 허브(G-02)에서 옮겨 온 둘이다(2026-09-21). 완료 횟수는 거기 요약
                  스트립에만 있던 값이고, 복기 링크는 AI 가이드 카드가 하던 일이다 —
                  "끝나면 만들어집니다" 라는 안내문보다 만들어진 것을 여는 길이 낫다. */}
              <div className="sh-glass-foot">
                <span className="sh-done">완료 <b className="num">{done.length}</b>회</span>
                {lastDone ? (
                  <Link className="sh-glass-link" to={`/sim/${lastDone.seasonId}/result`}>
                    최근 복기 보기 ›
                  </Link>
                ) : (
                  <Link className="sh-glass-link" to="/sim/history">기록 보기 ›</Link>
                )}
              </div>
            </>
          )}
        </dl>
      </aside>
    </section>
  )
}

/* ── 연습 주제 카드 ────────────────────────────────────────
   제목은 서버가 준다(seasons.title). 사진은 seasonArt 표에서 제목으로 찾는다.

   **한 줄 설명(note)은 그리지 않는다(2026-09-21).** 사진 위에 얹힌 13px 세 줄이라
   읽히지 않았다 — 어두운 막을 더 진하게 깔면 사진이 죽고, 글자를 키우면 카드가
   제목보다 설명이 큰 덩어리가 된다. 카드는 "어떤 장이었나" 만 말하고, 설명은
   들어간 뒤(G-03 시즌 상세)에 제 크기로 읽는다.
   이미 진행 중인 주제는 테두리를 밝히고 버튼이 "이어서 하기" 로 바뀐다.

   한 줄에 다 안 들어가는 폭에서는 가로로 넘겨 본다(scroll-snap). 양옆 화살표는 그
   스크롤을 한 카드씩 옮기는 버튼이다 — 카드가 다 보이는 폭에서는 CSS 가 감춘다. */
function Topics({ practice, ongoing, loading, error, onRetry }: {
  practice: OpenRun[]; ongoing: MyRun[]; loading: boolean
  error: ApiError | null; onRetry: () => void
}) {
  const track = useRef<HTMLUListElement>(null)
  const failure = shown(error)
  const items = practice.filter(isJoinable).sort(joinableFirst)
  const liveById = new Map(ongoing.map((r) => [r.seasonId, r]))

  const slide = (dir: 1 | -1) => {
    const el = track.current
    if (!el) return
    const card = el.querySelector<HTMLElement>('.sh-topic-card')
    const step = card ? card.offsetWidth + 16 : el.clientWidth * 0.8
    el.scrollBy({ left: dir * step, behavior: 'smooth' })
  }

  return (
    <section className="sh-topics" aria-label="연습 주제">
      <button type="button" className="sh-nav prev" onClick={() => slide(-1)} aria-label="이전 주제">
        <Ico size={20}><path d="m15 5-7 7 7 7" /></Ico>
      </button>

      {loading && <p className="sh-state">주제를 불러오는 중…</p>}
      {failure && <div className="sh-state"><ErrorState error={failure} onRetry={onRetry} inline /></div>}
      {!loading && !failure && items.length === 0 && (
        <p className="sh-state">지금 참가할 수 있는 연습 주제가 없습니다.</p>
      )}

      {items.length > 0 && (
        <ul className="sh-topic-track" ref={track}>
          {items.map((s, i) => {
            const art = seasonArt(s.title)
            const live = liveById.get(s.id)
            return (
              <li
                key={s.id}
                className={`sh-topic-card${live ? ' is-live' : ''}`}
                data-season={art?.key}
                style={art ? { backgroundImage: `url(${art.art})`, backgroundPosition: art.position } : undefined}
              >
                <span className="sh-topic-no num">{String(i + 1).padStart(2, '0')}</span>
                <h2>{s.title}</h2>
                {live ? (
                  <Link className="sh-topic-go" to={`/sim/${live.seasonId}/play`}>
                    이어서 하기<ArrowIcon />
                  </Link>
                ) : (
                  <Link className="sh-topic-go" to={`/sim/seasons/${s.id}`}>
                    시작하기<ArrowIcon />
                  </Link>
                )}
              </li>
            )
          })}
        </ul>
      )}

      <button type="button" className="sh-nav next" onClick={() => slide(1)} aria-label="다음 주제">
        <Ico size={20}><path d="m9 5 7 7-7 7" /></Ico>
      </button>
    </section>
  )
}

/* ── 사실 띠 ──────────────────────────────────────────────
   연습이 어떤 것인지 네 가지로 말한다. 주제 수와 시작 자산은 목록에서 나오는 값이라
   하드코딩하지 않는다 — 시작 자산이 한 값으로 모이지 않으면 그 칸은 빠진다. */
function Facts({ practice }: { practice: OpenRun[] | undefined }) {
  const total = practice?.length ?? 0
  const cash = agreed(practice, 'initialCash')
  return (
    <footer className="sh-facts is-dark" aria-label="연습 안내">
      <ul>
        {total > 0 && (
          <li><GridIcon /><span>총 <b className="num">{total}개</b>의 연습 주제</span></li>
        )}
        <li><ClockIcon /><span>각기 다른 시기와 시장 상황</span></li>
        {cash !== undefined && (
          <li><CoinsIcon /><span>가상의 <b className="num">{manwon(cash)}</b>으로 진행</span></li>
        )}
        <li><BarsIcon /><span>실제 데이터 기반 시뮬레이션</span></li>
      </ul>
      <p className="sh-quote">
        <span className="sh-hand">"연습이 더 나은 투자를 만든다."</span>
        <small>— ANTENNA</small>
      </p>
    </footer>
  )
}

export default function SeasonHome() {
  const { data, loading, error, reload } = useAsync(getMyRuns)
  const { data: open, loading: openLoading, error: openError, reload: reloadOpen } = useAsync(getPracticeRuns)

  const ongoing = data?.ongoing ?? []
  const done = data?.done ?? []
  /* 여러 개가 진행 중이면 가장 최근 것을 시작 버튼에 건다. 나머지는 카드에서 이어간다. */
  const lead = [...ongoing].sort(recentFirst)[0] ?? null
  /* 히어로의 "지금 시작하기" 가 갈 곳. 카드와 같은 규칙으로 고른 맨 앞 주제다 —
     목록이 비었거나 아직 안 왔으면 null 이고, 그때는 버튼을 내린다. */
  const firstOpen = (open?.items ?? []).filter(isJoinable).sort(joinableFirst)[0]
  const startAt = firstOpen ? `/sim/seasons/${firstOpen.id}` : null

  return (
    <main className="main">
      <div className="main-inner sim-home">
        {/* 목록이 실패해도 화면 전체를 오류로 덮지 않는다 — 실패한 칸만 안내를
            띄우고 나머지는 그대로 읽힌다. 판단은 shown() 이 한다. */}
        <Hero
          lead={lead} practice={open?.items} ongoing={ongoing} done={done} startAt={startAt}
          myError={error} myLoading={loading} onRetry={reload}
        />
        <Topics
          practice={open?.items ?? []} ongoing={ongoing}
          loading={openLoading} error={openError} onRetry={reloadOpen}
        />
        <Facts practice={open?.items} />
      </div>
    </main>
  )
}
