/* A-01 로그인 · /login
   담당 스토리 [ANT-FE-LOGIN]

   이 스토리가 채운 것은 화면 디자인이다.
   OAuth 는 auth/oauth.ts 에 만들어 둔 것을 그대로 부른다 —
   인가 요청·콜백 교환·토큰 저장 모두 그쪽 몫이라 여기서 다시 만들지 않는다.

   SSAFY 버튼은 자격증명이 env 에 들어왔을 때만 열린다([ANT-AUTH-02]).
   값이 없으면 부를 곳이 없어 그냥 실패하므로 잠근 채로 둔다.

   회원가입 화면은 없다 — 설계서 §2 상 최초 로그인 시 회원이 자동 생성되므로
   SSO 버튼이 로그인과 가입을 겸한다. */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { AUTH_NOTICE_KEY } from '../api/client'
import { isConfigured, startLogin } from '../auth/oauth'
import '../styles/auth.css'

const BrandMark = () => (
  <svg className="mark" viewBox="0 0 40 40" fill="none" aria-hidden="true">
    <rect width="40" height="40" rx="11" fill="currentColor" />
    <path d="M11.5 29.5 20 11.5l8.5 18" stroke="#fff" strokeWidth="4.2" strokeLinecap="round" strokeLinejoin="round" />
    <path d="M15.8 24.2h8.4" stroke="#fff" strokeWidth="4.2" strokeLinecap="round" />
  </svg>
)

const FEATURES = [
  {
    title: 'AI 리서치',
    icon: (
      <>
        <path d="M12 5a2.5 2.5 0 0 0-5 0 2.5 2.5 0 0 0-2 4 2.5 2.5 0 0 0 1 4.6A2.5 2.5 0 0 0 9 19a2.5 2.5 0 0 0 3-1.5z" />
        <path d="M12 5a2.5 2.5 0 0 1 5 0 2.5 2.5 0 0 1 2 4 2.5 2.5 0 0 1-1 4.6A2.5 2.5 0 0 1 15 19a2.5 2.5 0 0 1-3-1.5z" />
        <path d="M12 5v14" />
      </>
    ),
  },
  {
    title: '블록체인 검증',
    icon: (
      <>
        <path d="M12 3 4.5 6v6c0 4.5 3.2 7.9 7.5 9 4.3-1.1 7.5-4.5 7.5-9V6z" />
        <path d="m9 12 2.2 2.2L15.5 10" />
      </>
    ),
  },
  {
    title: '예측 포트폴리오',
    icon: (
      <>
        <path d="M3 20h18" /><path d="M6 20v-6M10.5 20v-9M15 20v-4" />
        <path d="m13 8 4-4M17 4h-3.4M17 4v3.4" /><path d="M19.5 20V8" />
      </>
    ),
  },
]

/** 앞부분을 이만큼 건너뛴다(초). 첫 프레임 몇 장이 나머지보다 밝아 되감길 때마다
 *  한 번씩 튄다. 재생을 시작할 때와 되감을 때 **같은 값**을 써야 앞뒤가 맞는다. */
const CLIP_START = 0.1

export default function Login() {
  /* 자격증명이 env 에 들어왔는지로 버튼을 연다. 빌드 시점에 정해지는 값이라
     렌더마다 다시 계산할 이유가 없다. */
  const ssafyReady = isConfigured('ssafy')
  /* 차단돼서 튕긴 사람에게만 한 줄. 한 번 읽고 지운다 — 새로고침하면 사라져도 된다 */
  const [notice] = useState<string | null>(() => {
    try {
      const v = sessionStorage.getItem(AUTH_NOTICE_KEY)
      sessionStorage.removeItem(AUTH_NOTICE_KEY)
      return v
    } catch { return null }
  })

  return (
    <div className="auth">
      <main className="auth-shell">
        <section className="auth-intro" aria-label="서비스 소개">
          <div className="intro-copy">
            <Link className="auth-brand" to="/">
              <BrandMark />
              <span className="name">ANTENNA</span>
            </Link>
            <h1>근거를 보고 예측하는<br />AI 투자 플랫폼</h1>
            <p className="lead">AI 리서치와 블록체인 검증, 예측 포트폴리오로<br />더 똑똑한 투자 결정을 경험하세요.</p>

            <ul className="feats">
              {FEATURES.map((f) => (
                <li className="feat" key={f.title}>
                  <span className="feat-ic">
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                         strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                      {f.icon}
                    </svg>
                  </span>
                  <h3>{f.title}</h3>
                </li>
              ))}
            </ul>
          </div>

          {/* 제자리 뛰는 개미(2026-09-18). 소리 없는 6.6초짜리 반복 영상이다.

              **알파가 없다.** H.264(avc1) 라 투명 채널을 못 싣고, 바탕이 #f7f6fb 로
              칠해져 온다. 로그인 화면 그러데이션(#fcfcff~#eff1fa)과 거의 같은 색이라
              mix-blend-mode 로 녹인다 — 안 녹이면 개미 둘레에 옅은 네모가 보인다.

              autoPlay 는 muted 와 playsInline 이 **둘 다** 있어야 모바일에서도 돈다.
              둘 중 하나라도 빠지면 iOS 가 재생을 막고 첫 프레임에서 멈춘다.

              poster 는 **이 영상에서 뽑은 프레임**이다(0.25초, 504×737, 43KB).
              캐릭터 PNG 를 쓰면 9.4MB 를 받는 동안 다른 포즈·다른 조명의 그림이 먼저
              떠 있다가 영상이 시작되는 순간 튄다 — "앞부분만 밝기가 다르다" 로 읽히던
              것이 이것이다. 같은 프레임을 쓰면 영상이 늦게 와도 화면이 안 바뀐다.

              아래 img 는 움직임을 줄인 사람에게 보여 줄 정지 그림이다(CSS 가 고른다). */}
          <figure className="intro-art">
            <video
              poster="/assets/character/white_ant/running-in-place-poster.jpg"
              autoPlay
              muted
              playsInline
              aria-hidden="true"
              /* 앞부분을 건너뛰는 자리. 주소에 #t=0.1 을 붙이는 방법도 있지만 source
                 안에서는 브라우저가 늘 지키지 않아(크롬 실측: 첫 재생이 0 에서 시작)
                 여기서 직접 맞춘다. 길이를 아는 시점이 metadata 다. */
              onLoadedMetadata={(e) => { e.currentTarget.currentTime = CLIP_START }}
              /* loop 속성을 안 쓴다. 되감길 때 브라우저는 **0 으로** 돌아가서
                 (크롬 실측) 잘라낸 앞부분이 매 바퀴 다시 나온다. 직접 되감아야 한다. */
              onEnded={(e) => {
                const v = e.currentTarget
                v.currentTime = CLIP_START
                v.play().catch(() => { /* 탭이 숨으면 브라우저가 막는다 — 다시 보이면 알아서 돈다 */ })
              }}
            >
              {/* src 를 video 에 직접 달지 않고 source 로 내린 이유 — **media 가 여기서만
                  먹는다.** 1180px 아래에서는 이 그림 칸이 통째로 숨는데(auth.css),
                  display:none 은 내려받기를 막지 못한다. 실제로 420px 에서 보이지도
                  않는 8.7MB 를 받고 있었다. 맞는 source 가 없으면 아무것도 받지 않는다.

                  이 조건은 **불러올 때 한 번만** 재는 값이다. 창을 좁혔다 넓히면 그
                  세션에서는 영상이 안 뜨고 poster 만 남는데, 새로고침이면 돌아온다. */}
              <source
                src="/assets/character/white_ant/running-in-place.mp4"
                type="video/mp4"
                media="(min-width: 1181px)"
              />
            </video>
            <img src="/assets/character/white_ant/antenna-character-transparent.png" alt="" aria-hidden="true" />
          </figure>
        </section>

        <section className="sso" aria-label="로그인">
          <h2>로그인</h2>
          <p className="sub">계정을 선택하여 간편하게 로그인하세요.</p>
          {notice === 'ACCOUNT_BANNED' && (
            <p className="sso-note is-error" role="alert">
              이용이 제한된 계정입니다. 다시 로그인해도 풀리지 않습니다 — 문의는 운영자에게 해 주세요.
            </p>
          )}

          {/* 구글로 나갔다가 /oauth/callback/google 로 전체 페이지 로드로 돌아온다.
              돌아온 뒤에는 늘 인사이트 홈으로 보낸다(2026-09-17). */}
          <button type="button" className="sso-btn google" onClick={() => startLogin('google')}>
            <svg width="24" height="24" viewBox="0 0 48 48" aria-hidden="true">
              <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
              <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
              <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
              <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
            </svg>
            Google로 로그인
          </button>

          {/* 개발자센터 자격증명이 env 에 들어와야 열린다([ANT-AUTH-02]).
              값이 없는 채로 누르면 부를 곳이 없어 그냥 실패하므로 잠가 둔다. */}
          <button
            type="button"
            className="sso-btn ssafy"
            onClick={() => startLogin('ssafy')}
            disabled={!ssafyReady}
            aria-describedby={ssafyReady ? undefined : 'ssafy-note'}
          >
            <svg width="26" height="26" viewBox="0 0 40 40" fill="none" aria-hidden="true">
              <path d="M11.5 29.5 20 11.5l8.5 18" stroke="currentColor" strokeWidth="4.2" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M15.8 24.2h8.4" stroke="currentColor" strokeWidth="4.2" strokeLinecap="round" />
            </svg>
            SSAFY로 로그인
          </button>
          {!ssafyReady && (
            <p className="sso-note" id="ssafy-note">SSAFY 로그인은 준비 중입니다.</p>
          )}
        </section>
      </main>
    </div>
  )
}
