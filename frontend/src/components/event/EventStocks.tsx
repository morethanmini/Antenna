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
import { payoutOfBet, type EventHome, type EventPhase, type EventStockCard } from '../../api/events'
import StockLogo from '../stocks/StockLogo'

const won = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')}원`)

/** 배당률. 그 방향 풀이 0 이면 서버가 null 을 준다 — 0 배당은 "걸면 못 받는다" 는 거짓이다 */
const odds = (n: number | null) => (n === null ? '—' : `${n.toFixed(2)}배`)

type CardProps = {
  s: EventStockCard
  /** 회차가 어느 단계인가. 깃발 문구와 링크가 이 값으로 갈린다 */
  phase: EventPhase
  /** C-01 이 대회 모드로 열리려면 주소에 이 값이 있어야 한다(StockDetail 가 본다) */
  slug: string
}

/* 깃발 — 카드가 맨 먼저 답하는 한 줄.
   **단계마다 말이 달라야 한다**(2026-09-18). 전에는 등록이 닫힌 뒤에도 안 건 종목에
   "아직" 이 떠 있었다. 아직 할 수 있다는 말로 읽히는데 등록 창은 이미 닫혔다 —
   눌러 들어가도 "지금은 대회 등록 시간이 아닙니다" 만 만난다.
   건 종목도 마찬가지다. 등록이 끝난 뒤의 "예측함" 은 지난 일을 말할 뿐이어서,
   지금 무엇을 기다리는지(판정)를 알려 주지 못한다. */
function flagOf(s: EventStockCard, phase: EventPhase) {
  if (s.result) return s.mine ? '판정 끝' : '미참여'
  if (phase === 'ENTRY') return s.mine ? '예측함' : '예측하기 ›'
  if (phase === 'BEFORE') return '아직'
  /* SEALED — 등록은 닫혔고 판정을 기다린다 */
  return s.mine ? '판정 대기' : '미참여'
}

function StockCard({ s, phase, slug }: CardProps) {
  const open = phase === 'ENTRY'
  const pool = s.pool.up + s.pool.down
  /* 맞았을 때 받을 돈. **등록이 닫힌 뒤에만 적는다**(2026-09-18).
     등록 중에는 풀이 계속 커져 이 값이 시시각각 달라지는데, 카드 열 장에 흔들리는
     숫자를 깔아 두면 "지금 이만큼 받는다" 로 읽힌다. 걸기 직전의 한 종목에 대해서는
     C-01 배팅 칸이 같은 값을 "지금 기준" 이라는 단서와 함께 이미 보여 준다 —
     거기는 고르는 자리라 흔들리는 값이어도 판단 재료가 된다.
     이미 건 배팅이라 내 돈은 풀에 들어 있다 — payoutIfIBet 이 아니라 이쪽이다. */
  const win = s.mine && phase === 'SEALED'
    ? payoutOfBet(s.pool, s.mine.direction, s.mine.stake)
    : null

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
        {flagOf(s, phase)}
      </span>

      {/* 판정의 **기준**이 되는 값이라 이름을 붙인다(2026-09-18). 숫자만 크게 놓으면
          현재가로 읽히는데, 이 회차의 승패는 이 값 대비로 갈린다.
          기준가는 관리자가 넣기 전까지 비어 있다 — 그때 대시 하나만 남기면 값이
          0 인지 아직 없는지 알 수 없다. */}
      <span className="ev-card-price num">
        <i>기준가</i>
        {s.basePrice === null ? <em>아직 없음</em> : `${s.basePrice.toLocaleString('ko-KR')}원`}
      </span>

      {/* 최신 종가와 그 대비를 **적지 않는다**(2026-09-18).
          판정은 오직 기준가 대비 판정일 종가로 갈리는데, 그 사이의 값을 카드마다
          띄우면 지금 이기고 있는지를 말하는 것처럼 읽힌다 — 실제로는 아무것도
          정해지지 않은 중간값이다. 서버는 여전히 lastPrice 를 내려 주므로,
          필요해지면 이 자리에 되살리면 된다(.ev-card-rate 규칙도 남겨 뒀다). */}

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
          {/* 맞았을 때 받을 돈. **판정 전에만** 적는다 — 판정이 끝나면 아래 결과 줄에
              실제로 받은 금액이 나오고, 그 옆에 예상치가 같이 있으면 어느 쪽이 진짜인지
              헷갈린다. null 은 아직 체인에 안 올라간 배팅이라(PENDING·FAILED 는 풀
              집계에서 빠진다) 지어내지 않고 줄을 걷는다. */}
          {!s.result && win !== null && (
            <span className="ev-card-win num">맞히면 {win.toLocaleString('ko-KR')} ANT</span>
          )}
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

  /* 내가 건 것이 **다 맞았을 때** 받을 돈의 합. 종목마다 흩어진 금액을 한 번은
     합쳐 줘야 "지금 얼마가 걸려 있나" 에 답이 된다 — 카드 다섯 장을 눈으로 더하게
     둘 값이 아니다.
     카드와 같은 문(phase === 'SEALED')을 쓴다. 등록 중에 합계를 띄우면 종목을 하나
     더 걸 때마다 다른 종목의 몫까지 같이 흔들려, 방금 한 일과 숫자의 변화가 어긋난다.
     아직 체인에 안 올라간 배팅(null)은 빼고 센다. */
  const win = event.phase !== 'SEALED' ? 0 : event.stocks.reduce(
    (sum, s) => sum + (s.mine && !s.result ? (payoutOfBet(s.pool, s.mine.direction, s.mine.stake) ?? 0) : 0),
    0,
  )

  return (
    <section className="card ev-block" aria-labelledby="ev-stocks-h">
      <header className="ev-block-head">
        <h2 id="ev-stocks-h">대회 종목 {event.stocks.length}</h2>
        {/* 기준가가 덜 찬 회차는 아직 열리지 않는다 — 서버 크론이 그걸 보고 연다 */}
        {event.phase === 'BEFORE' && <p>기준가가 모두 정해지면 등록이 열립니다.</p>}
        {event.phase === 'ENTRY' && (
          <p>종목을 눌러 방향·목표가·배팅을 적습니다. 종목마다 한 번씩 겁니다.</p>
        )}
        {/* 등록이 닫히면 풀이 더 커지지 않는다 — 그때부터 그 금액은 움직이지 않는다 */}
        {event.phase === 'SEALED' && (
          <p>등록이 끝나 <b>배당이 굳었습니다.</b> 맞힌 종목은 아래 금액을 그대로 받습니다.</p>
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
          {/* "모두" 를 빼지 않는다 — 다섯 종목을 다 맞혀야 나오는 금액이라, 그냥
              "맞히면" 으로 적으면 받게 될 돈처럼 읽힌다 */}
          {win > 0 && (
            <span className="ev-progress-win">
              모두 맞히면 <b className="num">{win.toLocaleString('ko-KR')} ANT</b>
            </span>
          )}
        </p>
      )}

      <ul className="ev-cards">
        {event.stocks.map((s) => (
          <StockCard
            key={s.stockCode}
            s={s}
            phase={event.phase}
            slug={event.slug ?? ''}
          />
        ))}
      </ul>
    </section>
  )
}
