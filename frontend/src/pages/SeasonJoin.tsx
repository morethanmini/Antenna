/* G-03 시즌 상세 · 참가 · /sim/seasons/:id
   담당 스토리 [ANT-FE-SEASON-JOIN]
   설계서 docs/화면설계서.md §3 G-03 · §4 G-03

   구조는 프로토타입 screens/sim-contest.html 의 ct-season 블록을 옮겼다.
   그 화면은 대회 허브(시즌 카드 + 리더보드 + 규칙)였고, 그중 시즌 카드와
   참가 버튼만 이 화면의 몫이다. 리더보드는 G-07, 규칙은 아래 요약으로 둔다.

   ── 응답 계약이 명세에 비어 있다 ────────────────────────────────
   API 명세 §모의투자 의 GET /seasons/{id} 는 "200 성공" 만 적혀 있고 필드가 없다
   (목록 GET /seasons 만 { id, mode, lengthDays, initialCash, entryFee, status } 로
   정의돼 있다). 그래서 아래 SeasonDetail 은 이 화면이 그리려면 반드시 필요한
   최소 필드를 적은 것이고, 백엔드와 맞출 때 이 타입이 기준이 된다.

   ── 날짜를 보여주지 않는다 ──────────────────────────────────────
   SeasonDetail 에 baseDate·연도 필드를 두지 않는다 — 타입에 아예 없어야 실수로
   그려지지 않는다.

   2026-09-07 — 연습은 종목이 실명이다. 그래서 연습에서 이건 은닉이 아니다:
   실명과 실제 주가가 함께 나가면 검색 한 번에 날짜가 나온다. 날짜를 안 그리는
   이유는 화면 축이 DAY n 이기 때문이고, 진짜로 가리는 쪽은 대회다.

   ── 참가는 여기서 한다 ──────────────────────────────────────────
   POST /seasons/{id}/join · PRACTICE·DEMO 는 즉시 201, COMPETITION 은 참가비
   소각 서명을 동반해 202 → M-02 폴링(명세 §1 규칙 4). 서명 흐름은 지갑 연동에
   걸려 있어 [ANT-FE-WALLET-LINK] 뒤에 붙는다 — 지금은 대회 버튼을 막고 이유를
   적어 둔다. 연습·시연은 바로 참가해서 G-04 로 넘긴다.

   ── 같은 주제는 진행 중 회차 하나 ─────────────────────────────
   서버가 강제한다 — 진행 중 회차가 있으면 POST /join 은 409 SEASON_ALREADY_JOINED
   이고, 본문 { restart: true } 일 때만 그 회차를 ABANDONED 로 버리고 새 회차를 연다.
   버튼은 "참여하기" 하나다 — 진행 중 회차가 있으면 그 버튼이 초기화 확인창을 띄운다
   (2026-09-09). 이어하기는 홈·연습 페이지 카드의 몫이라 여기 따로 두지 않는다.
   버린 회차는 끝낸 것이 아니라 성적표·복기가 생기지 않는다 — 확인창이 그 말을 한다.

   ── 2026-09-17 · 사건 아트 히어로 ───────────────────────────────
   시즌 다섯 장에 사건 아트를 깔았다. 위쪽 히어로만 사진이고 그 아래는 서비스
   기본 밝은 판 그대로다 — 다크로 만들어 봤다가 되돌렸다(이 화면만 → 모의투자
   모드 전체 순으로 넓혔다가 둘 다). 히어로 안쪽 글자만 흰색이라는 뜻이고,
   그 경계는 sim-join.css 가 지킨다.

   제목·설명은 시즌마다 통 이미지(lockup)로 들어온다(sim/seasonArt). 글자가
   그림이라 <img alt> 로 읽히게 하고, 표에 없는 시즌은 종전대로 글자 제목이다. */
import { useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/errors'
import { join as joinApi } from '../api/seasonPlay'
import { useApiQuery } from '../api/useApiQuery'
import ErrorState from '../components/state/ErrorState'
import EmptyState from '../components/state/EmptyState'
import { seasonArt } from '../sim/seasonArt'
import '../styles/screens/sim-join.css'

type Mode = 'PRACTICE' | 'COMPETITION' | 'DEMO'
type Status = 'SCHEDULED' | 'RUNNING' | 'CLOSED'

/** 이 화면이 필요한 최소 필드. 명세에 응답이 비어 있어 여기서 제안한다. */
type SeasonDetail = {
  id: number
  mode: Mode
  status: Status
  /** 시즌의 성격. "급락 구간" 처럼 무슨 장이었는지만 알려준다 */
  title: string
  /** 한 줄 설명 */
  note?: string
  /** 섹터 힌트. 상위 분류로만 온다 — 좁게 주면 종목이 추정된다 */
  sector?: string
  /** 총 게임일 */
  lengthDays: number
  initialCash: number
  /** 시즌 종목 수. 실명이며 시총 상위 최대 200 이다(API 명세 v0.30) */
  tickerCount: number
  /** 대회만 값이 있다. 소각하는 ANT 토큰이다 */
  entryFee?: number
  /** 대회 시간표. 연습·시연은 없다 */
  opensAt?: string
  closesAt?: string
  dayIntervalMinutes?: number
  /** 이미 참가한 시즌이면 이어하기로 바뀐다 */
  joined?: boolean
  /** 참가했을 때의 개인 진행일 */
  currentDay?: number
}

const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const ant = (n: number) => `${n.toLocaleString('ko-KR')} ANT`

const MODE_LABEL: Record<Mode, string> = {
  PRACTICE: '연습',
  COMPETITION: '대회',
  DEMO: '시연',
}

const TONE: Record<Mode, string> = {
  PRACTICE: 'learn',
  COMPETITION: 'contest',
  DEMO: 'demo',
}

const Ico = ({ children }: { children: React.ReactNode }) => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
)

/* 시각은 KST ISO-8601 로 온다(명세 §1 규칙 7). 시간표는 시:분만 쓴다. */
function hhmm(iso?: string) {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? null
    : `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

export default function SeasonJoin() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const season = useApiQuery<SeasonDetail>(`/seasons/${id}`)

  const [joining, setJoining] = useState(false)
  const [joinError, setJoinError] = useState<ApiError | null>(null)
  /* 초기화 확인창. 네이티브 dialog — 포커스 가둠·Esc·배경막을 브라우저가 준다.
     서버가 진행 중 회차를 알려 줄 때(joined)와, 화면이 낡아 참가가 409 로 막혔을 때
     둘 다 여기로 온다. */
  const restartDialog = useRef<HTMLDialogElement>(null)

  const s = season.data

  async function join(restart = false) {
    if (!s || joining) return
    setJoining(true)
    setJoinError(null)
    try {
      /* joinApi 가 Idempotency-Key 를 붙이고 성공 뒤 반납한다(api/seasonPlay). */
      await joinApi(s.id, restart)
      navigate(`/sim/${s.id}/play`)
    } catch (e) {
      const already = e instanceof ApiError && e.code === 'SEASON_ALREADY_JOINED'
      if (already && !restart) {
        restartDialog.current?.showModal()
      } else {
        restartDialog.current?.close()
        setJoinError(e instanceof ApiError ? e : null)
      }
    } finally {
      setJoining(false)
    }
  }

  if (season.loading) {
    return (
      <main className="main">
        <div className="main-inner sim-join">
          <div className="placeholder tall">{'시즌을 불러오는 중…'}</div>
        </div>
      </main>
    )
  }

  if (season.error || !s) {
    return (
      <main className="main">
        <div className="main-inner sim-join">
          <nav className="sj-crumb" aria-label="위치">
            <Link to="/sim">모의투자 홈</Link>
            <i aria-hidden="true">›</i>
            <span>시즌 상세</span>
          </nav>
          {season.error ? (
            <ErrorState error={season.error} onRetry={season.reload} />
          ) : (
            <EmptyState
              title="시즌을 찾을 수 없습니다"
              hint="종료되었거나 삭제된 시즌일 수 있습니다"
              action={{ label: '연습 주제 고르기', to: '/sim' }}
            />
          )}
        </div>
      </main>
    )
  }

  const closed = s.status === 'CLOSED'
  const isCompetition = s.mode === 'COMPETITION'
  const opens = hhmm(s.opensAt)
  const closes = hhmm(s.closesAt)
  const art = seasonArt(s.title)

  return (
    <main className="main">
      <div className={`main-inner sim-join ${TONE[s.mode]}`} data-season={art?.key}>
        {/* ── 히어로 ─────────────────────────────────────────
            사진은 배경으로 깔고 글자는 그 위에 올린다. 사진을 <img> 로 두면
            제목 덩어리와 겹치게 하려고 position:absolute 를 또 써야 해서다.
            장식이라 role 도 alt 도 없다 — 읽어야 할 글자는 아래 lockup 이 갖는다. */}
        <section
          className={`sj-hero${art ? ' has-art' : ''}`}
          style={
            art
              ? ({ '--art': `url("${art.art}")`, '--art-pos': art.position } as React.CSSProperties)
              : undefined
          }
        >
          <div className="sj-hero-body">
            <nav className="sj-crumb" aria-label="위치">
              <Link to="/sim">모의투자 홈</Link>
              <i aria-hidden="true">›</i>
              {/* 어느 갈래인지만 말한다. 링크가 아닌 이유(2026-09-21) — 연습 허브를
                  홈에 합쳐 "연습" 이 곧 앞 칸의 홈이 됐다. 같은 곳으로 두 번 걸 수 없고,
                  대회·시연은 애초에 목록 화면이 없다. 갈래는 사실이라 글자로 남긴다. */}
              <span>{MODE_LABEL[s.mode]}</span>
              <i aria-hidden="true">›</i>
              <span>시즌 상세</span>
            </nav>

            <header className="sj-head">
              <span className="sj-mode">{MODE_LABEL[s.mode]}</span>
              {art ? (
                /* 제목·설명이 한 장에 그려져 있다. h1 안에 넣어 제목의 자리는
                   지키고, 읽히는 글자는 alt 가 준다. */
                <>
                  <h1 className="sj-lockup">
                    <img
                      src={art.lockup}
                      alt={s.note ? `${s.title} — ${s.note}` : s.title}
                      style={{ maxWidth: `${art.lockupMax}px` }}
                    />
                  </h1>
                  {/* 좁은 화면 전용. lockup 안의 한 줄 설명은 그림이라 폭이 줄면
                      같이 줄어서 폰에서 못 읽는다. 그 크기부터는 글자로 다시
                      깔아 준다 — 넓은 화면에서는 CSS 가 숨긴다.
                      alt 가 이미 같은 문장을 읽어 주므로 보조기기에는 숨긴다. */}
                  {s.note && <p className="sj-note-sm" aria-hidden="true">{s.note}</p>}
                </>
              ) : (
                <>
                  <h1>{s.title}</h1>
                  {s.note && <p>{s.note}</p>}
                </>
              )}
            </header>
          </div>
        </section>

        {/* 시즌 카드 — 프로토타입 ct-season */}
        <section className="sj-card" aria-label="시즌 조건">
          <div className="sj-card-main">
            <dl className="sj-stats">
              <div>
                <Ico>
                  <rect x="3" y="4.5" width="18" height="16" rx="2" />
                  <path d="M8 3v3M16 3v3M3 10h18" />
                </Ico>
                <div>
                  <dt>기간</dt>
                  <dd><b className="num">{s.lengthDays}</b>게임일</dd>
                </div>
              </div>
              <div>
                <Ico>
                  <rect x="2.5" y="6" width="19" height="12" rx="2" />
                  <circle cx="12" cy="12" r="2.5" />
                </Ico>
                <div>
                  <dt>초기 예수금</dt>
                  <dd><b className="num">{won(s.initialCash)}</b></dd>
                </div>
              </div>
              <div>
                <Ico>
                  <path d="M3 20h18" /><path d="M6 20v-7M11 20v-11M16 20v-5" />
                </Ico>
                <div>
                  <dt>종목</dt>
                  {/* 그 구간 첫날 시가총액 상위 200 이라 규모가 지수와 같다(시더 주석).
                      200 일 때만 지수 이름으로 부른다 — 180 을 "코스피 180" 이라 쓰면
                      있지도 않은 지수가 된다. */}
                  <dd>
                    {s.tickerCount === 200
                      ? <b>코스피 <span className="num">200</span></b>
                      : <><b className="num">{s.tickerCount}</b>개</>}
                  </dd>
                </div>
              </div>
              {s.sector && (
                <div>
                  <Ico>
                    <path d="M12 3a9 9 0 1 0 9 9h-9z" />
                    <path d="M14.5 2.5A8 8 0 0 1 21.5 9.5h-7z" />
                  </Ico>
                  <div>
                    {/* "섹터" 는 종목을 그 업종으로 거른다는 뜻으로 읽힌다. 실제로는
                        주제가 가리키는 업종 하나일 뿐이고 종목은 전 업종이 들어 있다
                        (시더 주석). 그래서 이름을 "주목 업종" 으로 바꾼다. */}
                    <dt>주목 업종</dt>
                    <dd><b>{s.sector}</b></dd>
                  </div>
                </div>
              )}
            </dl>

            {/* 대회 시간표 — 명세 §2 모의투자, 연습·시연은 값이 없다 */}
            {isCompetition && (opens || closes || s.dayIntervalMinutes) && (
              <p className="sj-schedule">
                <Ico>
                  <circle cx="12" cy="12" r="9" /><path d="M12 7v5.5l3.5 2" />
                </Ico>
                {opens && closes && <span>{`${opens} ~ ${closes} 진행`}</span>}
                {s.dayIntervalMinutes && (
                  <span>{`${s.dayIntervalMinutes}분마다 1게임일`}</span>
                )}
              </p>
            )}
          </div>

          <div className="sj-cta-wrap">
            {closed ? (
              <>
                <span className="sj-cta is-off" aria-disabled="true">종료된 시즌</span>
                <p className="sj-cta-note">기록은 결과 화면에서 볼 수 있습니다</p>
              </>
            ) : isCompetition ? (
              /* 참가비 소각 서명이 필요해 지갑 연동에 걸려 있다.
                 눌러도 서명할 곳이 없어 막아 두고 이유를 적는다. */
              <>
                <span className="sj-cta is-off" aria-disabled="true">대회 참여하기</span>
                <p className="sj-cta-note">
                  {s.entryFee
                    ? `참가비 ${ant(s.entryFee)} 소각 · 지갑 서명이 필요합니다`
                    : '지갑 서명이 필요합니다'}
                </p>
              </>
            ) : (
              /* 버튼 하나. 진행 중 회차가 있으면(joined) 서버를 부르기 전에 확인창부터 —
                 같은 주제는 진행 중 회차 하나뿐이고 새로 시작은 곧 초기화다. */
              <>
                <button
                  className="sj-cta" type="button" disabled={joining}
                  onClick={() => { if (s.joined) restartDialog.current?.showModal(); else void join() }}
                >
                  {joining ? '참여하는 중…' : '연습 참여하기'}
                  {!joining && <em aria-hidden="true">›</em>}
                </button>
                <p className="sj-cta-note">
                  {s.joined
                    ? `${s.currentDay ? `${s.currentDay}게임일까지 진행한` : '진행 중인'} 모의투자가 있습니다`
                    : '참가비 없이 바로 시작합니다'}
                </p>
              </>
            )}
          </div>
        </section>

        {joinError && <ErrorState error={joinError} onRetry={() => void join()} inline />}

        {/* 초기화 확인. 성공하면 G-04 로 넘어가며 창도 같이 사라진다.
            실패는 창을 닫고 위의 ErrorState 로 보인다. */}
        <dialog className="sj-dialog" ref={restartDialog} aria-labelledby="sj-restart-title">
          <h2 id="sj-restart-title">이미 진행 중인 모의투자가 있습니다</h2>
          <p>
            초기화하고 다시 시작하시겠습니까?
            <br />
            진행했던 내용은 분석되지 않습니다.
          </p>
          <small>
            {s.currentDay ? `D+${s.currentDay}까지 진행한 회차는 ` : '지금 회차는 '}
            기록에 남지 않고 성적표·복기도 만들어지지 않습니다.
          </small>
          <div className="sj-dialog-actions">
            <button type="button" className="sj-dialog-btn"
                    onClick={() => restartDialog.current?.close()}>
              취소
            </button>
            <button type="button" className="sj-dialog-btn solid" disabled={joining}
                    onClick={() => void join(true)}>
              {joining ? '초기화하는 중…' : '초기화하고 다시 시작'}
            </button>
          </div>
        </dialog>

        {/* 진행 규칙 요약 — 명세 §2·설계서 §4 G-04 에서 확정된 것만 적는다.
            **한 줄 띠로 둔다(2026-09-23).** 넉 장 카드 + 설명문이었는데, 참가하기
            직전에 볼 것치고는 너무 무거웠다 — 여기는 계약서가 아니라 한눈에 훑고
            넘어가는 자리다. 문장을 다 지우고 핵심 구만 남긴다. 긴 설명은 title 로
            옮겨 마우스를 올리면 보이게 한다. */}
        <section className="sj-rules" aria-labelledby="sj-rules-title">
          <h2 id="sj-rules-title">진행 방식</h2>

          <ul className="sj-steps">
            <li title="어떤 장이었는지만 알려주고 실제 날짜는 가립니다. 종목은 실명이며, 그 구간의 대형주가 전부 들어 있습니다.">
              <Ico><path d="M6 3h8l4 4v14H6z" /><path d="M14 3v4h4" /><path d="M9 12h6M9 16h6" /></Ico>
              <b>날짜 비공개 · 종목 실명</b>
            </li>
            <li title="수량과 방향만 정하면 됩니다. 수수료도 예약 주문도 없습니다.">
              <Ico><path d="M4 8h13l-3-3M20 16H7l3 3" /></Ico>
              <b>종가로 일괄 체결</b>
            </li>
            <li title={isCompetition
              ? '모든 참가자가 같은 게임일을 같은 시각에 보며, 직접 넘길 수 없습니다.'
              : '중단해도 진행하던 게임일부터 이어서 할 수 있습니다.'}>
              <Ico><path d="M4 5l7 7-7 7zM13 5l7 7-7 7z" /></Ico>
              <b>{isCompetition ? '게임일 자동 진행' : '게임일 직접 진행'}</b>
            </li>
            <li>
              <Ico><path d="M3 20h18" /><path d="M6 20v-7M11 20v-11M16 20v-5" /></Ico>
              <b>{isCompetition ? '리더보드 반영' : '랭킹 · 신뢰도 미반영'}</b>
            </li>
          </ul>
        </section>
      </div>
    </main>
  )
}
