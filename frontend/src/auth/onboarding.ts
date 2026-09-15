/* M-09 온보딩 튜토리얼을 띄울 차례인지 기억한다.

   왜 플래그가 필요한가
   isNew 는 로그인 응답(A-01)에만 실려 오고, 그 값을 받는 곳은 OAuthCallback 이다.
   그런데 M-09 가 뜨는 자리는 **셸(Layout)** 이다 — 그 사이에 A-02 닉네임 화면이
   끼어 있고(RequireAccess 가 닉네임 없는 회원을 거기로 돌려보낸다) 그 화면은
   셸이 없어서, 라우터 state 로 값을 들고 갈 수 없다. 그래서 한 칸 건너 전달할
   자리를 둔다.

   왜 라우트가 아닌가
   라우트를 주면 히스토리에 남아 이탈 시 복귀가 어렵다(설계서 §4 M-09).
   그래서 셸 위 오버레이이고, 오버레이는 자기가 뜰 때를 스스로 알 수 없다.

   왜 홈이 아니라 셸인가
   비로그인 딥링크로 막혔던 회원은 가입 뒤 홈이 아니라 막혔던 경로로 착지한다
   (returnTo). 홈에만 걸면 그 사람은 튜토리얼을 아예 못 본다.

   왜 sessionStorage 인가
   returnTo 와 같은 이유이고 같은 자리다. 로그인 직후의 한 흐름 안에서만 쓰이며
   탭을 닫으면 정리된다. localStorage 에 두면 며칠 뒤 다른 날 들어왔을 때
   "로그인 직후" 도 아닌 시점에 튜토리얼이 떠오른다 — 진입 조건이 "A-01 로그인
   직후 isNew=true" 이므로 그 흐름보다 오래 살아서는 안 된다.

   **한 번 꺼내면 다시 세우지 않는다.** isNew 는 최초 1회뿐이라 건너뛰면 다시
   뜨지 않는 것이 설계다. 남은 단계는 H-03 환경 설정이 보완 안내로 받는다. */
const KEY = 'antena.onboarding'

/** A-01 응답의 isNew 가 참일 때 OAuthCallback 이 세운다. */
export function markOnboardingPending() {
  try { sessionStorage.setItem(KEY, '1') } catch { /* 무시 */ }
}

/** 셸(Layout)이 부른다. 꺼내면서 지우므로 두 번 뜨지 않는다. */
export function takeOnboardingPending(): boolean {
  try {
    const pending = sessionStorage.getItem(KEY) === '1'
    sessionStorage.removeItem(KEY)
    return pending
  } catch {
    return false
  }
}

/* ── 다시 열기 ─────────────────────────────────────────────
   M-09 는 가입 직후 isNew 가 참인 그 한 번만 뜬다. 건너뛰면 다시 볼 길이
   없었다 — 이미 가입한 회원은 영영 못 본다는 뜻이기도 하다.

   G-00 모의투자 온보딩이 같은 문제를 window 이벤트로 풀었으므로(sim/onboardingSeen.ts)
   여기도 같은 방식을 쓴다. 안내를 띄우는 곳은 셸이고 화면은 신호만 올린다.

   **pending 플래그를 다시 세우지 않는다.** 그건 "로그인 직후" 라는 한 흐름의
   표시라, 며칠 뒤 설정 화면에서 세우면 뜻이 달라진다. 여는 것과 최초 1회 판정은
   별개다. */
const OPEN = 'antena:onboarding'

/** 화면 안쪽에서 튜토리얼을 다시 연다. 듣는 곳은 셸 하나뿐이다. */
export function openOnboardingTour() {
  window.dispatchEvent(new Event(OPEN))
}

/** 셸이 구독한다. 해제 함수를 돌려주므로 useEffect 에서 그대로 반환하면 된다. */
export function onOnboardingOpen(fn: () => void) {
  window.addEventListener(OPEN, fn)
  return () => window.removeEventListener(OPEN, fn)
}
