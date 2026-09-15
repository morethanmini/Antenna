/* G-00 모의투자 온보딩 (오버레이 · 라우트 없음)
   담당 스토리 [ANT-FE-SEASON-ONBOARDING] · 지라 S15P21A507-239

   모의투자를 처음 연 사람에게 "무엇을 하는 곳인지" 를 세 걸음으로 알린다.
   셸(Layout)이 mode==='sim' 이고 아직 보지 않았을 때 띄운다.

   ── 왜 라우트가 아니라 팝업인가 ─────────────────────────
   같은 제품의 안내가 인사이트에서는 오버레이(M-09 OnboardingTour)인데 모의투자에서만
   전체 화면이면 두 갈래가 다른 서비스처럼 읽힌다. 라우트로 두면 히스토리에도 남아,
   안내를 닫은 뒤 뒤로 가기를 누르면 다시 안내가 뜬다.
   그래서 M-09 와 같은 자리·같은 규칙으로 맞췄다 — 셸 위 오버레이, 라우트 없음.

   ── M-09 와 따로인 이유 ────────────────────────────────
   M-09 는 가입 직후 한 번, 제품 전체(예측·검증·지갑)를 훑는다. 이건 진입 시점이
   다르다 — 모의투자를 처음 연 때이고, 그건 가입 직후일 수도 한참 뒤일 수도 있다.
   한 컴포넌트에 걸음을 더 붙이면 가입 직후 일곱 걸음이 되고, 정작 모의투자를 나중에
   켠 사람은 그때 이미 다 넘겨 버린 뒤다.
   두 개가 동시에 뜨지 않게 막는 것은 셸이 한다(Layout 의 `!tour`).

   ── 숫자를 그리지 않는다 ────────────────────────────────
   시안에는 종목명·매수가·수익률(+8.4% · 시장 +3.1%)과 날짜축이 적혀 있다. 여기 오는
   사람은 아직 아무것도 하지 않았으므로 전부 지어낸 값이고, 실제 수익률과 같은 자리·
   같은 서체로 그리면 연습 결과를 미리 본 것처럼 읽힌다. 날짜는 그 자체가 시대 단서다.
   대신 비교 그림은 /assets/pageset/card-market.webp(두 선이 그려진 유리 카드)로 둔다.

   시작 자산·시즌 수만 예외다. 그 둘은 지어낸 값이 아니라 GET /seasons?mode=PRACTICE
   응답에서 받아 그린다 — G-01 홈 히어로 칩과 같은 출처라 두 화면이 다른 값을 말할 수 없다.

   ── 없는 기능을 적지 않는다 ────────────────────────────
   걸음마다의 세 줄은 G-04 진행·G-06 매매일지·G-08 결과에 **실제로 있는 것**만 옮겼다.
   여기는 제품을 처음 보는 사람이 읽는 자리라, 여기 적힌 것은 곧 약속이 된다 — 들어가서
   찾지 못하면 안내가 아니라 낚시가 된다.
   그래서 시안의 "매수·매도 이유 기록 · 나만의 투자 노트" 는 뺐다. 주문 API 가
   (tickerId, side, qty) 뿐이라 이유를 적을 자리가 없다(api/seasonPlay.ts 의 order).
   메모 필드가 생기면 2걸음에 한 줄 더하면 된다.

   그림은 전부 public/assets 에 있는 것만 쓴다. 모의투자는 검정 개미다 —
   인사이트 쪽 흰 개미(white_ant)는 이 화면에 오지 않는다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import { agreed, getPracticeRuns, isJoinable, manwon } from '../api/seasons'
import { useAsync } from '../api/useAsync'
import { markSimOnboardingSeen } from '../sim/onboardingSeen'
import '../styles/screens/sim-onboarding.css'

type Props = {
  /** 닫는다. 끝냈든 건너뛰었든 "봤다" 로 남기는 것은 이 컴포넌트가 한다. */
  onClose: () => void
  /** 마지막 걸음의 "연습 시작하기". 셸이 닫고 G-02 로 보낸다. */
  onStart: () => void
}

const Ico = ({ children }: { children: React.ReactNode }) => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
)
const NoteIcon = () => (
  <Ico><rect x="5" y="3" width="14" height="18" rx="2.5" /><path d="M9 8h6M9 12h6M9 16h3.5" /></Ico>
)
const BarsIcon = () => <Ico><path d="M6 20v-6M12 20V7M18 20v-9" /></Ico>
const ScaleIcon = () => (
  <Ico><path d="M12 4v16M5 8h14" /><path d="M5 8l-2.5 5.5h5L5 8zM19 8l-2.5 5.5h5L19 8z" /></Ico>
)
const CalendarIcon = () => (
  <Ico><rect x="3" y="5" width="18" height="16" rx="2.5" /><path d="M8 3v4M16 3v4M3 10h18" /></Ico>
)
/* 지표 — 봉 위에 선이 지나간다. 이동평균·볼린저를 한 그림으로 줄인 모양이다 */
const IndicatorIcon = () => (
  <Ico><path d="M3 15.5 8 9l4 3.5L21 4" /><path d="M7 20v-4M12 20v-6M17 20v-3" /></Ico>
)
const NewsIcon = () => (
  <Ico><rect x="3" y="5" width="18" height="14" rx="2.5" /><path d="M7 9.5h6M7 13h6M16.5 9.5v5" /></Ico>
)
/* 자산 곡선 — 두 줄이 갈라진다(내 결과와 시장) */
const CurveIcon = () => (
  <Ico><path d="M3 20h18" /><path d="M4 16c4-1 6-7 9-8s5 2 7 1.5" /><path d="M4 18.5c4-.6 7-3 9-3.6s5 .8 7 .4" /></Ico>
)
const ShieldIcon = () => (
  <Ico><path d="M12 3 5 6v6c0 4.2 3 7.4 7 8.5 4-1.1 7-4.3 7-8.5V6z" /><path d="M9 12.5 11 14.5 15 10" /></Ico>
)
const BulbIcon = () => (
  <Ico><path d="M9 18h6M10 21h4" />
    <path d="M12 3a6 6 0 0 0-3.5 10.9c.6.4 1 1.1 1 1.8V16h5v-.3c0-.7.4-1.4 1-1.8A6 6 0 0 0 12 3z" /></Ico>
)
const CoinsIcon = () => (
  <Ico><ellipse cx="12" cy="6" rx="7.5" ry="3" /><path d="M4.5 6v5c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V6" />
    <path d="M4.5 11v5c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-5" /></Ico>
)
const StackIcon = () => (
  <Ico><path d="M12 3 3 7.5 12 12l9-4.5z" /><path d="m3 12 9 4.5L21 12" /><path d="m3 16.5 9 4.5 9-4.5" /></Ico>
)
const ArrowIcon = () => <Ico><path d="M5 12h13M13 6l6 6-6 6" /></Ico>
const BackIcon = () => <Ico><path d="M19 12H6M11 18l-6-6 6-6" /></Ico>

/* ── 세 걸음 ───────────────────────────────────────────────
   무대(art·drift)는 이름만 적고 위치·크기는 CSS(.sot-stage.s1~.s3)가 정한다.
   좌표를 마크업과 CSS 두 곳에 나눠 두면 시안과 맞출 때마다 두 파일을 오간다.

   문구는 G-01 홈과 G-02 모드 선택에 이미 적힌 표현만 옮겼다 — 여기서 새 약속을
   만들면 들어가서 없는 기능을 찾게 된다. */
const STEPS = [
  {
    key: 'practice',
    eyebrow: '투자의 첫걸음, 연습으로',
    title: <>실제 시장으로 <em>부담 없이 연습</em>해요</>,
    lead: '시장을 흔든 실제 구간을 골라, 그날의 주가 그대로 하루씩 되짚어요.',
    art: '/assets/pageset/ant-chart.webp',
    drift: ['card-practice', 'arrow'],
    points: [
      { icon: <BarsIcon />, text: '실제 과거 일봉으로 진행해요' },
      { icon: <CalendarIcon />, text: '하루씩 직접 넘기며 내 속도로' },
      { icon: <ScaleIcon />, text: '잃을 돈이 없으니 마음껏 시도해요' },
    ],
  },
  {
    /* G-04 시즌 진행 화면에 실제로 있는 것만 적는다.
       "매수 이유 기록 · 나만의 투자 노트" 는 시안에 있었지만 뺐다 — 주문 API 가
       (tickerId, side, qty) 뿐이라 이유를 적을 자리가 없다(api/seasonPlay.ts 의 order).
       메모 필드가 생기면 그때 이 걸음에 한 줄 더한다. */
    key: 'trade',
    eyebrow: '차트와 뉴스를 보고 판단해요',
    title: <>지표와 뉴스를 보며 <em>사고팔아요</em></>,
    lead: '앞날은 가려져 있어요. 그날까지의 흐름만 보고 판단합니다.',
    art: '/assets/character/black_ant/antenna-character-black.png',
    drift: ['buy', 'script', 'sell'],
    points: [
      { icon: <IndicatorIcon />, text: '이동평균 · 볼린저 · RSI · MACD' },
      { icon: <NewsIcon />, text: '그날의 뉴스로 무슨 일이 있었는지 확인' },
      { icon: <NoteIcon />, text: '모든 체결이 매매일지에 남아요' },
    ],
  },
  {
    /* G-08 결과 화면이 실제로 내려주는 값만 적는다(api/seasonPlay.ts 의
       SeasonFinishResult · SeasonResultData).
       "시장" 은 코스피가 아니라 그 시즌 종목을 똑같이 나눠 사서 들고 있었을 때다 —
       지수 이름을 쓰면 틀린 말이 되므로 "시장 대비" 까지만 쓴다. */
    key: 'review',
    eyebrow: '끝나면 성적표와 AI 복기',
    title: <>시장과 비교하며 <em>복기</em>해요</>,
    lead: '같은 구간을 그대로 들고 있었을 때와 내 결과를 나란히 봅니다.',
    art: '/assets/character/black_ant/antenna-character-black-run.png',
    drift: ['card-market', 'arrow'],
    points: [
      { icon: <CurveIcon />, text: '자산 곡선과 시장 대비 수익률' },
      { icon: <ShieldIcon />, text: '최대 낙폭 · 승률 · 손익비 · 평균 보유일' },
      { icon: <BulbIcon />, text: 'AI 복기 — 잘한 판단 · 아쉬운 판단 · 개선 제안' },
    ],
  },
] as const

export default function SimOnboardingTour({ onClose, onStart }: Props) {
  const [at, setAt] = useState(0)
  const dialogRef = useRef<HTMLDivElement>(null)

  /* 1걸음 칩("시작 자산 3,000만원 · 연습할 수 있는 시즌 5개")의 근거.
     실패해도 오류를 그리지 않는다 — 이 오버레이의 본문은 안내지 목록이 아니라서,
     칩만 빠지고 세 걸음은 그대로 읽힌다. */
  const { data: open } = useAsync(getPracticeRuns)
  const practice = open?.items
  const cash = agreed(practice, 'initialCash')
  /* 참가할 수 있는 것만 센다. 닫힌 시즌까지 세면 "5개" 라고 해 놓고 정작 목록에는
     넷만 있는 일이 생긴다. */
  const seasons = practice?.filter(isJoinable).length

  const step = STEPS[at]
  const last = at === STEPS.length - 1

  /* 끝내든 건너뛰든 "봤다" 로 남긴다. 건너뛴 사람에게 다음에 또 띄우면 그건 안내가
     아니라 관문이다 — 같은 내용은 G-01 의 "안내 다시 보기" 가 받는다. */
  const close = useCallback(() => { markSimOnboardingSeen(); onClose() }, [onClose])
  const start = useCallback(() => { markSimOnboardingSeen(); onStart() }, [onStart])

  const back = useCallback(() => setAt((i) => Math.max(0, i - 1)), [])
  const next = useCallback(() => setAt((i) => Math.min(STEPS.length - 1, i + 1)), [])

  /* 배경 클릭으로는 닫지 않는다 — 세 걸음을 넘기다 가장자리를 스치면 안내가 통째로
     사라지고, 한 번 닫으면 자동으로는 다시 뜨지 않는다(M-09 와 같은 판단).
     닫기는 X 와 "건너뛰기" 로만. 좌우 화살표로 걸음을 넘긴다. */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
      else if (e.key === 'ArrowRight') next()
      else if (e.key === 'ArrowLeft') back()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [close, next, back])

  // 열리면 초점을 안으로 들인다. 없으면 탭이 뒤 화면을 돌아다닌다.
  useEffect(() => { dialogRef.current?.focus() }, [])

  return (
    <div className="sot-backdrop">
      <div
        className="sot" role="dialog" aria-modal="true" aria-labelledby="sot-title"
        tabIndex={-1} ref={dialogRef}
      >
        {/* ── 왼쪽: 글 ──────────────────────────────── */}
        <div className="sot-copy">
          <div className="sot-rail">
            {/* 몇 걸음 남았는지 모르면 읽을지 건너뛸지 판단할 수 없다.
                지난 걸음과 남은 걸음을 다르게 칠해 어디까지 왔는지도 함께 알린다. */}
            <ol className="sot-dots" aria-label={`${STEPS.length}걸음 중 ${at + 1}번째`}>
              {STEPS.map((s, i) => (
                <li key={s.key} className={i === at ? 'is-on' : i < at ? 'is-done' : undefined} />
              ))}
            </ol>
            <b className="sot-count num">{at + 1} / {STEPS.length}</b>
          </div>

          {/* 걸음이 바뀌면 key 로 리마운트되어 등장 애니메이션이 다시 돈다.
              카드 크기는 CSS 가 고정해 두므로 글이 짧은 걸음에서도 흔들리지 않는다. */}
          <div className="sot-step" key={step.key}>
            <span className="sot-eyebrow">{step.eyebrow}</span>
            <h2 className="sot-title" id="sot-title">{step.title}</h2>
            <p className="sot-lead">{step.lead}</p>

            {/* 연습이 어떤 조건인지는 첫 걸음에서만 말한다. 뒤 두 걸음은 기록·복기
                이야기라 같은 칩을 세 번 보여 주면 읽히지 않는 배경이 된다.
                값이 안 오거나 시즌마다 달라 하나로 모이지 않으면 그 칩만 빠진다. */}
            {step.key === 'practice' && (cash !== undefined || !!seasons) && (
              <div className="sot-chips">
                {cash !== undefined && (
                  <dl className="sot-chip">
                    <CoinsIcon />
                    <div><dt>시작 자산</dt><dd className="num">{manwon(cash)}</dd></div>
                  </dl>
                )}
                {!!seasons && (
                  <dl className="sot-chip">
                    <StackIcon />
                    <div><dt>연습할 수 있는 시즌</dt><dd className="num">{seasons}개</dd></div>
                  </dl>
                )}
              </div>
            )}

            <ul className="sot-points">
              {step.points.map((p) => (
                <li key={p.text}><span className="sot-point-ico">{p.icon}</span>{p.text}</li>
              ))}
            </ul>
          </div>

          <div className="sot-actions">
            {/* 건너뛰기는 늘 남겨 둔다. 처음 들어온 사람을 붙잡아 두지 않는다 */}
            <button type="button" className="sot-skip" onClick={close}>건너뛰기</button>

            <div className="sot-nav">
              {/* 첫 걸음에서도 자리를 비우지 않는다 — 버튼이 생겼다 사라지면 다음
                  버튼이 좌우로 움직여 연달아 누를 때 빗나간다. */}
              <button
                type="button" className="sot-btn ghost" onClick={back}
                disabled={at === 0} aria-hidden={at === 0} tabIndex={at === 0 ? -1 : undefined}
              >
                <BackIcon />
                이전
              </button>

              {last ? (
                /* 모드 선택(G-02)으로 보낸다. 여기서 바로 시즌을 만들지 않는다 —
                   고르는 자리가 따로 있고, 그 화면이 시작 자산·기간을 보여준다. */
                <button type="button" className="sot-btn solid" onClick={start}>
                  연습 시작하기
                  <ArrowIcon />
                </button>
              ) : (
                <button type="button" className="sot-btn solid" onClick={next}>
                  다음
                  <ArrowIcon />
                </button>
              )}
            </div>
          </div>
        </div>

        {/* ── 오른쪽: 무대 ──────────────────────────────
            마스코트와 장식이 떠 있다. 전부 뜻이 없는 장식이라 alt 를 비우고 레이어째
            aria-hidden 으로 덮는다 — 말풍선 안 글씨도 왼쪽 글이 이미 같은 내용을
            말하고 있어 읽어 줄 필요가 없다.
            BUY·SELL 은 버튼 그림이지만 여기서는 장식이다. 누를 수 있는 것으로 보이지
            않게 작게 쓰고, 레이어가 마우스를 받지 않는다. */}
        <div className={`sot-stage s${at + 1}`} key={`art-${step.key}`}>
          <div className="sot-art-layer" aria-hidden="true">
            {step.drift.map((name, i) => (
              <img key={name} className={`sot-drift d${i + 1}`} src={`/assets/pageset/${name}.webp`} alt="" />
            ))}
            <img className="sot-art" src={step.art} alt="" />
          </div>
        </div>

        {/* 닫기는 무대 위에 얹는다 — 오른쪽 위가 닫기를 찾는 자리다 */}
        <button type="button" className="sot-x" aria-label="건너뛰기" onClick={close}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               strokeWidth="2" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
    </div>
  )
}
