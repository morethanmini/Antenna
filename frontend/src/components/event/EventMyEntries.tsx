/* 내 참가 내역 — J-01. GET /events/{id}/me.

   홈(종목 카드)과 겹쳐 보이지만 주인공이 다르다. 홈은 종목이 주인공이라 내 배팅이
   카드에 딸린 값으로 들어가고, 여기는 내가 건 것만 모아 상태·tx·오차까지 보여 준다.

   **미참가는 404 가 아니라 빈 목록이다** — 대회는 있고 내 기록만 없는 것이라, 화면도
   "아직 없다" 로 그린다. */
import { useCallback } from 'react'
import { fetchMyEntries, type BetStatus } from '../../api/events'
import { useAsync } from '../../api/useAsync'

const won = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')}원`)
const ant = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')} ANT`)

/** 서버 betStatus 를 사람 말로. FAILED 라도 사용자가 할 일은 없다 — 크론이 다시 보낸다 */
const STATUS: Record<BetStatus, string> = {
  PENDING: '전송 중',
  ACTIVE: '확정',
  REVEALED: '공개됨',
  SETTLED: '정산 완료',
  REFUNDED: '환불',
  FAILED: '실패 — 재시도 중',
}

export default function EventMyEntries({ eventId }: { eventId: number }) {
  const load = useCallback(() => fetchMyEntries(eventId), [eventId])
  const { data, loading, error, reload } = useAsync(load)

  if (loading) return null
  if (error) {
    return (
      <section className="card ev-block">
        <p className="ev-state">
          내 참가 내역을 불러오지 못했어요.{' '}
          <button type="button" className="ev-retry" onClick={reload}>다시 시도</button>
        </p>
      </section>
    )
  }
  if (!data) return null

  const s = data.summary
  return (
    <section className="card ev-block" aria-labelledby="ev-me-h">
      <header className="ev-block-head">
        <h2 id="ev-me-h">내 참가 내역</h2>
        {data.entries.length > 0 && (
          <p className="num">
            {s.stockCount}종목 · {ant(s.staked)}
            {/* 정산 전에는 null 이다. 0 으로 그리면 "본전" 으로 읽힌다 */}
            {s.net !== null && <> · 손익 <b className={s.net >= 0 ? 'is-up' : 'is-down'}>{ant(s.net)}</b></>}
            {s.rank !== null && <> · {s.rank}위</>}
          </p>
        )}
      </header>

      {data.entries.length === 0 ? (
        <p className="ev-state is-quiet">아직 등록한 종목이 없어요.</p>
      ) : (
        <ul className="ev-me-rows">
          {data.entries.map((e) => (
            <li key={e.predictionId}>
              <b>{e.name}</b>
              <span>{e.direction === 'UP' ? '오른다' : '내린다'}</span>
              <span className="num">{won(e.targetPrice)}</span>
              <span className="num">{ant(e.stake)}</span>
              <span className={`ev-me-status is-${e.betStatus.toLowerCase()}`}>{STATUS[e.betStatus]}</span>
              {e.result && (
                <span className="num ev-me-payout">
                  {e.result.winner === 'TIE' ? '환불' : e.result.winner === 'UP' ? '올랐다' : '내렸다'}
                  {' · '}{ant(e.result.payout)}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}

      {!s.qualified && data.entries.length > 0 && (
        <p className="ev-state is-quiet">
          순위에 들려면 종목을 더 골라야 합니다.
        </p>
      )}
    </section>
  )
}
