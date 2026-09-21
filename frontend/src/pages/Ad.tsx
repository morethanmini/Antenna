/* H-04 광고 등록 · /ads/new
   담당 스토리 [ANT-FE-AD]
   설계서 docs/화면설계서.md §3 H · §4 H-04 · §4 M-02 · §4 M-07

   설계 제약
   - imageFileId 는 업로드 응답에서 온 값만 받는다. 외부 URL 입력란을 두지 않는다.
   - linkUrl 은 https 만 허용한다.
   - 서명 전 비용을 명확히 보여준다(SignConfirm).
   - 등록된 배너는 B-01 홈의 /ads/active 로 노출된다.

   ── 승인제 (ANT-ADMIN-03, 명세 v0.78) ──────────────────────
   예전 제약 "등록은 202 + operationId, M-02 처리 대기로 이어진다 · 승인 대기 화면은 2차" 는
   없어졌다. 등록은 201 이고 **돈은 관리자가 승인하는 순간 나간다.** 그래서 폴링할 작업이 없고,
   이 화면은 "심사 중" 안내로 끝난다. 신청한 게재료는 승인 전까지 가용잔액에서 묶인다 —
   심사 중인 돈으로 배팅·구독을 할 수 없고, 취소하면 풀린다. 취소 버튼을 여기 둔 이유다.

   시작일을 고르는 칸이 없는 이유
   요청 본문에 days 만 있고 시작일이 없다. 게재 기간은 **승인되는 시점부터** 다시 잡힌다
   (AdBanner.approve) — 심사가 늦어진 만큼 손해를 보지 않는다. 그래서 화면도 날짜를 묻지 않고
   승인되면 시작한다고 알린다.

   비용은 서버가 준 단가로만 계산한다
   GET /ads/pricing 의 pricePerDay · minDays · maxDays · slotCount 를 그대로 쓴다. 가격이
   서명 문자열에 없어(days 만 들어간다) 서버가 단가를 바꿔도 서명으로는 막히지 않는다 —
   화면이 상수를 들고 있으면 "1 ANT × 7일" 이라 말하고 7,000 ANT 를 태울 수 있다.
   그래서 단가를 못 받았으면 금액을 짐작해 그리지 않고 등록 버튼을 잠근다. 소각은 되돌릴 수 없다.

   지갑 게이트는 ANT-FE-WALLET-GATE 판단을 따른다
   미설치와 미연동을 여기서 가르지 않는다 — M-01 이 네 갈래를 각각 다르게 안내한다. */
import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  LINK_MAX,
  cancelAd, createAd, adSigningPayload, fetchAdPricing, isValidLink, priceOf, type AdDraft,
} from '../api/ads'
import type { ApiError } from '../api/errors'
import { useAsync } from '../api/useAsync'
import { formatToken, requestNonce } from '../api/wallet'
import { useWalletBalance } from '../api/walletBalance'
import { useAuth } from '../auth/context'
import { connectAddress, hasWallet, personalSign } from '../wallet/provider'
import ImageUploadModal from '../components/upload/ImageUploadModal'
import WalletLinkModal from '../components/wallet/WalletLinkModal'
import WalletPrelink from '../components/wallet/WalletPrelink'
import ErrorState from '../components/state/ErrorState'
import ConfirmDialog from '../components/ConfirmDialog'
import { MAX_BYTES, formatBytes } from '../api/uploads'
import type { UploadedImage } from '../api/uploads'
import { errorText } from '../components/state/errorText'
import '../styles/screens/ad-new.css'

/* form     내용을 채우는 중
   signing  nonce 발급 → 지갑 서명 → 201 수신
   review   심사 중 — 관리자가 승인하면 소각되고 게재된다. 여기서 취소할 수 있다 */
type Step = 'form' | 'signing' | 'review'

export default function Ad() {
  const { user } = useAuth()

  const [banner, setBanner] = useState<UploadedImage | null>(null)
  const [linkUrl, setLinkUrl] = useState('')
  const [days, setDays] = useState(7)

  const [step, setStep] = useState<Step>('form')
  const [error, setError] = useState<ApiError | null>(null)
  /* 심사 중인 신청. 취소할 때 필요하다 */
  const [adId, setAdId] = useState<number | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [canceled, setCanceled] = useState(false)

  const [uploading, setUploading] = useState(false)
  const [linking, setLinking] = useState(false)

  const view: Step = step

  /* 단가 · 일수 범위 · 자리 수는 서버에서만 온다. 못 받은 동안 terms 가 null 이라 등록이 잠긴다 */
  const pricing = useAsync(fetchAdPricing)
  const terms = pricing.data

  const linkOk = isValidLink(linkUrl)
  const daysOk = terms !== null && days >= terms.minDays && days <= terms.maxDays
  /* 계산할 수 없으면(소수 일수 등) null 이다. 금액을 모르는 채로 서명시키지 않는다 */
  const price = terms ? priceOf(terms, days) : null
  const ready = banner !== null && linkOk && daysOk && price !== null

  /* 잔액이 게재료보다 적으면 서명까지 가지 않는다(ANT-FE-WALLET-PRELINK). 서버는 INSUFFICIENT_BALANCE 로
     막지만 그건 지갑 서명을 받은 **뒤**다. 방금 연동한 사람이 특히 그렇다 — 가입 보너스가 체인으로 오기
     전이라 잔액이 0 이고, 연동 뒤 자동으로 이어 가는 submit() 이 그대로 서명을 받았다.
     구독 모달과 같은 규칙: 이 화면이 열린 뒤에 읽은 값만 쓰고(fresh), 못 읽었으면 막지 않는다.
     보너스가 들어오면 잠금은 저절로 풀린다.
     ponytail: 이 숫자는 원장 합이라 서버의 가용잔액(심사 중인 다른 광고 등을 뺀 값)과 어긋날 수 있다 —
     GET /wallet/balance 가 가용잔액을 내리면 그걸로 바꾼다(ANT-TOKEN-12). */
  const wallet = useWalletBalance({ fresh: true })
  const balance = wallet.data?.balance
  const short = Boolean(user?.walletLinked) && price !== null && !!balance
    && /^\d+$/.test(balance) && BigInt(balance) < BigInt(price)

  const onUploaded = useCallback((image: UploadedImage) => {
    setBanner(image)
    setError(null)
  }, [])

  async function submit() {
    // short 도 여기서 본다 — 연동 직후의 자동 submit() 은 버튼을 거치지 않는다
    if (!ready || !banner || short) return

    /* 미설치와 미연동을 가르지 않는다 — M-01 이 네 갈래를 각각 다르게 안내하고,
       늦게 주입되는 확장까지 기다려 준다(ANT-FE-WALLET-GATE). */
    if (!hasWallet() || !user?.walletLinked) {
      setLinking(true)
      return
    }

    setError(null)
    setStep('signing')
    try {
      const draft: AdDraft = { imageFileId: banner.fileId, linkUrl, days }
      const address = await connectAddress()
      const nonce = await requestNonce('AD')
      /* 지갑 연동용 signingPayload 가 아니다. 신청 내용이 통째로 들어간다 —
         days 가 빠지면 3일치 서명으로 30일치를 등록할 수 있다(서버 주석). */
      const signature = await personalSign(adSigningPayload(draft, nonce), address)

      const accepted = await createAd(draft, signature)
      setAdId(accepted.adId)
      setCanceled(false)
      setStep('review')
    } catch (e) {
      setError(e as ApiError)
      /* 폼으로 돌린다. 멱등 키는 신청 내용에서 나오므로 같은 내용으로 재시도하면 같은 키가
         나가 게재료가 두 번 나가지 않는다(api/ads.ts adScope). */
      setStep('form')
    }
  }

  async function cancel() {
    if (adId === null) return
    setCancelling(false)
    setError(null)
    try {
      await cancelAd(adId)
      setCanceled(true)
      setAdId(null)
    } catch (e) {
      setError(e as ApiError)
    }
  }

  const text = error ? errorText(error) : null

  return (
    <main className="main">
      <div className="main-inner ad">
        <div className="page-head">
          <h1>광고 등록</h1>
          <p>홈 상단 배너 자리에 노출됩니다</p>
        </div>

        {view === 'form' && (
          <>
            {/* 지갑 없이 들어왔으면 적기 **전에** 연동부터 권한다(ANT-FE-WALLET-PRELINK) */}
            <WalletPrelink purpose="광고를 등록하려면" />

            {/* ── 배너 ─────────────────────────────────── */}
            <section className="ad-card">
              <h2>배너 이미지</h2>
              {/* 비율이 어긋나면 모달이 가운데를 잘라 맞춘다(M-07). 미리 알려 둔다 */}
              <p className="ad-hint">
                {`${formatBytes(MAX_BYTES)} 이하의 PNG · JPG · WebP. 가로세로 4:1 로 자르며, 남길 자리는 올릴 때 고릅니다.`}
              </p>

              {banner ? (
                <div className="ad-banner">
                  <img src={banner.url} alt="" />
                  <div className="ad-banner-foot">
                    <span className="num">{`${banner.width} × ${banner.height}`}</span>
                    <button type="button" className="ad-btn" onClick={() => setUploading(true)}>
                      바꾸기
                    </button>
                  </div>
                </div>
              ) : (
                <button type="button" className="ad-pick" onClick={() => setUploading(true)}>
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M12 16V4" /><path d="m7 9 5-5 5 5" />
                    <path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
                  </svg>
                  배너 올리기
                </button>
              )}
            </section>

            {/* ── 링크 ─────────────────────────────────── */}
            <section className="ad-card">
              <h2>이동할 주소</h2>
              <input
                type="url" className="ad-input"
                value={linkUrl} maxLength={LINK_MAX}
                placeholder="https://"
                onChange={(e) => setLinkUrl(e.target.value)}
                aria-invalid={linkUrl !== '' && !linkOk}
              />
              {/* https 제약은 입력 전에 알린다 — 다 쓰고 나서 거절하면 다시 써야 한다 */}
              <p className={`ad-hint ${linkUrl !== '' && !linkOk ? 'is-bad' : ''}`}>
                {linkUrl !== '' && !linkOk
                  ? 'https 로 시작하는 주소만 등록할 수 있습니다'
                  : 'https 주소만 등록할 수 있습니다'}
              </p>
            </section>

            {/* ── 기간 ─────────────────────────────────── */}
            <section className="ad-card">
              <h2>노출 기간</h2>
              <div className="ad-days">
                <input
                  type="number" className="ad-input is-num"
                  value={days} min={terms?.minDays} max={terms?.maxDays}
                  /* 위로만 자른다 — 아래까지 자르면 칸을 비우고 새로 칠 수가 없다. 모자란 값은 daysOk 가 막는다.
                     안 자르면 99999999일 → 99,999,999,000 ANT 가 그대로 그려졌다(QA 09-21, 서버는 maxDays 로 막는다). */
                  onChange={(e) => setDays(Math.min(Number(e.target.value), terms?.maxDays ?? Infinity))}
                />
                <span>일</span>
              </div>
              {/* 시작일 칸이 없는 이유를 적는다. 안 적으면 언제 걸리는지 모른다 */}
              <p className="ad-hint">
                {terms
                  ? `${terms.minDays}~${terms.maxDays}일. 시작일은 고를 수 없고, 관리자가 승인하는 시점부터 시작합니다.`
                  : '시작일은 고를 수 없고, 관리자가 승인하는 시점부터 시작합니다.'}
              </p>
            </section>

            {/* ── 비용 (SignConfirm) ───────────────────── */}
            <section className="ad-cost">
              {pricing.error ? (
                /* 단가를 못 받으면 금액을 짐작해 채우지 않는다. 이유를 말하고 등록을 잠근다 */
                <ErrorState error={pricing.error} onRetry={pricing.reload} inline />
              ) : !terms ? (
                <p className="ad-hint" aria-live="polite">게재 단가를 불러오는 중입니다…</p>
              ) : (
                <>
                  <div className="ad-cost-line">
                    <span>{`${formatToken(terms.pricePerDay)} ANT × ${days}일`}</span>
                    <b className="num">{price === null ? '—' : `${formatToken(price)} ANT`}</b>
                  </div>
                  {/* 돈은 승인 때 나간다. 그 전까지 묶인다는 것과, 소각 뒤엔 되돌릴 수 없다는 것을 서명 전에 말한다 */}
                  <p className="ad-cost-note">
                    {price === null
                      ? '노출 기간을 정수 일수로 넣으면 금액이 계산됩니다.'
                      : <>관리자가 승인하면 <b>{`${formatToken(price)} ANT 가 소각`}</b>됩니다. 승인 전에는 이 금액이 묶여 다른 곳에 쓸 수 없고, 취소하면 풀립니다. 소각 뒤에는 되돌릴 수 없습니다.</>}
                    {' '}
                    {terms.slotCount === 1
                      ? '자리가 하나뿐이라 이미 게재 중인 광고가 있으면 등록되지 않습니다.'
                      : `자리가 ${terms.slotCount}개라 모두 차 있으면 등록되지 않습니다.`}
                  </p>
                </>
              )}
            </section>

            {text && (
              <p className="ad-error" role="alert">
                <b>{text.title}</b>
                {text.hint && <span>{text.hint}</span>}
              </p>
            )}

            {short && price !== null && balance && (
              <p className="ad-error" role="alert">
                <b>ANT 잔액이 부족합니다</b>
                <span>{`보유 ${formatToken(balance)} ANT · 게재료 ${formatToken(price)} ANT. 방금 지갑을 연동했다면 가입 보너스가 들어오는 중일 수 있습니다 — 들어오면 버튼이 열립니다.`}</span>
              </p>
            )}

            <div className="ad-actions">
              <button
                type="button" className="ad-btn solid"
                disabled={!ready || short}
                onClick={() => void submit()}
              >
                서명하고 등록하기
              </button>
            </div>
          </>
        )}

        {/* ── 서명 · 대기 · 완료 ─────────────────────────── */}
        {view === 'signing' && (
          <section className="ad-progress" aria-live="polite">
            <span className="ad-spinner" aria-hidden="true" />
            <p className="ad-progress-title">지갑에서 서명을 기다리고 있습니다</p>
            <p className="ad-sub">지갑 창이 뜨지 않으면 확장 아이콘을 눌러 확인해 주세요.</p>
          </section>
        )}

        {/* 심사 중. 폴링할 작업이 없다 — 돈은 승인 때 나간다. 취소는 여기서만 할 수 있다 */}
        {view === 'review' && (
          <section className="ad-progress" aria-live="polite">
            <span className="ad-check" aria-hidden="true">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                <path d="m5 12.5 4.5 4.5L19 7.5" />
              </svg>
            </span>
            {canceled ? (
              <>
                <p className="ad-progress-title">신청을 취소했습니다</p>
                <p className="ad-sub">묶여 있던 게재료가 풀렸습니다. 아무것도 소각되지 않았습니다.</p>
                <button type="button" className="ad-btn solid" onClick={() => { setStep('form') }}>다시 신청하기</button>
              </>
            ) : (
              <>
                <p className="ad-progress-title">심사 중입니다</p>
                <p className="ad-sub">
                  {`관리자가 승인하면 ${price === null ? '' : `${formatToken(price)} ANT 가 `}소각되고 ${days}일간 홈 상단에 노출됩니다. 승인 전까지 이 금액은 묶여 있고, 취소하면 풀립니다.`}
                </p>
                <div className="ad-actions">
                  <Link className="ad-btn solid" to="/">홈으로</Link>
                  <button type="button" className="ad-btn" onClick={() => { setCancelling(true) }}>신청 취소</button>
                </div>
              </>
            )}
          </section>
        )}

        {cancelling && (
          <ConfirmDialog
            title="광고 신청을 취소합니다"
            description="심사 대기 중인 신청이 닫히고, 묶여 있던 게재료가 풀립니다. 다시 신청하려면 지갑 서명을 다시 해야 합니다."
            confirmLabel="취소하기"
            tone="danger"
            onConfirm={() => { void cancel() }}
            onCancel={() => { setCancelling(false) }}
          />
        )}

        {/* M-07 · M-01 은 라우트가 없는 모달이라 이 화면이 열고 닫는다 */}
        {uploading && (
          <ImageUploadModal
            purpose="AD"
            onClose={() => setUploading(false)}
            onUploaded={onUploaded}
          />
        )}

        {linking && (
          <WalletLinkModal
            onClose={() => setLinking(false)}
            // 연동이 끝나면 하려던 일로 되돌린다 — 다시 찾아 누르게 하지 않는다
            onLinked={() => { setLinking(false); void submit() }}
          />
        )}

        {/* 폼 밖에서 난 오류(폴링 조회 실패 등)는 여기 */}
        {view !== 'form' && error && <ErrorState error={error} />}
      </div>
    </main>
  )
}
