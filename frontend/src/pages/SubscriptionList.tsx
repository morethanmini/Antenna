/* E-03 내 구독 · /me/subscriptions
   담당 스토리 [ANT-FE-SUBSCRIPTION-LIST]
   설계서 docs/화면설계서.md §3 E · §4 E-03 · §4 H-02 · §7

   설계 제약
   - DELETE .../renewal 은 구독이 아니라 **갱신 예약**을 지운다. 라벨을 "자동 갱신 해지" 로
     쓰고, 만료일까지는 계속 이용 가능하다는 점을 함께 표시한다. "구독 취소" 로 쓰면
     지금 접근이 끊긴다고 읽는다.
   - PENDING 은 결제가 블록 확정을 기다리는 상태다. AnchorBadge 로 표현한다(§7 —
     "구독 PENDING 도 같은 컴포넌트").
   - 자동 갱신은 최초 구독 시 오퍼레이터 위임에 동의한 경우에만 배치 B4 가 처리한다.
     동의 상태를 화면에서 확인할 수 있어야 한다.
   - 갱신 실패 알림(RENEW_FAILED)이 이 화면으로 딥링크된다.

   위임 동의 상태를 renewEnabled 로 대신하는 이유
   응답에 operatorAuthorize 를 따로 내려 주는 필드가 없다(명세 §채널·구독 여섯 필드).
   위임 승인은 최초 구독 서명에 포함되므로 구독 행이 있다는 것 자체가 동의가 있었다는
   뜻이고, 그 뒤로 변하는 값은 갱신 예약뿐이다. 그래서 renewEnabled 를 "자동 갱신" 으로
   그린다. 서버가 동의 여부를 따로 내려 주게 되면 이 자리에 한 줄을 더한다.

   해지에 확인 단계를 두는 이유
   **다시 켜는 API 가 없다.** 명세에 DELETE 만 있고 되살리는 경로가 없어, 실수로 누르면
   이 화면에서 되돌릴 방법이 없다. 토글처럼 보이게 만들지 않는다. */
import { useCallback, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { formatExpiry } from '../api/channels'
import type { ApiError } from '../api/errors'
import {
  STATUS_LABEL, cancelRenewal, fetchMySubscriptions, type Subscription,
} from '../api/subscriptions'
import { useAsync } from '../api/useAsync'
import { formatDateTime, formatToken } from '../api/wallet'
import { DEFAULT_AVATAR } from '../auth/context'
import AnchorBadge from '../components/AnchorBadge'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/subscriptions.css'

export default function SubscriptionList() {
  const load = useCallback(() => fetchMySubscriptions(), [])
  const list = useAsync(load)

  /** 확인 단계에 들어간 구독. 한 번에 하나만 연다 */
  const [confirming, setConfirming] = useState<number | null>(null)
  /** 해지 요청 중인 구독. 버튼을 두 번 누르지 못하게 한다 */
  const [canceling, setCanceling] = useState<number | null>(null)
  const [cancelError, setCancelError] = useState<ApiError | null>(null)

  async function confirmCancel(id: number) {
    setCancelError(null)
    setCanceling(id)
    try {
      await cancelRenewal(id)
      setConfirming(null)
      /* 서버가 204 라 새 값을 주지 않는다. 지역 상태로 renewEnabled 를 뒤집는 대신
         목록을 다시 읽는다 — 해지와 함께 status 가 바뀌는 경우(만료 직전 등)를
         화면이 지어내지 않게 한다. */
      list.reload()
    } catch (e) {
      setCancelError(e as ApiError)
    } finally {
      setCanceling(null)
    }
  }

  const items = list.data?.items ?? []

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>내 구독</h1>
          <p>구독 중인 채널과 갱신 상태를 확인합니다</p>
        </div>

        {list.error && <ErrorState error={list.error} onRetry={list.reload} />}
        {list.loading && !list.data && <p className="sb-loading">불러오는 중…</p>}

        {list.data && items.length === 0 && (
          <EmptyState
            title="구독 중인 채널이 없습니다"
            hint="예측가 랭킹이나 채널 프로필에서 구독할 수 있습니다"
          />
        )}

        {items.length > 0 && (
          <ul className="sb-list">
            {items.map((s) => (
              <li key={s.id}>
                <Row
                  sub={s}
                  confirming={confirming === s.id}
                  busy={canceling === s.id}
                  error={confirming === s.id ? cancelError : null}
                  onAsk={() => { setCancelError(null); setConfirming(s.id) }}
                  onCancelAsk={() => { setCancelError(null); setConfirming(null) }}
                  onConfirm={() => void confirmCancel(s.id)}
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </main>
  )
}

type RowProps = {
  sub: Subscription
  confirming: boolean
  busy: boolean
  error: ApiError | null
  onAsk: () => void
  onCancelAsk: () => void
  onConfirm: () => void
}

function Row({ sub, confirming, busy, error, onAsk, onCancelAsk, onConfirm }: RowProps) {
  const { publisher, fee, status, startedAt, expiresAt, renewEnabled, txHash } = sub
  const navigate = useNavigate()

  /* 해시 말풍선. 보이는지만 state 로 두고 **자리는 ref 로 직접 옮긴다** —
     mousemove 마다 setState 하면 초당 수십 번 다시 그린다. 위치는 화면에만 있는 값이라
     React 를 거칠 이유가 없다. */
  const [hashOpen, setHashOpen] = useState(false)
  const tipRef = useRef<HTMLDivElement>(null)

  function moveTip(event: React.MouseEvent) {
    const tip = tipRef.current
    if (!tip) return
    /* position:fixed 라 clientX/Y 를 그대로 쓴다. 커서를 살짝 비껴 두지 않으면
       말풍선이 커서를 덮어 hover 가 깜빡인다(pointer-events:none 이라 실제로는 안 덮지만
       시각적으로 가린다). */
    tip.style.left = `${event.clientX + 16}px`
    tip.style.top = `${event.clientY + 18}px`
  }

  /* 카드를 누르면 그 예측가의 채널로. 안의 링크·버튼에서 시작한 클릭은 지나간다 —
     닉네임 링크를 누른 사람은 그리로 가려는 것이고, 해지 버튼을 누른 사람은 해지하려는 것이다.
     덩어리를 role="button" 으로 만들지 않는다: 안에 링크와 버튼이 있어 스크린리더가
     카드 전체를 거대한 버튼 하나로 읽고 그것들이 그 안에 갇힌다. 키보드 길은 닉네임 링크다. */
  function openChannel(event: React.MouseEvent) {
    if ((event.target as HTMLElement).closest('a, button')) return
    navigate(`/channels/${publisher.userId}`)
  }

  return (
    <article
      className={`sb-item is-${status.toLowerCase()}`}
      onClick={openChannel}
      onMouseEnter={() => { if (txHash) setHashOpen(true) }}
      onMouseLeave={() => setHashOpen(false)}
      onMouseMove={hashOpen ? moveTip : undefined}
    >
      {/* 체인 기록. 카드 안에 줄로 두지 않고 hover 로 옮겼다 — 구독이 커밋 원장(D-01)에
          오르지 않아 온체인 흔적이 이 해시뿐인데, 평소에 읽을 값은 아니다.
          pointer-events:none 이라 커서를 따라와도 아래 카드의 hover 가 끊기지 않는다. */}
      {hashOpen && txHash && (
        <div className="sb-hash-tip" ref={tipRef} role="tooltip">
          <span className="sb-hash-label">체인 기록</span>
          <code>{txHash}</code>
        </div>
      )}
      <div className="sb-head">
        {/* 예측가 얼굴. 구독은 "이 사람을 산 것" 인데 목록에는 이름 글자만 있었다(S15P21A507-273).
            안 올린 사람은 기본 개미다 — 서버가 null 을 준다. 장식이라 alt 는 비운다:
            바로 옆 링크가 이름을 읽어 주므로 사진까지 읽으면 두 번 듣는다. */}
        <img
          className="sb-avatar" alt="" aria-hidden="true"
          src={publisher.avatarUrl ?? DEFAULT_AVATAR}
        />
        <Link className="sb-channel" to={`/channels/${publisher.userId}`}>
          {publisher.nickname}
        </Link>

        {/* PENDING 만 AnchorBadge 다 — 블록 확정 대기라는 뜻이 앵커와 같다(§7).
            ACTIVE·EXPIRED 는 온체인 상태가 아니라 구독 기간의 문제라 별도 배지를 쓴다. */}
        {status === 'PENDING' ? (
          <AnchorBadge status="PENDING" label={STATUS_LABEL.PENDING} />
        ) : (
          <span className={`sb-badge is-${status.toLowerCase()}`}>{STATUS_LABEL[status]}</span>
        )}
      </div>

      {/* 관심 섹터. 이름 바로 아래에 작게 — 이 사람이 무엇을 보는 사람인지가
          금액보다 먼저 읽혀야 한다. 안 고른 사람은 줄째 뺀다(빈 칸을 만들지 않는다) */}
      {publisher.interests.length > 0 && (
        <ul className="sb-sectors" aria-label="관심 섹터">
          {publisher.interests.map((s) => <li key={s}>{s}</li>)}
        </ul>
      )}

      <dl className="sb-facts">
        <div>
          <dt>구독료</dt>
          {/* 결제 시점 박제값이다. 채널의 현재 가격을 나란히 두지 않는다 —
              두 숫자가 붙으면 어느 쪽이 내가 내는 값인지 흐려진다 */}
          <dd className="num">{`${formatToken(fee)} ANT`}</dd>
        </div>

        {/* 구독 시점. PENDING 은 아직 개시 전이라 서버가 null 을 준다 — 자리째 뺀다 */}
        {startedAt && (
          <div>
            <dt>구독 시작</dt>
            <dd className="num">{formatExpiry(startedAt)}</dd>
          </div>
        )}

        {/* 이 예측가가 요즘 활동하는가. 구독은 **앞으로 올** 예측을 사는 것이라
            마지막 활동이 갱신을 정하는 근거다. 한 건도 없으면 그 사실을 적는다 —
            줄을 빼면 '최근 예측' 이 없는 것인지 조용한 것인지 알 수 없다. */}
        <div>
          <dt>최근 예측</dt>
          <dd className={publisher.latestPredictionAt ? 'num' : 'sb-none'}>
            {publisher.latestPredictionAt ? formatDateTime(publisher.latestPredictionAt) : '아직 없음'}
          </dd>
        </div>

      </dl>

      {/* ── 자동 갱신 ─────────────────────────────────
          여부를 글자로 먼저 말하고, 켜져 있으면 언제 얼마가 빠지는지와 끄는 버튼을 함께 둔다.
          예전에는 '자동 갱신: 켜짐' 이 위 사실 목록에 있고 결제일 안내는 아래 따로 있어,
          같은 이야기가 카드 두 곳에 나뉘어 있었다. */}
      {status === 'PENDING' && (
        <p className="sb-note">
          결제가 체인에서 확정되면 구독이 시작됩니다. 완료되면 알림으로 알려 드립니다.
        </p>
      )}

      {status === 'EXPIRED' && (
        <p className="sb-note">
          {expiresAt
            ? `${formatExpiry(expiresAt)}에 이용 기간이 끝났습니다. 다시 보시려면 채널에서 구독해 주세요.`
            : '이용 기간이 끝났습니다. 다시 보시려면 채널에서 구독해 주세요.'}
        </p>
      )}

      {/* 결제 실패. 만료와 갈라 말한다 — 만료는 있었다가 끝난 것, 이건 시작된 적이 없는 것.
          돈이 빠져나갔는지가 첫 궁금증이라 그것부터 적는다. */}
      {status === 'FAILED' && (
        <p className="sb-note">
          결제가 체인에서 실패해 구독이 시작되지 않았습니다. 토큰은 빠져나가지 않았습니다. 채널에서 다시 시도해 주세요.
        </p>
      )}

      {status === 'ACTIVE' && !renewEnabled && (
        <div className="sb-renew">
          <p className="sb-note">
            <b className="sb-renew-state is-off">자동 갱신 꺼짐</b>
            {expiresAt ? ` · ${formatExpiry(expiresAt)}까지 이용하고 종료됩니다.` : ' · 만료일까지 이용하고 종료됩니다.'}
          </p>
        </div>
      )}

      {status === 'ACTIVE' && renewEnabled && !confirming && (
        <div className="sb-renew">
          <p className="sb-note">
            <b className="sb-renew-state is-on">자동 갱신 켜짐</b>
            {/* 금액을 함께 적는다 — 박제값이라 채널의 현재가가 올라도 이 값이 빠진다 */}
            {expiresAt
              ? ` · ${formatExpiry(expiresAt)}에 ${formatToken(fee)} ANT 가 결제됩니다.`
              : ` · 만료일에 ${formatToken(fee)} ANT 가 결제됩니다.`}
          </p>
          {/* "구독 취소" 가 아니다. 지금 끊는 것이 아니라 다음 결제를 막는 것이다 */}
          <button type="button" className="sb-btn" onClick={onAsk}>자동 갱신 해지</button>
        </div>
      )}

      {confirming && (
        <div className="sb-confirm" role="group" aria-label="자동 갱신 해지 확인">
          <p className="sb-confirm-title">자동 갱신을 해지할까요?</p>
          <p className="sb-confirm-body">
            {expiresAt
              ? `구독은 지금 끊기지 않습니다. ${formatExpiry(expiresAt)}까지 그대로 이용하고, 그 뒤로 결제되지 않습니다.`
              : '구독은 지금 끊기지 않습니다. 만료일까지 그대로 이용하고, 그 뒤로 결제되지 않습니다.'}
          </p>
          {/* 되돌리는 API 가 없다. 토글처럼 보이지 않게 미리 알린다 */}
          <p className="sb-confirm-warn">
            해지하면 이 화면에서 다시 켤 수 없습니다. 계속 이용하시려면 만료 후 채널에서
            새로 구독해야 합니다.
          </p>

          {error && (
            <p className="sb-error" role="alert">해지하지 못했습니다. 잠시 후 다시 시도해 주세요.</p>
          )}

          <div className="sb-confirm-actions">
            <button type="button" className="sb-btn" onClick={onCancelAsk} disabled={busy}>
              그대로 두기
            </button>
            <button type="button" className="sb-btn danger" onClick={onConfirm} disabled={busy}>
              {busy ? '해지하는 중…' : '자동 갱신 해지'}
            </button>
          </div>
        </div>
      )}
    </article>
  )
}
