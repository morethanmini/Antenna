/* A-02 온보딩 2단계 — 구독료 · /onboarding/fee
   담당 스토리 [ANT-AUTH-03]

   ── 왜 가입 때 묻는가 ─────────────────────────────────────
   구독료를 정해야 비로소 그 회원의 채널이 열린다 — 정하기 전에는 서버가
   FEE_NOT_SET 으로 구독을 막는다(ChannelFeeService 머리말). 그런데 이 값을 정하는
   자리가 환경 설정 안쪽 탭 하나뿐이라, 예측을 올리고도 아무도 구독할 수 없는 채널로
   남는 경우가 생긴다.

   **가입 시점이 이 값을 정하기 가장 싼 순간이기도 하다.** 나중에 바꾸면 구독자
   전원에게 FEE_CHANGED 알림이 나가고 이력에 행이 남는데(그 이력 자체가 잦은 인상을
   막는 억제 장치다), 가입 때는 구독자가 0명이라 알림도 이력 부담도 없다.

   ── 건너뛸 수 있다 ───────────────────────────────────────
   가입하는 모두가 예측가는 아니다. 읽으러 온 사람에게 가격부터 정하라고 하면
   가입이 막힌다. 그래서 **필수 단계로 만들지 않았다** — RequireAccess 는 닉네임만
   보므로(그 파일 참고) 건너뛰어도 아무 데도 걸리지 않고, 나중에 환경 설정 →
   내 채널에서 정할 수 있다.

   ── returnTo 는 여기서 소비한다 ──────────────────────────
   닉네임 화면이 takeReturnTo() 를 부르면 이 화면에 오기 전에 값이 지워져, 로그인
   전에 막혔던 경로로 못 돌아간다. 그래서 그 호출을 이 화면으로 옮겼다. */
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FEE_MAX, isValidFee, updateChannelFee } from '../api/channelFee'
import { ApiError } from '../api/client'
import { formatToken } from '../api/wallet'
import { takeReturnTo } from '../auth/returnTo'
import '../styles/auth.css'

export default function OnboardingFee() {
  const navigate = useNavigate()

  const [value, setValue] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /* 빈 칸은 "아직 안 정함" 이라 오류가 아니다 — 건너뛰기로 나가는 길이 있다.
     뭔가 적었을 때만 형식을 본다. */
  const touched = value !== ''
  const valid = isValidFee(value)

  function leave() {
    navigate(takeReturnTo(), { replace: true })
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!valid) return

    setSubmitting(true)
    setError(null)
    try {
      await updateChannelFee(value)
      leave()
    } catch (e) {
      /* 저장만 실패한 것이고 가입은 이미 끝났다. 여기 가둬 두지 않고 다시 시도하거나
         건너뛸 수 있게 둔다 — 값은 환경 설정에서도 정할 수 있다. */
      setError(
        e instanceof ApiError && e.code === 'INVALID_REQUEST'
          ? '구독료를 다시 확인해 주세요.'
          : '구독료를 저장하지 못했습니다. 잠시 후 다시 시도하거나 나중에 정할 수 있습니다.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  const hint = !touched
    ? `0 ~ ${FEE_MAX.toLocaleString('ko-KR')} ANT 사이로 정해 주세요. 나중에 환경 설정에서 바꿀 수 있습니다.`
    : !valid
      ? `정수로, ${FEE_MAX.toLocaleString('ko-KR')} ANT 까지 정할 수 있습니다.`
      : value === '0'
        /* 0 을 서버가 받는다. 실수로 둔 것인지 일부러 무료로 여는 것인지 구분해 준다 */
        ? '무료 채널이 됩니다 — 누구나 구독해서 전문을 볼 수 있습니다.'
        : `구독자는 30일에 ${formatToken(value)} ANT 를 냅니다.`

  return (
    <div className="auth">
      <main className="auth-shell">
        <section className="sso" aria-label="구독료 설정">
          <h2>구독료를 정해 주세요</h2>
          <p className="sub">
            내 채널을 구독하는 사람이 30일마다 내는 금액입니다.
          </p>

          <form onSubmit={submit}>
            {/* inputMode=numeric 으로 폰에서 숫자판이 뜨게 한다. type=number 를 쓰지 않는
                이유 — 위아래 화살표와 휠 스크롤로 값이 바뀌고, 소수점·지수(1e5)까지 들어온다.
                ANT 는 decimals 0 이라 소수 구독료라는 것이 없다. */}
            <div className="fee-field">
              <input
                className="nickname-input fee-input"
                type="text"
                inputMode="numeric"
                value={value}
                onChange={(e) => { setValue(e.target.value.trim()); setError(null) }}
                maxLength={6}
                autoFocus
                aria-label="구독료"
                aria-describedby="fee-hint"
                aria-invalid={touched && !valid}
                placeholder="예: 5000"
              />
              <span className="fee-unit" aria-hidden="true">ANT</span>
            </div>
            <p
              className={`nickname-hint is-${!touched ? 'idle' : valid ? 'available' : 'invalid'}`}
              id="fee-hint"
              aria-live="polite"
            >
              {error ?? hint}
            </p>

            <button
              type="submit"
              className="sso-btn ssafy"
              disabled={submitting || !valid}
            >
              {submitting ? '저장 중…' : '저장하고 시작하기'}
            </button>
          </form>

          {/* 건너뛰기를 버튼으로 둔다 — 작은 글씨 링크로 숨기면 "안 정하면 못 나간다" 로
              읽힌다. 실제로는 읽으러 온 사람에게 이 단계가 필요 없다. */}
          <button
            type="button"
            className="sso-btn ghost"
            onClick={leave}
            disabled={submitting}
          >
            나중에 정하기
          </button>

          <p className="fee-note">
            정하기 전에는 아무도 내 채널을 구독할 수 없습니다. 예측과 리포트는 그대로 올릴 수 있습니다.
          </p>
        </section>
      </main>
    </div>
  )
}
