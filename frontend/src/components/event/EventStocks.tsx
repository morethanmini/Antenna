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
import { Fragment } from 'react'
import { Link } from 'react-router-dom'
import { payoutOfBet, type EventHome, type EventPhase, type EventStockCard } from '../../api/events'
import StockLogo from '../stocks/StockLogo'

const won = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')}원`)

/**
 * 배당률. 그 방향 풀이 0 이면 서버가 null 을 준다 — 0 배당은 "걸면 못 받는다" 는 거짓이다.
 *
 * 그럴 때는 **아무것도 그리지 않는다**. 자리지기로 — 를 두었더니 아직 아무도 안 건
 * 종목마다 "— 0명" 이 두 줄씩 떠서, 열 장을 훑을 때 대시만 스무 개가 눈에 밟혔다.
 * 0명이라는 말이 이미 "배당이 없다" 는 뜻이라 대시는 같은 말을 한 번 더 하는 셈이다.
 */
const odds = (n: number | null) =>
  n === null ? null : <span className="ev-odds">{n.toFixed(2)}배</span>

/** 기준가 대비 지금. 기준가가 아직 없으면 잴 것이 없다 */
function change(base: number | null, last: number | null) {
  if (base === null || last === null || base === 0) return null
  return ((last - base) / base) * 100
}

type CardProps = {
  s: EventStockCard
  /** 회차가 어느 단계인가. 버튼 문구와 링크가 이 값으로 갈린다 */
  phase: EventPhase
  /** C-01 이 대회 모드로 열리려면 주소에 이 값이 있어야 한다(StockDetail 가 본다) */
  slug: string
}

/* 버튼에 적을 말 — 카드가 맨 먼저 답하는 한 줄.

   **단계마다 말이 달라야 한다**(김경민, 2026-09-18). 전에는 등록이 닫힌 뒤에도 안 건
   종목에 "아직" 이 떠 있었다. 아직 할 수 있다는 말로 읽히는데 등록 창은 이미 닫혔다 —
   눌러 들어가도 "지금은 대회 등록 시간이 아닙니다" 만 만난다. 건 종목도 마찬가지다.
   등록이 끝난 뒤의 "예측함" 은 지난 일을 말할 뿐이어서, 지금 무엇을 기다리는지(판정)를
   알려 주지 못한다.

   그 분기를 깃발 칩이 아니라 **버튼**이 받는다(2026-09-17 합침). 카드에서 상태를
   말하는 자리를 하나로 줄이면서 깃발을 걷었기 때문이다 — 말은 그대로 살린다. */
function ctaOf(s: EventStockCard, phase: EventPhase) {
  if (s.result) return s.mine ? '판정 끝' : '미참여'
  if (phase === 'ENTRY') return s.mine ? '예측 완료' : '예측 등록 ›'
  if (phase === 'BEFORE') return '아직'
  /* SEALED — 등록은 닫혔고 판정을 기다린다 */
  return s.mine ? '판정 대기' : '미참여'
}

function StockCard({ s, phase, slug }: CardProps) {
  const open = phase === 'ENTRY'
  const rate = change(s.basePrice, s.lastPrice)
  const tone = rate === null ? '' : rate > 0 ? ' is-up' : rate < 0 ? ' is-down' : ''
  const pool = s.pool.up + s.pool.down

  /* 등록 중이면 예측 탭으로, 아니면 종목 정보로. 닫힌 창에서 예측 탭을 열어 봐야
     쓸 수 없는 폼만 보인다. */
  const href = open
    ? `/stocks/${s.stockCode}?tab=predict&event=${slug}`
    : `/stocks/${s.stockCode}`

  /* 막대 비율. 아무도 안 걸었으면 반반으로 칠하지 않는다 — 그러면 "양쪽이 팽팽하다"
     는 거짓말이 된다. 그럴 때는 빈 트랙만 남긴다(.is-empty). */
  const upPct = pool === 0 ? 0 : (s.pool.up / pool) * 100

  /* 맞았을 때 받을 돈. **등록이 닫힌 뒤에만 적는다**(김경민, 2026-09-18).
     등록 중에는 풀이 계속 커져 이 값이 시시각각 달라지는데, 카드 열 장에 흔들리는
     숫자를 깔아 두면 "지금 이만큼 받는다" 로 읽힌다. 걸기 직전의 한 종목에 대해서는
     C-01 배팅 칸이 같은 값을 "지금 기준" 이라는 단서와 함께 이미 보여 준다.
     이미 건 배팅이라 내 돈은 풀에 들어 있다 — payoutIfIBet 이 아니라 이쪽이다.

     판정이 끝나면 적지 않는다(!s.result). 결과 줄에 실제로 받은 금액이 나오는데
     그 옆에 예상치가 같이 있으면 어느 쪽이 진짜인지 헷갈린다. */
  const win = s.mine && !s.result && phase === 'SEALED'
    ? payoutOfBet(s.pool, s.mine.direction, s.mine.stake)
    : null

  /* 내가 건 것. 카드에 줄로 펴지 않고 완료 버튼의 말풍선으로 접었다 — 한 장만 세 줄
     길어져 격자에서 튀었고, 같은 내용이 아래 "내 참가 내역" 에 이미 펴져 있다.

     말풍선은 눈으로만 보는 것이라(aria-hidden) 읽어 주는 몫은 이 문장이 맡는다.
     브라우저 기본 title 을 쓰지 않는 이유는 지도(MarketMap)와 같다 — 한 줄짜리
     회색 상자라 값이 여럿이면 읽기 어렵고 뜨기까지 1 초 넘게 걸린다. */
  const mineLabel = s.mine
    ? `${ctaOf(s, phase)} — ${s.mine.direction === 'UP' ? '오른다' : '내린다'},`
      + ` 목표가 ${won(s.mine.targetPrice)}, ${s.mine.stake.toLocaleString('ko-KR')} ANT`
      + (win === null ? '' : `, 맞히면 ${win.toLocaleString('ko-KR')} ANT`)
    : undefined

  const body = (
    <>
      <StockLogo code={s.stockCode} name={s.name} className="ev-card-logo" />
      <b className="ev-card-name">{s.name}</b>
      <span className="ev-card-code num">{s.stockCode}</span>


      {/* 기준가·등락률을 왼쪽에, 들어가는 문을 오른쪽에 둔다(설계 그림). */}
      <div className="ev-card-mid">
        <div className="ev-card-figs">
          {/* 판정의 **기준**이 되는 값이라 이름을 붙인다(김경민, 2026-09-18). 숫자만
              크게 놓으면 현재가로 읽히는데, 이 회차의 승패는 이 값 대비로 갈린다.
              기준가는 관리자가 넣기 전까지 비어 있다 — 그때 대시 하나만 남기면 값이
              0 인지 아직 없는지 알 수 없다. */}
          <span className="ev-card-price num">
            <i>기준가</i>
            {s.basePrice === null ? <em>아직 없음</em> : `${s.basePrice.toLocaleString('ko-KR')}원`}
          </span>
          {/* 최신 종가와 그 대비. **없으면 줄째로 걷는다**(김경민, 2026-09-18) — 전에는
              대시 하나만 남았는데, 기준가 바로 옆의 정체 모를 작대기가 기준가에 딸린
              기호처럼 보였다. 참고용 값이라 없을 때 자리까지 지킬 이유가 없다. */}
          {rate !== null && s.lastPrice !== null && (
            <span className={`ev-card-rate num${tone}`}>
              최신 {s.lastPrice.toLocaleString('ko-KR')}원
              <i>{`${rate > 0 ? '+' : ''}${rate.toFixed(2)}%`}</i>
            </span>
          )}
        </div>
        {/* 걸었으면 여기서 멈춘다. 한 종목에 두 번은 못 걸고(서버가 409),
            버튼만 잠가 두면 카드는 그대로 눌려 예측 탭이 열린다 — 아래에서
            카드째 링크를 뗀다. 문구가 곧 상태라 따로 배지를 달지 않는다 */}
        <span
          className={s.mine ? 'ev-card-cta is-done' : 'ev-card-cta'}
          aria-disabled={s.mine ? true : undefined}
          aria-label={mineLabel}
        >
          {ctaOf(s, phase)}
          {s.mine && (
            <span className="ev-card-tip" aria-hidden="true">
              <span className="ev-card-tip-name">내 예측</span>
              <span className="ev-card-tip-rows">
                <span>
                  <i>방향</i>
                  <b className={s.mine.direction === 'UP' ? 'up' : 'down'}>
                    {s.mine.direction === 'UP' ? '오른다' : '내린다'}
                  </b>
                </span>
                <span><i>목표가</i><b className="num">{won(s.mine.targetPrice)}</b></span>
                <span><i>배팅</i><b className="num">{s.mine.stake.toLocaleString('ko-KR')} ANT</b></span>
                {/* 맞으면 받을 돈. 카드에 줄로 펴지 않고 여기 넣는다 — 걸린 카드만
                    길어져 격자에서 튀는 것을 막으려고 내 배팅 줄을 접었고, 이 값도
                    같은 자리에 둔다. 봉인 뒤에만 값이 잡힌다(win). */}
                {win !== null && (
                  <span><i>맞히면</i><b className="num">{win.toLocaleString('ko-KR')} ANT</b></span>
                )}
              </span>
            </span>
          )}
        </span>
      </div>

      {/* 남의 방향·목표가는 애초에 응답에 없다. 집계만 보인다.
          숫자 두 줄보다 막대 한 줄이 "어느 쪽으로 쏠렸나" 를 먼저 답한다 */}
      <dl className="ev-card-pool">
        <div className="ev-card-pool-head"><dt>오른다</dt><dt>내린다</dt></div>
        <div className={pool === 0 ? 'ev-card-bar is-empty' : 'ev-card-bar'}>
          <i className="is-up" style={{ width: `${upPct}%` }} />
        </div>
        <div className="ev-card-pool-foot">
          <dd className="num">{odds(s.pool.oddsUp)}<i>{s.pool.upCount}명</i></dd>
          <dd className="num">{odds(s.pool.oddsDown)}<i>{s.pool.downCount}명</i></dd>
        </div>
      </dl>

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
      {/* 건 종목은 카드째 링크를 뗀다(2026-09-17). 한 종목당 한 번이라
          누를 데가 남아 있으면 예측 탭이 열리고, 거기서 서버가 409 로 막는다 —
          막을 것을 눌러 보게 한 뒤 막는 셈이다. is-static 이 커서·hover 도 끈다. */}
      {s.mine
        ? <div className="ev-card is-static">{body}</div>
        : <Link className="ev-card" to={href}>{body}</Link>}
    </li>
  )
}

export default function EventStocks({ event }: { event: EventHome }) {
  const { me, rules } = event
  const left = Math.max(0, rules.minStocks - me.stockCount)

  /* 걸음 칸. 칸 수는 **대회 종목 수**다 — 열 종목이면 열 칸. 자격선(최소 종목 수)은
     칸이 아니라 아래 문장이 말한다. 서버가 더 센 개수를 주는 경우는 없지만, 칸보다
     많이 차면 줄이 넘치므로 종목 수에서 끊는다. */
  const doneSteps = Math.min(me.stockCount, event.stocks.length)

  /* 내가 건 것이 **다 맞았을 때** 받을 돈의 합(김경민, 2026-09-18). 종목마다 흩어진
     금액을 한 번은 합쳐 줘야 "지금 얼마가 걸려 있나" 에 답이 된다 — 카드 다섯 장을
     눈으로 더하게 둘 값이 아니다.
     카드와 같은 문(phase === 'SEALED')을 쓴다. 등록 중에 합계를 띄우면 종목을 하나
     더 걸 때마다 다른 종목의 몫까지 같이 흔들려, 방금 한 일과 숫자의 변화가 어긋난다.
     아직 체인에 안 올라간 배팅(null)은 빼고 센다. */
  const win = event.phase !== 'SEALED' ? 0 : event.stocks.reduce(
    (sum, s) => sum + (s.mine && !s.result ? (payoutOfBet(s.pool, s.mine.direction, s.mine.stake) ?? 0) : 0),
    0,
  )

  return (
    <section className="card ev-block" aria-labelledby="ev-stocks-h">
      {/* 제목 줄과 진행 줄을 한 줄에 마주 세운다(2026-09-17). 따로 두었더니 회색 띠가
          카드 위에 한 겹 더 앉아 한 블록이 둘로 보였다. */}
      <header className="ev-block-head is-split">
        <div>
          <h2 id="ev-stocks-h">대회 종목 {event.stocks.length}</h2>
          {/* 기준가가 덜 찬 회차는 아직 열리지 않는다 — 서버 크론이 그걸 보고 연다 */}
          {event.phase === 'BEFORE' && <p>기준가가 모두 정해지면 등록이 열립니다.</p>}
          {event.phase === 'ENTRY' && (
            <p>종목을 눌러 방향·목표가·배팅을 적습니다. 종목마다 한 번씩 겁니다.</p>
          )}
        </div>

        {/* 진행 묶음. **자격을 여기서만 말한다** — 최소 종목 수는 등록을 막지 않고
            순위 자격으로만 걸리므로(2026-09-18), 화면이 말하지 않으면 다섯 개를 못 채운
            사람이 참가한 줄 알고 정산까지 간다. */}
        {event.phase !== 'BEFORE' && (
          <div className={left === 0 ? 'ev-progress is-ok' : 'ev-progress'}>
            {/* 걸음 칸. 아래 문장과 **같은 것을 말한다** — 문장은 몇 개 남았는지를,
                이쪽은 그 남은 개수를 한눈에 세게 한다. 그림뿐이라 읽어 주지 않고
                (alt="" · aria-hidden) 문장 쪽을 읽게 둔다. 두 번 읽히면 성가시다.

                잇는 점은 이미지가 아니라 CSS 다. 원본 점 그림이 2172px 짜리라 20px 로
                줄이면 뭉개지고, 앞뒤 칸이 다 찼는지에 따라 색이 바뀌어야 한다.
                색은 그 원본에서 뽑았다(#10d088 · #c0c0d0). */}
            <span className="ev-steps" aria-hidden="true">
              {Array.from({ length: event.stocks.length }, (_, i) => (
                <Fragment key={i}>
                  {i > 0 && (
                    <i className={i < doneSteps ? 'ev-step-link is-done' : 'ev-step-link'} />
                  )}
                  <img
                    className="ev-step"
                    src={i < doneSteps ? '/assets/event/step-done.webp' : '/assets/event/step-todo.webp'}
                    alt=""
                  />
                </Fragment>
              ))}
            </span>

            <span className="ev-progress-note">
              {left === 0
                ? `참가 자격을 갖췄습니다 · 건 돈 ${me.staked.toLocaleString('ko-KR')} ANT`
                : `${left}종목 더 걸어야 순위에 오릅니다 (최소 ${rules.minStocks}종목)`}
            </span>

            {/* "모두" 를 빼지 않는다 — 다섯 종목을 다 맞혀야 나오는 금액이라, 그냥
                "맞히면" 으로 적으면 받게 될 돈처럼 읽힌다(김경민) */}
            {win > 0 && (
              <span className="ev-progress-win">
                모두 맞히면 <b className="num">{win.toLocaleString('ko-KR')} ANT</b>
              </span>
            )}
          </div>
        )}
      </header>

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
