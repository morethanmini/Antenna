/* G-09 기록 · /sim/history
   담당 스토리 [ANT-FE-SEASON-HISTORY]
   설계서 docs/화면설계서.md §3 · §4 G-09 · API 명세 §모의투자

   ── 여기가 모의투자의 목록 자리다 ────────────────────────
   상단바의 "투자 포트폴리오" 가 이 주소다. 시즌 번호가 필요한 화면(진행·결과·
   매매일지)은 상단바에 못 걸리므로, 여기서 시즌을 고르고 그쪽으로 들어간다.

   ── 무엇으로 그리는가 ────────────────────────────────────
   GET /seasons/me 하나다. v0.39 에서 제목·종료 시각·최종 수익률이 붙어(대연님)
   목록에 필요한 값이 한 응답에 다 온다 — 시즌마다 상세를 다시 부르지 않는다.

   ── 2026-09-22 홈과 같은 밤으로 맞췄다 ────────────────────
   `is-night` 를 더해 건다), 이 파일은 그 위에 놓이는 배치만 정한다.
   클래스 접두사를 sh- 에서 **rc-** 로 바꿨다. 홈(sim-home.css)이 같은 sh- 를 쓰고
   있어서 두 화면을 오가면 나중에 실린 쪽이 이겼다 — 실제로 .sh-bar 가 겹쳐 있었다.

   ── 시안에서 그리지 않은 것 ──────────────────────────────
   1. 줄마다의 한 줄 설명. /seasons/me 에 note 가 없다. 주제 목록(GET /seasons)에는
      있지만 그쪽은 참가 가능한 것만 주므로, 이미 내려간 주제(급락과 반등 등)는
      끝내 빈칸이 된다. 한 목록 안에서 어떤 줄만 설명이 붙는 편이 없느니만 못하다.
   2. 분류 칩(정치·사회 · 거시경제 …). 응답에 그런 값이 없다. MyRun 에는 theme 도
      sector 도 오지 않는다 — 만들어 붙이면 화면이 서버가 모르는 말을 하게 된다.
   썸네일은 시즌 아트 표(sim/seasonArt.ts)에서 제목으로 찾는다. 표에 없으면 사진
   없이 도형만 둔다 — 그건 꾸며 낸 정보가 아니라 자리 표시다.

   ── 배지는 뺐다 ──────────────────────────────────────────
   설계서 §G-09 는 "기록·배지" 지만 배지는 MVP 가 아니다(2026-09-09). GET /users/me/badges
   도 서버에 없다. 자리만 만들어 두면 빈 상자가 화면을 차지하기만 하므로 통째로 뺐다. */
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/errors'
import { rate, signOf } from '../api/seasonPlay'
import {
  getMyRuns, MODE_LABEL, progressOf, recentFirst, type MyRun,
} from '../api/seasons'
import ErrorState from '../components/state/ErrorState'
import { seasonArt } from '../sim/seasonArt'
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

const LayersIcon = () => (
  <Ico size={22}><path d="M12 3 3 8l9 5 9-5z" /><path d="m3 13 9 5 9-5" /></Ico>
)
const TrendIcon = () => <Ico size={22}><path d="M4 16 10 10l4 4 6-7" /><path d="M15 7h5v5" /></Ico>
const CupIcon = () => (
  <Ico size={22}><path d="M7 4h10v5a5 5 0 0 1-10 0z" /><path d="M7 6H4.5a2.5 2.5 0 0 0 2.5 2.5M17 6h2.5A2.5 2.5 0 0 1 17 8.5" />
    <path d="M9.5 19h5M12 14v5" /></Ico>
)
const TargetIcon = () => (
  <Ico size={22}><circle cx="12" cy="12" r="8.5" /><circle cx="12" cy="12" r="4" /><circle cx="12" cy="12" r="1" fill="currentColor" /></Ico>
)
const ChartIcon = () => <Ico size={20}><path d="M6 20v-6M12 20V7M18 20v-9" /></Ico>

/* ── 요약 한 칸 ────────────────────────────────────────────
   아이콘 타일을 다시 넣었다(2026-09-22). 전에는 "숫자 셋을 나란히 읽는 자리라
   타일이 먼저 보인다" 고 뺐는데, 어두운 판에서는 칸끼리 구분되는 것이 테두리 하나뿐이라
   넷이 한 덩어리로 뭉쳤다. 타일은 칸을 가르는 역할을 한다. */
function Figure({ icon, label, value, sub, tone }: {
  icon: ReactNode
  label: string
  value: string
  sub: string
  tone?: string
}) {
  return (
    <div className="rc-fig">
      <i className={`rc-fig-ico ${tone ?? ''}`} aria-hidden="true">{icon}</i>
      <div className="rc-fig-body">
        <span className="rc-fig-label">{label}</span>
        <b className={`rc-fig-val num ${tone ?? ''}`}>{value}</b>
        <span className="rc-fig-sub">{sub}</span>
      </div>
    </div>
  )
}

/* ── 한 줄 ────────────────────────────────────────────────
   마친 판은 성적을, 진행 중인 판은 남은 길이를 오른쪽에 둔다 —
   같은 자리에 "그 판이 지금 무엇인지" 가 온다.

   모드 배지는 목록에 모드가 섞였을 때만 낸다. 연습만 있는 목록에서 모든 줄에
   "연습" 이 붙으면 구분하는 정보가 아니라 같은 글자가 n번 반복되는 것이다. */
function RunRow({ run, showMode }: { run: MyRun; showMode: boolean }) {
  const isDone = run.endedAt != null || run.returnRate != null
  const r = run.returnRate
  const ended = endedLabel(run.endedAt)
  const pct = progressOf(run.currentDay, run.lengthDays)
  const art = seasonArt(run.title)

  return (
    <li>
      <Link
        className={`rc-row ${isDone ? 'is-done' : 'is-go'}`}
        to={isDone ? `/sim/${run.seasonId}/result` : `/sim/${run.seasonId}/play`}
      >
        {/* 사진이 없는 주제는 도형만 둔다 — 빈 네모보다 낫고, 없는 정보를 지어내지도 않는다 */}
        <span className="rc-thumb" aria-hidden="true"
              style={art ? { backgroundImage: `url(${art.art})`, backgroundPosition: art.position } : undefined}>
          {!art && <ChartIcon />}
        </span>

        <span className="rc-name">
          {showMode && <span className="rc-mode">{MODE_LABEL[run.mode]}</span>}
          <b>{run.title}</b>
        </span>

        {isDone ? (
          <>
            <span className="rc-when">{ended ?? `${run.lengthDays}게임일`}</span>
            {/* 결과가 없는 완료 회차도 있다 — 끝냈지만 성적표를 안 굳힌 판이다 */}
            <span className={`rc-rate num ${r == null ? 'flat' : signOf(r)}`}>
              {r == null ? '성적표 없음' : rate(r)}
            </span>
          </>
        ) : (
          <>
            <span className="rc-when num">DAY {run.currentDay} / {run.lengthDays}</span>
            {/* 막대를 화면 끝까지 늘리지 않는다 — 길이가 정보인 것은 찬 비율이지 폭이 아니다 */}
            <span className="rc-bar" aria-hidden="true"><i style={{ width: `${pct}%` }} /></span>
          </>
        )}

        <i className="rc-chev" aria-hidden="true">
          {isDone ? <Ico size={16}><path d="M5 13l4 4L19 7" /></Ico> : <Ico size={16}><path d="m9 5 7 7-7 7" /></Ico>}
        </i>
      </Link>
    </li>
  )
}

/* ── 나의 성장 여정 ────────────────────────────────────────
   마친 판 수 하나로 칠한다. 서버에 단계 같은 것은 없고, 이건 새로 받아온 값이 아니라
   **위 요약의 "마친 시즌" 을 다르게 보여 주는 것**이다 — 그래서 숫자를 다시 적지 않고
   칸만 칠한다. 문턱은 1 · 2 · 4 · 8 이다(한 판이라도 하면 첫 칸이 켜진다).
   문턱을 바꾸면 여기 한 줄만 고치면 된다.

   **칸마다 달려 있던 설명 줄을 걷었다(2026-09-22).** 좁은 칸에서 두 줄씩 접혀
   카드가 그만큼 길어졌고, 그 길이 때문에 오른쪽 기둥이 목록 아래로 내려갔다.
   이름 넷이면 무엇을 말하는지는 위 한 줄(지금까지 n판…)이 이미 설명한다. */
const STEPS = [
  { at: 1, name: '시작' },
  { at: 2, name: '학습' },
  { at: 4, name: '경험' },
  { at: 8, name: '투자왕' },
]

function Journey({ done }: { done: number }) {
  const reached = STEPS.filter((s) => done >= s.at).length

  return (
    <section className="rc-side-card" aria-label="나의 성장 여정">
      <h2 className="rc-side-head"><ChartIcon />나의 성장 여정</h2>
      {/* 격려 카드 아래에 따로 있던 "지금까지 n판" 줄을 여기로 합쳤다(2026-09-22).
          이 카드가 곧 "내가 몇 판을 했나" 를 말하는 자리라 제자리이기도 하고,
          오른쪽 기둥이 목록보다 104px 아래로 내려가던 것도 이걸로 줄였다. */}
      <p className="rc-side-lead">
        {done === 0
          ? '첫 판을 끝내면 여기부터 채워져요.'
          : `지금까지 ${done}판을 끝냈어요. 연습할수록 투자 감각이 쌓여요.`}
      </p>

      <ol className="rc-steps" style={{ ['--reached' as string]: reached }}>
        {STEPS.map((s, i) => (
          <li key={s.name} className={i < reached ? 'on' : undefined}>
            <span className="rc-step-dot" aria-hidden="true">
              {i < reached && <Ico size={13}><path d="M5 13l4 4L19 7" /></Ico>}
            </span>
            <b>{s.name}</b>
          </li>
        ))}
      </ol>
    </section>
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
  /* 완주율 = 마친 판 / 내가 손댄 판 전부. 참가한 것 중 끝까지 간 비율이다 —
     참가 가능한 주제 수(GET /seasons)로 나누지 않는다. 그건 내 기록이 아니라
     서버가 몇 개를 열어 뒀는지이고, 주제가 늘면 내가 아무것도 안 해도 값이 떨어진다. */
  const touched = (ongoing?.length ?? 0) + done.length
  const finish = touched > 0 ? (done.length / touched) * 100 : null

  if (ongoing === null) {
    return (
      <main className="main">
        <div className="main-inner sim-record">
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
      <div className="main-inner sim-record">
        {/* 머리. 오른쪽 손글씨는 홈의 아래 띠와 같은 자리를 맡는다 — 숫자가 아니라
            이 화면이 왜 있는지를 말한다. 장식이라 스크린리더에서 뺀다. */}
        <header className="rc-head">
          <div>
            <nav className="rc-crumb" aria-label="위치">
              <Link to="/sim">모의투자 홈</Link>
              <i aria-hidden="true">›</i>
              <span>기록</span>
            </nav>
            <h1>기록</h1>
            <p>내가 참가한 시즌과 성과입니다</p>
          </div>

          {/* 시작 버튼이 오른쪽에 선다. 원래 여기 있었는데 격려 카드 안으로 옮겼다가
              되돌렸다(2026-09-22) — 오른쪽 기둥이 목록보다 181px 아래로 흘러서,
              기둥에서 덜어 낼 것 중 제자리가 있는 것이 이것이다. 이 화면의 으뜸
              행동이기도 해서 머리에 있는 편이 찾기도 쉽다.

              손글씨 인용("오늘의 연습이 더 나은 투자자를 만든다." — ANTENNA)은
              걷어냈다(2026-09-23). 어느 서비스에나 붙일 수 있는 말이라 이 화면이
              무엇을 보여 주는 곳인지 말해 주지 못했다 — 모의투자 홈에서 같은 이유로
              뺀 두 줄과 같은 판단이다. */}
          <div className="rc-head-side">
            <Link className="rc-go" to="/sim">새로 시작하기</Link>
          </div>
        </header>

        <div className="rc-body">
          {/* 왼쪽 기둥 — 요약 4칸과 목록이 같은 폭으로 선다(2026-09-22).
              요약이 화면 전체를 가로지르던 때는 오른쪽 기둥이 그 아래에서야 시작해,
              격려 카드와 성장 여정이 통째로 한 칸 내려앉아 있었다. 요약을 왼쪽으로
              들이면 오른쪽 기둥이 요약과 같은 높이에서 시작한다 — 화면 전체 높이는
              그대로이고(덩어리 순서만 바뀐다) 오른쪽 위 빈자리만 없어진다. */}
          <div className="rc-main">
        {/* ── 요약 4칸 ─────────────────────────────────── */}
        <section className="rc-figs" aria-label="기록 요약">
          <Figure
            icon={<LayersIcon />}
            label="마친 시즌"
            value={`${done.length}개`}
            sub={done.length === 0
              ? '아직 없습니다'
              : scored.length === done.length ? '모두 성적표 있음' : `${scored.length}개 성적표 있음`}
          />
          <Figure
            icon={<TrendIcon />}
            label="평균 수익률"
            value={avg == null ? '—' : rate(avg)}
            sub={scored.length === 0 ? '성적표가 없습니다' : `${scored.length}개 평균`}
            tone={avg == null ? 'flat' : signOf(avg)}
          />
          <Figure
            icon={<CupIcon />}
            label="가장 좋았던 판"
            value={best == null ? '—' : rate(best)}
            sub={best == null ? '아직 없습니다' : '최고 수익률'}
            tone={best == null ? 'flat' : signOf(best)}
          />
          <Figure
            icon={<TargetIcon />}
            label="완주율"
            value={finish == null ? '—' : `${finish.toFixed(1)}%`}
            sub={finish == null ? '아직 없습니다' : `참가한 ${touched}개 기준`}
          />
        </section>

          {/* ── 목록. 진행 중과 마친 시즌은 같은 목록의 두 상태다 ── */}
          <section className="rc-card">
            <div className="rc-tabs" role="tablist" aria-label="기록 종류">
              {TABS.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  role="tab"
                  id={`rc-tab-${t.key}`}
                  aria-selected={tab === t.key}
                  aria-controls="rc-panel"
                  className={tab === t.key ? 'on' : undefined}
                  onClick={() => setTab(t.key)}
                >
                  <span className="rc-tab-ico" aria-hidden="true">{t.icon}</span>
                  {t.label}
                  <em className="num">{t.count}</em>
                </button>
              ))}
            </div>

            <div className="rc-panel" id="rc-panel" role="tabpanel" aria-labelledby={`rc-tab-${tab}`}>
              {/* 정렬은 마친 시즌에만, 그것도 둘 이상일 때만 — 하나뿐이면 고를 것이 없다 */}
              {tab === 'DONE' && done.length > 1 && (
                <div className="rc-sorts" role="group" aria-label="정렬">
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
                <p className="rc-empty">
                  {tab === 'ONGOING' ? '이어서 할 연습이 없습니다.' : '아직 끝까지 마친 시즌이 없습니다.'}
                  <small>
                    {tab === 'ONGOING'
                      ? '연습을 시작하면 마지막으로 본 게임일에서 이어집니다.'
                      : '마지막 게임일까지 가면 성적표와 함께 여기에 쌓입니다.'}
                  </small>
                  <Link className="rc-go" to="/sim">연습 고르러 가기</Link>
                </p>
              ) : (
                <ul className="rc-list">
                  {rows.map((r) => <RunRow key={r.seasonId} run={r} showMode={showMode} />)}
                </ul>
              )}
            </div>
          </section>
          </div>

          {/* ── 오른쪽 기둥 ───────────────────────────── */}
          <aside className="rc-side">
            {/* 받은 시안 그대로 쓴다(2026-09-22). 글씨까지 그려진 한 장이라 코드로
                다시 짜 보았는데, 그럴 이유가 없었다 — 이 칸에는 서버에서 오는 값이
                하나도 없어서 글이 이미지든 글자든 달라질 것이 없다. 화면이 읽어 줄
                내용은 alt 가 들고 간다(시즌 아트 lockup 과 같은 처리다).
                아래 한 줄만 글자로 남긴다. 그건 내 기록에 따라 바뀌어 그림에 못 넣는다. */}
            <section className="rc-side-card rc-cheer">
              <img
                className="rc-cheer-card" src="/assets/page_train2/cheer-card.webp"
                alt="작은 연습이 큰 투자자를 만듭니다. 다양한 시장 상황을 경험하며 오늘도 더 나은 나를 만들어가요. 지금의 연습이, 내일의 기회를 만듭니다."
              />
              {/* 칭찬은 실제 기록에 따라 갈린다 — 한 판도 안 끝낸 사람에게
                  "잘하고 있어요" 라고 하면 빈말이 된다. */}
            </section>

            <Journey done={done.length} />
          </aside>
        </div>

        {loadError && <ErrorState error={loadError} onRetry={() => setLoadError(null)} inline />}
      </div>
    </main>
  )
}
