/* 인증 컨텍스트의 타입과 훅.
   AuthProvider(컴포넌트)와 파일을 나눠 둔다 — 한 파일이 컴포넌트와 훅을 함께
   내보내면 Vite Fast Refresh 가 동작하지 않는다. */
import { createContext, useContext } from 'react'

export type Role = 'USER' | 'ADMIN'

/**
 * 사진을 올리지 않은 사용자의 프로필 이미지.
 *
 * 같은 경로가 네 곳(AuthContext · oauth · session · Login)에 각각 박혀 있던 것을
 * 여기 하나로 모았다(2026-09-14) — 기본 그림을 바꿀 때 한 곳만 고치면 된다.
 *
 * 서버에는 아직 프로필 사진 칸이 없다(users 테이블에 컬럼이 없고 /users/me 도
 * 내려주지 않는다). 그래서 지금은 모두가 이 그림을 쓴다.
 */
export const DEFAULT_AVATAR = '/assets/character/default-avatar.svg'

export type AuthUser = {
  /** 내 채널(E-02)이 /channels/{userId} 라 셸에서 내 채널로 보내려면 id 가 필요하다 */
  id: number
  nickname: string
  /** 프로필 이미지 경로. 실제로는 GET /users/me 가 내려준다 */
  avatarUrl: string
  role: Role
  /** 지갑 연동 여부 — C-01 진입 시 M-01 을 띄울지 판단한다 */
  walletLinked: boolean
}

export type AuthState = {
  user: AuthUser | null
  authed: boolean
  /** 새로고침 직후 쿠키로 세션을 되살리는 중. 이때 가드가 판단하면 로그인 화면으로 잘못 튕긴다. */
  booting: boolean
  signIn: (user: AuthUser) => void
  signOut: () => void
  /** 온보딩에서 닉네임을 확정한 뒤 셸의 표시 이름을 갱신한다. */
  setNickname: (nickname: string) => void
  /** 프로필 사진을 바꾼 뒤 셸의 그림도 맞춘다 — 사이드바가 옛 그림을 들고 있으면 안 저장된 것처럼 보인다 */
  setAvatar: (avatarUrl: string) => void
  /** M-01 연동 성공 뒤 세운다. 이게 없으면 연동하고도 C-01 이 계속 모달을 띄운다. */
  setWalletLinked: (linked: boolean) => void
}

export const AuthCtx = createContext<AuthState | null>(null)

export function useAuth() {
  const ctx = useContext(AuthCtx)
  if (!ctx) throw new Error('useAuth 는 AuthProvider 안에서만 쓸 수 있다')
  return ctx
}
