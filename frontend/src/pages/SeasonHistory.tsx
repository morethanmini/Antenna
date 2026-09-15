/* G-09 기록 · /sim/history
   담당 스토리 [ANT-FE-SEASON-HISTORY]
   설계서 docs/화면설계서.md §3 · §4 G-09 · API 명세 §모의투자

   ── 여기가 모의투자의 목록 자리다 ────────────────────────
   사이드바의 "투자 포트폴리오" 가 이 주소다. 시즌 번호가 필요한 화면(진행·결과·
   매매일지)은 사이드바에 못 걸리므로, 여기서 시즌을 고르고 그쪽으로 들어간다.

   ── 무엇으로 그리는가 ────────────────────────────────────
   GET /seasons/me 하나다. v0.39 에서 제목·종료 시각·최종 수익률이 붙어(대연님)
   목록에 필요한 값이 한 응답에 다 온다 — 시즌마다 상세를 다시 부르지 않는다.

   ── 두 목록은 탭 하나다 (2026-09-15) ─────────────────────
   이어서 할 연습과 마친 시즌을 카드 두 장으로 세워 두니, 한 화면에 요약 4칸 + 카드
   2장 + 정렬 줄이 동시에 서서 어디부터 봐야 하는지가 없었다. 둘은 **같은 목록의 두
   상태**라 나란히 펼쳐 둘 이유가 없다 — 탭으로 접고, 개수는 탭 이름이 말한다.
   기본 탭은 이어서 할 연습이다. 진행 중인 판이 있는 사람이 여기 온 이유가 그것이다.

   요약도 3칸으로 줄였다. "진행 중 n개" 는 탭 이름이 이미 말하므로 칸을 따로 두면
   같은 숫자가 한 화면에 두 번 뜬다.

   ── 배지는 뺐다 ──────────────────────────────────────────
   설계서 §G-09 는 "기록·배지" 지만 배지는 MVP 가 아니다(2026-09-09). GET /users/me/badges
   도 서버에 없다. 자리만 만들어 두면 빈 상자가 화면을 차지하기만 하므로 통째로 뺐다 —
   배지가 생기면 목록 아래에 카드 하나를 더하면 된다. */
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/errors'
import { rate, signOf } from '../api/seasonPlay'
import {
  getMyRuns, MODE_LABEL, progressOf, recentFirst, type MyRun,
} from '../api/seasons'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/sim-history.css'

type Sort = 'RECENT' | 'BEST' | 'WORST'
type Tab = 'ONGOING' | 'DONE'

const SORTS: { key: Sort; label: string }[] = [
  { key: 'RECENT', label: '최근순' },
  { key: 'BEST', label: '수익률 높은 순' },
  { key: 'WORST', label: '수익률 낮은 순' },
]

/** 끝낸 날. 시즌의 시기가 아니라 내가 끝낸 실제 날짜라 시대 단서가 아니다 */
const endedLabel = (iso?: string | null) => {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? null
    : `${d.getMonth() + 1}월 ${d.getDate()}일 마침`
}

const Ico = ({ size = 18, children }: { size?: number; children: ReactNode }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
)

/** 요약 한 칸. 아이콘 타일을 두지 않는다 — 숫자 셋을 나란히 읽는 자리라
    칸마다 색 타일이 붙으면 숫자보다 타일이 먼저 보인다. */
function Figure({ label, value, sub, tone }: {
  label: string
  value: string
  sub: string
  tone?: string
}) {
  return (
    <div className="sh-fig">
      <span className="sh-fig-label">{label}</span>
      <b className={`sh-fig-val num ${tone ?? ''}`}>{value}</b>
      <span className="sh-fig-sub">{sub}</span>
    </div>
  )
}

/* 한 줄. 마친 판은 성적을, 진행 중인 판은 남은 길이를 오른쪽에 둔다 —
   같은 자리에 "그 판이 지금 무엇인지" 가 온다.

   모드 배지는 목록에 모드가 섞였을 때만 낸다. 연습만 있는 목록에서 모든 줄에
   "연습" 이 붙으면 구분하는 정보가 아니라 같은 글자가 n번 반복되는 것이다. */
function RunRow({ run, showMode }: { run: MyRun; showMode: boolean }) {
  const isDone = run.endedAt != null || run.returnRate != null
  const r = run.returnRate
  const ended = endedLabel(run.endedAt)
  const pct = progressOf(run.currentDay, run.lengthDays)

  return (
    <li>
      <Link
        className={`sh-row ${isDone ? 'is-done' : 'is-go'}`}
        to={isDone ? `/sim/${run.seasonId}/result` : `/sim/${run.seasonId}/play`}
      >
        <span className="sh-name">
          {showMode && <span className="sh-mode">{MODE_LABEL[run.mode]}</span>}
          <b>{run.title}</b>
        </span>

        {isDone ? (
          <>
            <span className="sh-when">{ended ?? `${run.lengthDays}게임일`}</span>
            {/* 결과가 없는 완료 회차도 있다 — 끝냈지만 성적표를 안 굳힌 판이다 */}
            <span className={`sh-rate num ${r == null ? 'flat' : signOf(r)}`}>
              {r == null ? '성적표 없음' : rate(r)}
            </span>
          </>
        ) : (
          <>
            <span className="sh-when num">DAY {run.currentDay} / {run.lengthDays}</span>
            {/* 막대를 화면 끝까지 늘리지 않는다 — 길이가 정보인 것은 찬 비율이지 폭이 아니다 */}
            <span className="sh-bar" aria-hidden="true"><i style={{ width: `${pct}%` }} /></span>
          </>
        )}

        <i className="sh-chev" aria-hidden="true">›</i>
      </Link>
    </li>
  )
}

/** 목록에 모드가 섞여 있는가. 섞였을 때만 줄마다 모드를 적는다 */
const mixedModes = (rows: MyRun[]) => new Set(rows.map((r) => r.mode)).size > 1

export default function SeasonHistory() {
  const [ongoing, setOngoing] = useState<MyRun[] | null>(null)
  const [done, setDone] = useState<MyRun[]>([])
  const [loadError, setLoadError] = useState<ApiError | null>(null)
  const [sort, setSort] = useState<Sort>('RECENT')
  const [tab, setTab] = useState<Tab>('ONGOING')

  useEffect(() => {
    let alive = true
    getMyRuns()
      .then((r) => {
        if (!alive) return
        setOngoing(r.ongoing)
        setDone(r.done)
        /* 이어서 할 판이 없는 사람에게 빈 탭을 먼저 보여 줄 이유가 없다 */
        setTab(r.ongoing.length > 0 ? 'ONGOING' : 'DONE')
        setLoadError(null)
      })
      .catch((e) => { if (alive) setLoadError(e instanceof ApiError ? e : null) })
    return () => { alive = false }
  }, [])

  /* 성적표가 없는 회차는 늘 뒤로 보낸다. 수익률로 세우는 자리에 값이 없는 줄이
     섞이면 어디까지가 정렬된 것인지 알 수 없다. */
  const sortedDone = useMemo(() => {
    const rows = [...done]
    if (sort === 'RECENT') return rows.sort(recentFirst)
    const dir = sort === 'BEST' ? -1 : 1
    return rows.sort((a, b) => {
      if (a.returnRate == null) return 1
      if (b.returnRate == null) return -1
      return (a.returnRate - b.returnRate) * dir
    })
  }, [done, sort])

  const sortedOngoing = useMemo(() => [...(ongoing ?? [])].sort(recentFirst), [ongoing])

  const scored = done.filter((r) => r.returnRate != null)
  const avg = scored.length
    ? scored.reduce((a, r) => a + (r.returnRate ?? 0), 0) / scored.length
    : null
  const best = scored.length ? Math.max(...scored.map((r) => r.returnRate ?? 0)) : null

  if (ongoing === null) {
    return (
      <main className="main">
        <div className="main-inner sim-history">
          {loadError
            ? <ErrorState error={loadError} onRetry={() => setLoadError(null)} />
            : <div className="placeholder tall">{'기록을 불러오는 중…'}</div>}
        </div>
      </main>
    )
  }

  const rows = tab === 'ONGOING' ? sortedOngoing : sortedDone
  const showMode = mixedModes(rows)
  const TABS: { key: Tab; label: string; count: number; icon: ReactNode }[] = [
    {
      key: 'ONGOING',
      label: '이어서 할 연습',
      count: ongoing.length,
      icon: <Ico size={15}><path d="m10 8.5 6 3.5-6 3.5z" /><circle cx="12" cy="12" r="9" /></Ico>,
    },
    {
      key: 'DONE',
      label: '마친 시즌',
      count: done.length,
      icon: <Ico size={15}><path d="M5 13l4 4L19 7" /></Ico>,
    },
  ]

  return (
    <main className="main">
      <div className="main-inner sim-history">
        <nav className="sh-crumb" aria-label="위치">
          <Link to="/sim">모의투자 홈</Link>
          <i aria-hidden="true">›</i>
          <span>기록</span>
        </nav>

        <header className="sh-head">
          <div>
            <h1>기록</h1>
            <p>내가 참가한 시즌과 성적입니다</p>
          </div>
          <Link className="sh-go" to="/sim/modes">새로 시작하기</Link>
        </header>

        {/* ── 요약 3칸 ─────────────────────────────────── */}
        <section className="sh-figs" aria-label="기록 요약">
          <Figure
            label="마친 시즌"
            value={`${done.length}개`}
            sub={done.length === 0
              ? '아직 없습니다'
              : scored.length === done.length ? '모두 성적표 있음' : `${scored.length}개 성적표 있음`}
          />
          <Figure
            label="평균 수익률"
            value={avg == null ? '—' : rate(avg)}
            sub={scored.length === 0 ? '성적표가 없습니다' : `${scored.length}개 평균`}
            tone={avg == null ? 'flat' : signOf(avg)}
          />
          <Figure
            label="가장 좋았던 판"
            value={best == null ? '—' : rate(best)}
            sub={best == null ? '아직 없습니다' : '최고 수익률'}
            tone={best == null ? 'flat' : signOf(best)}
          />
        </section>

        {/* ── 목록. 진행 중과 마친 시즌은 같은 목록의 두 상태다 ── */}
        <section className="sh-card">
          <div className="sh-tabs" role="tablist" aria-label="기록 종류">
            {TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                role="tab"
                id={`sh-tab-${t.key}`}
                aria-selected={tab === t.key}
                aria-controls="sh-panel"
                className={tab === t.key ? 'on' : undefined}
                onClick={() => setTab(t.key)}
              >
                <span className="sh-tab-ico" aria-hidden="true">{t.icon}</span>
                {t.label}
                <em className="num">{t.count}</em>
              </button>
            ))}
          </div>

          <div className="sh-panel" id="sh-panel" role="tabpanel" aria-labelledby={`sh-tab-${tab}`}>
            {/* 정렬은 마친 시즌에만, 그것도 둘 이상일 때만 — 하나뿐이면 고를 것이 없다 */}
            {tab === 'DONE' && done.length > 1 && (
              <div className="sh-sorts" role="group" aria-label="정렬">
                {SORTS.map((x) => (
                  <button
                    key={x.key}
                    type="button"
                    aria-pressed={sort === x.key}
                    className={sort === x.key ? 'on' : undefined}
                    onClick={() => setSort(x.key)}
                  >
                    {x.label}
                  </button>
                ))}
              </div>
            )}

            {rows.length === 0 ? (
              <p className="sh-empty">
                {tab === 'ONGOING' ? '이어서 할 연습이 없습니다.' : '아직 끝까지 마친 시즌이 없습니다.'}
                <small>
                  {tab === 'ONGOING'
                    ? '연습을 시작하면 마지막으로 본 게임일에서 이어집니다.'
                    : '마지막 게임일까지 가면 성적표와 함께 여기에 쌓입니다.'}
                </small>
                <Link className="sh-go" to="/sim/practice">연습 고르러 가기</Link>
              </p>
            ) : (
              <ul className="sh-list">
                {rows.map((r) => <RunRow key={r.seasonId} run={r} showMode={showMode} />)}
              </ul>
            )}
          </div>
        </section>

        {loadError && <ErrorState error={loadError} onRetry={() => setLoadError(null)} inline />}
      </div>
    </main>
  )
}
