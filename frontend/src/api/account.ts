/* 계정 도메인. API 명세서 §회원.

   GET /users/me         { id, nickname, introduce, interests, channels, walletAddress }
   PATCH /users/me       위 넷을 고친다. 네 필드 모두 선택이고, 배열 둘은 전체 교체다
   GET /users/me/badges  { items: [{ badgeCode, seasonId, pinned, createdAt }] } */
import { api } from './client'

export type ChannelPlatform = 'YOUTUBE' | 'X' | 'BLOG' | 'ETC'

export type UserChannel = {
  /** 화면 아이콘을 정한다. 표에 없는 곳은 ETC 로 담고 이름을 label 로 붙인다 */
  platform: ChannelPlatform
  /**
   * 사용자가 붙인 이름. 없으면 플랫폼 기본 이름을 쓴다(channelLabel).
   * MeResponse 에 @JsonInclude 가 없어 **null 로 실려 온다** — 옵셔널이 아니다.
   */
  label: string | null
  url: string
}

export type MyProfile = {
  id: number
  nickname: string
  introduce: string | null
  /** 프로필 사진 주소. 안 올렸으면 null — 화면이 DEFAULT_AVATAR 를 쓴다 */
  avatarUrl: string | null
  /* 서버가 내려준다(ANT-AUTH-05). 없으면 **빈 배열**이라 옵셔널이 아니다 —
     null 을 내리지 않기로 서버와 맞췄다(MeResponse 머리말). */
  /** 관심 섹터. 배열 전체 교체 방식이라 부분 수정이 없다 */
  interests: string[]
  channels: UserChannel[]
  /** 미연동이면 null. 연동 상태는 GET /wallet 이 정본이다 */
  walletAddress: string | null
}

/* 배지는 커서 페이징이 아니다 — { items } 로만 감싸 온다.
   조건 달성 시 서버가 부여하며, 획득분만 내려온다(미획득 목록은 없다). */
export type BadgeList = {
  items: Badge[]
}

export type Badge = {
  badgeCode: string
  /** 시즌에서 딴 배지면 채워진다 */
  seasonId: number | null
  /** 전시 pin 여부 */
  pinned: boolean
  createdAt: string
}

/* badgeCode 는 서버가 늘려 가는 값이다. 표에 없는 코드가 와도 화면이 비지 않도록
   라벨을 못 찾으면 코드를 그대로 보여 준다. */
const BADGE_LABEL: Record<string, string> = {
  CRISIS_SURVIVOR: '위기 극복',
  LONG_TERM_INVESTOR: '장기 투자자',
  PROFIT_ACHIEVED: '수익 달성',
  WINNING_STREAK: '연승',
}

export function badgeLabel(code: string) {
  return BADGE_LABEL[code] ?? code
}

export const PLATFORM_LABEL: Record<ChannelPlatform, string> = {
  YOUTUBE: '유튜브',
  X: 'X',
  BLOG: '블로그',
  ETC: '기타',
}

export function platformLabel(platform: string) {
  return PLATFORM_LABEL[platform as ChannelPlatform] ?? platform
}

/**
 * 화면에 보일 채널 이름.
 *
 * 사용자가 붙인 이름이 먼저다 — 표에 없는 곳(브런치·디스코드 …)을 ETC 로만 담으면
 * 프로필에 "기타" 가 여러 개 뜬다. 아이콘은 platform 이 정하고 이름은 여기서 정한다.
 */
export function channelLabel(channel: { platform: string; label?: string | null }) {
  return channel.label?.trim() || platformLabel(channel.platform)
}

/* 주소의 호스트로 플랫폼을 되짚는 표. 서브도메인은 끝만 맞으면 된다(www.youtube.com,
   m.blog.naver.com). 브런치·디스코드처럼 표에 없는 곳은 ETC 로 두고 이름을 직접 붙인다. */
const PLATFORM_HOSTS: [ChannelPlatform, string[]][] = [
  ['YOUTUBE', ['youtube.com', 'youtu.be']],
  ['X', ['x.com', 'twitter.com']],
  ['BLOG', ['blog.naver.com', 'tistory.com', 'velog.io', 'medium.com', 'blogspot.com', 'wordpress.com']],
]

/**
 * 스킴이 없는 주소에 https:// 를 붙인다. "youtube.com/@ant" 처럼 적는 사람이 많고, 서버는
 * https 만 받는다(400). 붙여 주면 될 것을 "https 로 시작해야 합니다" 로 돌려보내지 않는다.
 *
 * 이미 스킴이 있으면(http:// 포함) 손대지 않는다 — http 를 몰래 https 로 바꾸면 그 주소로
 * 정말 열리는지 우리가 보증하는 꼴이다. 그건 저장 때 오류로 알린다.
 */
export function withScheme(url: string) {
  const trimmed = url.trim()
  if (trimmed === '' || /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)) return trimmed
  return `https://${trimmed}`
}

/**
 * 주소에서 플랫폼을 되짚는다. 표에 없는 호스트거나 아직 주소가 아니면 null 이다.
 *
 * 편집 폼이 주소를 칠 때마다 부른다 — 유튜브 링크를 붙여 넣었는데 플랫폼을 따로 고르게
 * 하면 그걸 빼먹고 저장해 "직접 입력" 아이콘에 유튜브 주소가 붙는다(2026-09-22).
 * 스킴 없이 치는 중에도 잡히도록 withScheme 을 거친다.
 */
export function detectPlatform(url: string): ChannelPlatform | null {
  let host: string
  try {
    host = new URL(withScheme(url)).hostname.toLowerCase()
  } catch {
    return null
  }
  for (const [platform, hosts] of PLATFORM_HOSTS) {
    if (hosts.some((h) => host === h || host.endsWith(`.${h}`))) return platform
  }
  return null
}

/**
 * 프로필 수정. **보내는 필드만 바뀐다** — 온보딩은 닉네임만, 소개만 고치는 자리는 소개만 보낸다.
 *
 * 배열 둘은 보냈으면 **전체 교체**이고 빈 배열은 비우겠다는 뜻이다(명세 §회원). 그래서 한 항목만
 * 지우려 해도 목록 전체를 실어 보낸다 — 화면이 목록을 들고 있으므로 그게 더 단순하다.
 *
 * 닉네임이 남의 것이면 409(DUPLICATE_NICKNAME) 이고 **같은 요청의 다른 필드도 저장되지 않는다.**
 * 서버가 넷을 한 트랜잭션에 묶었다 — 화면은 한 번의 저장으로 받아들이기 때문이다.
 */
export function updateProfile(patch: ProfileUpdate) {
  return api.patch<MyProfile>('/users/me', patch)
}

export type ProfileUpdate = {
  nickname?: string
  /** 200자까지. 빈 문자열을 보내면 지운 것으로 본다 */
  introduce?: string
  /**
   * POST /uploads 가 준 fileId(purpose=PROFILE). 빈 문자열이면 지운다.
   * 남이 올린 파일이거나 용도가 다르면 400 이다 — 서버가 소유자와 용도를 본다.
   */
  avatarFileId?: string
  interests?: string[]
  /** url 은 https 만 받는다. 아니면 400(피싱 차단) */
  channels?: UserChannel[]
}
