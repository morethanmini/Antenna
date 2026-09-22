/* 비속어 선검사 (ANT-COMMUNITY-09) — 서버 common/text/ProfanityFilter.java 의 정규화를 그대로 옮겼다.

   쓰는 곳: 닉네임·소개·채널 라벨·리포트 제목/본문·리포트 댓글·예측 근거·대회 근거 폼.
   제출(지갑 서명) 직전에 불러서 걸리면 요청을 보내지 않고 PROFANITY_MESSAGE 를 보여 준다.

   정규화 순서(소문자 → NFC → 공백/제로폭 제거 → 구분기호 제거 → 단독 자모 제거 → 숫자 제거
   → 3회 이상 반복 축약)를 서버와 똑같이 유지해야 한다. 화면이 통과시킨 글을 서버가 거절하면
   사용자는 "왜 안 되는지" 알 길이 없다. 서버 ProfanityFilterTest 의 크로스 픽스처가 같은 입력·출력을 고정한다.

   ⚠️ UX 용 1차 필터다. API 를 직접 부르는 우회는 서버 @NoProfanity 가 막는다. */
import { ENCODED_BAD_WORDS } from './profanityWordlist'

/** 서버 @NoProfanity 의 message 와 같은 문구 — 우회 요청이 400 으로 돌아와도 같은 말이 뜬다. */
export const PROFANITY_MESSAGE = '사용할 수 없는 표현이 있어요.'

/** base64(UTF-8) → 문자열. atob 만 쓰면 한글이 깨진다. */
function decodeBase64(b64: string): string {
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

/* 정규화에서 지울 문자들. 안 보이는 문자가 소스에 섞이지 않도록 전부 \u 이스케이프(RegExp 문자열)로 적는다.
   서버 ProfanityFilter.isRemovable 과 같은 집합이다. */
const RE_SPACE = new RegExp('[\\s\\u200B-\\u200D\\uFEFF]+', 'g') // 공백 + 제로폭 문자
const RE_SEP = new RegExp('[._*~|/\\u005C\\u2010-\\u2015\\u00B7\\u2022\\u2027\\u30FB\\u30FC-]+', 'g') // 구분기호·점·각종 대시·역슬래시·하이픈
const RE_JAMO = new RegExp('[\\u1100-\\u11FF\\u3130-\\u318F\\uA960-\\uA97F\\uD7B0-\\uD7FF]+', 'g') // 음절 사이 낀 단독 한글 자모(우회용)
const RE_DIGIT = new RegExp('[0-9\\uFF10-\\uFF19]+', 'g') // 음절 사이 낀 숫자(반각+전각, 우회용)
const RE_REPEAT = /(.)\1{2,}/g // 같은 문자 3회 이상 반복 → 1회

/**
 * 비교용 정규화 — 대소문자·공백·구분기호·삽입 자모·삽입 숫자·과장 반복으로 하는 단순 우회를 흡수한다.
 * 예) "A B C" → "abc", "a-b-c" → "abc", "가ㅡ나" → "가나", "가1나" → "가나", "haaappy" → "happy"
 */
export function normalizeForProfanity(text: string): string {
  return text
    .toLowerCase()
    .normalize('NFC')
    .replace(RE_SPACE, '')
    .replace(RE_SEP, '')
    .replace(RE_JAMO, '')
    .replace(RE_DIGIT, '')
    .replace(RE_REPEAT, '$1')
}

/* 정규화 결과가 1글자로 "줄어든" 항목은 정상 문장을 마구 잡으므로 버린다(서버와 같은 안전망).
   원문부터 1글자인 항목은 큐레이터 의도로 보고 유지. 긴 단어를 앞에 두는 건 서버와 맞추려는 것뿐 — 판정엔 영향 없다. */
const NORMALIZED_WORDS: readonly string[] = ENCODED_BAD_WORDS
  .map((w) => decodeBase64(w))
  .filter((raw) => raw.length === 1 || normalizeForProfanity(raw).length >= 2)
  .map((raw) => normalizeForProfanity(raw))
  .filter(Boolean)
  .sort((a, b) => b.length - a.length)

/** 텍스트에 사전 속 비속어가 포함되면 true. 빈 값은 false — 필수 여부는 호출부 몫. */
export function containsProfanity(text: string | null | undefined): boolean {
  if (!text) return false
  const normalized = normalizeForProfanity(text)
  return NORMALIZED_WORDS.some((word) => normalized.includes(word))
}

/** 걸리면 안내 문구, 아니면 null — setError(profanityError(x)) 꼴로 바로 쓰려고 둔다. */
export function profanityError(text: string | null | undefined): string | null {
  return containsProfanity(text) ? PROFANITY_MESSAGE : null
}
