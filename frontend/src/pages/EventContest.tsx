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
import StockLogo from '../components/stocks/StockLogo'
import {
  CONTEST,
  CONTEST_CODES,
  PHASE_LABEL,
  cardHref,
  dayStamp,
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

/** 머리 — 지금 어느 대목인지와 다음 마감 하나만 크게 */
function Hero({ phase }: { phase: ReturnType<typeof phaseAt> }) {
  /* 단계마다 "다음에 올 일" 이 다르다. 셋 다 보여 주면 무엇을 봐야 할지
     고르게 만드는 셈이라, 지금 기다리는 것 하나만 적는다. */
  const next =
    phase === 'before' ? { label: '등록 시작', at: stamp(CONTEST.opensAt) }
    : phase === 'open' ? { label: '등록 마감', at: stamp(CONTEST.closesAt) }
    : phase === 'judging' ? { label: '결과 공개', at: dayStamp(CONTEST.revealDate) }
    : null

  return (
    <header className="ev-hero">
      <div className="ev-hero-text">
        <p className="ev-kicker">
          <span className={`ev-phase is-${phase}`}>{PHASE_LABEL[phase]}</span>
          이벤트
        </p>
        <h1 id="ev-h">{CONTEST.name}</h1>
        <p className="ev-lede">{CONTEST.lede}</p>

        {next && (
          <p className="ev-next">
            <b>{next.label}</b>
            <span className="num">{next.at}</span>
          </p>
        )}
      </div>
      <img
        className="ev-hero-art"
        src="/assets/character/antenna-characters-welcome.png"
        alt="" aria-hidden="true"
      />
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
        <p>
          {phase === 'open'
            ? '카드를 누르면 그 종목의 예측 등록으로 바로 갑니다.'
            : '등록 창이 열리면 카드에서 바로 예측할 수 있습니다.'}
        </p>
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

/** 규칙 — 네 줄. 순서가 곧 시간 순서다 */
function Rules() {
  const rows = [
    { k: '시작', v: `${dayStamp('2026-09-18')} 시작합니다.` },
    {
      k: '등록',
      v: `${stamp(CONTEST.opensAt)} ~ ${stamp(CONTEST.closesAt)} 사이에만 예측을 등록할 수 있습니다.`,
    },
    { k: '판정', v: `${dayStamp(CONTEST.targetDate)} 종가를 맞히는 대회입니다.` },
    { k: '공개', v: `결과는 ${dayStamp(CONTEST.revealDate)}에 공개합니다.` },
  ]

  return (
    <section className="card ev-block" aria-labelledby="ev-rules-h">
      <header className="ev-block-head">
        <h2 id="ev-rules-h">대회 규칙</h2>
        <p>열 종목이 같은 날 판정되므로, 방향과 정확도를 한 줄로 견줄 수 있습니다.</p>
      </header>
      <dl className="ev-rules">
        {rows.map((r) => (
          <div key={r.k}>
            <dt>{r.k}</dt>
            <dd>{r.v}</dd>
          </div>
        ))}
      </dl>
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
        <p>대회 종목에 예측을 등록하면 여기에 이름이 올라갑니다.</p>
      </header>

      {people.length === 0 ? (
        <p className="ev-state is-quiet">
          아직 참가자가 없어요. 첫 번째로 이름을 올려 보세요.
        </p>
      ) : (
        <ul className="ev-tiles">
          {people.map((p) => (
            <li key={p.userId}>
              <Link className="ev-tile" to={`/channels/${p.userId}`}>
                <img className="ev-tile-face" src={p.avatarUrl ?? ''} alt="" aria-hidden="true" />
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
        <Rules />
        <People />
      </div>
    </main>
  )
}
