/* E-02 채널 프로필 · /channels/:userId
   담당 스토리 [ANT-FE-CHANNEL-PROFILE]
   설계서 docs/화면설계서.md §3 E · §4 E-02 · §5 · §9.1 · §9.2

   설계 제약
   - 구독은 즉시 토글이 아니다. M-03 확인 → 서명 → 202 → M-02 → ACTIVE.
     그래서 구독 버튼이 상태를 바꾸지 않고 모달을 연다.
   - 결제 시점 fee 가 박제된다. 내가 낸 값과 지금 값이 다를 수 있어 둘 다 보여준다.
   - 백테스트는 무료이고 파라미터가 고정이다(3개월 · 상수 원금 · 수수료 0).
     사용자 입력 칸을 두지 않는다.
   - 미판정 예측은 구독자만 본다. 비구독자에게는 잠금 카드로 존재를 알린다 —
     없는 것처럼 감추면 구독 유인이 사라진다(§5).
   - 실적 통계는 hitRate · doneCount · avgError 세 개다.

   두지 않는 것
   핸들(@) · 구독자 수 · 작성 카운트 · 무료 팔로우 · 신뢰도 점수.
   /channels/{userId} 응답에 없고, 채널 관계는 유료 구독뿐이라 팔로우 개념이 없다.

   PENDING 을 ACTIVE 와 같게 그리지 않는다
   결제가 체인에서 확정되기를 기다리는 중이라 아직 열람 권한이 없다. 여기서
   "구독 중" 이라고 쓰면 잠긴 예측을 보고 고장으로 읽는다. */
import { useCallback, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  daysLeft, fetchBacktest, fetchChannel, fetchChannelPredictions,
  formatExpiry,
  type Backtest, type Channel, type ChannelPrediction,
} from '../api/channels'
import { phaseOf } from '../api/predictions'
import { fetchChannelReports, formatDate, type ChannelReportItem } from '../api/reports'
import { useAsync } from '../api/useAsync'
import { formatToken } from '../api/wallet'
import PredictionCard from '../components/prediction/PredictionCard'
import ChannelProfileEdit from '../components/channel/ChannelProfileEdit'
import ProfileAvatar from '../components/channel/ProfileAvatar'
import PredictionNote from '../components/channel/PredictionNote'
import SubscribeModal from '../components/channel/SubscribeModal'
import AbuseMenu from '../components/report/AbuseMenu'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import WalletLinkModal from '../components/wallet/WalletLinkModal'
import '../styles/screens/channel-profile.css'

const SUB_LABEL: Record<'PENDING' | 'ACTIVE' | 'EXPIRED' | 'FAILED', string> = {
  PENDING: '결제 확인 중',
  ACTIVE: '구독 중',
  EXPIRED: '만료됨',
  FAILED: '결제 실패',
}

/** 지표가 null 이면 "—" 다. 0 으로 그리면 "한 번도 못 맞혔다" 로 읽힌다 */
const pct = (v: number | null) => (v === null ? '—' : `${v}%`)

/** 채널 예측을 카드 props 로. 채널 화면이라 작성자 줄은 넣지 않는다 — 전부 같은 사람이다 */
/* to 를 주지 않는다 — 카드가 링크가 아니라 펼침 버튼이 된다.
   전에는 누르면 바로 C-03 으로 떠났다. 채널을 훑는 사람은 여러 건의 판단을
   비교해 보려는 것이라, 매번 화면을 떠났다 돌아오게 하면 흐름이 끊긴다.
   상세로 가는 길은 펼친 패널 안의 "자세히 보기" 가 맡는다. */
function toCard(p: ChannelPrediction) {
  return {
    id: p.id,
    stockCode: p.stockCode,
    stockName: p.stockName,
    direction: p.direction,
    status: p.status,
    locked: p.locked,
    targetPrice: p.targetPrice,
    errorRate: p.errorRate,
    horizon: p.horizon,
    revealDate: p.revealDate,
    dueDate: p.dueDate,
  }
}

/* 채널에서 볼 것. 순서가 곧 화면 순서다 — 예측이 먼저인 이유는 이 서비스가 파는 것이
   예측이고, 리포트는 그 근거이며, 백테스트는 둘을 검산한 결과이기 때문이다. */
const TABS = [
  ['PREDICTION', '예측'],
  ['REPORT', '리포트'],
  ['BACKTEST', '백테스트'],
] as const

type Tab = (typeof TABS)[number][0]

export default function ChannelProfile() {
  const { userId = '' } = useParams<{ userId: string }>()

  /* ?as=visitor — 내 채널을 **다른 예측가 눈으로** 본다(관리 화면의 띠에서 넘어온다).
     서버는 여전히 나를 주인으로 보고 다 열어 준다. 권한을 흉내 낼 방법이 없어서가
     아니라, 가리는 규칙이 단순해서 화면에서 다시 계산한다.
       locked      = 판정 전 (비구독자는 canRead=false 이므로 판정 전이 곧 잠김)
       noteLocked  = 항상 참 (canRead=false)
       리포트 잠김 = 구독자 전용 글
     서버의 PredictionViewService.canRead 와 같은 경계다. 규칙이 바뀌면 여기도 바꾼다.
     주인용 편집 화면은 쿼리 없는 /channels/{내 id} 쪽이다. */
  const [params] = useSearchParams()
  const asVisitor = params.get('as') === 'visitor'

  const loadChannel = useCallback(() => fetchChannel(userId), [userId])

  const ch = useAsync<Channel>(loadChannel)

  /* 어느 목록을 보고 있는가. 주소에 담지 않는다 — 특정 탭으로 바로 보내는 요구가
     아직 없고, 주소 상태는 뒤로 가기·공유 규칙까지 함께 정해야 한다. */
  const [tab, setTab] = useState<Tab>('PREDICTION')

  /* 구독 직후 예측 목록을 다시 읽게 하는 열쇠. 자식이 제 데이터를 쥐고 있어 부모가
     reload 를 부를 수 없으므로, key 를 바꿔 다시 마운트시킨다. */
  const [dataKey, setDataKey] = useState(0)

  /* M-03 은 라우트가 없는 모달이라 이 화면이 열고 닫는다.
     지갑이 없으면 M-03 을 닫고 M-01 을 띄운다 — 모달 2겹을 만들지 않는다. */
  const [subscribing, setSubscribing] = useState(false)
  const [linking, setLinking] = useState(false)


  const channel = ch.data
  const sub = channel?.mySubscription

  /** 주인 화면으로 그릴지. 미리보기 중이면 내 채널이라도 남처럼 그린다 */
  const owner = (channel?.isMe ?? false) && !asVisitor
  /** 미리보기 띠를 띄울 조건. 남의 채널에 ?as=visitor 를 붙여도 바뀔 게 없다 */
  const previewing = (channel?.isMe ?? false) && asVisitor

  /* 미리보기 안에서 **누구 눈으로** 볼지. 구독료를 정하는 사람이 정말 알고 싶은 것은
     "구독하면 뭐가 더 보이나" 이고, 그 차이는 둘을 번갈아 봐야 보인다.
     기본은 비구독자다 — 안 보이는 쪽이 구독 유인을 정하는 기준이라 먼저 확인할 값이다.
     구독자 눈은 서버가 주인에게 준 값 그대로다(canRead 가 참이면 잠글 것이 없다). */
  const [previewAs, setPreviewAs] = useState<'guest' | 'subscriber'>('guest')
  /** 다시 잠글지. 구독자 눈으로 볼 때는 서버가 준 대로 둔다 */
  const relock = previewing && previewAs === 'guest'

  /* 구독이 ACTIVE 로 바뀌면 예측 목록의 잠금이 풀린다. 그런데 **바로 다시 읽으면 안 된다** —
     useAsync.reload 가 data 를 null 로 되돌리는 순간 아래 {channel && …} 가 거짓이 되어
     모달까지 언마운트된다. 그러면 성공 화면을 못 보고, 다시 마운트된 모달이 confirm 부터
     시작해 결제가 끝난 사람에게 결제 버튼을 다시 보여준다.
     그래서 표시만 해 두고 모달이 닫힐 때 읽는다. 뒤 화면은 모달에 가려 어차피 안 보인다.
     리포트·백테스트는 목록 단계에 게이팅이 없어 그대로 둔다. */
  const [staleAfterSubscribe, setStaleAfterSubscribe] = useState(false)

  /* 모달의 effect 의존성에 들어가므로 매 렌더 새로 만들지 않는다 */
  const markStale = useCallback(() => setStaleAfterSubscribe(true), [])

  const closeSubscribe = useCallback(() => {
    setSubscribing(false)
    if (!staleAfterSubscribe) return
    setStaleAfterSubscribe(false)
    ch.reload()
    setDataKey((k) => k + 1)
  }, [staleAfterSubscribe, ch])

  return (
    <main className="main">
      <div className="main-inner ch">
        {ch.error && <ErrorState error={ch.error} onRetry={ch.reload} />}
        {ch.loading && !channel && <p className="ch-loading">채널을 불러오는 중…</p>}

        {channel && (
          <>
            {/* ── 모드 띠 — 내 채널에서만 ────────────────
                같은 화면을 두 눈으로 본다. 지금 어느 쪽인지와 반대편으로 가는 길을
                한 줄에 둔다 — 미리보기에서 편집 자리가 사라진 것을 고장으로 읽지
                않게 하고, 관리 화면에서 남에게 무엇이 보이는지 바로 열어 볼 수 있게. */}
            {channel.isMe && (
              <p className={`ch-preview${previewing ? '' : ' is-owner'}`}>
                <span>
                  {previewing
                    ? '다른 예측가에게 보이는 화면입니다. 가려지는 범위는 규칙대로 다시 계산해 보여 줍니다.'
                    : '내 채널 관리 화면입니다. 이름·소개·관심 섹터·외부 채널을 여기서 고칩니다.'}
                </span>
                {previewing
                  ? <Link className="ch-preview-btn" to={`/channels/${userId}`}>내 채널 관리</Link>
                  : <Link className="ch-preview-btn" to={`/channels/${userId}?as=visitor`}>미리보기</Link>}
              </p>
            )}

            {/* ── 채널 헤더 ─────────────────────────────── */}
            <header className="ch-head">
              {/* 사진은 값들과 다른 자리에 있어 ChannelProfileEdit 밖이다(ProfileAvatar 머리말).
                  안 올렸으면 서버가 null 을 주고 컴포넌트가 기본 그림을 쓴다 — 여기서 &&
                  로 감싸면 사진 없는 사람은 바꾸는 자리까지 사라진다. */}
              <ProfileAvatar
                src={channel.avatarUrl}
                isMe={owner}
                className="ch-avatar"
                onSaved={ch.reload}
              />
              <div className="ch-id">
                {/* 닉네임·소개·관심 섹터·외부 채널 넷을 통째로 맡긴다 — 읽기용 표시까지
                    포함이다. 화면과 컴포넌트가 나눠 그리면 누를 수 있는 자리와 고쳐지는
                    자리가 어긋난다. 내 채널이면 값을 눌러 그 항목만 고친다
                    (S15P21A507-195 에서 설정에서 옮겨 옴). */}
                <ChannelProfileEdit
                  profile={channel}
                  isMe={owner}
                  onSaved={ch.reload}
                />
              </div>

              {/* ── ⋯ 메뉴 (M-06 진입) ──────────────────
                  내 채널에는 아예 그리지 않는다. 자기 신고는 서버가 400 으로 막지만,
                  누를 수 있게 두면 그 400 을 보려고 누르는 사람이 생긴다.
                  미리보기(?as=visitor)에서도 숨긴다 — owner 가 아니라 isMe 로 본다. */}
              {!channel.isMe && (
                <AbuseMenu
                  className="ch-menu-wrap"
                  targetType="USER"
                  /* Channel.userId 는 경로 변수라 문자열이다. 서버 targetId 는 Long 이고
                     @Positive 라 문자열을 보내면 400 이 된다. */
                  targetId={Number(channel.userId)}
                  targetLabel={`${channel.nickname} 님을 신고합니다.`}
                  menuLabel="채널 메뉴"
                />
              )}
            </header>

            {/* ── 실적 통계 — 세 개 고정 ────────────────── */}
            <section className="ch-stats">
              <div>
                <dt>적중률</dt>
                <dd className="num">{pct(channel.stats.hitRate)}</dd>
              </div>
              <div>
                <dt>판정 완료</dt>
                <dd className="num">{`${channel.stats.doneCount.toLocaleString('ko-KR')}건`}</dd>
              </div>
              <div>
                <dt>평균 오차</dt>
                <dd className="num">{pct(channel.stats.avgError)}</dd>
              </div>
            </section>

            {/* ── 구독 카드 ─────────────────────────────── */}
            {owner ? (
              <section className="ch-sub is-me">
                {/* 채널은 가입과 함께 있지만 구독료는 정해야 생긴다. 아직이면 그 사실부터
                    말한다 — "바꿉니다" 라고만 하면 이미 값이 있는 줄 안다(S15P21A507-230) */}
                <p>
                  {channel.fee === null
                    ? '내 채널입니다. 구독료를 아직 정하지 않아 아무도 구독할 수 없습니다.'
                    : '내 채널입니다. 구독료는 설정에서 바꿉니다.'}
                </p>
                <Link className="ch-sub-link" to="/settings">
                  {channel.fee === null ? '구독료 정하기' : '설정으로'}
                </Link>
                <span className="ch-sub-count num">{`구독자 ${channel.subscriberCount.toLocaleString('ko-KR')}명`}</span>
              </section>
            ) : (
              <section className="ch-sub">
                <div className="ch-sub-price">
                  <span className="ch-sub-label">구독료</span>
                  {/* 채널은 가입과 함께 존재하지만 구독료는 정해야 생긴다. 그 사이 상태가
                      fee === null 이다. 0 으로 그리면 무료 채널로 읽힌다(S15P21A507-230). */}
                  {channel.fee === null ? (
                    <p className="ch-sub-unset">아직 정해지지 않았습니다</p>
                  ) : (
                    <p className="num">
                      <b>{formatToken(channel.fee)}</b><small>ANT</small>
                      <em>/ 30일</em>
                    </p>
                  )}
                  {/* 집계만이다. 누가 구독하는지는 어디에도 안 내린다(예측 분포가 인원만 내는 것과 같은 규칙) */}
                  <span className="ch-sub-count num">{`구독자 ${channel.subscriberCount.toLocaleString('ko-KR')}명`}</span>
                </div>

                {sub?.status === 'ACTIVE' && sub.expiresAt ? (
                  <div className="ch-sub-state">
                    <span className="ch-sub-badge is-active">{SUB_LABEL.ACTIVE}</span>
                    <p className="ch-sub-until">
                      {`${formatExpiry(sub.expiresAt)}까지 · ${daysLeft(sub.expiresAt)}일 남음`}
                    </p>
                    {/* 박제된 가격이 지금 가격과 다르면 알려 준다. 안 알리면 나중에
                        결제 금액이 달라 보이는 이유를 알 수 없다 */}
                    {sub.paidFee && sub.paidFee !== channel.fee && (
                      <p className="ch-sub-locked-fee">
                        {`${formatToken(sub.paidFee)} ANT 로 결제한 구독입니다. 만료까지 이 가격입니다.`}
                      </p>
                    )}
                    <p className="ch-sub-renew">
                      {sub.autoRenew
                        ? '만료일에 자동으로 갱신됩니다'
                        : '자동 갱신을 해지했습니다. 만료일에 종료됩니다'}
                    </p>
                  </div>
                ) : sub?.status === 'PENDING' ? (
                  /* 아직 열람 권한이 없다. ACTIVE 와 같게 그리면 잠긴 예측이 고장으로 읽힌다 */
                  <div className="ch-sub-state">
                    <span className="ch-sub-badge is-pending">{SUB_LABEL.PENDING}</span>
                    <p className="ch-sub-until">
                      결제가 체인에서 확정되면 구독이 시작됩니다. 완료되면 알림으로 알려 드립니다.
                    </p>
                  </div>
                ) : channel.fee === null ? (
                  /* 값이 없는 결제를 시작시키지 않는다. 버튼을 그려 두고 누르면 실패하게
                     하는 것보다, 왜 지금 구독할 수 없는지 말하는 편이 낫다 */
                  <p className="ch-sub-unset-note">
                    이 채널은 아직 구독료를 정하지 않아 구독할 수 없습니다.
                  </p>
                ) : previewing ? (
                  /* 내 채널을 내가 구독할 수는 없다. 버튼을 지우면 남이 보는 화면과
                     달라지므로 자리는 그대로 두고 누르지 못하게만 한다 */
                  <button type="button" className="ch-sub-cta" disabled title="미리보기입니다">
                    구독하기
                  </button>
                ) : (
                  <>
                    {/* 결제가 실패해 시작된 적 없는 구독. "만료됨" 과 다른 말을 쓴다 — 만료는 있었다가
                        끝난 것이고 이건 시작된 적이 없다. 돈이 빠져나갔는지가 첫 궁금증이라 그것부터. */}
                    {sub?.status === 'FAILED' && (
                      <p className="ch-sub-until">
                        지난번 결제가 체인에서 실패해 구독이 시작되지 않았습니다. 토큰은 빠져나가지 않았습니다.
                      </p>
                    )}
                    <button type="button" className="ch-sub-cta" onClick={() => setSubscribing(true)}>
                      {sub?.status === 'EXPIRED' ? '다시 구독하기'
                        : sub?.status === 'FAILED' ? '다시 시도하기'
                        : '구독하기'}
                    </button>
                  </>
                )}
              </section>
            )}

            {subscribing && (
              <SubscribeModal
                channel={channel}
                onClose={closeSubscribe}
                onSubscribed={markStale}
                onNeedWallet={() => { setSubscribing(false); setLinking(true) }}
              />
            )}

            {linking && (
              <WalletLinkModal
                onClose={() => setLinking(false)}
                // 연동이 끝나면 하려던 일로 되돌린다 — 다시 찾아 누르게 하지 않는다
                onLinked={() => { setLinking(false); setSubscribing(true) }}
              />
            )}

            {/* ── 예측 · 리포트 · 백테스트 ───────────────
                세로로 쌓지 않고 탭으로 가른다. 셋 다 길어질 수 있는 목록이라 쌓아 두면
                리포트를 보려는 사람이 예측 목록을 통째로 지나쳐야 하고, 백테스트는
                한참 아래에 있어 있다는 것조차 모른다.

                고른 탭만 그린다 — 덕분에 **안 보는 목록은 요청도 안 나간다.** 훅을
                조건부로 부를 수 없어 섹션마다 컴포넌트를 나눴다(리포트 피드 F-01 과 같은 수). */}
            <section className="ch-section">
              <div className="ch-section-head">
                <div className="ch-tabs" role="tablist" aria-label="채널에서 볼 것">
                  {TABS.map(([key, text]) => (
                    <button
                      key={key} type="button" role="tab"
                      id={`ch-tab-${key}`}
                      aria-selected={tab === key}
                      aria-controls={`ch-panel-${key}`}
                      className={tab === key ? 'is-on' : undefined}
                      onClick={() => setTab(key)}
                    >
                      {text}
                    </button>
                  ))}
                </div>

                {/* 미리보기에서만 뜬다. 구독으로 무엇이 열리는지는 둘을 나란히 봐야 안다 —
                    이 줄이 없으면 주인은 "가려진 쪽" 만 보고 구독료를 정하게 된다.
                    탭 밖에 두는 이유 — 이 토글은 예측과 리포트 **둘 다**의 잠금을 바꾼다.
                    예측 탭 안에 있으면 리포트에서 무엇이 바뀌는지 볼 수 없다. */}
                {previewing && (
                  <div className="ch-as" role="group" aria-label="누구 눈으로 볼지">
                    {([['guest', '비구독자'], ['subscriber', '구독자']] as const).map(([key, text]) => (
                      <button
                        key={key}
                        type="button"
                        className={previewAs === key ? 'is-on' : undefined}
                        aria-pressed={previewAs === key}
                        onClick={() => setPreviewAs(key)}
                      >
                        {text}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              <div role="tabpanel" id={`ch-panel-${tab}`} aria-labelledby={`ch-tab-${tab}`}>
                {/* key 에 dataKey 를 실어 구독 직후 다시 읽게 한다 — 부모가 자식의 reload 를
                    쥐고 있지 않아도 되고, 다시 마운트되면 useAsync 가 알아서 읽는다 */}
                {tab === 'PREDICTION' && (
                  <ChannelPredictions
                    key={`pred-${dataKey}`}
                    userId={userId} owner={owner} relock={relock}
                  />
                )}
                {tab === 'REPORT' && <ChannelReports userId={userId} relock={relock} />}
                {tab === 'BACKTEST' && <ChannelBacktest userId={userId} />}
              </div>
            </section>

          </>
        )}
      </div>
    </main>
  )
}

/* ── 탭 안쪽 셋 ─────────────────────────────────────────
   섹션마다 컴포넌트를 나눈 이유는 훅을 조건부로 부를 수 없어서다. 고른 탭만 마운트되므로
   useAsync 가 그때 돌고, **안 보는 목록은 요청이 아예 안 나간다**(리포트 피드 F-01 과 같은 수). */

/** 예측 목록. 펼친 한 건과 '가려질 건수' 는 이 목록만의 상태라 여기서 들고 있다. */
function ChannelPredictions({ userId, owner, relock }: {
  userId: string
  /** 주인 화면인가. 근거를 주인 눈으로 읽을지와 안내문 노출을 정한다 */
  owner: boolean
  /** 서버가 열어 준 것을 비구독자 기준으로 다시 잠글지(미리보기) */
  relock: boolean
}) {
  const load = useCallback(() => fetchChannelPredictions(userId), [userId])
  const preds = useAsync(load)

  /* 펼친 예측 한 건. 여러 개를 동시에 펴지 않는다 — 목록이 길어지면 무엇을
     보고 있었는지 놓치고, 판단 내용은 한 건씩 읽는 글이다.
     탭을 옮겼다 돌아오면 접힌다. 다시 마운트되기 때문인데, 그게 맞다 —
     다른 목록을 보고 온 사람에게 아까 펴 둔 근거가 남아 있을 이유가 없다. */
  const [openId, setOpenId] = useState<string | null>(null)

  /** 내 채널일 때, 비구독자에게는 가려질 건수. 판정 전이 곧 잠금 경계다(§5) */
  const lockedForOthers = (preds.data?.items ?? [])
    .filter((p) => phaseOf(p.status) === 'PENDING').length

  return (
    <>
            {/* 내 채널에서는 목록이 **남이 보는 것과 다르다** — 서버가 본인을 구독자와
                같이 취급해(PredictionViewService.canRead) 판정 전 건까지 다 열어 준다.
                그래서 채널 주인은 비구독자가 무엇을 못 보는지 알 수 없는데, 구독료를
                정하는 사람이 바로 그 사람이다. 몇 건이 가려지는지만 알려 준다.

                잠기는 조건은 "판정 전"이고 그게 곧 phaseOf(s) === 'PENDING' 이다
                (서버 isPending 과 같은 경계 — BASE · OPEN). 세는 데 서버를 더 부를
                필요가 없어 이미 받은 목록에서 센다.
                이 목록은 한 페이지뿐이라 "이 페이지 기준"이라는 뜻으로 적는다. */}
            {owner && lockedForOthers > 0 && (
              <p className="ch-gate-note">
                {`판정 전 ${lockedForOthers}건은 비구독자에게 방향·목표가가 가려집니다. 지금 화면은 주인에게만 다 보입니다.`}
              </p>
            )}

            {preds.error && <ErrorState error={preds.error} onRetry={preds.reload} inline />}
            {!preds.error && preds.data?.items.length === 0 && (
              <EmptyState title="아직 등록한 예측이 없습니다" />
            )}
            {preds.data && preds.data.items.length > 0 && (
              <ul className="ch-cards">
                {preds.data.items.map((p) => {
                  const id = String(p.id)
                  const open = openId === id
                  /* 미리보기에서는 서버가 다 열어 준 목록을 비구독자 기준으로 다시 잠근다 */
                  const locked = relock ? phaseOf(p.status) === 'PENDING' : p.locked
                  return (
                    <li key={p.id} className={open ? 'is-open' : ''}>
                      {/* 카드째로 누를 수 있어야 한다. <button> 으로 감싸면 안쪽의
                          제목·목록이 버튼 안에 들어가 마크업이 깨지므로 role 을 준다.
                          키보드로도 같은 동작이 되게 Enter·Space 를 받는다. */}
                      <div
                        role="button" tabIndex={0}
                        aria-expanded={open} aria-controls={`ch-note-${id}`}
                        className="ch-pred-hit"
                        onClick={() => setOpenId(open ? null : id)}
                        onKeyDown={(e) => {
                          if (e.key !== 'Enter' && e.key !== ' ') return
                          e.preventDefault()   // Space 로 화면이 스크롤되지 않게
                          setOpenId(open ? null : id)
                        }}
                      >
                        {/* channelId 를 넘기지 않는다 — LockedCard 의 "채널 구독하고 보기" 가
                            /channels/{id} 로 가는데 그게 바로 이 화면이라 눌러도 제자리다.
                            구독 CTA 는 위 구독 카드 하나로 충분하다. 다른 화면(B-03 등)에서는
                            여기로 보내야 하므로 카드가 channelId 를 받는 것 자체는 남긴다.
                            여기 안에 링크가 없어야 role="button" 이 성립한다. */}
                        {/* 서버가 잠긴 건에서 방향·목표가를 빼는 것과 같은 모양으로 만든다 —
                            값을 그대로 둔 채 자물쇠만 얹으면 미리보기가 거짓말이 된다 */}
                        <PredictionCard
                          data={locked
                            ? { ...toCard(p), locked, direction: null, targetPrice: null }
                            : toCard(p)}
                        />
                      </div>

                      {/* 접으면 언마운트된다 — 그래서 "열 때만 읽기" 가 훅 조건부 호출
                          없이 된다(PredictionNote 머리말) */}
                      {open && (
                        <div id={`ch-note-${id}`}>
                          <PredictionNote id={id} isMe={owner} locked={locked} preview={relock} />
                        </div>
                      )}
                    </li>
                  )
                })}
              </ul>
            )}
    </>
  )
}

/** 리포트 목록. 목록 단계에는 게이팅이 없어 다시 읽을 일이 없다. */
function ChannelReports({ userId, relock }: { userId: string; relock: boolean }) {
  const load = useCallback(() => fetchChannelReports(userId), [userId])
  const reports = useAsync(load)

  return (
    <>
            {reports.error && <ErrorState error={reports.error} onRetry={reports.reload} inline />}
            {!reports.error && reports.data?.items.length === 0 && (
              <EmptyState title="아직 발행한 리포트가 없습니다" />
            )}
            {reports.data && reports.data.items.length > 0 && (
              <ul className="ch-reports">
                {reports.data.items.map((r: ChannelReportItem) => {
                  /* 미리보기에서는 구독자 전용 글이 곧 잠긴 글이다 */
                  const locked = relock ? !r.visibility : r.locked
                  return (
                  <li key={r.id}>
                    <Link to={`/reports/${r.id}`}>
                      <span className="ch-report-title">{r.title}</span>
                      <span className="ch-report-meta">
                        {/* 두 배지는 뜻이 다르다 — visibility 는 글의 속성,
                            locked 는 내 권한이다(서버 주석). 합치지 않는다 */}
                        {!r.visibility && <em className="ch-badge">구독자 전용</em>}
                        {locked && (
                          <em className="ch-badge is-locked">
                            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                              <rect x="4" y="10" width="16" height="10" rx="2" />
                              <path d="M8 10V7a4 4 0 0 1 8 0v3" />
                            </svg>
                            잠김
                          </em>
                        )}
                        <span className="num">{formatDate(r.publishedAt)}</span>
                      </span>
                    </Link>
                  </li>
                  )
                })}
              </ul>
            )}
    </>
  )
}

/** 백테스트 — 무료 · 파라미터 고정. 미리보기와 무관하다(가릴 것이 없다). */
function ChannelBacktest({ userId }: { userId: string }) {
  const load = useCallback(() => fetchBacktest(userId), [userId])
  const back = useAsync<Backtest>(load)

  return (
    <>
            {back.error && <ErrorState error={back.error} onRetry={back.reload} inline />}

            {back.data && back.data.sampleCount === 0 ? (
              /* 표본이 없으면 0% 를 그리지 않는다 — "손실 없음" 으로 읽힌다 */
              <EmptyState
                title="아직 검증할 판정 결과가 없습니다"
                hint="예측이 판정되면 이 채널을 따라갔을 때의 수익률을 계산합니다"
              />
            ) : back.data && (
              <div className="ch-backtest">
                <div className="ch-bt-figures">
                  <div>
                    <dt>수익률</dt>
                    <dd className={`num ${back.data.returnRate >= 0 ? 'up' : 'down'}`}>
                      {`${back.data.returnRate > 0 ? '+' : ''}${back.data.returnRate}%`}
                    </dd>
                  </div>
                  <div>
                    <dt>KOSPI</dt>
                    <dd className="num">
                      {back.data.benchmarkRate === null
                        ? '—'
                        : `${back.data.benchmarkRate > 0 ? '+' : ''}${back.data.benchmarkRate}%`}
                    </dd>
                  </div>
                  <div>
                    <dt>표본</dt>
                    <dd className="num">{`${back.data.sampleCount}건`}</dd>
                  </div>
                </div>
                {/* 조건을 적어 둔다. 안 적으면 어떤 가정의 숫자인지 알 수 없다.
                    값을 화면에 박지 않고 응답에서 받는 이유 — 기간이 바뀌면
                    문구와 계산 근거가 어긋난다 */}
                <p className="ch-bt-terms">
                  {`최근 ${back.data.months}개월 · 원금 ${back.data.principal.toLocaleString('ko-KR')}원 · 수수료 0 기준입니다. 무료로 제공합니다.`}
                </p>
              </div>
            )}
    </>
  )
}
