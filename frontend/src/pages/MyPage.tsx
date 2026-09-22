/* E-05 마이페이지 · /me
   담당 스토리 [ANT-FE-MYPAGE] · S15P21A507-290
   설계서 docs/화면설계서.md §3 E · §9.1 · §9.2

   ── 레퍼런스: solved.ac 뼈대 + op.gg 순위 표기 ────────────────
   실측·선정 기준은 backend/docs/마이페이지 레퍼런스/실측.md 에 있다.

   1차 시안(Kaggle 뼈대)은 폐기했다. 공개 프로필 장르를 내 계정 화면에 들이댄 것이
   원인이었고, 그 결과 "통계를 읽어 주는 프로필" 이 되어 원래 화면의 일(허브)과 겹쳤다.

   이번에 지키는 것 셋 —
   1. **머리가 짧고 본문이 길다.** 레퍼런스 셋 다 그랬고 1차 시안만 반대였다.
   2. **숫자를 키우지 않는다.** op.gg 티어 20px · 나머지 12~14px, solved.ac 통계 14px.
      한국 성적표 화면은 크기가 아니라 색·진행 바·밀도로 성적을 말한다.
      (1차에서 34px 로 키웠던 것은 본문 16px 인 서양 레퍼런스에서 온 오역이다.)
   3. **진입점을 타일이 아니라 알약 한 줄로.** /reports 에서 쓰는 알약 어휘를 그대로 쓴다.
      타일 넷 + 버튼 둘이 한 줄로 합쳐져 화면이 짧아지고, 두 화면이 한 제품으로 읽힌다.

   ── 왜 네 군데를 따로 그리는가 ──────────────────────────
   조회 넷이 각자 도착한다. 하나라도 늦으면 첫 화면이 통째로 늦어지는 것을 막으려고
   섹션마다 자기 응답만 본다. 배지를 한 번 내렸던 이유가 이것이었다(S15P21A507-230).

   ── 두지 않는 것 ────────────────────────────────────
   가입일 · 구독자 수 · 팔로워 수 · 내 구독 개수 · 내 리포트 수 · 활동 타임라인.
   앞의 셋은 서버에 값이 없고, 개수 둘은 목록이 커서 페이징이라 전량을 읽어야 나온다.
   티어(다이아·플래티넘·골드)도 두지 않는다 — GET /rankings/me 의 tier 는 리플레이
   트랙에서만 채워진다. 이 화면은 실전이므로 항상 null 이다. */
import { Link } from 'react-router-dom'
import { useSharedHref } from '../sharedMode'
import { useApiQuery } from '../api/useApiQuery'
import { type BadgeList as BadgeListResponse, type MyProfile } from '../api/account'
import { type Portfolio } from '../api/predictions'
import { type MyRank } from '../api/rankings'
import { useWalletBalance } from '../api/walletBalance'
import BadgeList from '../components/BadgeList'
import ChannelProfileEdit from '../components/channel/ChannelProfileEdit'
import ProfileAvatar from '../components/channel/ProfileAvatar'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/mypage.css'

/* 허브가 보내는 곳. 목록 화면이 있는 것만 둔다.
   커뮤니티는 개발 범위에서 빠졌다(2026-09-14) — 그 진입점 이야기도 함께 지웠다.
   전에는 타일 넷(내 활동)과 버튼 둘(내 채널·포트폴리오)로 갈려 있었는데, 둘 다
   "여기서 나가는 길" 이라 한 줄로 합쳤다. 순서는 자주 쓰는 것부터다. */
type HubLink = {
  to: string
  label: string
  icon: string
  /** 두 모드가 같이 쓰는 화면. 모의투자에서 온 마이페이지라면 그 모드를 주소에 이어
      보낸다(sharedMode.ts) — 안 이으면 여기까지는 모의투자였다가 다음 화면에서
      인사이트로 넘어간다. 나머지는 인사이트 전용 화면이라 붙일 것이 없다. */
  shared?: true
}

const LINKS: HubLink[] = [
  { to: '/me/predictions', label: '내 예측', icon: 'chart' },
  { to: '/me/portfolio', label: '예측 포트폴리오', icon: 'target' },
  { to: '/me/subscriptions', label: '내 구독', icon: 'people' },
  { to: '/me/wallet', label: '지갑 · 토큰', icon: 'wallet', shared: true },
  /* 커밋 원장(D-01)의 임시 진입점이 여기 있었다. 사이드바에 '원장 내역' 이 생겨
     정본이 그쪽으로 갔으므로 뺀다. 애초에 원장은 개인 기록이 아니라 서비스 전체
     기록이라, 내 활동 목록보다 사이드바가 맞는 자리였다(S15P21A507-181). */
  /* 채널 설정(E-04)을 환경 설정 안으로 합쳤다 — 둘 다 내 계정을 손보는 곳인데
     진입점이 둘로 갈려 있었고 아이콘까지 같아 구분되지 않았다. */
  { to: '/settings', label: '설정', icon: 'gear', shared: true },
]

const ICON: Record<string, React.ReactNode> = {
  chart: <><path d="M3 3v18h18" /><path d="M7 15l4-5 3 3 5-7" /></>,
  people: <><path d="M17 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9.5" cy="7" r="3.5" /><path d="M22 20v-2a4 4 0 0 0-3-3.87" /></>,
  wallet: <><path d="M3 7h15a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /><path d="M3 7V6a2 2 0 0 1 2-2h11" /><circle cx="16" cy="13" r="1.4" /></>,
  gear: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1v.2a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-2.7-1.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0-1.1-2.7H3.4a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.7 6.3l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 2.7-1.1V2a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.2l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7h.2a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1" /></>,
  target: <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="5" /><circle cx="12" cy="12" r="1.2" /></>,
  ruler: <><path d="M3 15.5 15.5 3 21 8.5 8.5 21z" /><path d="M7.5 11 9 12.5M10.5 8 12 9.5M13.5 5 15 6.5" /></>,
  cube: <><path d="M12 2.8 20 7v10l-8 4.2L4 17V7z" /><path d="M4 7l8 4.2L20 7M12 11.2V21" /></>,
}

function Icon({ name, size = 16 }: { name: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      {ICON[name]}
    </svg>
  )
}

/**
 * 아직 오지 않은 값과 **없는 값**을 다르게 그린다.
 *
 * 판정이 0건이면 서버가 적중률·평균 오차를 null 로 준다(Portfolio 타입 주석). 그걸 0 으로
 * 그리면 "오차 0%" 라는 거짓말이 되므로 "—" 다. 반면 응답이 아직 안 온 것은 모르는 상태라
 * "…" 로 둔다 — 둘을 같은 기호로 쓰면 판정이 없는 계정이 영영 로딩 중처럼 보인다.
 */
function cell(loading: boolean, value: number | string | null | undefined, suffix = '') {
  if (loading) return '…'
  if (value === null || value === undefined) return '—'
  return `${typeof value === 'number' ? value.toLocaleString() : value}${suffix}`
}

/** solved.ac 의 통계 줄 — 숫자는 진하게, 단위·이름표는 흐리게, **같은 줄에 붙여** 쓴다 */
function Stat({ value, label }: { value: string; label: string }) {
  return (
    <li><b>{value}</b>{label}</li>
  )
}

export default function MyPage() {
  const href = useSharedHref()
  const profile = useApiQuery<MyProfile>('/users/me')
  /* 랭킹은 실전(REAL) 고정이다. 리플레이 순위는 모의투자 안에서 보는 값이고,
     여기에 트랙 탭을 두면 이 화면이 랭킹 화면(G-09)을 반쯤 베끼게 된다.
     랭킹에 없으면 204 라 data 가 undefined 로 온다 — 404 가 아니다(명세 v0.28). */
  const rank = useApiQuery<MyRank>('/rankings/me', { track: 'REAL' })
  const port = useApiQuery<Portfolio>('/predictions/me/portfolio')
  const wallet = useWalletBalance()
  const badges = useApiQuery<BadgeListResponse>('/users/me/badges')

  /* 서버가 빈 배열로 내려주므로(ANT-AUTH-05) null 가드는 필요 없다.
     profile.data 자체가 로딩 중 undefined 라 옵셔널 체이닝만 남긴다. */
  const interests = profile.data?.interests ?? []
  const channels = profile.data?.channels ?? []

  const p = port.data
  /* 판정된 건수. 적중률의 분모와 같은 수다 */
  const judged = p ? p.hit + p.miss : null

  return (
    <main className="main">
      <div className="main-inner">
        {/* ── 머리 ───────────────────────────────────────
            solved.ac 처럼 배너 띠가 화면의 머리다. 공용 .page-head("마이페이지" h1)를
            두지 않는 이유 — 이 화면의 제목은 내 닉네임이다. 제목을 하나 더 얹으면
            배너가 장식으로 밀리고, 레퍼런스 셋 중 어느 것도 그렇게 하지 않는다.
            그래서 ChannelProfileEdit 에 heading="h1" 을 준다(E-02 채널과 같다). */}
        {profile.error ? (
          <ErrorState error={profile.error} onRetry={profile.reload} />
        ) : (
          <section className="mp-hero">
            <div className="mp-banner" aria-hidden="true" />

            <div className="mp-hero-body">
              {/* 사진은 배너 경계에 걸터앉는다 — solved.ac·Letterboxd 가 같은 장치를 쓴다.
                  ChannelProfileEdit 밖인 이유는 ProfileAvatar 머리말에 있다. */}
              <ProfileAvatar
                src={profile.data?.avatarUrl ?? null}
                isMe={profile.data != null}
                className="mp-avatar"
                onSaved={profile.reload}
              />

              <div className="mp-who">
                {/* 닉네임·소개·관심 섹터·외부 채널은 E-02 채널 머리와 **같은 컴포넌트**가
                    그린다. 두 화면이 같은 값을 각자 그리면 한쪽만 고쳐져 표시가 갈린다
                    (S15P21A507-54). 넷을 한 덩어리로 들고 있으므로 일부만 떼어 내지 않는다 —
                    그래서 순위 줄이 이름 바로 아래가 아니라 이 덩어리 아래에 온다. */}
                {profile.data && (
                  <ChannelProfileEdit
                    isMe
                    heading="h1"
                    clickToEdit
                    profile={{
                      nickname: profile.data.nickname,
                      bio: profile.data.introduce,
                      interests,
                      externalLinks: channels,
                    }}
                    onSaved={profile.reload}
                  />
                )}
                {!profile.data && <h1>…</h1>}
              </div>

              {/* ── 성적 ───────────────────────────────────
                  신원과 같은 세로줄에 쌓아 두었더니 닉네임·소개·섹터·채널·순위·
                  적중률·통계가 일곱 줄로 붙어 벽처럼 읽혔다. 히어로 폭은 남는데
                  세로로만 쌓은 탓이다. 그래서 성적은 자기 칸에 자기 판을 갖는다 —
                  줄바꿈이 일어나도(좁은 화면) 덩어리가 유지된다.

                  ── 순위 줄 (op.gg) ─────────────────────
                    op.gg 는 순위와 백분위를 이름 옆 한 줄에 붙여 쓴다 —
                    "Ladder Rank 134 (0.0044% of top)". 칸을 따로 만들지 않는다.
                    분모는 못 쓴다: GET /rankings/me 가 percentile 만 주고, 명세가
                    "전체 예측가 중" 으로 쓰면 어긋난다고 못 박아 뒀다(판정 3건
                    미만은 랭킹에서 빠져 분모에 없다). 그래서 "상위 N%" 로만 쓴다. */}
              <div className="mp-record">
                <p className="mp-rank">
                  {rank.loading && '실전 랭킹 …'}
                  {!rank.loading && rank.data && (
                    <>
                      <em>실전 {rank.data.rank.toLocaleString()}위</em>
                      <span>상위 {rank.data.percentile.toFixed(1)}%</span>
                      {rank.data.delta !== 0 && (
                        <b className={rank.data.delta > 0 ? 'up' : 'down'}>
                          {rank.data.delta > 0 ? '▲' : '▼'} {Math.abs(rank.data.delta)}
                        </b>
                      )}
                    </>
                  )}
                  {!rank.loading && !rank.data && (
                    <span>판정 3건이 쌓이면 랭킹에 오릅니다</span>
                  )}
                </p>

                {/* solved.ac 의 티어 진행 바. 우리는 다음 등급이라는 개념이 없으므로
                    적중률 자체를 길이로 쓴다 — 숫자 하나가 형태가 되면 크게 쓰지 않아도
                    먼저 읽힌다(레퍼런스 셋 다 성적을 크기로 말하지 않는다). */}
                <div className="mp-gauge">
                  <div className="mp-gauge-head">
                    <b>적중률 {cell(port.loading, p?.hitRate?.toFixed(1), '%')}</b>
                    <small>{judged === null ? '판정 대기' : `판정 ${judged.toLocaleString()}건`}</small>
                  </div>
                  <div className="mp-gauge-rail">
                    <span style={{ width: `${p?.hitRate ?? 0}%` }} />
                  </div>
                </div>

                {/* solved.ac 의 "4,039문제 해결 1,591문제에 기여" 줄 */}
                <ul className="mp-stats">
                  <Stat value={cell(port.loading, p?.total)} label="건 예측" />
                  <Stat value={cell(port.loading, p?.pending)} label="건 판정 대기" />
                  <Stat value={cell(port.loading, p?.anchored)} label="건 체인 기록" />
                  <Stat value={cell(wallet.loading, wallet.data ? Number(wallet.data.balance) : null)} label=" ANT" />
                </ul>
              </div>
            </div>
          </section>
        )}

        {/* ── 나가는 길 ──────────────────────────────────
            타일 넷 + 버튼 둘이 알약 한 줄로 합쳐졌다. /reports 의 탭과 같은 어휘다.
            탭처럼 보이게 만들지 않은 이유 — 이것들은 다른 화면으로 **나가는** 링크지
            이 화면의 내용을 바꾸는 탭이 아니다. 모양이 동작을 속이면 안 된다. */}
        <nav className="mp-nav" aria-label="내 계정 바로 가기">
          {profile.data && (
            <Link className="mp-pill" to={`/channels/${profile.data.id}`}>
              <Icon name="people" />내 채널 관리
            </Link>
          )}
          {LINKS.map((l) => (
            <Link key={l.to} className="mp-pill" to={l.shared ? href(l.to) : l.to}>
              <Icon name={l.icon} />{l.label}
            </Link>
          ))}
        </nav>

        {/* ── 본문 ───────────────────────────────────── */}
        <section className="mp-block">
          <h2>예측 성적표</h2>
          <ul className="mp-score">
            <li className="mp-score-card">
              <div className="mp-score-head"><b>적중</b><Icon name="target" /></div>
              <strong>
                {cell(port.loading, p?.hit)}
                {judged !== null && <i>/ {judged.toLocaleString()}</i>}
              </strong>
              <small>{judged === null ? '판정 결과를 기다리는 중' : '판정된 예측 중'}</small>
            </li>
            <li className="mp-score-card">
              <div className="mp-score-head"><b>평균 오차</b><Icon name="ruler" /></div>
              <strong>{cell(port.loading, p?.avgError?.toFixed(1), '%')}</strong>
              <small>판정된 예측 기준</small>
            </li>
            {/* 어느 레퍼런스에도 없는 칸이다 — 예측을 체인에 박아 두는 서비스라서 생긴다.
                그래서 셋 중 이 카드만 브랜드색으로 채운다. */}
            <li className="mp-score-card is-chain">
              <div className="mp-score-head"><b>체인 기록</b><Icon name="cube" /></div>
              <strong>
                {cell(port.loading, p?.anchored)}
                {p && <i>/ {p.total.toLocaleString()}</i>}
              </strong>
              <small>전체 예측 중</small>
            </li>
          </ul>
        </section>

        {/* 배지가 돌아왔다. 한 번 내렸던 이유는 첫 화면이 밀려서였는데(S15P21A507-230),
            이제 이 자리는 자기 응답만 보고 그리므로 늦게 와도 위쪽을 잡지 않는다. */}
        <section className="mp-block">
          <h2>배지</h2>
          {badges.loading
            ? <p className="badges-empty">…</p>
            : <BadgeList badges={badges.data?.items ?? []} />}
        </section>
      </div>
    </main>
  )
}
