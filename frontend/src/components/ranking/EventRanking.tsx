/* E-01 랭킹 · 이벤트 탭 — /rankings?tab=event
   담당 스토리 [ANT-FE-RANKING-EVENT] · 기획 docs/기획_랭킹_이벤트탭.md §2 · §4

   **홈 배너로 들어온 사람이 착지할 순위 화면이다.** 배너 → 대회(J-01) 까지는 길이
   있었는데 "그래서 지금 누가 1등인가" 를 볼 곳이 없었다. 대회 화면 안의 순위 블록은
   등록·내 내역 아래에 깔린 꼬리라 순위를 보러 온 사람에게는 너무 깊다.

   기획서(§5.5)는 GET /rankings/event 를 새로 파는 안이었지만, 그 사이 서버가 회차를
   events 로 세워 GET /events/{id}/leaderboard 를 이미 주고 있다. 같은 값을 두 문으로
   받을 이유가 없어 **있는 문을 쓴다** — 대회 화면의 순위 블록과 이 화면이 한 응답을
   보므로 두 곳의 등수가 어긋날 수 없다.

   설계 제약
   - **정산 전에는 순위를 그리지 않는다**(2026-09-16 결정, EventLeaderboard 와 같은 규칙).
     봉인 뒤라도 마찬가지다 — 승패를 모르는데 순위로 뜨면 현재 등수로 읽힌다.
     서버가 그때는 items 를 비우고 notice 만 주므로 화면은 그걸 그대로 띄운다.
   - 손익률이 주 지표다. 손익 절대값(ANT)은 옆에 작게 — 비율만 있으면 얼마를 걸었는지가
     안 보인다(기획 §4.1).
   - 평균 오차는 열로 두지 않는다. 동점을 가를 때만 쓰는 값이라 상시 지표가 아니다.
   - 대회 정의(날짜·이름)는 event/contest.ts 한 곳이고, 진행 단계는 **서버 phase** 를 쓴다.
     접수는 서버 크론이 세 조건을 보고 열기 때문에 시각만 지났다고 열린 것이 아니다. */
import { Fragment, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  fetchContestEvent, fetchLeaderboard,
  type EventHome, type EventLeaderboard, type EventPhase, type LeaderboardItem,
} from '../../api/events'
import { BANNER_BASE, CONTEST, dayStamp, stamp } from '../../event/contest'
import EmptyState from '../state/EmptyState'
import ErrorState from '../state/ErrorState'
import type { ApiError } from '../../api/errors'
import '../../styles/screens/ranking-event.css'

/** 회차 하나를 잡고 그 순위를 읽는다. 둘을 한 번에 받아야 머리말과 표가 같은 회차를 말한다. */
type Loaded = { event: EventHome; board: EventLeaderboard }

async function loadEventRanking(): Promise<Loaded | null> {
  const event = await fetchContestEvent(CONTEST.id)
  if (!event) return null
  const board = await fetchLeaderboard(event.eventId)
  return { event, board }
}

/* ── 남은 시간 ────────────────────────────────────────────
   마감까지 얼마인가는 1초마다 바뀌는 값이라 렌더 밖에서 셀 수 없다.
   목표 시각이 없거나(단계상 기다릴 것이 없다) 이미 지났으면 null 을 준다. */
function useRemaining(target: string | null) {
  const [left, setLeft] = useState(() => remain(target))

  /* 단계가 넘어가면 세는 대상이 바뀐다. effect 로 맞추면 한 틱 동안 옛 마감이 남아
     "등록 마감까지" 아래에 지난 시각이 보인다 — 렌더 중에 조정한다(useAsync 와 같은 패턴). */
  const [seen, setSeen] = useState(target)
  if (seen !== target) {
    setSeen(target)
    setLeft(remain(target))
  }

  useEffect(() => {
    if (target === null) return
    const id = setInterval(() => setLeft(remain(target)), 1000)
    return () => clearInterval(id)
  }, [target])

  return left
}

function remain(target: string | null) {
  if (target === null) return null
  const ms = Date.parse(target) - Date.now()
  if (!Number.isFinite(ms) || ms <= 0) return null

  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  /* 하루가 넘게 남았으면 초까지 보여 줄 이유가 없다 — 숫자가 계속 떨려 읽기만 어렵다 */
  return d > 0 ? `${d}일 ${h}시간 ${m}분` : `${h}시간 ${m}분 ${sec}초`
}

/** 단계마다 "지금 무엇을 기다리는가" 가 하나씩이다. 셋을 같이 적으면 고르게 만든다. */
const PHASE_HEAD: Record<EventPhase, { pill: string; title: string; target: 'opensAt' | 'sealsAt' | null }> = {
  BEFORE: { pill: '시작 전', title: '등록 시작까지', target: 'opensAt' },
  ENTRY: { pill: '등록 중', title: '등록 마감까지', target: 'sealsAt' },
  SEALED: { pill: '결과 집계 중', title: '판정을 기다리는 중입니다', target: null },
  SETTLED: { pill: '최종 결과', title: '순위가 확정됐습니다', target: null },
}

const ant = (n: number | null) => (n === null ? '—' : `${n.toLocaleString('ko-KR')} ANT`)
const rate = (n: number | null) => (n === null ? '—' : `${n > 0 ? '+' : ''}${n.toFixed(2)}%`)
/** 손익 부호. 한국식으로 이익이 빨강이다(--up) */
const sign = (n: number | null) => (n === null ? '' : n > 0 ? 'is-up' : n < 0 ? 'is-down' : '')

/* ── 동점 ─────────────────────────────────────────────────
   순위는 손익률 → 평균 오차(맞힌 건만) → 판정 건수 → 먼저 등록한 순으로 갈린다
   (기획 §3.2). 그런데 **평균 오차를 열로 두지 않는다**(§4.1) — 동점을 가를 때만 쓰는
   값이라 상시 지표가 아니고, 열로 세우면 "오차가 작을수록 좋은 예측" 이라는 다른
   이야기를 상시로 하게 된다. 같은 손익률이 나왔을 때만 무엇으로 갈렸는지 덧붙인다.

   같은 손익률이 셋이면 셋 다 표시한다. 둘만 짝지으면 가운데 사람이 위아래 중 누구와
   묶인 것인지 알 수 없다.

   **서버가 준 값 그대로 비교한다. 화면에 찍히는 값이 아니다.**
   처음엔 화면값(소수 둘째 자리)으로 묶었는데, 그러면 18.004% 와 17.996% 가 둘 다
   +18.00% 로 찍힌다는 이유만으로 "동점 · 오차로 갈림" 이 붙는다. 그 둘은 동점이 아니라
   손익률로 갈린 것이라 오차는 순위에 아무 상관이 없다 — 틀린 설명을 붙이는 셈이다.
   눈에 같아 보이는데 설명이 없는 편이, 아닌 이유를 대는 것보다 낫다.

   **손익률이 null 인 줄은 묶지 않는다.** 값이 없는 것이지 같은 것이 아니다. 예전에는
   null 끼리 '—' 로 묶여 서로 동점이라고 우겼다.

   **표에서는 무리를 통째로 묶는다.** 줄마다 "동점" 이라고 적으면 셋이 붙었을 때 같은
   말이 세 번 나오고, 정작 3.15 와 6.05 중 뭐가 이겼는지는 각자 읽어 내야 한다. 무리
   위에 규칙을 한 번 적고 줄마다 값만 두면 두 값이 세로로 나란히 서서 크기 비교가
   저절로 된다. 시상대는 카드 셋이 가로로 떨어져 있어 묶을 수 없으므로 알약을 쓴다. */
function tieCounts(items: LeaderboardItem[]): Map<number, number> {
  const seen = new Map<number, number>()
  for (const r of items) {
    if (r.profitRate === null) continue
    seen.set(r.profitRate, (seen.get(r.profitRate) ?? 0) + 1)
  }
  return seen
}

/** 같은 손익률이 둘 이상인가. null 은 "값이 없다" 이지 "같다" 가 아니라 늘 거짓이다 */
const isTied = (r: LeaderboardItem, counts: Map<number, number>) =>
  r.profitRate !== null && (counts.get(r.profitRate) ?? 0) > 1

/** 동점 줄에 붙는 값. 오차가 없으면(맞힌 건이 없다) 다음 기준인 판정 건수로 말한다 */
const tieValue = (r: LeaderboardItem) =>
  r.avgError === null ? `판정 ${r.doneCount}건` : `오차 ${r.avgError.toFixed(2)}%`

/* ── 머리 띠 ──────────────────────────────────────────────
   배너와 같은 조각을 깐다. 배너를 눌러 들어온 사람이 "그 이벤트의 순위" 라는 걸
   글을 읽기 전에 안다. 대회 화면(J-01) 머리보다 낮고 조용하게 — 여기 주인공은
   그림이 아니라 표다. */
function Head({ event }: { event: EventHome }) {
  const head = PHASE_HEAD[event.phase]
  const left = useRemaining(head.target === null ? null : event[head.target])

  return (
    <header className="rke-head">
      <span className="rke-head-bg" aria-hidden="true">
        <img src={BANNER_BASE.bg} alt="" />
        <img className="rke-head-confetti" src={BANNER_BASE.confetti} alt="" />
      </span>

      <div className="rke-head-lead">
        <span className="rke-pill">{head.pill}</span>
        <p className="rke-head-title">{event.title}</p>

        {/* 남은 시간이 있으면 그것이 가장 큰 글자다. 지났거나 없는 단계면 제목만 남는다 */}
        {left ? (
          <p className="rke-count">
            <span className="rke-count-label">{head.title}</span>
            <b className="num">{left}</b>
          </p>
        ) : (
          <p className="rke-count"><span className="rke-count-label">{head.title}</span></p>
        )}

        <p className="rke-head-sub">
          {event.phase === 'SETTLED'
            ? `${dayStamp(event.settleDate)} 종가로 판정했습니다`
            : `${dayStamp(event.settleDate)} 종가로 판정합니다`}
          {head.target !== null && ` · 마감 ${stamp(event[head.target])}`}
        </p>
      </div>

      <img className="rke-head-mascot" src={BANNER_BASE.mascot} alt="" aria-hidden="true" />

      {/* 순위를 보러 왔다가 참가하고 싶어질 수 있다. 되돌아갈 문을 띠 안에 둔다 */}
      <Link className="rke-head-link" to={`/events/${CONTEST.id}`}>
        {event.phase === 'ENTRY' ? '대회 참가하기' : '대회 페이지'}
      </Link>
    </header>
  )
}

/** 시상대 — 상위 3명. 시연용 강조다(기획 §4.1). 참가자가 3명 미만이면 있는 만큼만 선다. */
function Podium(
  { top, meId, counts }:
  { top: LeaderboardItem[]; meId: number | null; counts: Map<number, number> },
) {
  /* 2 · 1 · 3 차례로 세운다. 1등이 가운데 높이 서는 모양이라 순서를 바꿔 그린다 */
  const order = [top[1], top[0], top[2]].filter((r): r is LeaderboardItem => r !== undefined)

  return (
    <ol className="rke-podium">
      {order.map((r) => (
        <li
          key={r.userId}
          className={`rke-pod is-${r.rank ?? 0}${r.userId === meId ? ' is-me' : ''}`}
        >
          {/* 1등에만 왕관. 손익률 색은 부호(이익 빨강·손실 파랑)를 따르므로 등수를
              말해 주지 못한다 — 1등이 손실이면 2·3등과 같은 파랑이다. 배너에 쓰던
              조각을 그대로 써서 두 화면이 한 이벤트로 묶인다. */}
          {r.rank === 1 && (
            <img className="rke-pod-crown" src={BANNER_BASE.crown} alt="" aria-hidden="true" />
          )}
          <span className="rke-pod-no num">{r.rank}</span>
          <Link className="rke-pod-name" to={`/channels/${r.userId}`}>{r.nickname ?? '이름 없음'}</Link>
          <b className={`num ${sign(r.profitRate)}`}>{rate(r.profitRate)}</b>
          {isTied(r, counts) && <span className="rke-tie num">동점 · {tieValue(r)}</span>}
          <span className="num rke-pod-net">{ant(r.net)}</span>
          <span className="num rke-pod-hit">적중 {r.hitCount}/{r.doneCount}</span>
        </li>
      ))}
    </ol>
  )
}

export default function EventRanking() {
  const [data, setData] = useState<Loaded | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<ApiError | null>(null)
  /* 다시 시도. 같은 조건으로 한 번 더 읽어야 해서 값을 올려 effect 를 다시 돌린다 */
  const [attempt, setAttempt] = useState(0)

  /* 목록은 따로 들고 이어 붙인다. 서버가 한 번에 20명까지만 주는데
     (EventLeaderboardService.DEFAULT_SIZE) 여기는 **전체 순위를 보러 오는 자리**라
     첫 장에서 끊기면 안 된다. 대회 화면의 순위 블록은 맛보기라 한 장으로 끝나도 되지만
     이 탭은 다르다.

     useCursorList 를 쓰지 않는 것은 회차와 순위를 한 번에 읽어야 하기 때문이다 —
     머리말과 표가 같은 회차를 말해야 한다(RankingBoard 의 "더 보기" 와 같은 판단). */
  const [items, setItems] = useState<LeaderboardItem[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [paging, setPaging] = useState(false)

  useEffect(() => {
    let cancelled = false
    loadEventRanking()
      .then((d) => {
        if (cancelled) return
        setError(null)
        setData(d)
        setItems(d?.board.items ?? [])
        setCursor(d?.board.nextCursor ?? null)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        setData(null)
        setItems([])
        setCursor(null)
        setError(e as ApiError)
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [attempt])

  /* 다음 장. 이미 읽은 줄은 건드리지 않는다 — 한 장을 못 가져온 것뿐인데 앞 순위를
     지우면 사용자가 보던 것을 잃는다(RankingBoard.loadMore 와 같은 규칙). */
  function loadMore() {
    if (paging || cursor === null || data === null) return
    setPaging(true)
    fetchLeaderboard(data.event.eventId, cursor)
      .then((page) => {
        setItems((prev) => [...prev, ...page.items])
        setCursor(page.nextCursor)
      })
      .catch((e: unknown) => {
        setCursor(null)
        setError(e as ApiError)
      })
      .finally(() => setPaging(false))
  }

  if (loading) return <p className="rk-loading">불러오는 중…</p>

  if (error) {
    return (
      <ErrorState
        error={error}
        onRetry={() => { setLoading(true); setAttempt((n) => n + 1) }}
      />
    )
  }

  /* 본 대회 회차가 없다 — 아직 안 만들어졌거나, 점검용 회차만 있다(그건 사용자 화면에 안 잡힌다,
     api/events.ts fetchContestSummary). 대회 화면과 같은 말로 받는다. */
  if (!data) {
    return (
      <EmptyState
        title="지금 진행 중인 대회가 없어요"
        hint="대회가 열리면 여기에 순위가 올라옵니다"
      />
    )
  }

  const { event, board } = data
  const me = board.me
  const top = items.slice(0, 3)
  const rest = items.slice(3)
  /* 읽어 온 줄만 놓고 센다. 장 경계에 걸린 동점(20등과 21등)은 "더 보기" 로 다음 장이
     붙는 순간 함께 표시된다 — 아직 못 본 사람과 묶어 "동점" 이라고 말할 수는 없다. */
  const counts = tieCounts(items)
  /* 내가 읽어 온 목록 안에 이미 있으면 따로 줄을 세우지 않는다 — 같은 값이 두 번 보인다.
     "더 보기" 로 내 줄이 올라오면 이 꼬리는 저절로 사라진다 */
  const meOutside = me !== null && items.every((r) => r.userId !== me.userId)

  return (
    <div className="rke">
      <Head event={event} />

      {/* 내 성적 — 참가자만. 자격 미달·미참가면 서버가 me 를 null 로 준다 */}
      {me && (
        <section className="rke-me">
          <div className="rke-me-rank">
            <span className="rke-me-label">내 순위</span>
            <p>
              <b className="num">{me.rank ?? '—'}</b>
              <small>위</small>
            </p>
          </div>
          <div className="rke-me-stats">
            <span className={`num rke-me-rate ${sign(me.profitRate)}`}>{rate(me.profitRate)}</span>
            <span className="num">{ant(me.net)}</span>
            <span className="num">적중 {me.hitCount}/{me.doneCount}</span>
          </div>
        </section>
      )}

      {/* 정산 전이면 서버가 items 를 비우고 notice 만 준다. 그대로 띄운다 */}
      {board.notice && <p className="rke-notice">{board.notice}</p>}

      {!board.notice && items.length === 0 && (
        <EmptyState
          title="아직 참가자가 없습니다"
          hint="등록이 끝나고 판정이 나면 순위가 올라옵니다"
        />
      )}

      {items.length > 0 && (
        <>
          <Podium top={top} meId={me?.userId ?? null} counts={counts} />

          {rest.length > 0 && (
            <div className="rke-table-wrap">
              <table className="rke-table">
                <thead>
                  <tr>
                    <th scope="col" className="rke-col-rank">순위</th>
                    <th scope="col">참가자</th>
                    <th scope="col" className="rke-col-num">손익률</th>
                    <th scope="col" className="rke-col-num">손익</th>
                    <th scope="col" className="rke-col-num">적중</th>
                  </tr>
                </thead>
                <tbody>
                  {rest.map((r, i) => {
                    const tie = isTied(r, counts)
                    /* 무리의 첫 줄에만 규칙을 적는다. 시상대에 걸쳐 있는 무리(3·4위)는
                       표에 4위부터 나오므로 "앞 줄과 손익률이 다른가" 로 판단한다 —
                       무리의 진짜 1등인지가 아니라 이 표에서 처음 나오는지가 기준이다. */
                    const opensGroup = tie && (i === 0 || rest[i - 1].profitRate !== r.profitRate)

                    return (
                      <Fragment key={r.userId}>
                        {opensGroup && (
                          <tr className="rke-tie-head">
                            <td colSpan={5}>손익률 동점 · 목표가 오차가 작은 쪽이 앞섭니다</td>
                          </tr>
                        )}
                        <tr
                          className={[
                            r.userId === me?.userId ? 'is-me' : '',
                            tie ? 'is-tied' : '',
                          ].filter(Boolean).join(' ')}
                        >
                          <td className="rke-col-rank num">{r.rank}</td>
                          <td>
                            <Link className="rke-name" to={`/channels/${r.userId}`}>
                              {r.nickname ?? '이름 없음'}
                            </Link>
                          </td>
                          <td className="rke-col-num">
                            {/* 한 줄의 주인공이다. 이름이 닻이고 나머지는 물러난다 */}
                            <span className={`num rke-rate ${sign(r.profitRate)}`}>{rate(r.profitRate)}</span>
                            {/* 오차는 열이 아니라 동점인 줄에만 나온다(기획 §4.1).
                                무리 머리가 이미 "동점" 이라고 말했으므로 값만 둔다 */}
                            {tie && <span className="rke-tie num">{tieValue(r)}</span>}
                          </td>
                          <td className="rke-col-num num rke-col-net">{ant(r.net)}</td>
                          <td className="rke-col-num num rke-col-hit">{r.hitCount}/{r.doneCount}</td>
                        </tr>
                      </Fragment>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}

          {/* 순위 밖(자격 미달)이 아니라, 아직 안 읽은 다음 장에 있을 때도 여기로 온다 */}
          {meOutside && me && (
            <p className="rke-me-foot num">
              내 순위 {me.rank ?? '—'}위 · {rate(me.profitRate)} · {ant(me.net)}
            </p>
          )}

          {paging && <p className="rk-loading">불러오는 중…</p>}

          {cursor !== null && !paging && (
            <button type="button" className="rk-more" onClick={loadMore}>더 보기</button>
          )}
        </>
      )}
    </div>
  )
}
