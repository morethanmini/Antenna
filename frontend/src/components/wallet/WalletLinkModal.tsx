/* M-01 지갑 연동 (모달 · 라우트 없음)
   담당 스토리 [ANT-FE-WALLET-LINK]
   설계서 docs/화면설계서.md §3 M · §4 · §6 · API 명세서 §1.2

   설계 제약
   - SignConfirm: 서명할 payload 를 접지 않고 펼쳐서 보여준 뒤 서명을 요청한다.
     무엇에 서명하는지 모른 채 지나가게 하지 않는다.
   - 미설치 · 서명 거부 · 주소 불일치 · 이미 연동됨은 각각 다른 안내다.
     한 문구로 합치지 않는다(errorText.ts 가 code 별로 나눠 들고 있다).
   - 409 WALLET_ALREADY_LINKED 는 재시도로 풀리지 않는다. 진행 버튼을 내린다.
   - 온보딩(M-09) 안에서 열지 않는다 — 모달 3겹이 된다. 호출부가 온보딩을 닫고 띄운다.
   - 연동 후 원래 하려던 동작으로 이어져야 한다. 그래서 성공을 onLinked 로 올려
     호출부(주로 C-01 예측 등록)가 하던 일을 마저 하게 한다.
   - 가입 보너스는 완료 화면 한 줄이 전부다(ANT-TOKEN-07). 수령 버튼을 두지 않는다 —
     사용자가 할 일이 없다. 연동 응답만으로는 전송이 됐는지 알 수 없어서, 그 줄이 작업을
     직접 조회해 확정됐을 때만 과거형으로 쓴다(ANT-FE-WALLET-SYNC).

   두지 않는 것: 연동 해제 · 다중 지갑 관리. 해당 API 가 없다. */
import { createPortal } from 'react-dom'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, ERROR_CODE } from '../../api/errors'
import { useOperation } from '../../api/operations'
import type { WalletLinkResult } from '../../api/wallet'
import { formatToken, linkWallet, requestNonce, shortAddress, signingPayload } from '../../api/wallet'
import { WALLET_INSTALL_URL, connectAddress, hasWallet, onWalletReady, personalSign } from '../../wallet/provider'
import { errorText } from '../state/errorText'
import TokenAutoAdd from './TokenAutoAdd'
import NetworkAdd from './NetworkAdd'
import { useAuth } from '../../auth/context'
import '../../styles/screens/wallet-link.css'

/* connect  지갑 연결 전
   confirm  주소·payload 를 펼쳐 놓고 서명을 기다린다 (SignConfirm)
   working  지갑 창이 떴거나 서버 응답을 기다린다
   done     연동 완료 */
type Step = 'connect' | 'confirm' | 'working' | 'done'

type Draft = {
  address: string
  payload: string
  /* 서버가 준 체인. payload 안에도 같은 값이 들어 있지만 문자열이라 다시 꺼내 쓰기 어렵다.
     완료 화면의 토큰 안내가 어느 체인의 ANT 인지 이 값으로 고른다(TokenAddGuide). */
  chainId: number
}

/** 재시도해도 같은 결과가 나오는 오류. "다시 시도"를 띄우면 사용자를 헛돌린다. */
function isTerminal(error: ApiError) {
  return error.code === ERROR_CODE.WALLET_ALREADY_LINKED
}

export default function WalletLinkModal({
  onClose,
  onLinked,
}: {
  onClose: () => void
  /** 연동된 주소. 호출부는 이걸 받아 원래 하려던 동작을 이어 간다. */
  onLinked?: (walletAddress: string) => void
}) {
  const { setWalletLinked } = useAuth()
  const [step, setStep] = useState<Step>('connect')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [linked, setLinked] = useState<string | null>(null)
  /* 연동과 함께 접수된 가입 보너스. 완료 화면의 한 줄이 이것만 보고 그릴지 말지 정한다.
     서버 체인이 꺼져 있으면 null 로 와서 줄이 아예 나오지 않는다. */
  const [bonus, setBonus] = useState<WalletLinkResult['bonus']>(null)
  /* 보너스가 실제로 들어왔는지 묻는다(2026-09-20, QA 09-18 "가입 시 ANT 가 들어오지 않음").
     전에는 "곧 지급됩니다" 만 남기고 끝이라, 0 ANT 를 본 사람은 안 준 것으로 읽었다.
     **창은 잠그지 않는다** — 확정까지 붙잡는 안은 연동 뒤에 하려던 일(예측 등록)을 막아서 버렸다.
     확정을 못 보고 닫으면 useOperation 이 정리하면서 잔액 저장소에 넘긴다(1분간 자주 확인).
     서버는 작업 조회에 종류를 가리지 않아 보너스 작업도 그대로 읽힌다. */
  const bonusStatus = useOperation(bonus?.operationId ?? null).operation?.status
  const [error, setError] = useState<ApiError | null>(null)

  const dialogRef = useRef<HTMLDivElement>(null)

  /* 지갑 창이 떠 있는 동안 닫으면 서명이 허공에 뜬다. working 에서는 잠근다. */
  const closable = step !== 'working'
  const close = useCallback(() => { if (closable) onClose() }, [closable, onClose])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [close])

  // 모달이 열리면 초점을 안으로 들인다. 없으면 탭이 뒤 화면을 돌아다닌다.
  useEffect(() => { dialogRef.current?.focus() }, [])

  /* ① 지갑 연결 → ② nonce 발급 → ③ payload 조립.
     서명은 여기서 하지 않는다 — 사용자가 payload 를 본 뒤 confirm 에서 한다. */
  async function connect() {
    setError(null)
    setStep('working')
    try {
      const address = await connectAddress()
      const nonce = await requestNonce('WALLET_LINK')
      setDraft({ address, payload: signingPayload('WALLET_LINK', address, nonce), chainId: nonce.chainId })
      setStep('confirm')
    } catch (e) {
      setError(e as ApiError)
      setStep('connect')
    }
  }

  /* ④ 서명 → ⑤ 연동 확정.
     nonce 는 본문에 넣지 않는다. 서버가 userId 로 꺼내 payload 를 재조립한다. */
  async function sign() {
    if (!draft) return
    setError(null)
    setStep('working')
    try {
      const signature = await personalSign(draft.payload, draft.address)
      const result = await linkWallet(draft.address, signature)
      setLinked(result.walletAddress)
      /* 서버 MR 이 아직 안 붙었으면 필드가 없다. undefined 를 null 로 눕혀 두 경우를 한 줄로 다룬다 */
      setBonus(result.bonus ?? null)
      setWalletLinked(true)
      setStep('done')
    } catch (e) {
      const err = e as ApiError
      setError(err)
      /* 서버까지 갔다면 nonce 는 이미 소비됐다 — SignatureGuard.recover 가 검증보다
         먼저 태운다(재시도로 서명을 갈아 끼우며 맞춰 보는 걸 막기 위해서다).
         그래서 같은 payload 로 다시 서명해도 NONCE_NOT_FOUND 뿐이다.
         연결 단계로 돌려 nonce 를 새로 받게 한다.

         지갑에서 거부하거나 확장이 없어 서버에 닿지 못한 실패(status 0)만
         payload 를 살려 둔다. 그때는 nonce 가 그대로 남아 있어 재서명이 된다. */
      if (err.status === 0) {
        setStep('confirm')
      } else {
        setDraft(null)
        setStep('connect')
      }
    }
  }

  const text = error ? errorText(error) : null
  const terminal = error !== null && isTerminal(error)
  /* 주입이 렌더보다 늦을 수 있어 상태로 들고 구독한다. 한 번 확인으로 끝내면
     늦게 주입된 지갑을 영영 못 본다(provider.ts 의 onWalletReady). */
  const [walletReady, setWalletReady] = useState(hasWallet)
  useEffect(() => onWalletReady(() => setWalletReady(true)), [])

  return createPortal(
    <div className="wl-backdrop" onClick={close}>
      <div
        className="wl-modal" role="dialog" aria-modal="true" aria-labelledby="wl-title"
        tabIndex={-1} ref={dialogRef}
        // 배경 클릭으로 닫히므로 내부 클릭이 거기까지 올라가지 않게 막는다
        onClick={(e) => e.stopPropagation()}
      >
        <div className="wl-head">
          <h2 id="wl-title">지갑 연동</h2>
          <button
            type="button" className="wl-x" aria-label="닫기"
            onClick={close} disabled={!closable}
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>

        {step === 'done' && linked ? (
          <div className="wl-body">
            <p className="wl-done-mark" aria-hidden="true">
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="9" /><path d="m8.5 12.2 2.4 2.4 4.6-4.9" />
              </svg>
            </p>
            <p className="wl-done-title">지갑을 연동했습니다</p>
            <code className="wl-address">{linked}</code>

            {/* 보너스를 알 수 있는 화면은 여기뿐이다. 세 가지로 갈린다.
                · 확정(SUCCEEDED)  — 그때만 과거형이다. 보통 연동 뒤 4~8초.
                · 전송 실패(FAILED) — 서버 재시도 배치가 10분 안팎에 다시 보낸다. "최대 1분" 이
                  거짓이 되는 유일한 경우라 문구를 바꾼다.
                · 그 밖(확인 중 · 조회 실패) — 입금 중. 정상이면 블록 2초 + 인덱서 5초 안쪽이다.
                금액은 서버 설정(app.token.amounts.signup-bonus)이라 응답값을 그대로 쓴다. */}
            {bonus && (
              bonusStatus === 'SUCCEEDED' ? (
                <p className="wl-done-bonus is-paid">
                  가입 보너스 {formatToken(bonus.amount)} ANT 가 지급됐습니다
                </p>
              ) : bonusStatus === 'FAILED' ? (
                <p className="wl-done-bonus">
                  가입 보너스가 늦어지고 있어요. 잠시 뒤 자동으로 들어와요.
                  <small>창을 닫아도 괜찮습니다.</small>
                </p>
              ) : (
                <p className="wl-done-bonus">
                  가입 보너스 {formatToken(bonus.amount)} ANT가 들어오는 중이에요. 최대 1분 걸려요.
                  <small>창을 닫아도 괜찮습니다.</small>
                </p>
              )
            )}

            {/* 연동은 끝났지만 지갑 목록에는 아직 ANT 가 없다. 이 칸이 스스로 지갑에 넣는다 —
                "곧 지급됩니다" 를 읽은 사람이 다음에 할 일이라 묻지 않고 바로 부른다
                (TokenAutoAdd 머리말). 실패하면 그 안에서 손으로 넣는 절차로 넘어간다. */}
            {draft && <TokenAutoAdd chainId={draft.chainId} />}

            <p className="wl-note">이제 예측을 등록하고 토큰을 받을 수 있어요.</p>
            <div className="wl-actions">
              <button
                type="button" className="wl-btn solid"
                onClick={() => { onLinked?.(linked); onClose() }}
              >
                확인
              </button>
            </div>
          </div>
        ) : (
          <div className="wl-body">
            {/* 지갑 확장이 아예 없으면 연결 버튼을 눌러도 할 수 있는 게 없다.
                누르게 한 뒤 실패시키지 않고 처음부터 안내로 바꾼다. */}
            {!walletReady ? (
              <>
                <p className="wl-lead">브라우저에서 지갑을 찾을 수 없습니다.</p>
                <p className="wl-note">
                  지갑 확장을 설치하고 잠금을 해제한 뒤 이 창을 다시 열어 주세요.
                </p>
                {/* "설치하세요" 만 두면 무엇을 어디서 설치할지 알 수 없다.
                    새 탭으로 연다 — 이 창을 닫으면 하던 예측 등록이 끊긴다.
                    rel 은 새 탭이 이 페이지를 조작하지 못하게 막는다. */}
                <a
                  className="wl-btn solid wl-install"
                  href={WALLET_INSTALL_URL}
                  target="_blank"
                  rel="noreferrer noopener"
                >
                  지갑 설치하기
                </a>

                {/* 확장은 **페이지가 로드될 때만** 주입된다. 이 탭을 열어 둔 채
                    설치하면 이 탭에는 들어오지 않아, 설치를 마치고 돌아와도
                    같은 안내가 그대로 남는다. 그때 할 일은 새로고침뿐이라
                    말로만 알리지 않고 버튼으로 준다. */}
                <button
                  type="button"
                  className="wl-btn ghost wl-reload"
                  onClick={() => window.location.reload()}
                >
                  설치를 마쳤다면 새로고침
                </button>
              </>
            ) : (
              <>
                <p className="wl-lead">
                  {draft
                    ? '아래 내용에 서명하면 지갑이 계정에 연결돼요.'
                    : '계정에 연결할 지갑을 선택해 주세요.'}
                </p>

                {draft && (
                  <>
                    {/* 네트워크는 연동보다 먼저다. 연동 자체는 체인과 무관하지만(서명 + DB),
                        끝나는 순간 ANT 를 넣고 그건 **지갑이 보고 있는 네트워크에** 들어간다.
                        여기 두는 이유 — 서비스 체인을 알게 되는 가장 이른 지점이 이 화면이다.
                        chainId 는 POST /wallet/nonce 응답에 실려 오므로 연결 전에는 알 수 없다. */}
                    <NetworkAdd chainId={draft.chainId} />

                    <div className="wl-field">
                      <span className="wl-label">연결할 주소</span>
                      <code className="wl-address" title={draft.address}>
                        {shortAddress(draft.address)}
                      </code>
                    </div>

                    {/* SignConfirm — 접지 않는다. 서명할 원문을 그대로 보여준다 */}
                    <div className="wl-field">
                      <span className="wl-label">서명할 내용</span>
                      <pre className="wl-payload">{draft.payload}</pre>
                    </div>

                    <p className="wl-note">
                      이 서명은 소유 확인에만 쓰입니다. 자산이 이동하거나 수수료가 들지 않습니다.
                    </p>
                  </>
                )}
              </>
            )}

            {text && (
              <div className="wl-error" role="alert">
                <b>{text.title}</b>
                {text.hint && <span>{text.hint}</span>}
              </div>
            )}

            <div className="wl-actions">
              <button type="button" className="wl-btn ghost" onClick={close} disabled={!closable}>
                {terminal ? '닫기' : '취소'}
              </button>

              {/* 409 는 재시도로 풀리지 않는다 — 진행 버튼 자체를 내린다 */}
              {walletReady && !terminal && (
                <button
                  type="button" className="wl-btn solid"
                  onClick={draft ? sign : connect}
                  disabled={step === 'working'}
                >
                  {step === 'working' ? '지갑 확인 중…' : draft ? '서명하고 연동하기' : '지갑 연결하기'}
                </button>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
    ,
    document.body,
  )
}
