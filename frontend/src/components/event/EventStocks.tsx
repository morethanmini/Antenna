/* 대회 종목 카드 — J-01. 서버가 준 stocks[] 를 그대로 그린다.

   **종목 목록을 화면 상수로 두지 않는다.** idx 가 온체인 커밋
   keccak(eventId, idx, side, noteHash) 의 재료라, 목록이 하나만 어긋나도 배팅이
   엉뚱한 종목에 걸린다. 순서까지 서버를 따른다 — 전에는 contest.ts 의
   CONTEST_CODES 를 돌며 종목 상세를 열 번 불렀다(2026-09-16 에 걷어냈다).

   ── 카드가 곧 등록 입구다 (2026-09-18) ──────────────────────
   전에는 카드가 보여 주기만 하고, 등록은 아래 묶음 폼에서 열 종목을 한꺼번에 적었다.
   이제 **종목마다 따로** 등록한다 — 카드를 누르면 그 종목의 C-01(예측 등록)로 가고,
   거기서 방향·목표가·근거·배팅을 적는다. 예측을 쓰는 화면이 한 벌뿐이라 대회 예측도
   평소 예측과 한 글자도 다르지 않게 된다.

   그래서 카드가 답해야 하는 것이 둘 늘었다.
     · 이 종목에 내가 걸었나            — mine
     · 지금까지 몇 개 걸었나 · 자격은    — 머리의 진행 줄 */
import { Link } from 'react-router-dom'
import type { EventHome, EventStockCard } from '../../api/events'
import StockLogo from '../stocks/StockLogo'

const won = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')}원`)

/** 배당률. 그 방향 풀이 0 이면 서버가 null 을 준다 — 0 배당은 "걸면 못 받는다" 는 거짓이다 */
const odds = (n: number | null) => (n === null ? '—' : `${n.toFixed(2)}배`)

/** 기준가 대비 지금. 기준가가 아직 없으면 잴 것이 없다 */
function change(base: number | null, last: number | null) {
  if (base === null || last === null || base === 0) return null
  return ((last - base) / base) * 100
}

type CardProps = {
  s: EventStockCard
  /** 등록을 받는 중인가. 아니면 카드는 눌러도 예측 탭이 열리지 않는다 */
  open: boolean
  /** C-01 이 대회 모드로 열리려면 주소에 이 값이 있어야 한다(StockDetail 가 본다) */
  slug: string
}

function StockCard({ s, open, slug }: CardProps) {
  const rate = change(s.basePrice, s.lastPrice)
  const tone = rate === null ? '' : rate > 0 ? ' is-up' : rate < 0 ? ' is-down' : ''
  const pool = s.pool.up + s.pool.down

  /* 등록 중이면 예측 탭으로, 아니면 종목 정보로. 닫힌 창에서 예측 탭을 열어 봐야
     쓸 수 없는 폼만 보인다. */
  const href = open
    ? `/stocks/${s.stockCode}?tab=predict&event=${slug}`
    : `/stocks/${s.stockCode}`

  const body = (
    <>
      <StockLogo code={s.stockCode} name={s.name} className="ev-card-logo" />
      <b className="ev-card-name">{s.name}</b>
      <span className="ev-card-code num">{s.stockCode}</span>

      {/* 걸었는지부터 답한다. 카드 열 장을 훑으며 "어디까지 했더라" 를 세는 화면이라
          이 한 줄이 맨 위에 있어야 한다 */}
      <span className={s.mine ? 'ev-card-flag is-done' : 'ev-card-flag'}>
        {s.mine ? '예측함' : open ? '예측하기 ›' : '아직'}
      </span>

      {/* 기준가가 판정의 기준이다. 최신가는 참고용이라 작게 둔다 */}
      <span className="ev-card-price num">{won(s.basePrice)}</span>
      <span className={`ev-card-rate num${tone}`}>
        {rate === null ? '—' : `${rate > 0 ? '+' : ''}${rate.toFixed(2)}%`}
      </span>

      {/* 남의 방향·목표가는 애초에 응답에 없다. 집계만 보인다 */}
      <dl className="ev-card-pool">
        <div><dt>오른다</dt><dd className="num">{odds(s.pool.oddsUp)}<i>{s.pool.upCount}명</i></dd></div>
        <div><dt>내린다</dt><dd className="num">{odds(s.pool.oddsDown)}<i>{s.pool.downCount}명</i></dd></div>
      </dl>
      {pool > 0 && <p className="ev-card-sum num">풀 {pool.toLocaleString('ko-KR')} ANT</p>}

      {s.mine && (
        <p className="ev-card-mine">
          <b>{s.mine.direction === 'UP' ? '오른다' : '내린다'}</b>
          <span className="num">{won(s.mine.targetPrice)}</span>
          <span className="num">{s.mine.stake.toLocaleString('ko-KR')} ANT</span>
        </p>
      )}

      {s.result && (
        <p className={`ev-card-result is-${s.result.winner.toLowerCase()}`}>
          {s.result.winner === 'TIE' ? '무승부 · 환불'
            : s.result.winner === 'UP' ? '올랐다' : '내렸다'}
          <span className="num">{won(s.result.closePrice)}</span>
        </p>
      )}
    </>
  )

  return (
    <li className={s.mine ? 'ev-card-wrap is-mine' : 'ev-card-wrap'}>
      {/* 이미 건 종목도 누를 수 있게 둔다 — 한 종목에 두 번은 못 걸지만(서버가 409),
          그 종목의 차트·근거를 다시 보러 가는 길까지 막을 이유가 없다 */}
      <Link className="ev-card" to={href}>{body}</Link>
    </li>
  )
}

export default function EventStocks({ event }: { event: EventHome }) {
  const { me, rules } = event
  const left = Math.max(0, rules.minStocks - me.stockCount)

  return (
    <section className="card ev-block" aria-labelledby="ev-stocks-h">
      <header className="ev-block-head">
        <h2 id="ev-stocks-h">대회 종목 {event.stocks.length}</h2>
        {/* 기준가가 덜 찬 회차는 아직 열리지 않는다 — 서버 크론이 그걸 보고 연다 */}
        {event.phase === 'BEFORE' && <p>기준가가 모두 정해지면 등록이 열립니다.</p>}
        {event.phase === 'ENTRY' && (
          <p>종목을 눌러 방향·목표가·배팅을 적습니다. 종목마다 한 번씩 겁니다.</p>
        )}
      </header>

      {/* 진행 줄. **자격을 여기서만 말한다** — 최소 종목 수는 등록을 막지 않고
          순위 자격으로만 걸리므로(2026-09-18), 화면이 말하지 않으면 다섯 개를 못 채운
          사람이 참가한 줄 알고 정산까지 간다. */}
      {event.phase !== 'BEFORE' && (
        <p className={left === 0 ? 'ev-progress is-ok' : 'ev-progress'}>
          <b className="num">{me.stockCount}/{event.stocks.length}종목</b>
          <span>
            {left === 0
              ? `참가 자격을 갖췄습니다 · 건 돈 ${me.staked.toLocaleString('ko-KR')} ANT`
              : `${left}종목 더 걸어야 순위에 오릅니다 (최소 ${rules.minStocks}종목)`}
          </span>
        </p>
      )}

      <ul className="ev-cards">
        {event.stocks.map((s) => (
          <StockCard
            key={s.stockCode}
            s={s}
            open={event.phase === 'ENTRY'}
            slug={event.slug ?? ''}
          />
        ))}
      </ul>
    </section>
  )
}
