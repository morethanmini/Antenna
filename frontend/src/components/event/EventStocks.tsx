/* 대회 종목 카드 — J-01. 서버가 준 stocks[] 를 그대로 그린다.

   **종목 목록을 화면 상수로 두지 않는다.** idx 가 온체인 커밋
   keccak(eventId, idx, side, noteHash) 의 재료라, 목록이 하나만 어긋나도 배팅이
   엉뚱한 종목에 걸린다. 순서까지 서버를 따른다 — 전에는 contest.ts 의
   CONTEST_CODES 를 돌며 종목 상세를 열 번 불렀다(2026-09-16 에 걷어냈다).

   덤으로 쿼리가 열에서 하나로 줄고, 기준가·풀·배당률·내 배팅이 같이 온다. */
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

function StockCard({ s }: { s: EventStockCard }) {
  const rate = change(s.basePrice, s.lastPrice)
  const tone = rate === null ? '' : rate > 0 ? ' is-up' : rate < 0 ? ' is-down' : ''
  const pool = s.pool.up + s.pool.down

  return (
    <li className={s.mine ? 'ev-card-wrap is-mine' : 'ev-card-wrap'}>
      <div className="ev-card is-static">
        <StockLogo code={s.stockCode} name={s.name} className="ev-card-logo" />
        <b className="ev-card-name">{s.name}</b>
        <span className="ev-card-code num">{s.stockCode}</span>

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
      </div>
    </li>
  )
}

export default function EventStocks({ event }: { event: EventHome }) {
  return (
    <section className="card ev-block" aria-labelledby="ev-stocks-h">
      <header className="ev-block-head">
        <h2 id="ev-stocks-h">대회 종목 {event.stocks.length}</h2>
        {/* 기준가가 덜 찬 회차는 아직 열리지 않는다 — 서버 크론이 그걸 보고 연다 */}
        {event.phase === 'BEFORE' && <p>기준가가 모두 정해지면 등록이 열립니다.</p>}
        {event.phase === 'ENTRY' && <p>아래 등록 칸에서 방향과 목표가를 한 번에 적습니다.</p>}
      </header>

      <ul className="ev-cards">
        {event.stocks.map((s) => <StockCard key={s.stockCode} s={s} />)}
      </ul>
    </section>
  )
}
