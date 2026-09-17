/* 회차 순위 — J-01. GET /events/{id}/leaderboard.

   **정산 전에는 순위를 내리지 않는다**(2026-09-16 결정). 봉인 뒤라도 마찬가지다 —
   승패를 모르는데 화면에 순위로 뜨면 사용자는 현재 등수로 읽는다. 서버가 그때는
   items 를 비우고 notice 문구만 주므로, 화면은 그걸 그대로 띄운다. */
import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { fetchLeaderboard } from '../../api/events'
import { useAsync } from '../../api/useAsync'

const ant = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')} ANT`)

export default function EventLeaderboard({ eventId }: { eventId: number }) {
  const load = useCallback(() => fetchLeaderboard(eventId), [eventId])
  const { data, loading } = useAsync(load)

  if (loading || !data) return null

  return (
    <section className="card ev-block" aria-labelledby="ev-rank-h">
      <header className="ev-block-head">
        <h2 id="ev-rank-h">순위</h2>
        {/* 이 블록은 등록·내 내역 아래에 깔린 꼬리라 순위만 보러 온 사람에게는 너무 깊다.
            같은 값을 크게 그리는 곳이 랭킹의 이벤트 탭이다 — 거기로 가는 문을 둔다 */}
        <Link className="ev-rank-all" to="/rankings?tab=event">전체 순위 보기</Link>
      </header>

      {data.notice ? (
        <p className="ev-state is-quiet">{data.notice}</p>
      ) : (
        <ol className="ev-rank-rows">
          {data.items.map((r) => (
            <li key={r.userId} className={data.me?.userId === r.userId ? 'is-me' : ''}>
              <span className="num ev-rank-no">{r.rank}</span>
              <b>{r.nickname ?? '이름 없음'}</b>
              <span className="num">적중 {r.hitCount}/{r.doneCount}</span>
              <span className={`num ${(r.net ?? 0) >= 0 ? 'is-up' : 'is-down'}`}>{ant(r.net)}</span>
              {r.profitRate !== null && (
                <span className="num">{r.profitRate > 0 ? '+' : ''}{r.profitRate.toFixed(2)}%</span>
              )}
            </li>
          ))}
        </ol>
      )}

      {/* 자격 미달·미참가는 서버가 me 를 null 로 준다 — 순위 밖이라 보여 줄 등수가 없다.
          자기 성적은 위의 "내 참가 내역" 이 자격과 무관하게 보여 준다 */}
      {data.me && data.items.every((r) => r.userId !== data.me?.userId) && (
        <p className="ev-state is-quiet num">내 순위 {data.me.rank}위 · {ant(data.me.net)}</p>
      )}
    </section>
  )
}
