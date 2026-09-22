/* 라우트 표 — docs/화면설계서.md §3 의 화면 목록을 그대로 옮긴다.
   모달(M-*)은 라우트를 갖지 않고, A-04 오류·빈 상태도 라우트가 없다.

   화면 구현은 각 담당 스토리의 몫이라 여기서는 자리(Placeholder)만 잡는다.
   story 는 그 자리를 채울 Jira 스토리의 슬러그다. */
import type { ComponentType } from 'react'
import type { Mode } from './Layout'
import Login from './pages/Login'
import OAuthCallback from './pages/OAuthCallback'
import OnboardingFee from './pages/OnboardingFee'
import OnboardingNickname from './pages/OnboardingNickname'
import Search from './pages/Search'
import Home from './pages/Home'
import Stocks from './pages/Stocks'
import StockDetail from './pages/StockDetail'
import Watchlist from './pages/Watchlist'
import PredictNew from './pages/PredictNew'
import PredictList from './pages/PredictList'
import PredictDetail from './pages/PredictDetail'
import Portfolio from './pages/Portfolio'
import LedgerList from './pages/LedgerList'
import Anchor from './pages/Anchor'
import Verify from './pages/Verify'
import VerifyReport from './pages/VerifyReport'
import Ranking from './pages/Ranking'
import ChannelProfile from './pages/ChannelProfile'
import SubscriptionList from './pages/SubscriptionList'
import ChannelFee from './pages/ChannelFee'
import MyPage from './pages/MyPage'
import Reports from './pages/Reports'
import ReportDetail from './pages/ReportDetail'
import ReportNew from './pages/ReportNew'
import SeasonHome from './pages/SeasonHome'
import SeasonMode from './pages/SeasonMode'
import SeasonPractice from './pages/SeasonPractice'
import SeasonJoin from './pages/SeasonJoin'
import SeasonPlay from './pages/SeasonPlay'
import SeasonTrades from './pages/SeasonTrades'
import SeasonResult from './pages/SeasonResult'
import SeasonHistory from './pages/SeasonHistory'
import Wallet from './pages/Wallet'
import Settings from './pages/Settings'
import Ad from './pages/Ad'
import AdminAbuse from './pages/AdminAbuse'
import AdminUser from './pages/AdminUser'
import AdminEvent from './pages/AdminEvent'
import AdminAd from './pages/AdminAd'
import EventContest from './pages/EventContest'

export type Access =
  /** 비로그인도 볼 수 있다 — 설계서 §1 기준 A-01 하나뿐 */
  | 'public'
  /** JWT 필요 */
  | 'user'
  /** 관리자 전용, 그 외 403 */
  | 'admin'

export type PageMeta = {
  /** 화면설계서 화면 ID */
  screen: string
  /** 이 화면을 그리는 컴포넌트 */
  element: ComponentType
  path: string
  title: string
  access: Access
  /** 셸 모드. 없으면 셸 없이 단독 렌더한다(로그인) */
  mode?: Mode
  /** 두 모드에 다 속한 화면(환경 설정 · 마이페이지 · 지갑처럼 어느 쪽 상단바에서도 여는 곳).
      `?mode=sim` 으로 들어오면 셸이 그 모드를 유지하고, mode 는 주소에 아무 말이
      없을 때의 기본값이 된다. 그 밖의 화면은 주소의 mode 를 무시한다 — 종목 탐색이
      모의투자 상단바를 달고 나오면 안 된다. 읽는 곳은 App.tsx 의 Page, 주소 규칙은
      sharedMode.ts 다. */
  sharedMode?: true
  /** 사이드바에서 활성으로 표시할 메뉴 키 */
  nav?: string
  bodyClass?: string
  /** 이 화면을 구현할 Jira 스토리 슬러그 */
  story: string
}

export const ROUTES: PageMeta[] = [
  // ── A. 인증 · 공통 ────────────────────────────────────
  { screen: 'A-01', element: Login, path: '/login', title: '로그인', access: 'public', story: 'ANT-FE-LOGIN' },
  /* 프로바이더가 인가 코드를 돌려주는 A-01 의 복귀 구간. 화면설계서에는 없는 경로다.
     구글·SSAFY 가 같은 화면을 쓰므로 :provider 로 한 자리만 잡는다 — 표에 없는 값은
     OAuthCallback 이 걸러 오류로 보여준다.
     access 가 'public' 이어야 한다 — 가드가 막으면 코드를 교환하기도 전에 /login 으로 튕긴다. */
  { screen: 'A-01', element: OAuthCallback, path: '/oauth/callback/:provider', title: '로그인 중', access: 'public', story: 'ANT-AUTH-01' },
  { screen: 'A-02', element: OnboardingNickname, path: '/onboarding/nickname', title: '닉네임 설정', access: 'user', story: 'ANT-AUTH-03' },
  /* 닉네임 다음 단계. **건너뛸 수 있다** — RequireAccess 는 닉네임만 보므로 가드가 따로 없다 */
  { screen: 'A-02', element: OnboardingFee, path: '/onboarding/fee', title: '구독료 설정', access: 'user', story: 'ANT-AUTH-03' },
  { screen: 'A-03', element: Search, path: '/search', title: '통합 검색', access: 'user', mode: 'insight', nav: 'stocks', bodyClass: 'is-night', story: 'ANT-FE-SEARCH' },

  // ── B. 인사이트 ──────────────────────────────────────
  { screen: 'B-01', element: Home, path: '/', title: '인사이트 홈', access: 'user', mode: 'insight', nav: 'home', bodyClass: 'insight-home is-night is-glass', story: 'ANT-FE-HOME' },
  { screen: 'B-02', element: Stocks, path: '/stocks', title: '종목 탐색', access: 'user', mode: 'insight', nav: 'stocks', bodyClass: 'stocks-page is-night', story: 'ANT-FE-STOCKS' },
  { screen: 'B-03', element: StockDetail, path: '/stocks/:code', title: '종목 상세', access: 'user', mode: 'insight', nav: 'stocks', bodyClass: 'stock-detail-page is-night', story: 'ANT-FE-STOCK-DETAIL' },
  { screen: 'B-04', element: Watchlist, path: '/watchlist', title: '관심 종목', access: 'user', mode: 'insight', nav: 'stocks', bodyClass: 'watchlist-page is-night', story: 'ANT-FE-WATCHLIST' },

  // ── C. 예측 ──────────────────────────────────────────
  { screen: 'C-01', element: PredictNew, path: '/predict', title: '예측 등록', access: 'user', mode: 'insight', nav: 'market', bodyClass: 'prediction-create-page is-night', story: 'ANT-FE-PREDICT-NEW' },
  { screen: 'C-02', element: PredictList, path: '/me/predictions', title: '내 예측 현황', access: 'user', mode: 'insight', nav: 'portfolio', bodyClass: 'my-predictions-page is-night', story: 'ANT-FE-PREDICT-LIST' },
  { screen: 'C-03', element: PredictDetail, path: '/predictions/:id', title: '예측 상세', access: 'user', mode: 'insight', nav: 'portfolio', bodyClass: 'prediction-detail-page is-night', story: 'ANT-FE-PREDICT-DETAIL' },
  { screen: 'C-04', element: Portfolio, path: '/me/portfolio', title: '예측 포트폴리오', access: 'user', mode: 'insight', nav: 'portfolio', bodyClass: 'forecast-page is-night', story: 'ANT-FE-PORTFOLIO' },

  // ── D. 온체인 검증 ───────────────────────────────────
  /* 넷 다 sharedMode 다 — 자물쇠(상단바)는 두 모드 어디에도 서 있는데, 그 길로 들어온
     원장이 인사이트로 고정돼 있어 모의투자에서 누르면 토글과 배경이 통째로 넘어갔다
     (2026-09-23). 봉인 기록은 어느 모드의 기록이냐를 가리지 않는 화면이라 설정 · 지갑과
     같은 부류다. 원장 안에서 서로 오가는 링크는 useSharedHref 로 모드를 잇는다. */
  { screen: 'D-01', element: LedgerList, path: '/ledger', title: '확정 기록', access: 'user', mode: 'insight', sharedMode: true, nav: 'ledger', bodyClass: 'is-night', story: 'ANT-FE-LEDGER-LIST' },
  { screen: 'D-02', element: Anchor, path: '/ledger/anchors/:id', title: '체인 기록 상세', access: 'user', mode: 'insight', sharedMode: true, nav: 'ledger', bodyClass: 'is-night', story: 'ANT-FE-ANCHOR' },
  { screen: 'D-03', element: Verify, path: '/ledger/verify/:predictionId', title: '4단계 검산', access: 'user', mode: 'insight', sharedMode: true, nav: 'ledger', bodyClass: 'is-night', story: 'ANT-FE-VERIFY' },
  /* 리포트 검산. 예측 검산과 경로를 나눈 이유는 id 가 다른 시퀀스라서다 —
     /ledger/verify/:id 하나로 두면 12 가 예측인지 리포트인지 알 수 없다. */
  { screen: 'D-04', element: VerifyReport, path: '/ledger/verify/report/:reportId', title: '리포트 검산', access: 'user', mode: 'insight', sharedMode: true, nav: 'ledger', bodyClass: 'is-night', story: 'ANT-CHAIN-17' },

  // ── E. 채널 · 구독 ───────────────────────────────────
  { screen: 'E-01', element: Ranking, path: '/rankings', title: '주가 예측 랭킹', access: 'user', mode: 'insight', nav: 'market', bodyClass: 'predictor-market-page', story: 'ANT-FE-RANKING' },
  /* 모의투자 랭킹(/sim/rankings)은 뺐다(2026-09-16). REPLAY 트랙 누적 순위인데,
     "한 시즌 안의 순위는 G-07 시즌 리더보드가 이미 보여 준다" 가 그때의 근거였다.

     ⚠ **그 G-07 이 빈 자리표시자였고 2026-09-17 에 지웠다.** 그래서 지금은 모의투자
     순위를 볼 화면이 하나도 없다. 순위를 되살려야 한다면 여기가 아니라 그 결정부터
     다시 봐야 한다. 실전 랭킹(/rankings)은 그대로다. */
  { screen: 'E-02', element: ChannelProfile, path: '/channels/:userId', title: '채널 프로필', access: 'user', mode: 'insight', nav: 'market', bodyClass: 'is-night', story: 'ANT-FE-CHANNEL-PROFILE' },
  { screen: 'E-03', element: SubscriptionList, path: '/me/subscriptions', title: '내 구독', access: 'user', mode: 'insight', nav: 'portfolio', bodyClass: 'is-night', story: 'ANT-FE-SUBSCRIPTION-LIST' },
  { screen: 'E-04', element: ChannelFee, path: '/me/channel', title: '내 채널 설정', access: 'user', mode: 'insight', nav: 'portfolio', bodyClass: 'is-night', story: 'ANT-FE-CHANNEL-FEE' },
  /* nav 가 사이드바 어느 키와도 겹치지 않는다 — 마이페이지는 사이드바가 아니라 상단
     계정 메뉴로 들어오는 화면이라 아무것도 활성이 아닌 것이 맞다. 전에는 'portfolio' 라
     /me 에 있는데 예측 포트폴리오에 불이 들어왔다.
     App.tsx 가 `meta.nav ?? 'home'` 으로 기본값을 주므로 비우면 이번엔 홈이 켜진다. */
  /* 상단 계정 메뉴로 **어느 모드에서든** 들어온다 — 환경 설정(H-03)과 같은 sharedMode 다. */
  { screen: 'E-05', element: MyPage, path: '/me', title: '마이페이지', access: 'user', mode: 'insight', sharedMode: true, nav: 'me', bodyClass: 'mypage-page is-night', story: 'ANT-FE-MYPAGE' },

  // ── F. 리포트 ───────────────────────────────────────
  // 커뮤니티(F-04 피드 · F-05 글 상세 · F-06 글 작성)는 개발 범위에서 뺐다
  // (2026-09-14). 화면과 라우트를 지웠고, 서버 API 는 그대로 남아 있다.
  { screen: 'F-01', element: Reports, path: '/reports', title: '리포트 피드', access: 'user', mode: 'insight', nav: 'report', bodyClass: 'reports-page is-night', story: 'ANT-FE-REPORTS' },
  { screen: 'F-02', element: ReportDetail, path: '/reports/:id', title: '리포트 상세', access: 'user', mode: 'insight', nav: 'report', bodyClass: 'is-night', story: 'ANT-FE-REPORT-DETAIL' },
  { screen: 'F-03', element: ReportNew, path: '/reports/new', title: '리포트 작성', access: 'user', mode: 'insight', nav: 'report', bodyClass: 'is-night', story: 'ANT-FE-REPORT-NEW' },

  // ── G. 모의투자 ──────────────────────────────────────
  /* G-00 모의투자 온보딩은 라우트가 없다 — M-09 와 같이 셸 위 오버레이다
     (components/SimOnboardingTour.tsx, Layout 이 띄운다). */
  { screen: 'G-01', element: SeasonHome, path: '/sim', title: '모의투자 홈', access: 'user', mode: 'sim', nav: 'play', bodyClass: 'sim-home-page is-night is-glass', story: 'ANT-FE-SEASON-HOME' },
  /* **G-02 모드 선택을 걷어냈다(2026-09-21).** 모의투자를 연습 하나로 가기로 정해져
     고를 것이 없어졌다. 라우트는 넘겨보내는 자리로 남긴다(C-01 과 같은 처리) —
     사이드바·안내 투어가 여기를 가리키던 동안 즐겨찾기에 들어갔을 수 있고, 없는
     경로면 App.tsx 가 인사이트 홈으로 보내 모의투자에서 아예 떨어진다. */
  { screen: 'G-02', element: SeasonMode, path: '/sim/modes', title: '연습하기', access: 'user', mode: 'sim', nav: 'play', story: 'ANT-FE-SEASON-MODE' },
  /* **연습 허브도 걷어내 홈(G-01)에 합쳤다(2026-09-21).** 홈과 같은 두 응답으로 같은
     질문에 답하고 있었다 — 자세한 사정은 pages/SeasonPractice.tsx 머리말에 있다.
     모드 선택과 같이 넘겨보내는 자리로만 남긴다. */
  { screen: 'G-02', element: SeasonPractice, path: '/sim/practice', title: '연습하기', access: 'user', mode: 'sim', nav: 'play', story: 'ANT-FE-SEASON-PRACTICE' },
  { screen: 'G-03', element: SeasonJoin, path: '/sim/seasons/:id', title: '시즌 상세 · 참가', access: 'user', mode: 'sim', nav: 'play', bodyClass: 'sim-join-page is-night is-glass', story: 'ANT-FE-SEASON-JOIN' },
  { screen: 'G-04', element: SeasonPlay, path: '/sim/:id/play', title: '시즌 진행', access: 'user', mode: 'sim', nav: 'play', bodyClass: 'sim-play-page is-night', story: 'ANT-FE-SEASON-PLAY' },
  /* **G-05 시즌 리서치를 지웠다(2026-09-17).** 프로토타입에서 빠진 뒤로 "결론 나면
     정리한다" 는 주석과 함께 자리표시자만 남아 있었는데, 안 만들기로 정해졌다. */
  { screen: 'G-06', element: SeasonTrades, path: '/sim/:id/trades', title: '매매일지', access: 'user', mode: 'sim', nav: 'portfolio', bodyClass: 'sim-trades-page is-night is-glass', story: 'ANT-FE-SEASON-TRADES' },
  /* **G-07 시즌 리더보드도 지웠다(2026-09-17).** 지라에서 폐기된 스토리다.

     ⚠ 그래서 **한 시즌 안의 순위를 볼 화면이 지금 하나도 없다.** 사이드바에서
     '모의투자 랭킹' 을 내릴 때(2026-09-16) "그건 시즌 리더보드(G-07)가 이미 보여
     준다" 를 근거로 삼았는데, 그 G-07 이 빈 페이지였다. 순위를 다시 보여줘야 한다면
     이 자리가 아니라 그 결정부터 되짚어야 한다. */
  { screen: 'G-08', element: SeasonResult, path: '/sim/:id/result', title: '결과 · AI 복기', access: 'user', mode: 'sim', nav: 'portfolio', bodyClass: 'sim-result-page is-night is-glass', story: 'ANT-FE-SEASON-RESULT' },
  { screen: 'G-09', element: SeasonHistory, path: '/sim/history', title: '기록 · 배지', access: 'user', mode: 'sim', nav: 'portfolio', bodyClass: 'sim-record-page is-night is-glass', story: 'ANT-FE-SEASON-HISTORY' },

  // ── H. 지갑 · 알림 · 설정 · 광고 ─────────────────────
  /* 상단바 잔액 칩 · 계정 메뉴로 **어느 모드에서든** 들어온다 — 환경 설정(H-03)과 같은
     sharedMode 다. nav 'portfolio' 는 인사이트 줄 기준이고, 모의투자로 들어오면 App.tsx 가
     아무것도 칠하지 않는다(같은 키가 그 줄에서는 '투자 포트폴리오' 다). */
  { screen: 'H-01', element: Wallet, path: '/me/wallet', title: '지갑 · 토큰', access: 'user', mode: 'insight', sharedMode: true, nav: 'portfolio', bodyClass: 'is-night', story: 'ANT-FE-WALLET' },
  /* H-02 알림함은 **라우트를 두지 않는다**(2026-09-16). 알림은 보러 가는 것이
     아니라 하던 일 옆에서 확인하고 닫는 것이라, 상단바 종에서 여는 팝업
     하나로 끝낸다(components/NotificationBell). 설계서 §3 H-02 에는 이 경로가
     적혀 있지만 문서는 팀 공용이라 손대지 않았고, 이 주석이 그 차이를 적어
     두는 자리다. */
  /* 상단바 톱니로 **어느 모드에서든** 들어온다. 모의투자에서 눌렀는데 상단바 토글이
     인사이트로 넘어가 있었다(2026-09-22) — 들어온 모드를 ?mode= 로 받아 유지한다
     (sharedMode · sharedMode.ts). 주소에 없으면 인사이트다. 마이페이지(E-05)·지갑(H-01)도 같다. */
  { screen: 'H-03', element: Settings, path: '/settings', title: '환경 설정', access: 'user', mode: 'insight', sharedMode: true, nav: 'home', bodyClass: 'is-night', story: 'ANT-FE-SETTINGS' },
  { screen: 'H-04', element: Ad, path: '/ads/new', title: '광고 등록', access: 'user', mode: 'insight', nav: 'home', bodyClass: 'is-night', story: 'ANT-FE-AD' },

  /* ── I. 관리자 ────────────────────────────────────────
     I-01 신고 처리 · I-02 회원 제재는 dev 에서 한 줄짜리 자리표시자라 라우트째 지워졌다가(2026-09-17),
     이 브랜치에서 실제 화면으로 되살아났다(ANT-FE-ADMIN-MODERATION · 서버 ANT-ADMIN-01).
     I-03 시즌 운영은 지라 폐기 그대로 — 되살리지 않는다. 설계서 §3 I 에는 남아 있다(문서는 팀 공용).
     access:'admin' 은 RequireAccess 의 ADMIN 가지가 지킨다. */
  { screen: 'I-01', element: AdminAbuse, path: '/admin/abuse-reports', title: '신고 처리', access: 'admin', mode: 'insight', nav: 'home', bodyClass: 'is-night', story: 'ANT-FE-ADMIN-MODERATION' },
  { screen: 'I-02', element: AdminUser, path: '/admin/users', title: '회원 제재', access: 'admin', mode: 'insight', nav: 'home', bodyClass: 'is-night', story: 'ANT-FE-ADMIN-MODERATION' },
  /* I-04 는 설계서에 없다. 이벤트 관리자 API 6종(ANT-EVENT-04·06)이 열려 있는데 부르는
     화면이 없어 붙였다. nav 는 home 으로 둔다 — 사이드바에 메뉴를 만들지 않는다. */
  { screen: 'I-04', element: AdminEvent, path: '/admin/events', title: '이벤트 운영', access: 'admin', mode: 'insight', nav: 'home', bodyClass: 'is-night', story: 'ANT-FE-ADMIN-EVENT' },
  /* I-05 광고 승인(ANT-ADMIN-03). 명세가 "관리자 승인은 2차" 로 미뤄 뒀던 것을 여기서 한다 — 승인 버튼이 곧 소각 tx 다. */
  { screen: 'I-05', element: AdminAd, path: '/admin/ads', title: '광고 승인', access: 'admin', mode: 'insight', nav: 'home', bodyClass: 'is-night', story: 'ANT-ADMIN-03' },

  /* ── J. 이벤트 ───────────────────────────────────────
     설계서 §3 에 없는 화면이다. 이벤트용으로 뒤에 붙은 것이라 A~I 를 밀지 않으려고
     새 글자를 쓴다 — 설계서는 그대로 두고 여기만 늘린다.
     **nav 가 없다.** 사이드바에 메뉴를 만들지 않는 화면이라, 어느 메뉴를 켤지
     정할 것이 없다. 들어오는 길은 링크와 홈 배너뿐이다. */
  { screen: 'J-01', element: EventContest, path: '/events/scalping', title: '천하제일 단타대회', access: 'user', mode: 'insight', bodyClass: 'event-contest-page is-night', story: 'ANT-FE-EVENT-CONTEST' },
]
