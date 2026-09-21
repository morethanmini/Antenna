/* G-00 모의투자 온보딩을 이미 봤는지 기억한다.

   왜 M-09 의 auth/onboarding.ts 와 따로 두는가
   그쪽은 "로그인 직후 isNew=true" 한 흐름 안에서만 사는 값이라 sessionStorage 다.
   이쪽은 조건이 다르다 — **모의투자에 처음 들어온 때**이고, 그 시점은 가입 직후일
   수도 있고 한참 뒤일 수도 있다. 탭을 닫으면 사라지는 자리에 두면 들어올 때마다
   안내가 다시 떠서, 두 번째부터는 방해가 된다.

   왜 서버가 아닌가
   "봤다" 를 담아 줄 필드가 /users/me 에 없다. 서버에 자리가 생기면 이 파일의 두
   함수만 그쪽으로 갈아끼우면 되도록, 읽고 쓰는 곳을 여기 두 개로 묶어 둔다.

   왜 회원별로 나누지 않는가
   키 하나로 브라우저 단위로만 기억한다. 한 브라우저를 여럿이 쓰면 뒤에 온 사람이
   안내를 못 보지만, 그 사람도 G-01 의 "처음이라면 이렇게 시작해요" 와 화면 안의
   다시 보기로 같은 내용을 볼 수 있다. 회원 id 를 키에 섞으면 로그아웃 상태에서
   읽을 수 없어(이 값을 읽는 시점에 user 가 아직 안 올 수 있다) 더 자주 어긋난다.

   저장이 막힌 브라우저(사생활 보호 모드 등)에서는 읽기가 늘 false 다 —
   안내가 매번 뜨지만 건너뛸 수 있으므로 막히는 곳은 없다. */
const KEY = 'antena.sim.onboarding'

/** 안내를 끝냈거나 건너뛰었을 때 G-00 이 세운다. */
export function markSimOnboardingSeen() {
  try { localStorage.setItem(KEY, '1') } catch { /* 저장이 막혀 있으면 그냥 다시 본다 */ }
}

/** 셸(Layout)이 모의투자 모드로 들어올 때 읽는다. 지우지 않으므로 다음에도 참이다. */
export function simOnboardingSeen(): boolean {
  try { return localStorage.getItem(KEY) === '1' } catch { return false }
}

/* ── 다시 열기 ─────────────────────────────────────────────
   안내는 라우트가 없는 오버레이라 링크로 열 수 없고, 띄우는 곳은 셸이다. G-01 홈의
   "안내 다시 보기" 처럼 화면 안쪽에서 열려면 셸까지 신호가 올라가야 한다.

   왜 context 가 아닌가
   셸이 이미 tour · linking · briefing 세 개의 오버레이를 상태로 들고 있다. 그 중
   하나를 열자고 프로바이더를 새로 두면 트리 전체가 그 값을 구독하게 된다 —
   여는 쪽은 클릭 한 번뿐이라 window 이벤트면 충분하고, 셸 말고는 아무도 듣지 않는다.
   M-10 브리핑이 쿼리스트링으로 같은 일을 하는 것과 같은 결의 선택이다. */
const OPEN = 'antena:sim-onboarding'

/** 화면 안쪽에서 안내를 다시 연다. 듣는 곳은 셸 하나뿐이다. */
export function openSimOnboarding() {
  window.dispatchEvent(new Event(OPEN))
}

/** 셸이 구독한다. 해제 함수를 돌려주므로 useEffect 에서 그대로 반환하면 된다. */
export function onSimOnboardingOpen(fn: () => void) {
  window.addEventListener(OPEN, fn)
  return () => window.removeEventListener(OPEN, fn)
}

/* ── G-04 진행 화면 안내(PlayCoachMarks) ──────────────────
   G-00 과 달리 **두 군데에 적는다.**

   G-00 은 "모의투자가 무엇인지" 라 한 번 보면 끝이지만, 이쪽은 어느 버튼이 무슨 일을
   하는지라 한 번에 외워지지 않는다. 닫았다고 영영 감추면 실수로 닫은 사람이 되돌릴
   길이 화면 안 글자 버튼뿐이고, 그렇다고 들어올 때마다 띄우면 하루 넘기다 목록을
   오가는 동안 화면이 계속 어두워진다. 그래서 둘로 나눈다.

   sessionStorage  그냥 닫았다. 이 탭을 쓰는 동안만 조용하고, 다음에 새로 열면 또 뜬다.
   localStorage    "다시 보지 않기" 를 체크하고 닫았다. 그때부터 영영 뜨지 않는다.

   회원별로 나누지 않는 것은 G-00 과 같다 — 이 값을 읽는 시점에 user 가 아직 안 올 수
   있어서, id 를 키에 섞으면 오히려 더 자주 어긋난다.

   ⚠ 예전 버전은 닫기만 해도 localStorage 에 '1' 을 적었다. 그때 닫은 사람은 이미
   "다시 보지 않기" 로 남아 있으므로 자동으로는 뜨지 않는다 — 화면 안 "화면 안내 보기"
   로 열 수 있고, 지우려면 이 키를 지우면 된다. */
const PLAY_KEY = 'antena.sim.play.coach'

/**
 * 안내를 닫았다.
 *
 * @param forever "다시 보지 않기" 를 체크했는가. 아니면 이 탭 세션 동안만 조용하다.
 */
export function markPlayCoachClosed(forever: boolean) {
  try {
    sessionStorage.setItem(PLAY_KEY, '1')
    if (forever) localStorage.setItem(PLAY_KEY, '1')
  } catch { /* 저장이 막혀 있으면 그냥 다시 본다 */ }
}

/** G-04 가 화면을 다 그린 뒤 읽는다. 둘 중 하나라도 서 있으면 저절로 뜨지 않는다. */
export function playCoachMuted(): boolean {
  try {
    return localStorage.getItem(PLAY_KEY) === '1' || sessionStorage.getItem(PLAY_KEY) === '1'
  } catch {
    return false
  }
}
