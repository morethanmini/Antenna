/* ANTENA 공용 셸 — 상단 네비게이션 바 한 줄.

   **사이드바(레일)를 걷어냈다(2026-09-17).** 왼쪽에 늘 떠 있던 레일이 본문을 56px
   밀고 있었고, 펼침/접힘 때문에 상단바·본문·가운데 정렬 보정이 서로를 따라다녀야 했다
   (:has() 규칙 셋 + 1712px·1944px 보정 둘). 메뉴가 넷뿐이라 한 줄에 들어가므로 그
   장치가 전부 필요 없어졌다.

   **홈 메뉴를 두지 않는다 — 로고가 홈이다.** 둘 다 두면 같은 곳으로 가는 길이 나란히
   두 개다. 로고는 **모드별 홈**으로 간다(인사이트 `/` · 모의투자 `/sim`) — 전에는 `/`
   로 박혀 있어 모의투자에서 누르면 모드 밖으로 튕겨 나갔다.

   곁창 둘(검색줄 · 계정 메뉴)의 닫기 규칙은 components/useDismiss.ts 에 모았다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from './auth/context'
import { onOnboardingOpen, takeOnboardingPending } from './auth/onboarding'
import BriefingModal from './components/BriefingModal'
import ConfirmDialog from './components/ConfirmDialog'
import NotificationBell from './components/NotificationBell'
import OnboardingTour from './components/OnboardingTour'
import SimOnboardingTour from './components/SimOnboardingTour'
import WalletLinkModal from './components/wallet/WalletLinkModal'
import { useBriefingParam } from './components/briefingParam'
import { useDismiss } from './components/useDismiss'
import { onSimOnboardingOpen, simOnboardingSeen } from './sim/onboardingSeen'
import { useApiQuery } from './api/useApiQuery'
import { formatToken, type WalletBalance } from './api/wallet'

export type Mode = 'insight' | 'sim'

/* 모드마다 홈과 메뉴가 다르다. **홈은 nav 에 넣지 않는다** — 로고가 그 자리다. */
const MODES = {
  insight: {
    label: '인사이트',
    home: '/',
    nav: [
      { key: 'stocks', text: '종목 탐색', href: '/stocks' },
      { key: 'market', text: '주가 예측 랭킹', href: '/rankings' },
      { key: 'report', text: '리포트', href: '/reports' },
      /* 원장은 개인 기록이 아니라 **서비스 전체**의 기록이다(GET /anchors 에 사용자
         필터가 없다). 랭킹이 "누가 잘하나", 원장이 "그 기록이 조작은 아닌가" 다. */
      { key: 'ledger', text: '원장 내역', href: '/ledger' },
      /* 커뮤니티(F-04~F-06)는 개발 범위에서 뺐다(2026-09-14).
         예측 포트폴리오(C-04)는 마이페이지의 '내 활동' 에 있다 — 이 줄은 서비스를
         둘러보는 길이고, 포트폴리오는 계정 안쪽 화면이라 거기가 맞다.
         routes.ts 의 nav:'portfolio' 는 그대로 둔다 — 짝이 없으면 아무것도 활성이
         아니고, 계정 안쪽 화면에서는 그게 맞다(/me 가 nav:'me' 로 같은 상태다). */
    ],
  },
  sim: {
    label: '모의 투자',
    home: '/sim',
    nav: [
      { key: 'play', text: '투자하기', href: '/sim/modes' },
      { key: 'portfolio', text: '투자 포트폴리오', href: '/sim/history' },
      /* '모의투자 랭킹' 을 내렸다(2026-09-16). 한 시즌 안의 순위는 시즌 리더보드(G-07)가
         이미 보여 준다. 둘을 같이 두면 "내 등수" 가 두 곳에서 다른 값으로 나온다. */
    ],
  },
} as const

/* 네비바 잔액.
   조건부로 그려지는 자리라 훅을 바깥에 둘 수 없어 작은 컴포넌트로 감쌌다 —
   로그인했을 때만 마운트되고, 그때 한 번만 부른다(셸은 라우트가 바뀌어도 살아 있다).

   홈 카드(B-01)와 **같은 함수**를 쓴다. 전에는 여기가 0 을, 홈이 목업 값을 띄워 같은
   화면에서 두 숫자가 어긋났다.

   못 받았을 때도 — 다. 0 은 "잔액이 없다" 는 주장이라, 못 불러온 것과 구분돼야 한다.

   눌러서 H-01 지갑·토큰으로 간다 — 잔액을 본 사람이 다음에 궁금한 것은 "어디서 늘고
   줄었나" 이고, 그 답이 그 화면의 원장이다. 못 불러왔을 때(—)도 링크는 살려 둔다. */
function WalletChip() {
  const { data } = useApiQuery<WalletBalance>('/wallet/balance')

  return (
    <Link className="wallet" to="/me/wallet" aria-label="보유 ANT · 지갑으로">
      <i />
      <span className="num">{data ? formatToken(data.balance) : '—'}</span> ANT
    </Link>
  )
}

/* 네비바 아래로 내려오는 검색줄.

   **아이콘 하나로 접어 뒀다.** 전에는 상단바 한가운데를 검색창이 차지했는데, 이
   서비스에서 검색은 첫 동작이 아니다 — 종목은 종목 탐색(B-04)의 필터가 받고, 여기서만
   되는 것은 채널·리포트까지 한 번에 훑는 것이다. 그만큼의 자리를 늘 내줄 일은 아니다.

   입력과 이동까지만 맡는다. 결과는 A-03(`/search?q=`)이 그린다 — 자동완성 드롭다운도
   그 스토리 몫이라 셸에서 종목 데이터를 들고 있지 않는다. */
function NavSearch({ open, onClose, triggerRef }: {
  open: boolean
  onClose: (returnFocus?: boolean) => void
  triggerRef: React.RefObject<HTMLButtonElement | null>
}) {
  const navigate = useNavigate()
  const boxRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState('')

  useDismiss({ open, panel: boxRef, trigger: triggerRef, onClose })

  /* 열면 곧바로 칠 수 있어야 한다 — 열어 놓고 한 번 더 눌러야 하면 아이콘으로 접은
     값이 사라진다 */
  useEffect(() => { if (open) inputRef.current?.focus() }, [open])

  if (!open) return null

  return (
    <div className="navsearch" ref={boxRef}>
      <form
        className="navsearch-form" role="search"
        onSubmit={(e) => {
          e.preventDefault()
          const q = query.trim()
          if (!q) return
          onClose()
          navigate(`/search?q=${encodeURIComponent(q)}`)
        }}
      >
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
        </svg>
        <input
          ref={inputRef}
          type="search" id="navbar-search" autoComplete="off"
          aria-label="종목 · 예측가 · 리포트 검색"
          placeholder="종목명 · 종목코드 · 예측가 · 리포트 제목 (예: 삼성전자, 005930)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </form>
    </div>
  )
}

export default function Layout({ mode, nav, bodyClass, children }: { mode: Mode; nav: string; bodyClass: string; children: React.ReactNode }) {
  const { authed, user, signOut } = useAuth()
  const navigate = useNavigate()
  const [confirmingSignOut, setConfirmingSignOut] = useState(false)

  /* 곁창 둘. 하나를 열면 다른 하나는 닫는다 — 네비바 아래 같은 자리에 겹쳐 뜬다 */
  const [searchOpen, setSearchOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const searchBtn = useRef<HTMLButtonElement>(null)
  const menuBtn = useRef<HTMLButtonElement>(null)
  const menuBox = useRef<HTMLDivElement>(null)

  const closeSearch = useCallback((returnFocus?: boolean) => {
    setSearchOpen(false)
    if (returnFocus) searchBtn.current?.focus()
  }, [])
  const closeMenu = useCallback((returnFocus?: boolean) => {
    setMenuOpen(false)
    if (returnFocus) menuBtn.current?.focus()
  }, [])
  useDismiss({ open: menuOpen, panel: menuBox, trigger: menuBtn, onClose: closeMenu })

  /* M-09 온보딩 튜토리얼. **홈이 아니라 셸에 둔다** — 비로그인 딥링크로 막혔던
     회원은 가입 뒤 홈이 아니라 그 경로로 착지한다(returnTo). 홈에만 걸면 그
     사람은 튜토리얼을 아예 못 본다.

     로그인(A-01)과 닉네임(A-02)에는 셸이 없어서(routes.ts 의 mode 가 없다)
     저절로 제외된다. 그래도 authed · nickname 을 확인한다 — 셸은 가드가
     리다이렉트하는 동안에도 한 번 그려지고, 그때 띄우면 빈 화면 위에 뜬다.

     ref 로 잠그는 이유: 이 이펙트는 authed · nickname 에 반응하는데, 한 번
     꺼낸 뒤 그 값이 바뀌어 다시 돌면 빈 플래그를 읽어 **열려 있는 튜토리얼을
     닫아 버린다**(H-03 에서 닉네임을 고치면 실제로 그렇게 된다). */
  const [tour, setTour] = useState(false)
  const tourChecked = useRef(false)
  useEffect(() => {
    if (tourChecked.current || !authed || !user?.nickname) return
    tourChecked.current = true
    setTour(takeOnboardingPending())
  }, [authed, user?.nickname])

  /* H-03 환경 설정의 "안내 다시 보기". 띄우는 곳이 셸이라 화면은 신호만 올린다
     (auth/onboarding.ts 의 openOnboardingTour). G-00 과 같은 방식이다. */
  useEffect(() => onOnboardingOpen(() => setTour(true)), [])

  /* G-00 모의투자 온보딩. M-09 와 같은 자리·같은 규칙이다(셸 위 오버레이, 라우트 없음).
     다른 점은 진입 조건뿐 — 가입 직후가 아니라 **모의투자 모드로 처음 들어왔을 때**다.

     M-09 와 마찬가지로 ref 로 잠근다. 한 번 판단한 뒤 값이 바뀌어 다시 돌면 이미
     "봤다" 가 된 플래그를 읽어 **열려 있는 안내를 닫아 버린다**. */
  const [simTour, setSimTour] = useState(false)
  const simChecked = useRef(false)
  useEffect(() => {
    if (simChecked.current || mode !== 'sim' || !authed || !user?.nickname) return
    simChecked.current = true
    setSimTour(!simOnboardingSeen())
  }, [mode, authed, user?.nickname])

  /* G-01 홈의 "안내 다시 보기". 오버레이를 띄우는 곳이 셸이라 화면 안쪽에서는
     신호만 올려 보낸다(sim/onboardingSeen.ts 의 openSimOnboarding). */
  useEffect(() => onSimOnboardingOpen(() => setSimTour(true)), [])

  /* M-01 지갑 연동. **튜토리얼 안에서 열지 않는다** — 모달 3겹이 되기 때문이고,
     WalletLinkModal 머리말이 "호출부가 온보딩을 닫고 띄운다" 로 계약을 적어 두었다. */
  const [linking, setLinking] = useState(false)

  /* M-10 브리핑 상세. 여는 쪽(B-01 홈 띠 · B-03 브리핑 카드)은 쿼리만 세우고,
     그리는 것은 셸이 맡는다 — M-09 오버레이를 셸로 올린 것과 같은 이유다.
     띠가 비어 있으면 그 컴포넌트는 null 을 돌려주는데, 모달을 그 안에 두면
     ?briefing=12 로 들어온 딥링크가 아무것도 못 연다. */
  const briefing = useBriefingParam()

  useEffect(() => { document.body.className = bodyClass }, [bodyClass])

  const modeConfig = MODES[mode] ?? MODES.insight

  return (
    <>
      <header className="navbar">
        {/* 로고가 홈이다. 모드별 홈으로 간다 — 모의투자에서 누르면 모의투자 홈이다 */}
        <Link className="brand" to={modeConfig.home}>
          <img className="brand-logo" src="/assets/logo/antenna-wordmark.png" alt="ANTENNA 홈" />
        </Link>

        <nav className="modeswitch" aria-label="서비스 선택">
          {(['insight', 'sim'] as const).map((k) => (
            <Link key={k} to={MODES[k].home} className={k === mode ? 'on' : ''}>{MODES[k].label}</Link>
          ))}
        </nav>

        <nav className="navmenu" aria-label="주요 메뉴">
          {modeConfig.nav.map((n) => (
            <Link key={n.key} to={n.href} className={n.key === nav ? 'on' : ''}>{n.text}</Link>
          ))}
        </nav>

        <div className="navbar-right">
          {authed ? (
            <>
              <WalletChip />

              <button
                ref={searchBtn}
                className="iconbtn" type="button"
                aria-label="검색" aria-expanded={searchOpen} aria-controls="navbar-search"
                onClick={() => { setMenuOpen(false); setSearchOpen((v) => !v) }}
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round">
                  <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
                </svg>
              </button>

              {/* 알림은 페이지가 아니라 이 자리에서 여는 팝업이다(H-02).
                  data-bell 은 팝업이 "바깥을 눌렀나" 를 가릴 때 이 버튼을 알아보는
                  표시다 — 없으면 종을 누를 때 팝업이 닫히고 곧바로 다시 열린다. */}
              <NotificationBell />

              <Link className="iconbtn" to="/settings" aria-label="설정">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="3" />
                  <path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1v.2a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-2.7-1.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7H3.4a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.7 6.3l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 2.7-1.1V2a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.2l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7h.2a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1" />
                </svg>
              </Link>

              {/* 프로필 사진 = 계정 메뉴. 마이페이지·지갑·로그아웃 셋이 여기 모인다 —
                  전에는 사이드바 바닥(마이페이지)과 상단바 아이콘(로그아웃)으로 갈려 있었다 */}
              <div className="accountmenu">
                <button
                  ref={menuBtn}
                  className="avatarbtn" type="button"
                  aria-label="계정 메뉴" aria-haspopup="menu" aria-expanded={menuOpen}
                  onClick={() => { setSearchOpen(false); setMenuOpen((v) => !v) }}
                >
                  <img src={user?.avatarUrl} alt="" />
                </button>

                {menuOpen && (
                  <div className="accountmenu-pop" ref={menuBox} role="menu" aria-label="계정">
                    <Link role="menuitem" to="/me" onClick={() => closeMenu()}>마이페이지</Link>
                    <Link role="menuitem" to="/me/wallet" onClick={() => closeMenu()}>내 지갑</Link>
                    {/* 바로 나가지 않고 한 번 더 묻는다 — 되돌리려면 OAuth 를 다시 타야 한다.
                        refresh 는 httpOnly 쿠키라 브라우저가 직접 못 지우고, 서버에 폐기를
                        요청하는 이 버튼이 유일한 정리 수단이다. */}
                    <button
                      role="menuitem" type="button" className="is-danger"
                      onClick={() => { closeMenu(); setConfirmingSignOut(true) }}
                    >
                      로그아웃
                    </button>
                  </div>
                )}
              </div>
            </>
          ) : (
            <nav className="navbar-auth" aria-label="계정">
              <Link className="navbar-login" to="/login">로그인</Link>
              <Link className="navbar-signup" to="/login">회원가입</Link>
            </nav>
          )}
        </div>
      </header>

      <NavSearch open={searchOpen} onClose={closeSearch} triggerRef={searchBtn} />

      {children}

      {/* 화면 내용 뒤에 둔다. 오버레이라 위치는 CSS(z-index)가 정하지만,
          읽는 순서에서도 본문 다음에 오는 게 맞다 */}
      {tour && (
        <OnboardingTour
          onClose={() => setTour(false)}
          onLinkWallet={() => { setTour(false); setLinking(true) }}
        />
      )}

      {/* M-09 가 떠 있는 동안에는 기다린다. 가입 직후 모의투자로 바로 들어온 회원에게
          두 안내가 겹쳐 뜨면 어느 쪽을 읽는 것인지 알 수 없다. */}
      {simTour && !tour && (
        <SimOnboardingTour
          onClose={() => setSimTour(false)}
          onStart={() => { setSimTour(false); navigate('/sim/modes') }}
        />
      )}

      {linking && <WalletLinkModal onClose={() => setLinking(false)} />}

      {briefing.id !== null && (
        <BriefingModal id={briefing.id} onClose={briefing.close} />
      )}

      {confirmingSignOut && (
        <ConfirmDialog
          title="정말 로그아웃할까요?"
          description="이 브라우저의 세션이 끝납니다. 다시 이용하려면 로그인 화면에서 한 번 더 로그인해야 합니다."
          confirmLabel="로그아웃"
          tone="danger"
          onCancel={() => setConfirmingSignOut(false)}
          onConfirm={() => { signOut(); navigate('/login', { replace: true }) }}
        />
      )}
    </>
  )
}
