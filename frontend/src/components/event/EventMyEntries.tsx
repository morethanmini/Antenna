/* 내 참가 내역 — J-01. GET /events/{id}/me.

   홈(종목 카드)과 겹쳐 보이지만 주인공이 다르다. 홈은 종목이 주인공이라 내 배팅이
   카드에 딸린 값으로 들어가고, 여기는 내가 건 것만 모아 상태·tx·오차까지 보여 준다.

   **미참가는 404 가 아니라 빈 목록이다** — 대회는 있고 내 기록만 없는 것이라, 화면도
   "아직 없다" 로 그린다. */
import { useCallback, useEffect, useState } from 'react'
import { fetchMyEntries, type BetStatus } from '../../api/events'
import { useAsync } from '../../api/useAsync'

/* 확정을 기다리는 동안 다시 묻는 간격. 블록이 10초쯤이고 인덱서가 5초마다 읽으므로
   그 둘을 합친 만큼이면 대개 한 번에 잡힌다. */
const POLL_MS = 6000

const won = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')}원`)
const ant = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')} ANT`)

/**
 * 서버 betStatus 를 사람 말로. FAILED 라도 사용자가 할 일은 없다 — 크론이 다시 보낸다.
 *
 * ACTIVE 를 "확정" 이라 부르던 것을 "등록 완료" 로 바꿨다(2026-09-17). ACTIVE 는 배팅이
 * 체인에 올라갔다는 뜻일 뿐 승패와는 무관한데, "확정" 은 결과가 정해졌다는 말로 읽힌다 —
 * 바로 아래 SETTLED("정산 완료") 와도 헷갈렸다.
 */
const STATUS: Record<BetStatus, string> = {
  PENDING: '전송 중',
  ACTIVE: '등록 완료',
  REVEALED: '공개됨',
  SETTLED: '정산 완료',
  REFUNDED: '환불',
  FAILED: '실패 — 재시도 중',
}

export default function EventMyEntries({ eventId }: { eventId: number }) {
  /* 폴링에 reload 를 쓰지 않는다. useAsync 의 reload 는 (1) 매 렌더 새로 만들어져 effect 의
     의존으로 넣으면 타이머가 계속 새로 걸리고, (2) 부르는 순간 data 를 null 로 만들어 화면이
     깜빡인다. 대신 load 의 신원을 바꿔 useAsync 가 스스로 다시 읽게 한다 — 그 길은 끝났을 때만
     state 를 바꾸므로 이미 그린 것이 그대로 있다. */
  const [tick, setTick] = useState(0)
  const load = useCallback(() => {
    void tick
    return fetchMyEntries(eventId)
  }, [eventId, tick])
  const { data, loading, error, reload } = useAsync(load)

  /* **전송 중인 배팅이 있을 때만 다시 묻는다.**
     등록은 202 라 저장만 끝났고 확정은 인덱서가 BetPlaced 를 보고 한다. 다시 묻지 않으면
     화면이 "전송 중" 에서 멈춰 있고, 성공 화면이 "아래에서 확정을 확인할 수 있습니다" 라고
     한 말이 거짓이 된다(2026-09-16 로컬에서 확인).

     끝난 배팅만 남으면 멈춘다 — 대회 화면을 열어 둔 채로 두는 사람이 있는데, 확정된 뒤에도
     6초마다 묻는 것은 서버에 대고 아무것도 바뀌지 않을 질문을 반복하는 것이다. */
  const waiting = data?.entries.some((e) => e.betStatus === 'PENDING') ?? false
  useEffect(() => {
    if (!waiting) return
    const id = setInterval(() => setTick((t) => t + 1), POLL_MS)
    return () => clearInterval(id)
  }, [waiting])

  /* 이미 그린 것이 있으면 다시 읽는 동안에도 그대로 둔다 */
  if (loading && !data) return null
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
              {/* 방향은 낱말과 색을 같이 준다 — 색만으로는 못 읽는 사람이 있고,
                  낱말만 있으면 줄 열 개를 훑을 때 어느 쪽인지 한눈에 안 들어온다.
                  국내 관례대로 오른다가 빨강이다(--up) */}
              <span className={e.direction === 'UP' ? 'ev-me-dir is-up' : 'ev-me-dir is-down'}>
                {e.direction === 'UP' ? '오른다' : '내린다'}
              </span>
              <span className="num">{won(e.targetPrice)}</span>
              {/* 건 돈. 토큰은 브랜드 보라로 묶는다 — 잔액 칩·구독료와 같은 색이라
                  "이건 ANT 다" 가 화면마다 같은 신호로 읽힌다 */}
              <span className="num ev-me-stake">{ant(e.stake)}</span>
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
          순위에 들려면 종목을 더 골라 주세요.
        </p>
      )}
    </section>
  )
}
