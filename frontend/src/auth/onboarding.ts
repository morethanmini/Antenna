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
   가입 절차(A-02 닉네임 · 구독료)를 지나 어느 화면으로 들어오든 한 번은 보여야
   한다. 홈 화면 안에만 걸어 두면 그 화면을 안 거치는 경로가 생기는 순간 못 본다.

   왜 sessionStorage 인가
   로그인 직후의 한 흐름 안에서만 쓰이며
   탭을 닫으면 정리된다. localStorage 에 두면 며칠 뒤 다른 날 들어왔을 때
   "로그인 직후" 도 아닌 시점에 튜토리얼이 떠오른다.

   **매 로그인마다 세운다(2026-09-16).** 전에는 isNew 가 참인 가입 직후 한 번뿐이라,
   건너뛴 사람과 그 기능이 생기기 전에 가입한 사람은 영영 볼 수 없었다. 이제는
   로그인할 때마다 뜨고, 그만 보고 싶은 사람이 **직접 꺼야** 멈춘다 — 끄는 스위치가
   아래 '다시 보지 않기'다. */
const KEY = 'antena.onboarding'

/** 로그인에 성공하면 OAuthCallback 이 세운다. 신규·기존을 가리지 않는다. */
export function markOnboardingPending() {
  try { sessionStorage.setItem(KEY, '1') } catch { /* 무시 */ }
}

/**
 * 셸(Layout)이 부른다. 꺼내면서 지우므로 한 흐름에 두 번 뜨지 않는다.
 *
 * **꺼 둔 사람에게는 false 다.** 플래그는 그래도 꺼내 버린다 — 남겨 두면 나중에
 * 설정에서 다시 켰을 때 그때 뜬금없이 떠오른다.
 */
export function takeOnboardingPending(): boolean {
  try {
    const pending = sessionStorage.getItem(KEY) === '1'
    sessionStorage.removeItem(KEY)
    return pending && !isOnboardingOff()
  } catch {
    return false
  }
}

/* ── 다시 보지 않기 ───────────────────────────────────────
   매 로그인마다 뜨게 바꾸면서 끄는 스위치가 필요해졌다. 안내를 이미 아는 사람에게
   로그인할 때마다 덮개를 씌우면 그건 안내가 아니라 통행료다.

   **localStorage 다.** pending 과 반대로 오래 살아야 하는 값이다 — 탭을 닫아도,
   다음 날에도 꺼져 있어야 "다시 보지 않기" 라는 말이 지켜진다.

   **사용자별로 나누지 않는다.** 한 브라우저를 여럿이 쓰면 뒤에 온 사람이 안내를
   못 보지만, 그 사람도 H-03 환경 설정의 '안내 다시 보기' 로 언제든 열 수 있다.
   회원 id 를 키에 섞으면 로그인 전에는 읽을 수 없어(이 값을 보는 시점에 user 가
   아직 안 올 수 있다) 더 자주 어긋난다 — G-00 의 sim/onboardingSeen.ts 와 같은 판단이다.

   서버에 두지 않는 이유는 /users/me 에 담을 필드가 없어서다. 자리가 생기면 이
   두 함수만 그쪽으로 갈아끼우면 된다. */
const OFF_KEY = 'antena.onboarding.off'

/** 자동으로 뜨는 것을 껐는가. 껐어도 '안내 다시 보기' 로는 열린다. */
export function isOnboardingOff(): boolean {
  try { return localStorage.getItem(OFF_KEY) === '1' } catch { return false }
}

/** 튜토리얼의 '다시 보지 않기' 가 부른다. 끄고 켜기 둘 다 여기로 온다. */
export function setOnboardingOff(off: boolean) {
  try {
    if (off) localStorage.setItem(OFF_KEY, '1')
    else localStorage.removeItem(OFF_KEY)
  } catch { /* 저장이 막혀 있으면 이번 판만 적용된다 */ }
}

/* ── 다시 열기 ─────────────────────────────────────────────
   자동으로 뜨는 것을 꺼 둔 사람도 언제든 열어 볼 수 있어야 한다.

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
