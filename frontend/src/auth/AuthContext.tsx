/* 셸이 쓰는 최소 인증 상태.
   accessToken 은 메모리, refresh 는 httpOnly 쿠키라 여기에 저장하지 않는다.
   새로고침 뒤의 복원은 auth/session.ts 의 restoreSession 이 쿠키로 한다. */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { setAccessToken, setUnauthorizedHandler } from '../api/client'
import SessionExpiredModal from '../components/session/SessionExpiredModal'
import { endSession, restoreSession } from './session'
import { AuthCtx, type AuthState, type AuthUser } from './context'

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [booting, setBooting] = useState(true)
  /** M-08. 재발급까지 실패한 401 하나당 한 번 선다 */
  const [expired, setExpired] = useState(false)

  const navigate = useNavigate()

  /* 새로고침하면 accessToken(메모리)은 사라지고 refresh 쿠키만 남는다.
     쿠키로 세션을 되살린다. 실패하면 비로그인으로 시작한다. */
  useEffect(() => {
    let cancelled = false
    restoreSession()
      .then((restored) => {
        if (cancelled || !restored) return
        setUser(restored)
      })
      .finally(() => { if (!cancelled) setBooting(false) })
    return () => { cancelled = true }
  }, [])

  /* 401 UNAUTHENTICATED 는 API 클라이언트가 전역으로 넘겨준다(M-08).
     재발급까지 실패한 경우만 오고, SIGNER_MISMATCH 같은 다른 401 은 여기 오지 않는다
     — client.ts 가 code 로 먼저 가른다.

     여기서 user 를 바로 비우지 않는다. 비우면 RequireAccess 가 곧장 로그인으로 튕겨
     사용자가 보던 화면과 쓰던 글이 눈앞에서 사라진다(설계 제약: "사용자가 보던 화면과
     입력값을 잃지 않는다"). 창을 띄워 알리고, 비우는 것은 사용자가 "다시 로그인" 을
     눌렀을 때 한다. accessToken 은 client.ts 가 이미 비웠으므로 이 상태로 나가는 요청은
     인증 없이 나가고, 그 401 은 hadToken=false 라 이 자리를 다시 부르지 않는다
     — 창이 여러 번 뜨지 않는 것도 그 덕이다. */
  useEffect(() => {
    setUnauthorizedHandler(() => setExpired(true))
  }, [])

  /** "다시 로그인". 비우고 로그인 화면으로 보낸다.

      **보던 자리로 돌려보내지 않는다**(2026-09-17). 로그인 뒤에는 늘 인사이트 홈이다.
      위 주석의 설계 제약("보던 화면과 입력값을 잃지 않는다")은 **세션이 끊긴 순간**에
      화면을 비우지 않는다는 뜻으로 지킨다 — 창을 띄워 알리고, 비우는 것은 사용자가
      이 버튼을 눌렀을 때다. 그 뒤 어디로 가느냐는 별개이고, 그쪽을 홈으로 정했다. */
  const relogin = useCallback(() => {
    setExpired(false)
    setAccessToken(null)
    setUser(null)
    navigate('/login')
  }, [navigate])

  const value = useMemo<AuthState>(() => ({
    user,
    authed: user !== null,
    booting,
    signIn: (next: AuthUser) => setUser(next),
    signOut: () => {
      /* 서버 응답을 기다리지 않는다 — 화면은 즉시 로그아웃 상태가 되어야 한다.
         access token 정리는 endSession 이 요청을 마친 뒤에 한다(토큰이 있어야 부를 수 있다). */
      void endSession()
      setUser(null)
    },
    setNickname: (nickname: string) =>
      setUser((prev) => (prev ? { ...prev, nickname } : prev)),
    setAvatar: (avatarUrl: string) =>
      setUser((prev) => (prev ? { ...prev, avatarUrl } : prev)),
    setWalletLinked: (linked: boolean) =>
      setUser((prev) => (prev ? { ...prev, walletLinked: linked } : prev)),
  }), [user, booting])

  /* 창은 여기 하나뿐이다. 화면마다 두면 동시에 401 을 받은 요청 수만큼 겹쳐 뜬다
     (설계 제약: "동시에 여러 요청이 401을 받아도 모달은 하나여야 하고"). */
  return (
    <AuthCtx.Provider value={value}>
      {children}
      {expired && (
        <SessionExpiredModal onRelogin={relogin} onDismiss={() => setExpired(false)} />
      )}
    </AuthCtx.Provider>
  )
}
