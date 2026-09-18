/* 천하제일 단타대회 — 이벤트용 예측 대회(J-01)의 정의.
   날짜·종목·글귀를 여기 한 곳에만 둔다. 화면 여러 곳에 흩어 두면 일정이 하루
   밀릴 때 한 군데를 빠뜨리고, 그 한 군데가 틀린 날짜를 계속 띄운다.

   이 대회의 목적은 서비스로 사람을 끌어오는 것이다. 들어오는 길이 둘뿐이라
   (링크 · 인사이트 홈 배너) 사이드바에는 넣지 않는다.

   **판정 방식은 아직 서버에 없다.** 지금 예측 API 는 만기일을 `기준일 + horizon`
   으로 잡고 horizon 최소가 3일이라(api/predictions.ts), 9/18~9/19 에 등록하면
   기준일이 이미 9/21 이라서 만기가 9/24 아래로 내려오지 않는다. "9/21 종가를
   맞힌다" 를 그대로 태울 수 없다는 뜻이다. 이 화면은 그 앞단(안내·종목 고르기)
   까지만 맡고, 등록 양식에 붙을 대회 전용 요소는 따로 정한다. */

/** 시각은 KST ISO-8601 로 적는다(명세 §1 규칙 7). 오프셋을 붙여야 보는 사람의
 *  시간대와 무관하게 같은 순간을 가리킨다 — `+09:00` 을 빼면 안 된다. */
export const CONTEST = {
  /** 주소·링크에 쓰는 이름. `/events/scalping` 과 `?event=` 값이 같은 문자열이다 */
  id: 'scalping',
  name: '천하제일 단타대회',
  /** 한 줄 소개. 홈 배너와 대회 머리가 같은 문장을 쓴다 */
  lede: '정해진 10개 종목, 같은 날 판정. 방향과 정확도를 한 줄로 세웁니다.',

  /** 대회가 시작하는 날(YYYY-MM-DD) */
  startDate: '2026-09-18',
  /** 등록 창이 열리는 순간 */
  opensAt: '2026-09-18T13:30:00+09:00',
  /** 등록 창이 닫히는 순간 */
  closesAt: '2026-09-19T11:59:00+09:00',
  /** 맞혀야 하는 종가의 날짜(YYYY-MM-DD) */
  targetDate: '2026-09-21',
  /**
   * 순위를 공개하는 **순간**.
   *
   * 날짜만 있던 것을 시각까지로 넓혔다(2026-09-16 담당자 확정). 날짜만 두면
   * 그날 0시부터 공개된 것으로 볼 수밖에 없어, 실제로 여는 13:30 까지 반나절
   * 동안 화면이 "공개됨" 이라고 거짓말을 한다.
   */
  revealAt: '2026-09-22T13:30:00+09:00',
} as const

/** 대회 종목(2026-09-15 확정). **순서가 곧 카드 순서다** — 주신 차례를 그대로 둔다.
 *
 *  보통주만 담는다. "삼성전자" 로 찾으면 005935 삼성전자우가, "현대차" 로 찾으면
 *  005387·005385 가 함께 걸리는데, 우선주는 종가가 따로 움직여 같은 종목을 두 번
 *  넣는 셈이 된다. 열 코드 모두 DB 에서 확인한 값이다. */
/**
 * 대회 종목 — **화면 진입 표시용이다. 등록의 근거가 아니다.**
 *
 * 등록에 쓰는 목록은 서버가 준다(`GET /events/{id}` 의 `stocks[]`). idx 가 온체인 커밋
 * keccak(eventId, idx, side, noteHash) 의 재료라 순서까지 서버를 따라야 하고, 여기 상수와
 * 어긋나면 배팅이 엉뚱한 종목에 걸린다(2026-09-16).
 *
 * 그래서 이 배열이 남아 있는 곳은 StockDetail 의 "대회로 들어왔는가" 표시 하나뿐이다.
 * 그 화면에서는 이제 등록을 받지 않으므로 어긋나도 그림만 달라진다. 대회가 끝나면
 * StockDetail 의 대회 분기와 함께 지운다.
 */
export const CONTEST_CODES: readonly string[] = [
  '005930', // 삼성전자
  '000660', // SK하이닉스
  '373220', // LG에너지솔루션 — "LG엔솔"
  '005380', // 현대차
  '012450', // 한화에어로스페이스
  '034020', // 두산에너빌리티
  '068270', // 셀트리온
  '035420', // NAVER
  '298040', // 효성중공업
  '035720', // 카카오
]

/**
 * 지금이 대회의 어느 대목인가.
 *
 * - `before` 아직 안 열렸다
 * - `open` 등록할 수 있다
 * - `judging` 등록은 닫혔고 결과를 기다린다
 * - `done` 결과가 나왔다
 */
export type Phase = 'before' | 'open' | 'judging' | 'done'

export function phaseAt(now: Date = new Date()): Phase {
  const t = now.getTime()
  if (t < Date.parse(CONTEST.opensAt)) return 'before'
  if (t < Date.parse(CONTEST.closesAt)) return 'open'
  if (t < Date.parse(CONTEST.revealAt)) return 'judging'
  return 'done'
}

export const PHASE_LABEL: Record<Phase, string> = {
  before: '시작 전',
  open: '예측 등록 중',
  judging: '결과 집계 중',
  done: '결과 공개',
}

/* ── 배너 그림 ────────────────────────────────────────────
   배너는 **한 장이 아니라 여러 겹**이다. 처음엔 완성본 한 장을 받아 썼는데,
   그 그림이 4.69:1 이고 홈의 배너 자리는 그보다 한참 길고 낮아서 — 세로로
   자르면 제목도 캐릭터도 안 남고, 비율대로 넣으면 홈이 통째로 밀렸다.

   조각으로 받으면 우리 비율에 맞춰 **다시 배치**할 수 있다. 배경·차트·색종이는
   추상 무늬라 늘리거나 잘라도 티가 안 나고, 제목·캡슐·캐릭터만 비율을 지키면
   된다. 자리 값은 home.css 가 띠 높이(cqh) 하나로 잰다.

   단계가 바뀌면 **캡슐·제목·문구 세 장만** 갈아끼운다. 나머지는 공통이다. */

const ART = (name: string) => `/assets/event/${name}.webp`

/**
 * 대회 페이지(J-01) 머리에 쓰는 조각.
 *
 * **opensAt 만 날짜가 그림에 구워져 있다.** "등록 시작 · 9월 18일 (금) 13:10" 이라
 * 시작 전에만 맞는 말이다. 다른 단계에서는 이 그림을 쓰지 않고 글자로 그린다 —
 * 등록이 닫힌 뒤에 "등록 시작" 이 떠 있으면 거짓말이 된다.
 */
export const HERO = {
  event: ART('hero-pill-event'),
  title: ART('hero-title'),
  lede: ART('hero-lede'),
  opensAt: ART('hero-opens-at'),
  rulesTitle: ART('hero-rules-title'),
  rules: ART('hero-rules'),
}

/**
 * 대회 규칙 네 줄. 순서가 곧 시간 순서다.
 *
 * 화면에는 그림(HERO.rules)으로 나가지만, **그림에 구워진 글자는 읽어 주는
 * 기기가 못 읽는다.** 그래서 같은 내용을 여기 글로도 두고 화면 밖에 함께 깐다.
 * 날짜가 바뀌면 이 글과 그림이 어긋나므로 그림도 같이 다시 받아야 한다.
 */
export const RULES: { k: string; v: string }[] = [
  { k: '시작', v: `${dayStamp(CONTEST.startDate)} 시작합니다.` },
  { k: '등록', v: `${stamp(CONTEST.opensAt)} ~ ${stamp(CONTEST.closesAt)} 사이에만 예측을 등록할 수 있습니다.` },
  { k: '판정', v: `${dayStamp(CONTEST.targetDate)} 종가를 맞히는 대회입니다.` },
  { k: '공개', v: `결과는 ${stamp(CONTEST.revealAt)}에 공개합니다.` },
]

/** 단계와 무관하게 늘 깔리는 겹 */
export const BANNER_BASE = {
  bg: ART('bg'),
  chart: ART('chart'),
  confetti: ART('confetti'),
  crown: ART('crown'),
  mascot: ART('mascot'),
  sticker: ART('sticker'),
}

/**
 * 시상대 무대 — 랭킹의 이벤트 탭(E-01)이 쓴다.
 *
 * 받은 원본은 1~2MB PNG 열한 장(합 12MB)이었다. 배경은 가로 1600, 조각은 700 으로 줄여
 * webp 로 굽고 원본은 지웠다(합 2.2MB) — 순위 화면은 배너처럼 한 번 스치는 곳이 아니라
 * 결과를 들여다보는 자리라, 그림 열한 장이 매번 12MB 씩 내려오면 표가 뜨기 전에 기다리게 된다.
 *
 * **무대 배경 한 장만 쓴다.** 조명·관중·색종이 겹도 함께 받았는데(stage-lights ·
 * stage-crowd · stage-confetti) 셋 다 배경에 이미 그려져 있어 겹쳐도 달라지는 게 거의 없고,
 * 둘이 합쳐 680KB 라 배경(77KB)의 아홉 배다. 자산은 남겨 두었으니 더 화려하게 갈 때 얹으면 된다.
 */
export const STAGE = {
  bg: ART('stage-bg'),
  /** 등수별 받침. 숫자가 그림에 새겨져 있어 따로 글자를 얹지 않는다 */
  podium: { 1: ART('podium-1'), 2: ART('podium-2'), 3: ART('podium-3') } as Record<number, string>,
  /** 등수별 캐릭터. 사람마다 다른 게 아니라 **자리에 붙는 그림**이다 — 1등 자리에는 늘
   *  트로피를 든 단타왕이 선다. 참가자 사진은 표에서 보여 준다 */
  char: { 1: ART('hero-champion'), 2: ART('char-2nd'), 3: ART('char-3rd') } as Record<number, string>,
  trophy: ART('trophy'),
}

export type BannerArt = {
  pill: string
  title: string
  copy: string
  /** 그림에 구워진 글자를 그대로 옮긴 것. 스크린리더는 이것만 읽는다 */
  alt: string
}

/**
 * 단계별로 갈아끼우는 세 장. 나머지 여섯 겹은 네 단계가 함께 쓴다.
 *
 * **네 단계가 모두 그림을 갖는다**(2026-09-16). 한동안 judging 만 비어 있었는데
 * — 등록은 닫혔고 결과는 아직인 구간이라 "대회 종료" 도 "진행 중" 도 거짓말이
 * 된다 — "결과 집계 중" 캡슐을 받아 채웠다. Partial 이 아니라 Record 인 이유가
 * 이것이다. 단계를 새로 만들면 그림도 같이 만들어야 한다는 뜻이고, 안 만들면
 * 컴파일이 막는다.
 */
export const BANNER: Record<Phase, BannerArt> = {
  before: {
    pill: ART('pill-before'),
    title: ART('title-main'),
    copy: ART('copy-before'),
    alt: '천하제일 단타대회 — 시작 전. 롱이냐 숏이냐! 예측 최강자는 누구?',
  },
  open: {
    pill: ART('pill-open'),
    title: ART('title-main'),
    copy: ART('copy-before'),
    alt: '천하제일 단타대회 — 대회 진행 중. 롱이냐 숏이냐! 예측 최강자는 누구?',
  },
  judging: {
    pill: ART('pill-judging'),
    title: ART('title-main'),
    copy: ART('copy-before'),
    alt: '천하제일 단타대회 — 결과 집계 중. 롱이냐 숏이냐! 예측 최강자는 누구?',
  },
  done: {
    pill: ART('pill-done'),
    title: ART('title-done'),
    copy: ART('copy-done'),
    alt: '천하제일 단타대회 — 대회 종료. 최종 랭킹 공개. 단타왕은 누구? 지금 최종 순위를 확인하세요!',
  },
}

/**
 * 대회 예측의 **고정 기간**(캘린더일).
 *
 * 대회는 시작일 종가를 기준으로 판정일 종가를 맞히는 것이라, 기간이 사람마다
 * 다를 수 없다. 숫자를 박지 않고 두 날짜에서 뽑는 이유는 일정이 하루 밀렸을 때
 * 여기만 옛 값으로 남는 일을 막기 위해서다.
 *
 * 서버가 이벤트 예측에 한해 기준일을 등록 당일로 잡아 주어야 이 값이 맞는다
 * (docs/기획_랭킹_이벤트탭.md §5.3). 평소 규칙대로면 기준일이 다음 평일이라
 * 만기가 하루 밀린다.
 */
export const CONTEST_HORIZON = Math.round(
  (Date.parse(`${CONTEST.targetDate}T00:00:00+09:00`)
    - Date.parse(`${CONTEST.startDate}T00:00:00+09:00`)) / 86_400_000,
)

/** 대회 페이지 주소. 링크로 퍼뜨리는 주소라 한 곳에서만 만든다 */
export const CONTEST_PATH = `/events/${CONTEST.id}`

/**
 * 종목 카드가 가리키는 곳.
 *
 * **등록 창이 열려 있을 때만 예측 탭으로 보낸다.** 닫혀 있는데 예측 탭을 열면
 * 쓸 수 없는 양식 앞에 사람을 세우는 꼴이라, 그때는 종목 정보 탭으로 보낸다.
 * `event` 가 붙은 채로 들어온 것을 B-03 이 알아보고 대회 전용 요소를 띄운다.
 */
export function cardHref(code: string, phase: Phase) {
  /* 개발 빌드에서는 창이 닫혀 있어도 예측 탭으로 보낸다(ANT-FE-EVENT-BET).
     등록 창이 9/18 13:10 에야 열리는데, 그때까지 이 카드가 종목 정보로만 보내면
     대회 전용 요소(배너·배팅)를 만들면서 확인할 길이 없다 — 주소를 손으로 쳐야 한다.
     B-03 도 같은 자리에 같은 예외를 두고 있어 두 쪽이 함께 열리고 함께 닫힌다.
     `import.meta.env.DEV` 는 빌드 때 상수로 접히므로 운영 번들에는 이 가지가 없다. */
  return phase === 'open' || import.meta.env.DEV
    ? `/stocks/${code}?tab=predict&event=${CONTEST.id}`
    : `/stocks/${code}`
}

/** "9월 18일 (금) 13:10" — 규칙 줄에 쓰는 표기 */
export function stamp(iso: string, withTime = true) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    ...(withTime ? { hour: '2-digit', minute: '2-digit', hour12: false } : {}),
  })
}

/** 날짜만 적는 자리(YYYY-MM-DD 를 받는다) */
export function dayStamp(ymd: string) {
  return stamp(`${ymd}T00:00:00+09:00`, false)
}
