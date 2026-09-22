/* M-03 구독 결제 확인 (모달 · 라우트 없음)
   담당 스토리 [ANT-FE-CHANNEL-PROFILE]
   설계서 docs/화면설계서.md §3 M · §4 E-02 · §4 M-02 · §4 M-03

   설계 제약
   - 구독은 즉시 토글이 아니다. M-03 확인 → 서명 → 202 → M-02 → ACTIVE 다.
     버튼 하나로 끝나는 것처럼 보이게 만들지 않는다 — 그래서 단계가 화면에 드러난다.
   - 최초 구독 서명에는 오퍼레이터 위임 승인(operatorAuthorize)이 포함된다.
     자동 갱신이 여기에 달려 있으므로 모달에서 반드시 고지한다.
   - 결제 시점의 fee 가 박제된다. 이후 채널이 가격을 바꿔도 기존 구독은 옛 가격이다.

   operatorAuthorize 에 대해
   서명 원문에 operatorAuthorize=true 가 고정 문자열로 들어간다(서버 SubscriptionService.
   Signed, 2026-09-16 확인). 위임을 실제로 체인에 거는 경로는 아직 없고 ANT-TOKEN-06 몫이다 —
   그래서 승인 필드를 따로 두지 않고 문구로 고지한다. 원문은 api/channels.ts 의
   subscribeSigningPayload 한 곳에만 있다.

   M-01 에서 배운 것을 그대로 잇는다
   - 지갑이 없으면 서명 창을 띄우지 않고 M-01 로 먼저 보낸다. 실패시켜 놓고
     "지갑을 연동하세요" 라고 말하면 한 번 헛돌린 뒤에 알려 주는 셈이다.
   - 서버까지 간 실패는 nonce 가 이미 소비됐다(SignatureGuard.recover 가 검증보다
     먼저 태운다). 그래서 재시도는 nonce 발급부터 다시 한다.

   왜 waiting 에서 닫기를 허용하는가
   결제는 체인에서 진행 중이고 화면이 붙어 있을 이유가 없다. 닫지 못하게 하면
   3분짜리 대기에 사용자를 묶어 둔다. 서명 중(signing)만 잠근다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import { subscribe, subscribeSigningPayload, type Channel } from '../../api/channels'
import { ERROR_CODE, type ApiError } from '../../api/errors'
import { useOperation } from '../../api/operations'
import { formatToken, requestNonce } from '../../api/wallet'
import { useWalletBalance } from '../../api/walletBalance'
import { connectAddress, hasWallet, personalSign } from '../../wallet/provider'
import { useAuth } from '../../auth/context'
import { errorText } from '../state/errorText'
import OperationProgress from '../operation/OperationProgress'
import '../../styles/screens/subscribe.css'

/* confirm  가격·기간·위임을 펼쳐 놓고 동의를 기다린다 (SignConfirm)
   signing  nonce 발급 → 지갑 서명 → 202 수신
   waiting  M-02 — 체인 확정 대기
   done     SUCCEEDED */
type Step = 'confirm' | 'signing' | 'waiting' | 'done'

type Props = {
  channel: Channel
  onClose: () => void
  /** 접수(202)되거나 ACTIVE 가 되면 호출부가 채널을 다시 읽는다. 두 번 불려도 같은 뜻이다 */
  onSubscribed: () => void
  /** 지갑이 없을 때. 이 모달이 M-01 을 직접 띄우면 모달 2겹이 된다 */
  onNeedWallet: () => void
}

export default function SubscribeModal({ channel, onClose, onSubscribed, onNeedWallet }: Props) {
  const { user } = useAuth()
  const [step, setStep] = useState<Step>('confirm')
  const [error, setError] = useState<ApiError | null>(null)
  const [operationId, setOperationId] = useState<string | null>(null)

  const op = useOperation(operationId)
  /* 상단 칩과 같은 저장소를 읽는다(ANT-FE-WALLET-SYNC) — 이 창의 "보유 N ANT" 와 칩이 어긋나지 않는다.
     fresh: 이 창이 열린 뒤에 읽은 값만 받는다. 그 전에는 data 가 없어 아래 short 가 서지 않는다 —
     "열 때 새로 읽고, 못 읽었으면 막지 않는다" 는 전의 규칙 그대로다. */
  const wallet = useWalletBalance({ fresh: true })
  const dialogRef = useRef<HTMLDivElement>(null)

  const closable = step !== 'signing'
  const close = useCallback(() => { if (closable) onClose() }, [closable, onClose])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [close])

  // 모달이 열리면 초점을 안으로 들인다. 없으면 탭이 뒤 화면을 돌아다닌다.
  useEffect(() => { dialogRef.current?.focus() }, [])

  const status = op.operation?.status

  /* 성공은 폴링 결과에서 바로 읽는다 — setStep 으로 옮겨 적으면 같은 사실을 두 곳에
     두게 되고, effect 안 setState 라 렌더가 한 번 더 돈다(oxlint react/set-state-in-effect).
     step 은 사용자가 만든 단계만 들고, 화면은 이 파생값으로 그린다. */
  const view: Step = status === 'SUCCEEDED' ? 'done' : step

  /* 성공을 호출부에 알리는 것만 effect 로 남는다. 여기서는 상태를 바꾸지 않는다 —
     외부(체인)에서 일어난 변화를 화면 밖으로 전하는 일이라 effect 가 제자리다. */
  useEffect(() => {
    if (status === 'SUCCEEDED') onSubscribed()
  }, [status, onSubscribed])

  async function start() {
    /* 미설치와 미연동을 여기서 가르지 않는다. M-01 이 이미 네 갈래(미설치 · 서명 거부 ·
       주소 불일치 · 이미 연동됨)를 각각 다른 안내로 다루고, 늦게 주입되는 확장까지
       onWalletReady 로 기다려 준다. 여기서 미설치만 걸러 문구를 쓰면 같은 안내가 두 곳에
       생긴다 — ANT-FE-WALLET-GATE 가 PredictTab 에 세운 판단을 그대로 따른다.

       walletLinked 를 함께 보는 것이 핵심이다. 확장은 깔았지만 계정에 연동하지 않은
       사람을 hasWallet 만으로 통과시키면, 서명까지 다 받은 뒤 서버에서
       WALLET_NOT_LINKED 로 튕긴다. 헛서명을 시키지 않는다. */
    if (!hasWallet() || !user?.walletLinked) {
      onNeedWallet()
      return
    }
    setError(null)
    setStep('signing')
    try {
      const address = await connectAddress()
      const nonce = await requestNonce('SUBSCRIBE')
      /* 원문은 구독 전용이다. 범용 빌더(address·chainId·nonce)로 서명하면 서버가 조립한
         원문(publisherId·fee·operatorAuthorize)과 달라 복원 주소가 어긋나고, 지갑을 바꿔도
         절대 통과하지 않는 SIGNER_MISMATCH 가 난다(2026-09-16 운영에서 실제로 났다). */
      const signature = await personalSign(
        subscribeSigningPayload(channel.userId, channel.fee, nonce), address,
      )

      const { operationId: id } = await subscribe(channel.userId, { signature })
      setOperationId(id)
      setStep('waiting')
      /* 접수된 순간부터 뒤 화면은 낡았다. 여기서 알리지 않으면 확정 전에 닫았을 때
         호출부가 채널을 다시 읽지 않아 "구독하기" 버튼이 그대로 남고, 다시 누르면
         PENDING 중복으로 409 다(QA 09-18). 다시 읽으면 '결제 확인 중' 배지가 선다. */
      onSubscribed()
    } catch (e) {
      setError(e as ApiError)
      /* nonce 를 들고 있지 않아 M-01 처럼 payload 를 살려 둘 것이 없다 — 확인 단계로
         돌리면 다음 시도가 nonce 발급부터 다시 한다.
         멱등 키는 202 를 받기 전까지 유지된다(api/channels.ts subscribe). 서명만 다시 받아
         재시도해도 서버가 같은 키·같은 채널을 재생하므로 결제가 두 번 나가지 않는다. */
      setStep('confirm')
    }
  }

  const text = error ? subscribeErrorText(error) : null
  /* 구독료 미설정(fee === null) 채널은 E-02 가 구독 버튼 자체를 내주지 않아 여기 닿지
     않는다. 그래도 금액 자리를 빈 문자열로 두지 않는다 — 만약 닿았을 때 "결제 금액"
     칸이 비면 얼마가 빠져나가는지 모르는 채로 서명하게 된다. */
  const fee = channel.fee === null ? '금액 미정' : `${formatToken(channel.fee)} ANT`

  /* 잔액이 구독료보다 적으면 서명까지 가지 않는다(2026-09-19, QA 09-18 "0원인데 구독을 누르면 결제 확인 중").
     서버는 이미 INSUFFICIENT_BALANCE 로 막지만 그건 지갑 서명을 받은 **뒤**다 — 헛서명을 시키지 않는다.
     잔액을 못 읽었으면 막지 않는다. 모르는 것으로 구독을 막을 수는 없고, 서버가 마지막에 본다.
     ponytail: 이 숫자는 원장(token_ledger) 합이라 서버가 보는 체인 가용잔액과 드물게 어긋난다 —
     GET /wallet/balance 가 가용잔액을 내리면 그걸로 바꾼다(PredictTab 의 배팅 잠금과 같은 한계). */
  const balance = wallet.data?.balance
  const short = channel.fee !== null && !!balance
    && /^\d+$/.test(balance) && /^\d+$/.test(channel.fee) && BigInt(balance) < BigInt(channel.fee)

  return (
    <div className="sub-backdrop" onClick={close} role="presentation">
      <div
        className="sub-modal" role="dialog" aria-modal="true" aria-labelledby="sub-title"
        tabIndex={-1} ref={dialogRef} onClick={(e) => e.stopPropagation()}
      >
        <div className="sub-head">
          <h2 id="sub-title">{view === 'done' ? '구독이 시작됐습니다' : '구독 결제 확인'}</h2>
          {closable && (
            <button type="button" className="sub-x" aria-label="닫기" onClick={close}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M6 6l12 12" /><path d="M18 6 6 18" />
              </svg>
            </button>
          )}
        </div>

        {view === 'confirm' && (
          <div className="sub-body">
            <dl className="sub-terms">
              <div>
                <dt>채널</dt>
                <dd>{channel.nickname}</dd>
              </div>
              <div>
                <dt>결제 금액</dt>
                <dd className="num sub-amount">{fee}</dd>
              </div>
              <div>
                <dt>이용 기간</dt>
                <dd>30일</dd>
              </div>
            </dl>

            {/* 결제 시점 가격 박제. 나중에 값이 달라 보이는 이유를 미리 알린다.
                "지금 금액이 고정됩니다" 를 앞에 두었다가 걷었다(2026-09-23) — 뒷문장이
                같은 말을 값까지 넣어 다시 하고 있었다. */}
            <p className="sub-note">
              채널이 가격을 올려도 만료일까지 <b>{fee}</b> 그대로입니다.
            </p>

            {/* 설계 제약 — 반드시 고지한다. 자동 갱신이 여기에 달려 있다 */}
            <section className="sub-authorize">
              {/* "오퍼레이터 위임 승인" 은 계약 용어고 화면 어디에도 그 말이 없다.
                  범위(구독료만)는 돈이 걸린 고지라 남기되, "인출되지 않습니다" 대신
                  무엇이 빠져나가는지로 적는다(2026-09-23). */}
              <p className="sub-authorize-title">자동 갱신 권한도 같이 승인합니다</p>
              <p>
                다음 달부터 구독료를 대신 낼 수 있게 해 주는 서명입니다.
                <b> 매달 구독료만 빠져나갑니다.</b>
              </p>
              <p className="sub-authorize-off">
                마이페이지 <b>구독 관리</b>에서 언제든 끌 수 있습니다. 끈 뒤에도 만료일까지 봅니다.
              </p>
            </section>

            {text && (
              <p className="sub-error" role="alert">
                <b>{text.title}</b>
                {text.hint && <span>{text.hint}</span>}
              </p>
            )}

            {short && (
              <p className="sub-error" role="alert">
                <b>ANT 잔액이 부족합니다</b>
                <span>{`보유 ${formatToken(balance)} ANT · 구독료 ${fee}. 지갑에서 잔액을 확인해 주세요.`}</span>
              </p>
            )}

            <div className="sub-actions">
              <button type="button" className="sub-btn" onClick={close}>취소</button>
              <button type="button" className="sub-btn solid" disabled={short} onClick={() => void start()}>
                서명하고 구독하기
              </button>
            </div>
          </div>
        )}

        {view === 'signing' && (
          <div className="sub-body sub-progress" aria-live="polite">
            <span className="sub-spinner" aria-hidden="true" />
            <p className="sub-progress-title">지갑에서 서명을 기다리고 있습니다</p>
            <p className="sub-sub">지갑 창이 뜨지 않으면 확장 아이콘을 눌러 확인해 주세요.</p>
          </div>
        )}

        {/* 대기·실패 갈래는 M-02 공유 본문이 그린다([ANT-FE-OPERATION]).
            여기서 따로 쓰면 같은 상황을 네 흐름이 제각기 말하게 된다 */}
        {view === 'waiting' && (
          <div className="sub-body">
            <OperationProgress op={op} kind="SUBSCRIBE" />
            <div className="sub-actions">
              <button type="button" className="sub-btn" onClick={close}>닫기</button>
            </div>
          </div>
        )}

        {view === 'done' && (
          <div className="sub-body sub-progress" aria-live="polite">
            <span className="sub-check" aria-hidden="true">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                <path d="m5 12.5 4.5 4.5L19 7.5" />
              </svg>
            </span>
            <p className="sub-progress-title">{`${channel.nickname} 채널을 30일간 이용합니다`}</p>
            <p className="sub-sub">미판정 예측과 근거를 이제 볼 수 있습니다.</p>
            <div className="sub-actions">
              <button type="button" className="sub-btn solid" onClick={onClose}>확인</button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/* SIGNER_MISMATCH 는 구독에서 뜻이 둘이다 — 다른 지갑으로 서명했거나, 서명 뒤 접수 전에
   채널이 구독료를 바꿔 서버가 **새 fee 로 원문을 조립**했거나(서버 Signed 주석 — 모르는 값으로
   결제되느니 거절한다). 서버가 둘을 같은 코드로 내리므로 공용 문구("연동하려는 주소의 계정으로
   바꾸라")는 후자에서 사용자를 엉뚱한 곳으로 보낸다. 여기서만 두 갈래를 함께 말한다. */
function subscribeErrorText(error: ApiError) {
  if (error.code !== ERROR_CODE.SIGNER_MISMATCH) return errorText(error)
  return {
    title: '서명이 맞지 않습니다',
    hint: '구독료가 방금 바뀌었거나 다른 지갑으로 서명했습니다. 채널을 새로고침해 금액을 확인한 뒤 다시 서명해 주세요',
    retryable: true,
  }
}
