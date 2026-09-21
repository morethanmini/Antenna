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
import { formatToken } from './api/wallet'
import { useWalletBalance } from './api/walletBalance'

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
      /* 원장 내역이 있던 자리다(2026-09-21). 원장은 서비스 전체의 기록이라 둘러보는
         줄에 어울렸지만, 매일 들어와 보는 것은 내가 건 예측이 어디까지 왔나다.
         원장으로 가는 길은 없애지 않고 상단바 오른쪽 아이콘으로 옮겼다. */
      { key: 'portfolio', text: '내 예측', href: '/me/predictions' },
      /* 커뮤니티(F-04~F-06)는 개발 범위에서 뺐다(2026-09-14).

         이 줄의 nav 키가 'portfolio' 라 예측 상세(C-03)와 예측 포트폴리오(C-04)에서도
         이 메뉴가 켜진다 — routes.ts 가 셋에 같은 키를 달아 두었고, 셋 다 "내가 건
         예측" 갈래라 그대로 둔다. */
    ],
  },
  sim: {
    label: '모의 투자',
    home: '/sim',
    nav: [
      /* 연습 허브로 바로 간다(2026-09-21). 모드 선택(/sim/modes)은 고를 것이
         하나뿐이 되어 걷어냈다 — 그 라우트는 이제 여기로 넘겨보내기만 한다. */
      { key: 'play', text: '투자하기', href: '/sim/practice' },
      { key: 'portfolio', text: '투자 포트폴리오', href: '/sim/history' },
      /* '모의투자 랭킹' 을 내렸다(2026-09-16). 한 시즌 안의 순위는 시즌 리더보드(G-07)가
         이미 보여 준다. 둘을 같이 두면 "내 등수" 가 두 곳에서 다른 값으로 나온다. */
    ],
  },
} as const

/* 네비바 잔액.
   조건부로 그려지는 자리라 훅을 바깥에 둘 수 없어 작은 컴포넌트로 감쌌다 —
   로그인했을 때만 마운트된다.

   **공유 잔액 저장소를 읽는다**(2026-09-20, QA 09-18 "상단 보유잔액과 지갑 잔액의 싱크가
   맞지 않음"). 전에는 여기서 직접 한 번 읽었는데, 셸은 라우트가 바뀌어도 다시 마운트되지
   않아서 그 값이 새로고침 전까지 그대로였다 — 구독하고 홈에 가면 카드는 5,000, 여기는
   10,000 이었다. 이제 홈 카드 · 지갑 화면 · 결제 창과 같은 값을 같은 순간에 본다. 언제 다시
   읽는지는 api/walletBalance.ts 머리말.

   못 받았을 때도 — 다. 0 은 "잔액이 없다" 는 주장이라, 못 불러온 것과 구분돼야 한다.

   눌러서 H-01 지갑·토큰으로 간다 — 잔액을 본 사람이 다음에 궁금한 것은 "어디서 늘고
   줄었나" 이고, 그 답이 그 화면의 원장이다. 못 불러왔을 때(—)도 링크는 살려 둔다. */
function WalletChip() {
  const { data } = useWalletBalance()

  return (
    <Link className="wallet" to="/me/wallet" aria-label="보유 ANT · 지갑으로">
      <span className="num">{data ? formatToken(data.balance) : '—'}</span> ANT
    </Link>
  )
}

/* 통합 검색(A-03)으로 내려오던 검색줄이 여기 있었다 — 2026-09-21 에 걷었다.

   상단바 오른쪽 아이콘 자리를 원장 내역이 가져가면서 검색 버튼이 사라졌고, 버튼이
   없으니 이 줄을 열 길도 없어졌다. 셸에서 /search 로 가는 문은 이제 없다.

   되살릴 때는 git 역사에서 이 블록(NavSearch · SEARCH_MAX)과 상단바의 버튼을 함께
   꺼내면 된다. 화면(A-03)과 API(GET /search)는 그대로 살아 있다. */

export default function Layout({ mode, nav, bodyClass, children }: { mode: Mode; nav: string; bodyClass: string; children: React.ReactNode }) {
  const { authed, user, signOut } = useAuth()
  const navigate = useNavigate()
  const [confirmingSignOut, setConfirmingSignOut] = useState(false)

  /* 곁창 둘. 하나를 열면 나머지는 닫는다 — 네비바 아래 같은 자리에 겹쳐 뜬다.
     검색줄이 셋째였는데 2026-09-21 에 걷었다(위 주석). */
  const [menuOpen, setMenuOpen] = useState(false)
  /* 좁은 화면에서 메뉴 줄(.navmenu)이 햄버거로 접힐 때 그 안을 여는 곁창이다.
     넓을 때는 CSS 가 버튼을 숨기므로 이 상태는 쓰이지 않는다. */
  const [navOpen, setNavOpen] = useState(false)
  const menuBtn = useRef<HTMLButtonElement>(null)
  const menuBox = useRef<HTMLDivElement>(null)
  const navBtn = useRef<HTMLButtonElement>(null)
  const navBox = useRef<HTMLDivElement>(null)

  const closeMenu = useCallback((returnFocus?: boolean) => {
    setMenuOpen(false)
    if (returnFocus) menuBtn.current?.focus()
  }, [])
  const closeNav = useCallback((returnFocus?: boolean) => {
    setNavOpen(false)
    if (returnFocus) navBtn.current?.focus()
  }, [])
  useDismiss({ open: menuOpen, panel: menuBox, trigger: menuBtn, onClose: closeMenu })
  useDismiss({ open: navOpen, panel: navBox, trigger: navBtn, onClose: closeNav })

  /* M-09 온보딩 튜토리얼. **홈이 아니라 셸에 둔다** — 비로그인 딥링크로 막혔던
     회원도 가입 절차가 끝나면 이 셸 안으로 들어온다. 홈 화면에만 걸어 두면 처음
     닿는 곳이 홈이 아닐 때 튜토리얼을 아예 못 본다.

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
        {/* 로고가 홈이다. 모드별 홈으로 간다 — 모의투자에서 누르면 모의투자 홈이다.

            좁아지면 **워드마크를 마크 한 장으로 갈아 끼운다.** 전에는 워드마크를
            CSS 로 잘라 왼쪽 22% 만 남겼는데(.brand 에 overflow:hidden), 잘린 자리는
            디자인이 정한 여백이 아니라 우연히 걸린 곳이라 마크가 한쪽으로 치우쳤다.
            같은 폴더에 마크 파일이 따로 있으므로 그것을 쓴다.

            둘을 겹쳐 두고 CSS 로 감추지 않고 <picture> 로 고르는 이유 — 겹쳐 두면
            안 보이는 쪽까지 내려받는다. media 로 고르면 브라우저가 한 장만 받는다.
            alt 는 <img> 가 들고 있으므로 어느 쪽이 뽑히든 같다.

            워드마크는 배경을 지운 3D 버전으로 바꿨다(2026-09-18). 전 파일은 흰 배경이
            깔린 RGB 라 유리 상단바 위에서 흰 판이 떠 보였다. 새 파일은 투명 여백까지
            잘라내고 720px 로 줄여 둬서, 상단바가 쓰는 폭(최대 170px)의 4배를 넘는다 —
            고해상도에서도 또렷하면서 0.5MB → 74KB 로 줄었다. */}
        <Link className="brand" to={modeConfig.home}>
          <picture>
            <source media="(max-width:600px)" srcSet="/assets/logo/antenna-mark-light.png" />
            <img className="brand-logo" src="/assets/logo/antenna-wordmark-3d.png" alt="ANTENNA 홈" />
          </picture>
        </Link>

        {/* data-on 이 알의 자리를 정한다. 켜진 링크에 배경을 주지 않고 알 하나를
            옮기는 구조라, 어느 칸이 켜졌는지를 부모가 알아야 한다. */}
        <nav className="modeswitch" aria-label="서비스 선택" data-on={mode}>
          {/* 미끄러지는 알. 읽어 줄 것이 없는 장식이라 스크린리더에서 숨긴다 —
              어느 쪽이 켜졌는지는 링크의 aria-current 가 이미 말한다. */}
          <span className="modeswitch-thumb" aria-hidden="true" />
          {(['insight', 'sim'] as const).map((k) => (
            /* aria-current 가 **유일한** 상태 표시다 — 켜진 칸을 칠하던 배경이
               장식용 알로 옮겨 가 스크린리더가 읽을 것이 없어졌다 */
            <Link
              key={k}
              to={MODES[k].home}
              className={k === mode ? 'on' : ''}
              aria-current={k === mode ? 'page' : undefined}
            >
              {MODES[k].label}
            </Link>
          ))}
        </nav>

        <nav className="navmenu" aria-label="주요 메뉴">
          {modeConfig.nav.map((n) => (
            <Link key={n.key} to={n.href} className={n.key === nav ? 'on' : ''}>{n.text}</Link>
          ))}
        </nav>

        <div className="navbar-right">
          {/* 접힌 메뉴 줄. **자리가 모자랄 때만 CSS 가 꺼낸다**(.navburger 는 기본이
              display:none 이다) — 넓은 화면에서는 위의 .navmenu 가 그대로 한 줄로 선다.
              로그인 여부 바깥에 둔다: 메뉴는 계정이 아니라 서비스를 둘러보는 길이다. */}
          <div className="navburger">
            <button
              ref={navBtn}
              className="iconbtn" type="button"
              aria-label="메뉴" aria-haspopup="menu" aria-expanded={navOpen}
              onClick={() => { setMenuOpen(false); setNavOpen((v) => !v) }}
            >
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M4 7h16M4 12h16M4 17h16" />
              </svg>
            </button>

            {navOpen && (
              <div className="navburger-pop" ref={navBox} role="menu" aria-label="주요 메뉴">
                {modeConfig.nav.map((n) => (
                  <Link
                    key={n.key} role="menuitem" to={n.href}
                    className={n.key === nav ? 'on' : ''}
                    aria-current={n.key === nav ? 'page' : undefined}
                    onClick={() => closeNav()}
                  >
                    {n.text}
                  </Link>
                ))}
              </div>
            )}
          </div>

          {authed ? (
            <>
              <WalletChip />

              {/* 검색 아이콘이 있던 자리다(2026-09-21). 원장 내역이 메뉴에서 내려오면서
                  이리로 왔다 — 원장은 하루에 여러 번 여는 곳은 아니지만, 이 서비스가
                  "기록이 조작되지 않았음" 을 파는 이상 길이 끊기면 안 된다.

                  ⚠ 통합 검색(A-03)으로 가는 길이 이 버튼 하나뿐이었다. 지금은 주소를
                  직접 치는 것 말고는 닿을 데가 없다 — 검색을 되살릴 자리를 정할 때
                  이 주석을 같이 본다. */}
              {/* 자물쇠. 장부(줄 그은 노트)와 사슬도 그려 봤는데, 원장(D-01)이 말하는
                  것은 "기록을 적어 둔 책" 이 아니라 **봉인** 이다 — 한 번 올라가면 못
                  바꾼다는 것. 예측 등록 발판 위에서도 같은 말을 쓴다("예측이 봉인됩니다"). */}
              <Link className="iconbtn" to="/ledger" aria-label="확정 기록">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="4.6" y="10.4" width="14.8" height="9.4" rx="2.4" />
                  <path d="M8.3 10.4V7.7a3.7 3.7 0 0 1 7.4 0v2.7" />
                </svg>
              </Link>

              {/* 알림은 페이지가 아니라 이 자리에서 여는 팝업이다(H-02).
                  data-bell 은 팝업이 "바깥을 눌렀나" 를 가릴 때 이 버튼을 알아보는
                  표시다 — 없으면 종을 누를 때 팝업이 닫히고 곧바로 다시 열린다. */}
              <NotificationBell />

              {/* 600px 아래에서는 이 자리를 비우고 계정 메뉴 안으로 내려간다
                  (.nav-gear · .accountmenu-pop .only-narrow) — 햄버거가 아이콘 한
                  칸을 더 쓰면서 360px 에 1px 밖에 안 남았다. 잔액 칩이 먼저 같은
                  길로 내려갔다: 좁아지면 상단바에 남길 것과 계정 메뉴로 접을 것을
                  가르고, 설정은 하루에 한 번 열 자리가 아니다. */}
              <Link className="iconbtn nav-gear" to="/settings" aria-label="설정">
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
                  onClick={() => { setNavOpen(false); setMenuOpen((v) => !v) }}
                >
                  <img src={user?.avatarUrl} alt="" />
                </button>

                {menuOpen && (
                  <div className="accountmenu-pop" ref={menuBox} role="menu" aria-label="계정">
                    <Link role="menuitem" to="/me" onClick={() => closeMenu()}>마이페이지</Link>
                    <Link role="menuitem" to="/me/wallet" onClick={() => closeMenu()}>내 지갑</Link>
                    {/* 넓을 때는 톱니 아이콘이 바에 있으므로 여기서는 숨는다 —
                        같은 길이 두 곳에 보이면 어느 쪽을 눌러야 하는지가 생긴다 */}
                    <Link role="menuitem" className="only-narrow" to="/settings" onClick={() => closeMenu()}>환경 설정</Link>
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
            /* **지금은 그려질 일이 없다.** 셸이 붙는 라우트는 전부 access:'user' 이고,
               비로그인은 RequireAccess 가 셸을 그리기 전에 /login 으로 보낸다. 공개
               라우트는 /login 과 OAuth 복귀 둘뿐인데 그 둘은 mode 가 없어 셸이 없다
               (설계서 §1 "공개 화면은 A-01 하나"). 그래도 지우지 않는 것은, 서비스
               소개 같은 공개 화면이 생기면 그날 바로 필요한 자리여서다 — 2026-09-17
               에 강제로 그려 레이아웃이 멀쩡한 것까지 확인해 뒀다. */
            <nav className="navbar-auth" aria-label="계정">
              <Link className="navbar-login" to="/login">로그인</Link>
              <Link className="navbar-signup" to="/login">회원가입</Link>
            </nav>
          )}
        </div>
      </header>


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
          onStart={() => { setSimTour(false); navigate('/sim/practice') }}
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
