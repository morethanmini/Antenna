/* E-05 마이페이지 · /me
   담당 스토리 [ANT-FE-MYPAGE]
   설계서 docs/화면설계서.md §3 E · §9.1 · §9.2

   설계 제약
   - 이 화면은 허브다. 스스로 데이터를 많이 만들지 않고 각 화면으로 보낸다.
   - 두지 않는 것: 핸들(@) · 구독자 수 · 작성 카운트 · 유저 레벨 · 신뢰도 점수 · 친구.
     /users/me 응답에 없고 사용자 등급 필드 자체가 없다. 배지와 랭킹 티어뿐이다.

   배지 섹션은 두지 않는다. S15P21A507-230 에서 내렸다가 GET /users/me/badges 가 열려
   되살렸는데(ANT-SEASON-09) 다시 내렸다 — 이 화면은 허브다. 배지는 시즌 성과물이라
   랭킹(G-09)에서 보는 것이 맞고, 여기서는 목록 하나를 더 읽느라 첫 화면만 늦어졌다.
   컴포넌트(components/BadgeList)와 GET /users/me/badges 는 그대로 둔다. */
import { Link } from 'react-router-dom'
import { useApiQuery } from '../api/useApiQuery'
import { platformLabel, type MyProfile } from '../api/account'
import ChannelProfileEdit from '../components/channel/ChannelProfileEdit'
import ProfileAvatar from '../components/channel/ProfileAvatar'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/mypage.css'

/* 허브가 보내는 곳. 목록 화면이 있는 것만 둔다.
   커뮤니티는 개발 범위에서 빠졌다(2026-09-14) — 그 진입점 이야기도 함께 지웠다. */
const LINKS = [
  { to: '/me/predictions', label: '내 예측', desc: '등록한 예측과 판정 결과', icon: 'chart' },
  /* 예측 포트폴리오(C-04)는 이 목록이 아니라 위 프로필 카드의 버튼으로 둔다.
     -230 에서는 사이드바를 정본으로 보고 여기서 뺐는데 2026-09-15 에 반대로 정했다 —
     사이드바는 서비스를 둘러보는 길이고, 포트폴리오는 내 계정 안쪽 화면이다. */
  { to: '/me/subscriptions', label: '내 구독', desc: '구독 중인 채널과 갱신 상태', icon: 'people' },
  { to: '/me/wallet', label: '지갑 · 토큰', desc: 'ANT 잔액과 획득·사용 내역', icon: 'wallet' },
  /* 커밋 원장(D-01)의 임시 진입점이 여기 있었다. 사이드바에 '원장 내역' 이 생겨
     정본이 그쪽으로 갔으므로 뺀다 — 예측 포트폴리오를 뺀 것과 같은 이유다.
     애초에 원장은 개인 기록이 아니라 서비스 전체 기록이라, 내 활동 목록보다
     사이드바가 맞는 자리였다(S15P21A507-181). */
  /* 채널 설정(E-04)을 환경 설정 안으로 합쳤다 — 둘 다 내 계정을 손보는 곳인데
     진입점이 둘로 갈려 있었고 아이콘까지 같아 구분되지 않았다. */
  /* 프로필은 더 이상 설정에 없다 — 내 채널로 옮겼다(S15P21A507-195) */
  { to: '/settings', label: '설정', desc: '구독료와 알림, 로그아웃', icon: 'gear' },
] as const

const ICON: Record<string, React.ReactNode> = {
  chart: <><path d="M3 3v18h18" /><path d="M7 15l4-5 3 3 5-7" /></>,
  people: <><path d="M17 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9.5" cy="7" r="3.5" /><path d="M22 20v-2a4 4 0 0 0-3-3.87" /></>,
  wallet: <><path d="M3 7h15a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /><path d="M3 7V6a2 2 0 0 1 2-2h11" /><circle cx="16" cy="13" r="1.4" /></>,
  gear: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1v.2a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-2.7-1.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7H3.4a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.7 6.3l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 2.7-1.1V2a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.2l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7h.2a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1" /></>,
}

export default function MyPage() {
  const profile = useApiQuery<MyProfile>('/users/me')

  /* 서버가 빈 배열로 내려주므로(ANT-AUTH-05) null 가드는 필요 없다.
     profile.data 자체가 로딩 중 undefined 라 옵셔널 체이닝만 남긴다. */
  const interests = profile.data?.interests ?? []
  const channels = profile.data?.channels ?? []

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>마이페이지</h1>
          <p>내 프로필과 활동을 한곳에서 확인합니다</p>
        </div>

        {/* ── 프로필 요약 ──────────────────────────────── */}
        {profile.error ? (
          <ErrorState error={profile.error} onRetry={profile.reload} />
        ) : (
          <section className="mp-profile">
            {/* 사진은 카드 왼쪽이라 ChannelProfileEdit 밖이다(ProfileAvatar 머리말) */}
            <ProfileAvatar
              src={profile.data?.avatarUrl ?? null}
              isMe={profile.data != null}
              className="mp-avatar"
              onSaved={profile.reload}
            />

            <div className="mp-who">
              {/* 닉네임·소개·관심 섹터·외부 채널은 E-02 채널 머리와 **같은 컴포넌트**가
                  그린다. 값을 눌러 그 항목만 고친다. 두 화면이 같은 값을 각자 그리면
                  한쪽만 고쳐져 표시가 갈린다 — 실제로 그래서 마이페이지에는 편집이
                  없었다(S15P21A507-54). 여기는 늘 내 프로필이라 isMe 를 물을 것이 없다. */}
              {profile.data && (
                <ChannelProfileEdit
                  isMe
                  heading="h2"
                  profile={{
                    nickname: profile.data.nickname,
                    bio: profile.data.introduce,
                    interests,
                    externalLinks: channels.map((c) => ({
                      label: platformLabel(c.platform), url: c.url,
                    })),
                  }}
                  onSaved={profile.reload}
                />
              )}
              {!profile.data && <h2>…</h2>}
            </div>

            <div className="mp-actions">
              {/* 내 채널로 가는 길은 하나다 — 쿼리 없는 관리 화면. 이 카드에서 이미
                  프로필을 고칠 수 있으므로, 여기서 보내는 곳은 예측·리포트까지 함께
                  손보는 자리여야 한다. 남에게 보이는 모습은 그 화면 안의 띠에서 넘어간다.
                  환경 설정에는 프로필이 없다(S15P21A507-195 에서 옮겨 옴). */}
              {profile.data && (
                <Link className="mp-btn solid" to={`/channels/${profile.data.id}`}>
                  내 채널 관리
                </Link>
              )}
              {/* 예측 포트폴리오(C-04)가 사이드바에서 여기로 왔다. 사이드바는 서비스를
                  둘러보는 길이고 포트폴리오는 내 계정 안쪽이다(Layout.tsx 주석).
                  아래 "내 활동" 목록이 아니라 이 자리인 이유는 **성과를 보는 화면**이라서다 —
                  내 채널이 남에게 보이는 성적표라면 포트폴리오는 나만 보는 성적표다. */}
              <Link className="mp-btn ghost" to="/me/portfolio">예측 포트폴리오</Link>
            </div>
          </section>
        )}

        {/* 외부 채널은 프로필 카드 안으로 들어갔다 — 같은 값을 카드와 별도 섹션이
            나눠 그리고 있었고, 고치는 자리는 카드 쪽이다(S15P21A507-54). */}

        {/* ── 활동 진입점 ──────────────────────────────── */}
        <section className="mp-block">
          <h3>내 활동</h3>
          <ul className="mp-links">
            {LINKS.map((l) => (
              <li key={l.to}>
                <Link to={l.to}>
                  <span className="mp-link-ic">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                      {ICON[l.icon]}
                    </svg>
                  </span>
                  <span className="mp-link-text">
                    <b>{l.label}</b>
                    <small>{l.desc}</small>
                  </span>
                  <em className="mp-link-go" aria-hidden="true">›</em>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </main>
  )
}
