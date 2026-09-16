/* J-01 천하제일 단타대회 · /events/scalping
   담당 스토리 [ANT-FE-EVENT-CONTEST]

   이벤트용 예측 대회. 들어오는 길은 **링크와 인사이트 홈 배너 둘뿐**이라
   사이드바에 메뉴를 만들지 않는다. 그래서 이 화면은 처음 온 사람이 아무 맥락
   없이 착지한다고 보고 짰다 — 무슨 대회인지, 언제까지 뭘 해야 하는지,
   어디를 눌러야 하는지가 한 화면에 다 있어야 한다.

   차례는 "무엇을 해야 하나" 가 먼저다. 규칙을 맨 위에 길게 깔면 종목 카드가
   접히는데, 정작 눌러야 하는 것은 카드다. 그래서 머리에 지금 단계와 마감만
   크게 적고, 자세한 규칙은 카드 아래로 내렸다.

   대회 정의(날짜·종목·글귀)는 event/contest.ts 한 곳에 있다. 이 파일에는
   날짜를 적지 않는다 — 일정이 밀릴 때 고칠 곳이 둘이 되면 하나를 빠뜨린다. */
import { Link } from 'react-router-dom'
import { fetchStocks } from '../api/stocks'
import type { StockListItem } from '../api/stocks'
import { useAsync } from '../api/useAsync'
import { DEFAULT_AVATAR } from '../auth/context'
import StockLogo from '../components/stocks/StockLogo'
import {
  BANNER_BASE,
  CONTEST,
  CONTEST_CODES,
  HERO,
  RULES,
  cardHref,
  phaseAt,
  stamp,
} from '../event/contest'
import '../styles/screens/event-contest.css'

/** 못 찾은 종목의 자리. 상장폐지·코드 오타로 한 칸이 비어도 열 칸은 선다 —
 *  한 칸이 사라지면 "열 종목" 이라는 말과 화면이 어긋난다 */
const missing = (code: string): StockListItem => ({
  code, name: code, sector: null, market: null,
  prevClose: null, changeRate: null, per: null, pbr: null, marketCap: null,
  predictionCount: 0, upRatio: null, watched: false,
})

/* 10개 종목을 한 번에 받는 파라미터가 서버에 없다(StockFilter 에 codes 가 없다).
   그래서 코드마다 q= 로 한 건씩 부르고 동시에 기다린다 — 열 번이라 감당되는
   수고이고, 서버에 codes 를 새로 뚫는 것보다 손이 적다. 종목이 크게 늘면
   그때 서버 쪽을 여는 게 맞다.

   **모듈 스코프 함수다.** useAsync 는 load 가 바뀌면 다시 읽으므로 컴포넌트
   안에서 만들면 매 렌더 새로 읽는다(useAsync 머리말). */
function loadContestStocks(): Promise<StockListItem[]> {
  return Promise.all(
    CONTEST_CODES.map(async (code) => {
      try {
        /* fetchStocks 는 필터를 받아 **함수를 돌려준다**(커서는 부르는 쪽 몫).
           여기선 커서가 없으니 빈 질의로 한 번만 부른다. */
        const page = await fetchStocks({ q: code, sort: 'MARKET_CAP' })({})
        /* q 는 이름·코드 부분 일치라 다른 종목이 딸려 올 수 있다 */
        return page.items.find((s) => s.code === code) ?? missing(code)
      } catch {
        return missing(code)
      }
    }),
  )
}

const pct = (n: number | null) =>
  n === null ? '—' : `${n > 0 ? '+' : ''}${n.toFixed(2)}%`

const won = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')}원`)

/* 머리 — 대회 이름 · 한 줄 소개 · 다음 마감 · 규칙을 한 카드에 담는다.

   **규칙을 따로 카드로 두지 않는다**(2026-09-16). 머리가 왼쪽에 글만 두고
   오른쪽을 통째로 비워 두는 모양이었는데, 그 빈자리에 규칙이 들어가면 머리
   하나로 "무슨 대회인지 · 언제 뭘 해야 하는지" 가 끝난다. 카드 하나가 줄고
   종목 카드가 그만큼 위로 올라온다.

   글자는 대부분 그림이다. 읽어 주는 기기가 그림 속 글자를 못 읽으므로,
   제목·소개는 alt 로, 규칙 네 줄은 화면 밖 목록으로 함께 깐다. */
function Hero({ phase }: { phase: ReturnType<typeof phaseAt> }) {
  /* 단계마다 "다음에 올 일" 이 다르다. 셋 다 보여 주면 무엇을 봐야 할지
     고르게 만드는 셈이라, 지금 기다리는 것 하나만 적는다. */
  const next =
    phase === 'before' ? { label: '등록 시작', at: stamp(CONTEST.opensAt) }
    : phase === 'open' ? { label: '등록 마감', at: stamp(CONTEST.closesAt) }
    : phase === 'judging' ? { label: '결과 공개', at: stamp(CONTEST.revealAt) }
    : null

  return (
    <header className="ev-hero">
      {/* 바탕 — 배너와 같은 조각을 쓴다. 두 화면이 같은 이벤트라는 게 보인다 */}
      <span className="ev-hero-bg" aria-hidden="true">
        <img className="ev-hero-bgimg" src={BANNER_BASE.bg} alt="" />
        <img className="ev-hero-chart" src={BANNER_BASE.chart} alt="" />
        <img className="ev-hero-confetti" src={BANNER_BASE.confetti} alt="" />
      </span>

      {/* **상자 폭이 곧 제목 폭이다.** 그래야 소개 줄과 알약이 제목에 가로
          가운데로 맞는다 — 폭이 다른 것을 옆에 끼우면 아래 줄들이 제목이 아니라
          칸 왼쪽 끝을 기준으로 서 버린다. */}
      <div className="ev-hero-lead">
        <img className="ev-hero-event" src={HERO.event} alt="이벤트" />

        <h1 id="ev-h" className="ev-hero-h1">
          <img className="ev-hero-title" src={HERO.title} alt={CONTEST.name} />
        </h1>
        <img className="ev-hero-lede" src={HERO.lede} alt={CONTEST.lede} />

        {/* **시작 전에도 글자로 그린다.** 원래는 그림(HERO.opensAt)을 썼는데 거기
            "13:10" 이 구워져 있고, 담당자 확정값이 13:30 으로 바뀌었다(2026-09-16).
            날짜가 박힌 그림은 일정이 흔들릴 때마다 조용히 거짓말을 한다 — 여기만
            글자로 돌리면 contest.ts 한 곳을 고치는 것으로 끝난다.
            새 그림을 받으면 아래 한 줄을 되살리면 된다:
              {phase === 'before' ? <img className="ev-hero-next" src={HERO.opensAt}
                 alt={`${next?.label} ${next?.at}`} /> : …} */}
        {next ? (
          <p className="ev-next">
            <b>{next.label}</b>
            <span className="num">{next.at}</span>
          </p>
        ) : null}
      </div>

      <img className="ev-hero-mascot" src={BANNER_BASE.mascot} alt="" aria-hidden="true" />

      <div className="ev-hero-rules">
        <img className="ev-hero-rules-title" src={HERO.rulesTitle} alt="대회 규칙" />
        <img className="ev-hero-rules-panel" src={HERO.rules} alt="" aria-hidden="true" />
        {/* 위 그림에 구워진 것과 같은 내용. 읽어 주는 기기는 이쪽만 읽는다 */}
        <dl className="sr-only">
          {RULES.map((r) => (
            <div key={r.k}><dt>{r.k}</dt><dd>{r.v}</dd></div>
          ))}
        </dl>
      </div>
    </header>
  )
}

/** 종목 카드 한 장 */
function StockCard({ s, phase }: { s: StockListItem; phase: ReturnType<typeof phaseAt> }) {
  const rate = s.changeRate ?? null
  const tone = rate === null ? '' : rate > 0 ? ' is-up' : rate < 0 ? ' is-down' : ''

  return (
    <li>
      <Link className="ev-card" to={cardHref(s.code, phase)}>
        <StockLogo code={s.code} name={s.name} className="ev-card-logo" />
        <b className="ev-card-name">{s.name}</b>
        <span className="ev-card-code num">{s.code}</span>
        <span className="ev-card-price num">{won(s.prevClose ?? null)}</span>
        {/* 등락은 낱말이 없어 색만 남는다 — 부호를 반드시 붙인다 */}
        <span className={`ev-card-rate num${tone}`}>{pct(rate)}</span>
        <span className="ev-card-go" aria-hidden="true">
          {phase === 'open' ? '예측하기 ›' : '종목 보기 ›'}
        </span>
      </Link>
    </li>
  )
}

/** 대회 종목 10 */
function Stocks({ phase }: { phase: ReturnType<typeof phaseAt> }) {
  const { data, loading, error, reload } = useAsync(loadContestStocks)

  return (
    <section className="card ev-block" aria-labelledby="ev-stocks-h">
      <header className="ev-block-head">
        <h2 id="ev-stocks-h">대회 종목 {CONTEST_CODES.length}</h2>
        {/* 등록 창이 닫혀 있을 때는 아무 말도 하지 않는다. "열리면 예측할 수
            있습니다" 는 지금 할 수 있는 일이 없다는 말을 길게 적은 것이라,
            규칙에 이미 적힌 날짜를 한 번 더 읽게 만들 뿐이다. */}
        {phase === 'open' && <p>카드를 누르면 그 종목의 예측 등록으로 바로 갑니다.</p>}
      </header>

      {loading && (
        /* 자리를 미리 잡아 둔다. 카드가 없다가 열 장이 한 번에 나타나면
           아래 규칙 칸이 통째로 밀려 내려간다 */
        <ul className="ev-cards" aria-hidden="true">
          {CONTEST_CODES.map((code) => (
            <li key={code}><span className="ev-card is-skel" /></li>
          ))}
        </ul>
      )}

      {error && (
        <p className="ev-state">
          종목을 불러오지 못했어요.{' '}
          <button type="button" className="ev-retry" onClick={reload}>다시 시도</button>
        </p>
      )}

      {data && (
        <ul className="ev-cards">
          {data.map((s) => <StockCard key={s.code} s={s} phase={phase} />)}
        </ul>
      )}
    </section>
  )
}


/** 참가자 타일 */
function People() {
  /* **아직 서버가 참가자를 주지 않는다.** "이 대회에 등록한 사람" 을 가려내려면
     예측에 대회 표시가 붙어야 하는데, 그 표시는 등록 양식에 붙을 대회 전용
     요소와 함께 정해진다. 그때 이 함수만 실제 호출로 바꾸면 된다 —
     화면은 빈 목록도 그릴 줄 알아야 하므로 지금 그 길을 먼저 만들어 둔다. */
  const people: { userId: number; nickname: string; avatarUrl: string | null }[] = []

  return (
    <section className="card ev-block" aria-labelledby="ev-people-h">
      <header className="ev-block-head">
        <h2 id="ev-people-h">참가자 {people.length > 0 && <span className="num">{people.length}</span>}</h2>
        {/* 아무도 없을 때만 안내한다. 이미 이름이 올라와 있으면 그 줄이
            무엇을 하라는 말인지 설명하고 있어, 같은 말을 한 번 더 할 이유가 없다 */}
        {people.length === 0 && <p>대회 종목에 예측을 등록하면 여기에 이름이 올라갑니다.</p>}
      </header>

      {people.length === 0 ? (
        <p className="ev-state is-quiet">
          아직 참가자가 없어요. 첫 번째로 이름을 올려 보세요.
        </p>
      ) : (
        <ul className="ev-tiles">
          {people.map((p) => (
            <li key={p.userId}>
              {/* 칸이 좁아 닉네임이 잘린다. 손을 얹으면 전체가 뜨도록 title 을 단다 —
                  잘리지 않은 이름에도 붙지만, 잘렸는지 아닌지는 CSS 만으로 알 수 없다 */}
              <Link className="ev-tile" to={`/channels/${p.userId}`} title={p.nickname}>
                {/* 사진을 안 올린 사람은 서버가 null 을 준다. 빈 문자열을 넣으면
                    브라우저가 **지금 주소를 다시 부른다** — 기본 개미로 떨어뜨린다 */}
                <img className="ev-tile-face" src={p.avatarUrl ?? DEFAULT_AVATAR}
                     alt="" aria-hidden="true" />
                <span className="ev-tile-name">{p.nickname}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

export default function EventContest() {
  /* 단계는 렌더할 때 한 번 센다. 초 단위로 다시 세지 않는 것은, 이 화면이
     열려 있는 동안 창이 열리거나 닫히는 순간을 실시간으로 넘길 만큼
     중요하지 않아서다 — 새로고침하거나 카드를 누를 때 다시 센다. */
  const phase = phaseAt()

  return (
    <main className="main">
      <div className="main-inner ev" aria-labelledby="ev-h">
        <Hero phase={phase} />
        <Stocks phase={phase} />
        <People />
      </div>
    </main>
  )
}
