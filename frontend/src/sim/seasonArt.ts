/* 연습 시즌 한 장짜리 아트 — G-03 시즌 상세(SeasonJoin)의 히어로가 쓴다.
   담당 스토리 [ANT-FE-SEASON-JOIN]

   ── 왜 프론트에 표가 있나 ────────────────────────────────────────
   시즌은 백엔드 시더가 만든다(PracticeSeasonSeeder). 그 시더에는 이미지 칼럼이
   없고, 이미지는 화면이 어떻게 생겼는지에 딸린 것이라 서버가 알아야 할 값도
   아니다. 그래서 "어떤 시즌인가" 는 서버가, "그 시즌을 어떤 그림으로 보여주나"
   는 여기가 맡는다.

   ── 무엇을 키로 쓰나 ────────────────────────────────────────────
   시즌 id 는 기동마다 새로 발급될 수 있어(시더가 스펙에 없는 시즌을 지우고 다시
   만든다) 키가 못 된다. 업종(theme·sector)은 주제당 하나씩이라 후보였지만,
   주제가 늘면 겹친다. 남는 것은 제목이고, 제목은 시더의 SEASONS 에 적힌 그대로
   내려온다. 제목이 바뀌면 이 표의 키도 같이 바꾼다 — 못 찾으면 그림 없이
   글자 제목으로 떨어지므로 화면이 깨지지는 않는다.

   ── 두 장씩이다 ─────────────────────────────────────────────────
   art  배경 사진. 알파가 없다(webp/RGB).
   lockup  제목·한 줄 설명·영문 카피가 한 덩어리로 그려진 투명 PNG → webp.
           글자가 그림이라 확대해도 선명하지 않고 번역·검색도 안 된다. 그래서
           화면은 lockup 을 <img alt> 로 넣어 제목을 읽을 수 있게 하고,
           표에 없는 시즌은 평범한 글자 제목으로 그린다.

   ── 시즌별 강조색은 없다 ────────────────────────────────────────
   한때 시즌마다 accent 색을 하나씩 들고 진행 방식 아이콘·머리말에 썼는데,
   밝은 판에서 빨강·주황 아이콘이 뜬금없이 튀었다. 시즌을 말하는 것은 사진이
   하면 되고, 히어로 밖은 브랜드 하나로 간다(2026-09-17). 그래서 여기에 색이
   없다 — 다시 넣고 싶어지면 그때 이 문단부터 지운다. */

const BASE = '/assets/page_train'

export type SeasonArt = {
  /** data-season 에 실어 CSS 가 시즌별 미세 보정을 걸 수 있게 한다 */
  key: string
  /** 배경 사진 */
  art: string
  /** 제목 덩어리(투명 배경) */
  lockup: string
  /** 배경 사진에서 살려야 할 지점. background-position 값 그대로다 */
  position: string
  /** 제목 덩어리의 최대 폭(px). 원본 여백을 잘라 둔 탓에 시즌마다 체감 크기가
      달라서, 다섯 장이 같은 무게로 보이도록 장마다 잡는다 */
  lockupMax: number
}

/** 제목 → 아트. 키는 PracticeSeasonSeeder.SEASONS 의 title 과 글자까지 같아야 한다. */
const ART: Record<string, SeasonArt> = {
  '트럼프 재당선과 환율 급등': {
    key: 'trump',
    art: `${BASE}/trump-art.webp`,
    lockup: `${BASE}/trump-title.webp`,
    position: '58% 38%',
    lockupMax: 660,
  },
  '코로나 대확산, 멈춘 두 달': {
    key: 'covid',
    art: `${BASE}/covid-art.webp`,
    lockup: `${BASE}/covid-title.webp`,
    position: '62% 42%',
    lockupMax: 620,
  },
  '전쟁이 터진 날': {
    key: 'war',
    art: `${BASE}/war-art.webp`,
    lockup: `${BASE}/war-title.webp`,
    position: '60% 50%',
    lockupMax: 640,
  },
  '하루 만의 폭락, 엔캐리 청산': {
    key: 'yen',
    art: `${BASE}/yen-art.webp`,
    lockup: `${BASE}/yen-title.webp`,
    position: '55% 45%',
    lockupMax: 720,
  },
  '비상계엄과 탄핵 정국': {
    key: 'martial',
    art: `${BASE}/martial-art.webp`,
    lockup: `${BASE}/martial-title.webp`,
    position: '52% 45%',
    lockupMax: 700,
  },
}

/** 표에 없으면 null — 화면은 글자 제목으로 떨어진다. */
export function seasonArt(title?: string): SeasonArt | null {
  if (!title) return null
  return ART[title.trim()] ?? null
}
