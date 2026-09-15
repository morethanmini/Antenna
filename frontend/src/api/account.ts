/* 계정 도메인. API 명세서 §회원.

   GET /users/me         { id, nickname, introduce, interests, channels, walletAddress }
   PATCH /users/me       위 넷을 고친다. 네 필드 모두 선택이고, 배열 둘은 전체 교체다
   GET /users/me/badges  { items: [{ badgeCode, seasonId, pinned, createdAt }] } */
import { api } from './client'

export type ChannelPlatform = 'YOUTUBE' | 'X' | 'BLOG' | 'ETC'

export type UserChannel = {
  platform: ChannelPlatform
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
