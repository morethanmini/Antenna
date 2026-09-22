/* E-01 예측가 랭킹 — /rankings 주가 예측 랭킹 (REAL) · 인사이트 모드
   담당 스토리 [ANT-FE-RANKING]

   **모의투자 랭킹(/sim/rankings · REPLAY)은 화면에서 내렸다(2026-09-16).**
   한 시즌 안의 순위는 G-07 시즌 리더보드가 보여 주고, 둘을 같이 두면 "내 등수" 가
   두 곳에서 다른 값으로 나온다. RankingBoard 의 track 갈래(tier 표시 등)는 남겨
   둔다 — REPLAY 를 되살릴 때 화면을 다시 쓰면 되고, 지우면 그때 다시 짜야 한다.
   설계서 docs/화면설계서.md §3 E · §4 E-01 · §7 · §9.2 · §10

   **설계서와 다르다.** §4 E-01 은 한 화면에 트랙 탭(REAL/REPLAY)을 두는 것으로
   적고 있는데, 화면을 둘로 갈랐다(2026-09-09 결정, 김경민 님과 합의).

   이유는 셸이다. 사이드바가 인사이트·모의 투자 두 모드로 갈리고 라우트마다
   mode 가 붙는데, 한 화면이 두 모드에 속할 수 없다. 탭 하나로 두면 모의 투자
   바에서 "모의투자 랭킹" 을 눌렀을 때 인사이트 모드로 넘어간다 — 실제로 그랬다.
   트랙을 라우트가 정하므로 탭은 없앴다.

   설계 제약
   - 트랙 분리는 타협하지 않는다. REAL 과 REPLAY 지표를 같은 표에 섞지 않는다.
     이제 화면부터 갈렸으니 섞일 자리가 없다.
   - tier 는 REPLAY 전용이다. 실전 화면에 노출하지 않는다.
   - 배치 B3 스냅샷이라 computedAt 표시가 필수다(SnapshotStamp). 실시간처럼 보이면 안 된다.
   - score 산식은 미확정이다. 값만 표시하고 산식 설명을 쓰지 않는다.
   - "더 보기"는 커서가 아니라 fromRank 오프셋이다 — 배치 스냅샷이라 중간 삽입이 없어
     오프셋이 안전하다(명세 §1 페이징 예외). 그래서 useCursorList 를 쓰지 않는다.

   두지 않는 것: 실전 화면의 리플레이 티어 표시. 섹터 필터.

   **섹터 칩을 내렸다(2026-09-17).** GET /stocks/sectors 의 KRX 업종명을 그대로 칩으로
   깔던 자리인데, 어휘가 26개라 머리 아래를 두 줄로 채우고도 정작 눌러 볼 만한 칩이
   없었다 — 예측이 쌓인 업종은 몇 개뿐이라 대부분 빈 표로 떨어진다. 필터가 기간 하나로
   줄면서 표가 그만큼 위로 올라온다.

   되살릴 때는 api/rankings.ts 의 sector 질의 인자가 그대로 살아 있으니 칩만 다시 그리면
   된다. 서버 쪽은 건드리지 않았다. */
import { useEffect, useState } from 'react'
import { useUrlState } from '../urlState'
import { Link, useSearchParams } from 'react-router-dom'
import type { ApiError } from '../api/errors'
import {
  PERIODS, PERIOD_LABEL, TIER_LABEL,
  fetchMyRank, fetchRankings, formatComputedAt, metric,
  type MyRank, type Period, type RankingRow, type Track,
} from '../api/rankings'
import EventRanking from '../components/ranking/EventRanking'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import SnapshotStamp from '../components/SnapshotStamp'
import { CONTEST } from '../event/contest'
import '../styles/screens/ranking.css'

const PAGE = 20

/** 트랙만 다르고 나머지는 같다. 두 라우트가 이 하나를 나눠 쓴다. */
function RankingBoard({ track }: { track: Track }) {
  /* 주소에 둔다 — 채널로 갔다 뒤로 오면 보던 기간이 살아 있어야 한다(2026-09-22 QA) */
  const [period, setPeriod] = useUrlState<Period>('period', 'ALL', PERIODS)

  const [rows, setRows] = useState<RankingRow[]>([])
  const [computedAt, setComputedAt] = useState<string | null>(null)
  const [me, setMe] = useState<MyRank | null>(null)
  const [loading, setLoading] = useState(true)
  /** 마지막 응답이 꽉 찼으면 더 있을 수 있다. 응답에 hasNext 가 없어 이렇게 가른다 */
  const [more, setMore] = useState(false)
  /* 목업일 때는 실패할 일이 없어 오류 자리가 없었다. 실 API 로 넘기면서 둔다 —
     없으면 401·500 에 화면이 조용히 빈 채로 남고, 사용자는 순위가 없는 줄 안다. */
  const [error, setError] = useState<ApiError | null>(null)
  /* 다시 시도. 필터를 바꾸지 않고 같은 조건으로 다시 읽어야 하는데, changePeriod 는
     값이 같으면 조기 반환하므로 쓸 수 없다. 이 값을 올려 effect 를 다시 돌린다. */
  const [attempt, setAttempt] = useState(0)

  /* 필터가 바뀌면 목록을 처음부터 다시 읽는다. 이어 붙이지 않고 통째로 갈아친다.
     track 은 라우트가 정하는 값이라 이 화면 안에서 바뀌지 않는다. */
  /* loading 은 이 effect 가 아니라 아래 changePeriod 에서 세운다.
     effect 안에서 동기 setState 를 하면 렌더가 한 번 더 돈다 — 첫 진입은 useState(true) 가 덮는다. */
  useEffect(() => {
    let cancelled = false

    const query = track === 'REAL'
      ? { track, period, limit: PAGE }
      // 리플레이는 기간이 없다. 시즌 선택은 G-01 이 시즌을 만든 뒤 붙인다.
      : { track, limit: PAGE }

    Promise.all([fetchRankings(query), fetchMyRank(track)])
      .then(([page, mine]) => {
        if (cancelled) return
        setError(null)
        setRows(page.items)
        setComputedAt(page.computedAt)
        // 랭킹에 없으면 204 라 undefined 다. 내 순위 카드를 그리지 않는다
        setMe(mine ?? null)
        setMore(page.items.length === PAGE)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        /* 목록을 비운다. 옛 필터 결과가 오류 배너와 함께 남아 있으면
           "이 조건의 결과" 로 읽힌다. */
        setRows([])
        setMe(null)
        setMore(false)
        setError(e as ApiError)
      })
      .finally(() => { if (!cancelled) setLoading(false) })

    return () => { cancelled = true }
  }, [track, period, attempt])

  /* 필터를 바꾸는 순간이 곧 다시 읽기 시작하는 순간이다. 여기서 로딩을 세운다. */
  function changePeriod(next: Period) {
    if (next === period) return
    setLoading(true)
    setPeriod(next)
  }

  /* 순위 직행. 커서가 아니라 다음 순위부터 오프셋으로 가져온다. */
  function loadMore() {
    if (loading || !more) return
    setLoading(true)
    const next = rows.length + 1
    const query = track === 'REAL'
      ? { track, period, limit: PAGE, fromRank: next }
      : { track, limit: PAGE, fromRank: next }

    fetchRankings(query)
      .then((page) => {
        setRows((prev) => [...prev, ...page.items])
        setMore(page.items.length === PAGE)
      })
      .catch((e: unknown) => {
        /* 이미 읽은 줄은 그대로 둔다 — 다음 장을 못 가져온 것뿐이라 앞 순위를 지우면
           사용자가 보던 것을 잃는다. "더 보기" 만 닫고 오류를 알린다. */
        setMore(false)
        setError(e as ApiError)
      })
      .finally(() => setLoading(false))
  }

  /* 머리(제목·소개)는 이 컴포넌트 밖이다 — 탭이 생기면서 두 탭이 한 머리를 나눠 쓴다 */
  return (
    <>
    {/* 내 순위 — 트랙마다 다르다. 티어는 REPLAY 에서만 붙는다 */}
    {me && (
      <section className="rk-me">
        <div className="rk-me-rank">
          {/* 내 순위는 **늘 전체 기간 기준**이다(GET /rankings/me 가 track 만 받는다 · 명세 §랭킹).
              아래 표는 고른 기간으로 걸러지므로, 기간을 좁히면 "표에는 1명인데 내가 2위" 처럼
              둘이 다른 이야기를 한다(2026-09-22 QA). 기준이 다르다는 것을 카드가 직접 말한다. */}
          <span className="rk-me-label">
            내 순위{track === 'REAL' && period !== 'ALL' && ' · 전체 기간 기준'}
          </span>
          <p><b className="num">{me.rank}</b><small>위</small></p>
        </div>

        <div className="rk-me-stats">
          <span className="num">{`상위 ${me.percentile}%`}</span>
          {me.delta !== 0 && (
            <span className={`rk-delta ${me.delta > 0 ? 'up' : 'down'} num`}>
              {`${me.delta > 0 ? '▲' : '▼'} ${Math.abs(me.delta)}`}
            </span>
          )}
          {/* 실전에서는 서버가 null 을 준다 — 여기서 걸러도 트랙 조건을 한 번 더 둔다 */}
          {track === 'REPLAY' && me.tier && (
            <span className="rk-tier">{TIER_LABEL[me.tier]}</span>
          )}
        </div>
      </section>
    )}

    {/* 표 툴바 — 왼쪽은 이 목록이 언제 만들어졌는지, 오른쪽은 무엇으로 걸렀는지 */}
    <div className="rk-tablebar">
      {computedAt ? <SnapshotStamp at={formatComputedAt(computedAt)} /> : <span />}

      {/* 두 값 중 하나를 고르는 배타 선택이라 세그먼트 컨트롤로 둔다.
          고른 값과 고르지 않은 값이 나란히 보여, 지금 무엇으로 집계 중인지와
          무엇으로 바꿀 수 있는지를 한 번에 읽는다. 기간은 REAL 에만 있다. */}
      {track === 'REAL' && (
        <div className="rk-period-seg" role="group" aria-label="집계 기간">
          {PERIODS.map((p) => (
            <button
              key={p} type="button"
              aria-pressed={period === p}
              className={period === p ? 'on' : ''}
              onClick={() => changePeriod(p)}
            >{PERIOD_LABEL[p]}</button>
          ))}
        </div>
      )}
    </div>

    {/* 오류가 먼저다. 빈 목록과 못 불러온 것은 다르다 — 오류를 빈 상태로 그리면
        "아직 랭킹이 없구나" 로 읽혀 다시 시도할 생각을 못 한다. */}
    {error && (
      <ErrorState
        error={error}
        onRetry={() => { setLoading(true); setAttempt((n) => n + 1) }}
      />
    )}

    {!error && !loading && rows.length === 0 && (
      <EmptyState
        title="집계된 랭킹이 없습니다"
        hint="판정이 끝난 예측이 쌓이면 다음 배치에 반영됩니다"
      />
    )}

    {rows.length > 0 && (
      <div className="rk-table-wrap">
        <table className="rk-table">
          <thead>
            <tr>
              <th scope="col" className="rk-col-rank">순위</th>
              <th scope="col">예측가</th>
              {/* score 산식은 미확정이라 머리글에 설명을 달지 않는다 */}
              <th scope="col" className="rk-col-num">점수</th>
              <th scope="col" className="rk-col-num">적중률</th>
              <th scope="col" className="rk-col-num">평균 오차</th>
              <th scope="col" className="rk-col-num">판정</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.userId} className={r.rank <= 3 ? 'is-top' : undefined}>
                {/* 1~3 위만 메달로 세운다 — 표 전체에 색을 뿌리면 위계가 사라진다 */}
                <td className="rk-col-rank">
                  <span className={`rk-rank num${r.rank <= 3 ? ` rk-medal is-${r.rank}` : ''}`}>{r.rank}</span>
                </td>
                <td>
                  {/* 예측가 이름은 채널 프로필(E-02)로 가는 문이다 */}
                  <Link className="rk-name" to={`/channels/${r.userId}`}>{r.nickname}</Link>
                </td>
                {/* 점수가 이 표의 정렬 기준이라 가장 굵다 */}
                <td className="rk-col-num rk-col-score num">{metric(r.score, 1)}</td>
                <td className="rk-col-num num">{metric(r.hitRate, 1, '%')}</td>
                <td className="rk-col-num num">{metric(r.avgError, 2)}</td>
                <td className="rk-col-num num">{r.doneCount.toLocaleString('ko-KR')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )}

    {loading && <p className="rk-loading">불러오는 중…</p>}

    {more && !loading && (
      <button type="button" className="rk-more" onClick={loadMore}>더 보기</button>
    )}
    </>
  )
}

/* ── 두 랭킹 오가기 ───────────────────────────────────────
   정확도(서비스 랭킹) · 이벤트 둘이다. 기획 docs/기획_랭킹_이벤트탭.md §2.

   **라우트는 하나다.** 트랙(REAL/REPLAY)을 화면으로 갈랐던 것은 셸 모드가 달라서였는데
   (파일 머리말), 이 둘은 모두 인사이트 모드라 셸이 갈리지 않는다.

   상태는 쿼리스트링(`?tab=event`)에 남긴다 — 시연 때 링크 하나로 이벤트 순위를 열 수
   있어야 하고, 홈 배너·대회 화면에서 여기로 바로 보낼 주소가 필요하다.

   **이벤트가 끝나도 길은 남긴다.** 최종 결과를 계속 볼 수 있어야 한다(기획 §2).

   ── 세그먼트 탭을 걷고 그림 버튼 하나로 바꿨다(2026-09-18) ──
   회색 알약 둘을 나란히 두면 이벤트 랭킹이 "또 하나의 필터" 로 보인다. 지금은 보고 있는
   쪽 이름을 머리가 말하고, 오른쪽 위 버튼이 **가지 않은 쪽 하나만** 가리킨다. 둘 중
   어디에 있든 누를 것이 하나뿐이라 고민할 것이 없고, 캐릭터가 들고 있는 버튼이라
   회색 글자와 달리 안 눌러 보고 지나치지 않는다. */
const ART = {
  /* 그림에 글자가 구워져 있다. 읽어 주는 기기를 위해 alt 로 같은 말을 남긴다 */
  event: { src: '/assets/event/btn-event.webp', alt: '이벤트 랭킹 보기' },
  accuracy: { src: '/assets/event/btn-service.webp', alt: '서비스 랭킹 보기' },
} as const
type Tab = keyof typeof ART

/** 보고 있는 쪽의 제목과 한 줄 소개. 한 머리를 둘이 나눠 쓴다 */
const HEAD: Record<Tab, { h1: string; p: string }> = {
  accuracy: { h1: '주가 예측 랭킹', p: '판정이 끝난 예측만 집계합니다' },
  event: { h1: '이벤트 랭킹', p: `${CONTEST.name} 참가자의 베팅 손익률 순위입니다` },
}

export default function Ranking() {
  const [params, setParams] = useSearchParams()
  /* 모르는 값이 오면 기본 탭이다 — 주소를 손으로 고친 사람에게 빈 화면을 주지 않는다 */
  const tab: Tab = params.get('tab') === 'event' ? 'event' : 'accuracy'

  /* **버튼은 조건 없이 늘 선다.**
     한때 회차를 한 번 읽어 보고 없으면 버튼을 숨겼다("없는 대회를 부추기지 않는다").
     그런데 이 버튼은 이벤트 랭킹으로 가는 **유일한 길**이라, 숨기면 회차가 서기 전까지
     그런 화면이 있다는 것 자체를 알 수 없다. 조회가 느리거나 실패해도 같이 사라졌다.
     빈 회차는 이벤트 랭킹 화면이 "대회를 준비하고 있어요" 로 정직하게 받는다 —
     거기서 할 말을 여기서 미리 감출 이유가 없다. 덤으로 요청 하나가 줄었다. */
  const other: Tab = tab === 'accuracy' ? 'event' : 'accuracy'

  function go(next: Tab) {
    if (next === tab) return
    /* 기본 쪽은 쿼리를 지운다. `/rankings` 와 `/rankings?tab=accuracy` 가 같은 화면인데
       주소가 둘이면 공유된 링크가 제각각이 된다. replace 라 뒤로 가기가 이 전환을 되짚지 않는다 */
    if (next === 'accuracy') params.delete('tab')
    else params.set('tab', next)
    setParams(params, { replace: true })
  }

  return (
    /* 배경이 탭마다 다르다 — 아래 CSS 가 이 클래스로 갈라 본다 */
    <main className={`main rk-page ${tab === 'event' ? 'is-event' : 'is-accuracy'}`}>
      {/* ── 배경 ───────────────────────────────────────────────
          **탭마다 다르다.**
          - 서비스 랭킹: 액자 그림. 화면 안에 그림이 없는 쪽이라 배경이 그 몫을 한다.
          - 이벤트 랭킹: 그림에서 뽑은 색만. 화면 안에 이미 무대 그림이 있어,
            밖을 또 그림으로 두르면 그림 위에 그림이 되어 시상대가 묻힌다.

          그림은 통째로 아주 느리게 흔들린다. aria-hidden 으로 읽어 주는 기기는 건너뛴다. */}
      <div className="rk-sky" aria-hidden="true">
        <img className="rk-sky-bg" src="/assets/event/rank-bg.webp" alt="" />
      </div>

      <div className="main-inner">
        {/* 제목과 전환 버튼이 한 줄이다. 버튼이 오른쪽 위에 서야 제목과 눈높이가 맞고,
            아래 목록과 겹치지 않는다 */}
        <div className="page-head rk-head">
          <div>
            <h1>{HEAD[tab].h1}</h1>
            <p>{HEAD[tab].p}</p>
          </div>

          <button type="button" className="rk-switch" onClick={() => go(other)}>
            <img src={ART[other].src} alt={ART[other].alt} />
          </button>
        </div>

        {/* 탭을 옮기면 통째로 갈아친다 — 정확도 표가 이벤트 순위로 읽히면 안 된다.
            REPLAY 를 쓰던 SimRanking 은 모의투자 랭킹과 함께 내렸다(파일 머리말) */}
        {tab === 'accuracy' ? <RankingBoard track="REAL" /> : <EventRanking />}
      </div>
    </main>
  )
}

