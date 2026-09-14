/* H-03 환경 설정 · /settings
   담당 스토리 [ANT-FE-SETTINGS]
   설계서 docs/화면설계서.md §3 H · §4 H-03 · §9.2

   설계 제약
   - 설정 항목은 notifyVerdict · notifySeason · notifySocial · chartView 4개뿐이다.
     임의 토글을 늘리지 않는다. AI 힌트·난이도 설정은 두지 않는다.
   - M-09 온보딩은 최초 1회만 뜬다. 건너뛴 사용자를 위한 보완 안내를 이 화면에 둔다.

   프로필은 여기 없다 — E-02 내 채널에서 고친다
   닉네임·소개·관심 섹터·외부 채널은 **남에게 보이는 값**이라, 남이 보는 그 화면에서
   고치는 편이 맞다. 설정에 두면 내가 어떻게 보이는지 확인하러 화면을 한 번 더 옮겨야
   했다. 이 화면에는 구독료(E-04)와 알림·차트 뷰, 로그아웃만 남는다.

   한 화면 안에서 탭으로 오간다
   설정 항목이 넷이라 세로로 쌓으면 아래 것이 스크롤 밖으로 나간다 — 무엇이 있는지
   자체를 모른다. B-03 종목 상세와 같은 탭 패턴을 쓴다(role=tablist · 쿼리스트링).
   탭 밖에 남기는 둘이 있다.
   - 온보딩 보완: 지금 해야 할 일을 알리는 자리라 탭 안에 숨기면 안 보인다.
   - 로그아웃: 설정 패널이 아니라 행동이다. 탭 하나를 버튼 한 개로 채우게 된다.

   백엔드 준비 상태
   GET·PUT /users/me/settings 는 아직 없다. 알림 3종과 차트 뷰는 잠긴 카드로 둔다 —
   토글을 그려 두고 동작만 막으면 고장 난 화면으로 읽힌다. */
import { useCallback, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '../auth/context'
import LockedCard from '../components/state/LockedCard'
import ChannelFeeSection from '../components/settings/ChannelFeeSection'
import WalletLinkModal from '../components/wallet/WalletLinkModal'
import '../styles/screens/settings.css'

/* 탭 셋. 첫 탭이 기본이라 쿼리가 붙지 않는다 — 나머지는 ?tab=notify 처럼 실린다. */
const TABS = [
  { key: 'fee', label: '구독료' },
  { key: 'notify', label: '알림' },
  { key: 'chart', label: '트레이딩 뷰' },
] as const

type TabKey = (typeof TABS)[number]['key']

const isTab = (v: string | null): v is TabKey => TABS.some((t) => t.key === v)

/* 명세 §회원 의 설정 4개. 서버가 열리면 이 표가 토글 목록이 된다.
   지금은 무엇이 준비 중인지 알려 주는 데만 쓴다. */
const SETTING_ITEMS = [
  { key: 'notifyVerdict', label: '판정 결과 알림', desc: '내 예측이 판정되면 알려 줍니다' },
  { key: 'notifySeason', label: '시즌 진행 · 보상 알림', desc: '시즌 전환과 보상 지급을 알려 줍니다' },
  { key: 'notifySocial', label: '구독 · 구독료 변경 알림', desc: '구독 갱신과 구독료 변경을 알려 줍니다' },
] as const

export default function Settings() {
  const navigate = useNavigate()
  const { user, signOut } = useAuth()

  // M-09 를 건너뛴 사용자를 위한 보완 진입로(설계 제약)
  const [linking, setLinking] = useState(false)

  /* 탭은 쿼리스트링에 둔다 — 옛 /me/channel 링크가 기본 탭에 그대로 닿고,
     새로고침해도 보던 자리가 유지된다. 모르는 값이면 구독료로 떨어뜨린다. */
  const [params, setParams] = useSearchParams()
  const raw = params.get('tab')
  const tab: TabKey = isTab(raw) ? raw : 'fee'
  const goTab = useCallback((next: TabKey) => {
    const p = new URLSearchParams(params)
    if (next === 'fee') p.delete('tab')
    else p.set('tab', next)
    /* 탭 전환은 히스토리를 쌓지 않는다 — 네 번 오간 뒤 뒤로가기를 네 번 눌러야
       마이페이지로 돌아가면 답답하다(B-03 과 같은 판단). */
    setParams(p, { replace: true })
  }, [params, setParams])

  const current = user?.nickname ?? ''
  const walletLinked = user?.walletLinked ?? false

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>환경 설정</h1>
          <p>구독료와 알림, 로그아웃을 관리합니다</p>
        </div>

        {/* ── 온보딩 보완 ───────────────────────────────
            M-09 는 최초 1회만 뜨고 isNew 는 다시 참이 되지 않는다.
            건너뛴 사용자가 남은 단계를 마칠 수 있는 자리가 여기다. */}
        {(current === '' || !walletLinked) && (
          <section className="st-onboarding">
            <h2>아직 마치지 않은 설정</h2>
            <ul>
              {current === '' && (
                <li>
                  <b>닉네임</b>
                  <span>예측 기록과 랭킹에 표시됩니다.</span>
                  {/* 정하는 자리가 내 채널로 옮겨 갔다 — 여기서 알리기만 하고 보낸다 */}
                  <Link className="st-btn solid" to={`/channels/${user?.id ?? ''}`}>내 채널에서 정하기</Link>
                </li>
              )}
              {!walletLinked && (
                <li>
                  <b>지갑 연동</b>
                  <span>연동해야 예측을 등록하고 토큰을 받을 수 있습니다.</span>
                  <button type="button" className="st-btn solid" onClick={() => setLinking(true)}>
                    지갑 연동하기
                  </button>
                </li>
              )}
            </ul>
          </section>
        )}

        {/* ── 탭 ────────────────────────────────────────
            잠긴 둘(알림·트레이딩 뷰)도 탭으로 남긴다. 빼면 그런 설정이 있다는 것
            자체를 모르게 되고, 서버가 열렸을 때 "없던 게 생겼다" 로 읽힌다.
            대신 무엇이 준비 중인지는 각 패널 안에서 말한다. */}
        <div className="st-tabs" role="tablist" aria-label="환경 설정 항목">
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              role="tab"
              id={`st-tab-${t.key}`}
              aria-selected={tab === t.key}
              aria-controls={`st-panel-${t.key}`}
              className={tab === t.key ? 'is-on' : undefined}
              onClick={() => goTab(t.key)}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div role="tabpanel" id={`st-panel-${tab}`} aria-labelledby={`st-tab-${tab}`} tabIndex={-1}>

        {/* ── 구독료 ─────────────────────────────────
            E-04 [ANT-FE-CHANNEL-FEE] 의 별도 화면(/me/channel)이었는데 여기로 합쳤다(2026-09-07).
        
            닉네임·소개·관심 섹터·외부 채널은 여기 없다 — E-02 내 채널에서 본다.
            그 넷은 **남에게 보이는 값**이라, 남이 보는 그 화면에서 고치는 것이 맞다.
            설정에 두면 내가 어떻게 보이는지 확인하러 화면을 한 번 더 옮겨야 했다.
        
            섹션 안쪽은 ChannelFeeSection 이 맡는다 — 설계 제약 셋(차기 주기 적용 ·
            FEE_CHANGED 확인 단계 · 변경 이력)도 그 파일 머리말에 있다.
            **아직 목업이다.** GET·PUT /me/channel/fee 가 없다(api/channelFee.ts). */}
        {tab === 'fee' && <ChannelFeeSection />}

        {/* ── 알림 ─────────────────────────────────── */}
        {tab === 'notify' && (
        <section className="st-block">
          <h2>알림</h2>
          {/* 기본 문구는 구독 잠금용이라 title 로 갈아끼운다 — 구독으로 풀리는 잠금이 아니다 */}
          <LockedCard label="알림 설정" title="알림 설정은 준비 중입니다">
            <ul className="st-pending">
              {SETTING_ITEMS.map((s) => (
                <li key={s.key}>
                  <b>{s.label}</b>
                  <span>{s.desc}</span>
                </li>
              ))}
            </ul>
          </LockedCard>
        </section>
        )}

        {/* ── 트레이딩 기본 뷰 ──────────────────────── */}
        {tab === 'chart' && (
        <section className="st-block">
          <h2>트레이딩 기본 뷰</h2>
          <LockedCard label="차트 뷰 설정" title="차트 뷰 설정은 준비 중입니다">
            <p className="st-pending-note">
              모의투자 차트를 초급(BEGINNER) 또는 고급(ADVANCED) 중 어느 쪽으로 열지 정합니다.
            </p>
          </LockedCard>
        </section>
        )}

        </div>

        {/* ── 로그아웃 — 탭 밖에 둔다 ──────────────────
            설정 패널이 아니라 행동이다. 탭 하나를 버튼 한 개로 채우게 되고,
            어느 탭에 있든 여기서 나갈 수 있는 편이 낫다. */}
        <section className="st-block">
          <h2>로그아웃</h2>
          <p className="st-note">
            이 브라우저에서 세션을 종료합니다. 다시 이용하려면 로그인해야 합니다.
          </p>
          <button
            type="button" className="st-btn danger"
            onClick={() => { signOut(); navigate('/login', { replace: true }) }}
          >
            로그아웃
          </button>
        </section>

        {linking && (
          <WalletLinkModal
            onClose={() => setLinking(false)}
            /* 연동되면 셸의 walletLinked 가 서고 위 보완 안내가 사라진다 —
               이 화면은 더 이상 /users/me 를 읽지 않아 따로 할 일이 없다 */
            onLinked={() => setLinking(false)}
          />
        )}
      </div>
    </main>
  )
}
