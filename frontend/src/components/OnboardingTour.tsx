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
/* 나침반 — "어디로 가면 되는지" 줄 앞에 선다 */
const CompassIcon = () => (
  <Ico><circle cx="12" cy="12" r="9" /><path d="m15.5 8.5-2 5-5 2 2-5z" /></Ico>
)

const STEPS = [
  {
    key: 'what',
    eyebrow: '예측을 남기는 곳',
    title: <>예측을 <em>기록</em>으로 남깁니다</>,
    body: '종목의 방향과 목표가를 정해 예측을 등록하면, 그 순간의 판단이 그대로 잠깁니다. 나중에 고칠 수 없습니다.',
    art: '/assets/character/white_ant/antenna-character-transparent.png',
    drift: ['/assets/blockchain-animation/cube/cube-draft.webp',
            '/assets/blockchain-animation/effect/spark-small.webp'],
  },
  {
    /* 그림이 이 걸음의 설명을 대신한다 — 개미가 블록을 받침대에 올리는 장면이
       곧 "커밋을 체인에 올린다" 다. */
    key: 'proof',
    eyebrow: '고칠 수 없다는 증거',
    title: <>고치지 않았다는 걸 <em>증명</em>합니다</>,
    body: '등록한 예측은 커밋 해시로 봉인되고 블록체인에 올라갑니다. 만기가 지나면 결과와 오차가 자동으로 판정됩니다.',
    /* 이 걸음만 정지컷이 아니라 **실제 봉인 장면**이다. C-01 등록 진행 모달이
       쓰는 BlockchainScene 을 그대로 가져온다 — 개미들이 블록을 안고 나와
       받침대에 하나씩 놓고 빈손으로 나가고, 받침대 위 사슬이 그만큼 자란다.

       phase 를 'signing' 으로 고정하는 것이 핵심이다. 그 값이면 **내 개미는
       건물에서 나오지 않고 동료들만 도는** 상태라, 끝나지 않고 계속 반복된다.
       'settled' 로 두면 내 개미가 블록을 놓고 이야기가 끝나 화면이 멎는다 —
       온보딩은 읽는 동안 계속 살아 있어야 한다.

       anchor 는 null 이다. 여기서는 서버에 물어본 앵커가 없고, 그 값이 없으면
       장면이 확정 연출로 넘어가지 않는다.

       정지컷을 쓰지 않는 이유: 이 장면이 우리가 말하는 "봉인" 그 자체다.
       개미가 블록을 놓는 것을 보여 주면 글을 읽지 않아도 뜻이 전해진다. */
    scene: true,
  },
  {
    /* 모의투자는 셸이 모드로 가르는 제품의 절반이다(Layout 의 modeswitch).
       예측만 안내하고 끝내면 새 회원이 절반을 못 본 채 나간다.

       문구는 G-02 모드 선택 카드에 적힌 값만 옮겼다 — 연습은 "원하는 때 직접
       진행", 대회는 "1시간마다 자동 진행 · 랭킹 반영"이다. 없는 기능을
       약속하지 않으려고 여기서 새 표현을 만들지 않았다.

       이 걸음만 검정 개미다. 가리키는 곳의 캐릭터가 그쪽이라, 흰 개미를 세우면
       안내와 도착지가 어긋난다. */
    key: 'sim',
    eyebrow: '부담 없이 연습하기',
    title: <>과거 시세로 먼저 <em>연습</em>할 수 있습니다</>,
    body: '모의투자는 지난 장을 되돌려 매매해 보는 곳입니다. 연습은 원하는 때 직접 진행하고, 대회는 1시간마다 자동으로 넘어가며 랭킹에 반영됩니다.',
    art: '/assets/pageset/ant-chart.webp',
    drift: ['/assets/pageset/card-practice.webp', '/assets/pageset/arrow.webp'],
    /* 자리를 방향으로 못 박지 않는다. 전에는 왼쪽 사이드바였고 지금은 위쪽
       네비바라(2026-09-17) "왼쪽 위" 라고 적었으면 셸을 바꿀 때마다 틀린 안내가
       된다. 메뉴 안에 있다는 것까지만 말하면 자리가 옮겨져도 계속 맞는다. */
    where: '메뉴의 "모의 투자"에서 언제든 시작할 수 있습니다.',
  },
  {
    key: 'wallet',
    eyebrow: '마지막 한 걸음',
    title: <>지갑을 <em>연동</em>해 주세요</>,
    body: '예측을 등록하려면 지갑 서명이 필요합니다. 연동은 지금 해도 되고, 첫 예측을 등록할 때 해도 됩니다.',
    art: '/assets/blockchain-animation/mascot/cheer-3.webp',
    drift: ['/assets/blockchain-animation/cube/cube-anchored.webp',
            '/assets/blockchain-animation/effect/ring.webp'],
  },
] as const

export default function OnboardingTour({ onClose, onLinkWallet }: Props) {
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

  /* 2걸음 봉인 장면의 스프라이트를 **열리자마자** 받아 둔다.
     BlockchainScene 도 자기 몫을 마운트 때 받지만, 그 시점은 이미 2걸음에
     도착한 뒤다 — 40여 장이 오는 동안 개미 그림이 비어 있는데 위치 애니메이션은
     그대로 돌아, 보이지 않는 무언가가 미끄러져 가는 것처럼 보였다.
     1걸음을 읽는 몇 초가 그걸 받아 둘 시간이다. */
  useEffect(() => {
    SCENE_FRAMES.forEach((src) => { const im = new Image(); im.src = src })
  }, [])

  // 오버레이가 열리면 초점을 안으로 들인다. 없으면 탭이 뒤 화면을 돌아다닌다.
  useEffect(() => { dialogRef.current?.focus() }, [])

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

            {/* 어디로 가면 되는지. 여기서 링크로 보내면 튜토리얼이 중간에 끊겨
                지갑 안내를 못 보고 나간다 — 그래서 자리만 알려 주고 걸음은
                그대로 잇는다(라우트가 없어 되돌아올 수도 없다). */}
            {'where' in step && (
              <p className="ob-where">
                <CompassIcon />
                {step.where}
              </p>
            )}

            {/* 마지막 걸음에서만 지금까지 마친 것과 남은 것을 보여준다.
                닉네임은 A-02 에서 이미 끝냈으므로 할 일이 아니라 확인이다. */}
            {last && (
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
                    {walletLinked ? '연동됨' : '예측 등록에 필요합니다'}
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
                walletLinked ? (
                  /* 연동까지 끝난 회원은 바로 할 일로 보낸다 */
                  <Link className="ob-btn solid" to="/stocks" onClick={close}>
                    종목 둘러보기
                    <ArrowIcon />
                  </Link>
                ) : (
                  /* M-01 을 여기서 열지 않는다 — 호출부가 이걸 닫고 띄운다 */
                  <button
                    type="button" className="ob-btn solid"
                    onClick={() => { setOnboardingOff(off); onLinkWallet() }}
                  >
                    지갑 연동하기
                    <ArrowIcon />
                  </button>
                )
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
            {'scene' in step ? (
              <div className="ob-scene">
                <BlockchainScene phase="signing" anchor={null} />
              </div>
            ) : (
              <>
                {step.drift.map((src, i) => (
                  <img key={src} className={`ob-drift d${i + 1}`} src={src} alt="" />
                ))}
                <img className="ob-art" src={step.art} alt="" />
              </>
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
