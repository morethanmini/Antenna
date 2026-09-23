/* M-09 온보딩 튜토리얼 (오버레이 · 라우트 없음)
   담당 스토리 [ANT-FE-LOGIN]
   설계서 docs/화면설계서.md §3 M · §4 M-09

   설계 제약
   - **라우트를 주지 않는다.** 라우트면 히스토리에 남아 이탈 시 복귀가 어렵다.
     셸(Layout) 위 오버레이로만 뜬다 — 홈에만 걸면 비로그인 딥링크로 막혔다가
     가입한 회원이 못 본다(그 사람은 막혔던 경로로 착지한다).
   - **isNew 는 최초 1회뿐이다.** 건너뛰면 다시 뜨지 않는다 — 남은 단계는
     H-03 환경 설정의 "아직 마치지 않은 설정" 이 받는다.
   - **M-01 지갑 연동을 이 안에서 열지 않는다.** 모달 3겹이 되기 때문이다.
     연동을 누르면 onLinkWallet 으로 올려 호출부(셸)가 이걸 닫고 띄운다.
     WalletLinkModal 머리말에도 같은 계약이 적혀 있다.

   닉네임 입력을 두지 않는 이유
   설계서 §4 M-09 는 이 화면의 API 로 닉네임 확인·저장을 함께 적고 있다. 그건
   온보딩이 한 덩어리였을 때의 목록이고, 실제로는 A-02(/onboarding/nickname)가
   차단 라우트로 갈라져 나갔다 — RequireAccess 가 닉네임 없는 회원을 그리로
   돌려보내므로 **이 오버레이가 뜨는 시점에 닉네임은 이미 있다.** 여기서 폼을 다시 두면
   같은 일을 두 곳에서 하고, 무엇보다 도달할 수 없는 입력이 된다.
   그래서 닉네임은 "끝난 단계" 로 보여주고, 남은 것(지갑)만 할 일로 남긴다.

   지갑 상태를 GET /wallet 으로 다시 묻지 않는 이유
   같은 사실(linked)이 로그인·세션 복원에서 읽는 /users/me 에 이미 실려 있고
   셸이 walletLinked 로 들고 있다. 한 번 더 물으면 요청만 늘고, 두 값이 어긋날
   때 어느 쪽이 참인지 화면이 판단해야 한다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/context'
import { isOnboardingOff, setOnboardingOff } from '../auth/onboarding'
import BlockchainScene from './prediction/BlockchainScene'
import { ALL as SCENE_FRAMES } from './prediction/animationFrames'
import '../styles/screens/onboarding-tour.css'

type Props = {
  onClose: () => void
  /** 지갑 연동을 누르면 올린다. 호출부가 이 오버레이를 닫고 M-01 을 띄운다. */
  onLinkWallet: () => void
}

/* 걸음마다 무엇을 알려 줄지. 마지막 걸음에서만 할 일이 생긴다 —
   앞의 둘은 읽고 넘기는 자리다. */
const Ico = ({ children }: { children: React.ReactNode }) => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
)
const ArrowIcon = () => <Ico><path d="M5 12h13M13 6l6 6-6 6" /></Ico>
const BackIcon = () => <Ico><path d="M19 12H6M11 18l-6-6 6-6" /></Ico>

const STEPS = [
  {
    key: 'what',
    eyebrow: '내 판단을 기록해요',
    title: <>오를지, 내릴지<br /><em>내 판단을 남겨요</em></>,
    body: '종목과 목표가를 고르면 끝.',
    art: '/assets/character/black_ant/antenna-character-black-desk-plan.png',
    drift: [],
  },
  {
    /* 블록과 사슬을 함께 든 개미가 "고칠 수 없는 기록"을 글보다 먼저 설명한다. */
    key: 'proof',
    eyebrow: '나중에도 믿을 수 있게',
    title: <>쓴 뒤에는<br /><em>고칠 수 없어요</em></>,
    body: '예측은 블록체인에 저장돼요. 만기일이 되면 결과와 오차도 자동으로 계산됩니다.',
    scene: true,
    drift: [],
  },
  {
    key: 'wallet',
    eyebrow: '이제 준비 끝',
    title: <>지갑은 필요할 때<br /><em>연결해도 괜찮아요</em></>,
    body: '예측을 등록할 때 한 번 서명해요. 지갑 연동은 언제든 할 수 있고, 1분이면 끝나요.',
    art: '/assets/character/black_ant/antenna-character-black-coins-ticket.png',
    drift: ['/assets/blockchain-animation/cube/cube-anchored.webp',
            '/assets/blockchain-animation/effect/ring.webp'],
  },
  {
    key: 'sim',
    eyebrow: '한 가지 더',
    title: <>모의투자도<br /><em>따로 준비돼 있어요</em></>,
    body: '실제 과거 시세로 연습하는 모의투자도 있어요. 들어가면 별도 안내가 시작됩니다.',
    art: '/assets/pageset/ant-chart.webp',
    drift: [],
  },
] as const

export default function OnboardingTour({ onClose }: Props) {
  /* user 는 null 일 수 있는 타입이지만, 셸이 authed·nickname 을 확인한 뒤에만
     띄우므로 여기서는 이미 확정된 값이다. 그래도 타입을 우겨 넣지 않고 옵셔널로
     읽는다 — 그 조건이 바뀌면 여기서 터진다. */
  const { user } = useAuth()
  const nickname = user?.nickname ?? ''
  const walletLinked = user?.walletLinked ?? false
  const [at, setAt] = useState(0)

  /* '다시 보지 않기'. 이미 꺼 둔 사람이 '안내 다시 보기' 로 열었을 수도 있어
     지금 값을 그대로 읽어 온다 — 체크가 풀린 채로 보이면 끄기를 두 번 하게 된다. */
  const [off, setOff] = useState(isOnboardingOff)

  /* 닫는 모든 길이 여기를 지난다(건너뛰기 · X · 종목 둘러보기 · 지갑 연동).
     체크 상태를 닫는 순간에 저장한다 — 체크할 때마다 쓰면 마음을 바꿔 다시 풀고
     그냥 넘긴 사람에게도 꺼진 채로 남는다. */
  const close = useCallback(() => { setOnboardingOff(off); onClose() }, [off, onClose])
  const dialogRef = useRef<HTMLDivElement>(null)

  const step = STEPS[at]
  const last = at === STEPS.length - 1

  /* 배경 클릭으로는 닫지 않는다. 로그인 직후 딱 한 번 뜨는 안내라, 실수로
     스쳐 닫으면 다시 볼 길이 없다(라우트가 없으므로 되돌아올 수도 없다).
     닫기는 X 와 "건너뛰기" 로만 — 둘 다 닫는다는 뜻이 분명한 자리다. */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [close])

  // 오버레이가 열리면 초점을 안으로 들인다. 없으면 탭이 뒤 화면을 돌아다닌다.
  useEffect(() => { dialogRef.current?.focus() }, [])

  // 두 번째 걸음에 도착했을 때 봉인 스프라이트가 바로 움직이도록 미리 받아 둔다.
  useEffect(() => {
    SCENE_FRAMES.forEach((src) => { const image = new Image(); image.src = src })
  }, [])

  const next = useCallback(() => setAt((i) => Math.min(i + 1, STEPS.length - 1)), [])
  /* 되돌아갈 수 있게 한다. 전에는 앞으로만 갈 수 있어, 한 걸음 넘긴 뒤 다시
     읽으려면 튜토리얼을 닫는 수밖에 없었다(닫으면 다시 뜨지 않는다). */
  const back = useCallback(() => setAt((i) => Math.max(0, i - 1)), [])

  return (
    <div className="ob-backdrop">
      <div
        className="ob-modal" role="dialog" aria-modal="true" aria-labelledby="ob-title"
        tabIndex={-1} ref={dialogRef}
      >
        {/* ── 왼쪽: 글 ──────────────────────────────── */}
        <div className="ob-copy">
          <div className="ob-head">
            <p className="ob-hello">
              {nickname ? `${nickname} 님, 환영합니다` : '환영합니다'}
            </p>
            {/* 걸음 표시. 몇 걸음 남았는지 모르면 건너뛸지 읽을지 판단할 수 없다 */}
            <ol className="ob-dots" aria-label={`${STEPS.length}걸음 중 ${at + 1}번째`}>
              {STEPS.map((s, i) => (
                <li key={s.key} className={i === at ? 'is-on' : i < at ? 'is-done' : undefined} />
              ))}
            </ol>
            <b className="ob-count num">{at + 1} / {STEPS.length}</b>
          </div>

          {/* 걸음이 바뀌면 key 로 리마운트되어 등장 애니메이션이 다시 돈다.
              카드 크기는 CSS 가 고정해 두므로 글이 짧은 걸음에서도 흔들리지 않는다. */}
          <div className="ob-step ob-body" key={step.key}>
            <span className="ob-eyebrow">{step.eyebrow}</span>
            <h2 id="ob-title">{step.title}</h2>
            <p className="ob-text">{step.body}</p>

            {/* 지갑 걸음에서 지금까지 마친 것과 남은 것을 보여준다.
                닉네임은 A-02 에서 이미 끝냈으므로 할 일이 아니라 확인이다. */}
            {step.key === 'wallet' && (
              <ul className="ob-checks">
                <li className="is-done">
                  <span className="ob-mark" aria-hidden="true">✓</span>
                  <b>닉네임</b>
                  <span className="ob-sub">{nickname}</span>
                </li>
                <li className={walletLinked ? 'is-done' : undefined}>
                  <span className="ob-mark" aria-hidden="true">{walletLinked ? '✓' : '○'}</span>
                  <b>지갑 연동</b>
                  <span className="ob-sub">
                    {walletLinked ? '연동됨' : '예측 등록에 필요해요'}
                  </span>
                </li>
              </ul>
            )}
          </div>

          <div className="ob-actions">
            {/* 건너뛰기는 늘 남겨 둔다. 로그인 직후에 붙잡아 두지 않는다 */}
            {/* 왼쪽에 끄기 스위치와 건너뛰기를 같이 둔다. 둘 다 "그만 볼래" 쪽
                행동이라 한 덩어리로 묶이는 것이 읽기 쉽다. */}
            <div className="ob-left">
              <label className="ob-mute">
                <input
                  type="checkbox" checked={off}
                  onChange={(e) => setOff(e.currentTarget.checked)}
                />
                다시 보지 않기
              </label>
              <button type="button" className="ob-skip" onClick={close}>
                {last ? '나중에 하기' : '건너뛰기'}
              </button>
            </div>

            <div className="ob-nav">
              {/* 첫 걸음에서도 자리를 지킨다 — 버튼이 생겼다 사라지면 다음 버튼이
                  좌우로 움직여 연달아 누를 때 빗나간다. */}
              <button
                type="button" className="ob-btn ghost" onClick={back}
                disabled={at === 0} aria-hidden={at === 0} tabIndex={at === 0 ? -1 : undefined}
              >
                <BackIcon />
                이전
              </button>

              {last ? (
                <Link className="ob-btn solid" to="/stocks" onClick={close}>
                  인사이트 시작
                  <ArrowIcon />
                </Link>
              ) : (
                <button type="button" className="ob-btn solid" onClick={next}>
                  다음
                  <ArrowIcon />
                </button>
              )}
            </div>
          </div>
        </div>

        {/* ── 오른쪽: 무대 ──────────────────────────────
            마스코트와 장식이 떠 있다. 전부 뜻이 없는 장식이라 alt 를 비우고
            레이어째 aria-hidden 으로 덮는다 — 왼쪽 글이 이미 같은 내용을 말한다. */}
        <div className={`ob-stage s${at + 1}`} key={`art-${step.key}`}>
          <div className="ob-art-layer" aria-hidden="true">
            {step.drift.map((src, i) => (
              <img key={src} className={`ob-drift d${i + 1}`} src={src} alt="" />
            ))}
            {'scene' in step ? (
              <div className="ob-scene">
                <BlockchainScene phase="signing" anchor={null} />
              </div>
            ) : (
              <img className="ob-art" src={step.art} alt="" />
            )}
          </div>
        </div>

        {/* 닫기는 무대 위에 얹는다 — 오른쪽 위가 닫기를 찾는 자리다 */}
        <button type="button" className="ob-x" aria-label="건너뛰기" onClick={close}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               strokeWidth="2" strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
    </div>
  )
}
