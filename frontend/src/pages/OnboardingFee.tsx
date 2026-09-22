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

   ── 0 으로 시작하고, 되돌릴 수 있다고 알린다 ─────────────
   가입하는 모두가 예측가는 아니다. 읽으러 온 사람에게 가격부터 정하라고 하면
   가입이 막힌다. 그래서 **기본값을 0(무료)으로 두었다** — 정할 것이 없는 사람은
   그대로 저장하고 지나가면 되고, 값은 나중에 마이페이지 → 설정 → 구독료에서
   바꾼다(H-03 의 첫 탭, components/settings/ChannelFeeSection).

   **필수 단계는 아니다** — RequireAccess 는 닉네임만 보므로(그 파일 참고) 이
   화면을 지나지 않아도 아무 데도 걸리지 않는다.

   ── 이미 정한 사람은 들여보내지 않는다 (2026-09-22 QA) ────
   같은 이유로 **온보딩을 마친 계정도 이 주소로 되돌아올 수 있다.** 그런데 이 화면은
   현재 값을 읽지 않고 늘 0 에서 시작하므로, 구독료가 500 인 채널에도 "0 · 무료 채널이
   됩니다" 를 보여 주고 그대로 저장하면 유료 채널이 무료로 떨어졌다. 위 머리말의 전제
   ("가입 때는 구독자가 0명이라 알림도 이력 부담도 없다")가 그때 깨진다 — 구독자에게
   FEE_CHANGED 가 나가고 이력에도 행이 남는다.

   그래서 **값이 이미 있으면 홈으로 돌려보낸다.** 여기서 현재 값을 읽어 채우는 길도
   있지만, 그러면 동일값 차단·구독자 알림 확인까지 이 화면이 다시 갖춰야 한다 — 그건
   이미 설정(ChannelFeeSection)이 하는 일이고, 바꾸는 자리는 거기 하나면 된다.

   ── 끝나면 홈으로 ────────────────────────────────────────
   저장하든 건너뛰든 인사이트 홈으로 보낸다. 전에는 로그인 전에 막혔던 경로로 되돌려
   보냈는데(auth/returnTo.ts) 그 장치를 통째로 걷어냈다(2026-09-17) — 가입 절차를 막
   끝낸 사람을 맥락 없는 화면에 떨어뜨리는 편이 오히려 헷갈린다. */
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FEE_MAX, fetchChannelFee, isValidFee, updateChannelFee } from '../api/channelFee'
import { ApiError } from '../api/client'
import { formatToken } from '../api/wallet'
import '../styles/auth.css'

export default function OnboardingFee() {
  const navigate = useNavigate()

  /* **0 으로 시작한다.** 가입 직후 가장 흔한 답이 "일단 받지 않겠다" 이고 그 뜻이
     0 이다. 빈 칸으로 두면 저장 버튼이 잠겨 있어, 무료로 열려는 사람까지 숫자를
     찍어야 넘어갈 수 있었다. 값을 정해 둘 사람은 0 을 덮어쓰면 된다(아래 select). */
  const [value, setValue] = useState('0')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /* 이미 정해 둔 값이 있으면 이 화면은 보여 주지 않는다(위 머리말). 다 읽기 전에
     폼을 그리면 0 이 한 번 번쩍이고, 그 사이에 저장을 누를 수도 있다.
     **못 읽으면 신규로 친다** — 조회가 실패했다고 가입 절차를 막을 이유는 없다. */
  const [ready, setReady] = useState(false)
  useEffect(() => {
    let alive = true
    fetchChannelFee()
      .then((c) => {
        if (!alive) return
        if (c.fee !== null) navigate('/', { replace: true })
        else setReady(true)
      })
      .catch(() => { if (alive) setReady(true) })
    return () => { alive = false }
  }, [navigate])

  /* 들어오자마자 0 을 통째로 잡아 둔다. autoFocus 만으로는 커서가 0 뒤에 서서
     바로 "5000" 을 치면 "05000" 이 된다 — 초점이 오는 순간에는 브라우저가 커서
     자리를 나중에 정하므로 onFocus 안에서 select() 해도 풀린다. 그래서 초점을
     주는 일까지 이 effect 가 맡는다(autoFocus 속성은 뺐다). */
  const inputRef = useRef<HTMLInputElement>(null)
  useEffect(() => {
    const el = inputRef.current
    if (!el) return
    el.focus()
    el.select()
  }, [ready])

  /* 칸을 비우면 "아직 안 정함" 이라 오류가 아니다 — 저장만 잠기고 값은 다시
     적을 수 있다. 뭔가 적었을 때만 형식을 본다. */
  const touched = value !== ''
  const valid = isValidFee(value)

  function leave() {
    navigate('/', { replace: true })
  }

  /* 같은 틱에 두 번 들어오는 것을 막는다. disabled={submitting} 은 state 라 다음
     커밋에야 서는데, 그 전에 두 번째 호출이 닿으면 PUT 이 두 번 나가고 구독자에게
     같은 알림이 두 번 간다(2026-09-22 QA). */
  const sending = useRef(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!valid || sending.current) return

    sending.current = true
    setSubmitting(true)
    setError(null)
    try {
      await updateChannelFee(value)
      leave()
    } catch (e) {
      /* 저장만 실패한 것이고 가입은 이미 끝났다. 버튼은 열어 둔 채로 두어 그대로
         다시 누를 수 있게 한다 — 값은 마이페이지 → 설정 → 구독료에서도 정할 수 있다. */
      setError(
        e instanceof ApiError && e.code === 'INVALID_REQUEST'
          ? '구독료를 다시 확인해 주세요.'
          : '구독료를 저장하지 못했습니다. 잠시 후 다시 시도하거나 나중에 정할 수 있습니다.',
      )
    } finally {
      sending.current = false
      setSubmitting(false)
    }
  }

  const hint = !touched
    /* 범위를 말하지 않는다 — 상한은 형식이 틀렸을 때 아래 분기가 이미 알려 주고,
       "나중에 바꿀 수 있다" 는 .fee-note 가 늘 들고 있다. 빈 칸에서 막히는 이유는
       값을 못 적어서가 아니라 얼마가 적당한지 몰라서이므로, 그 기준을 준다. */
    ? '적중률이 쌓이기 전에는 낮게 시작하는 편이 구독자를 모으기 좋습니다.'
    : !valid
      ? `정수로, ${FEE_MAX.toLocaleString('ko-KR')} ANT 까지 정할 수 있습니다.`
      : value === '0'
        /* 0 을 서버가 받는다. 실수로 둔 것인지 일부러 무료로 여는 것인지 구분해 준다 */
        ? '무료 채널이 됩니다 — 누구나 구독해서 전문을 볼 수 있습니다.'
        : `구독자는 30일에 ${formatToken(value)} ANT 를 냅니다.`

  /* 조회가 끝나기 전에는 아무것도 그리지 않는다 — 여기서 잠깐 보이는 것은
     "0 · 무료 채널이 됩니다" 라, 이미 유료인 사람에게는 틀린 말이다. */
  if (!ready) return null

  return (
    <div className="auth">
      <main className="auth-shell">
        {/* fee-step — 이 화면의 버튼만 작게 둔다. .sso-btn 은 로그인 화면의
            SSO 버튼과 같은 규칙이라 전역으로 줄이면 그쪽이 같이 작아진다. */}
        <section className="sso fee-step" aria-label="구독료 설정">
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
                ref={inputRef}
                className="nickname-input fee-input"
                type="text"
                inputMode="numeric"
                value={value}
                /* 앞자리 0 은 값에 뜻이 없다. 설정 화면(ChannelFeeSection)과 같은 규칙이다 */
                onChange={(e) => {
                  setValue(e.target.value.trim().replace(/^0+(?=\d)/, ''))
                  setError(null)
                }}
                /* 다시 눌러 들어올 때도 0 을 덮어쓸 수 있게. 마우스로 누르는
                   경우에는 이 handler 만으로 선택이 유지된다 */
                onFocus={(e) => e.target.select()}
                maxLength={6}
                aria-label="구독료"
                aria-describedby="fee-hint"
                aria-invalid={touched && !valid}
                placeholder="예: 100"
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

          {/* "나중에 정하기" 버튼을 없애고 이 한 줄로 바꿨다. 기본값이 0 이라 그냥
              저장해도 되는 자리가 됐고, 버튼이 둘이면 "저장하면 못 바꾼다" 로 읽혀
              오히려 고르기 어려워진다. 되돌릴 수 있다는 사실만 알리면 충분하다. */}
          <p className="fee-note">
            구독료는 <b>마이페이지 → 설정 → 구독료</b> 에서 언제든 바꿀 수 있습니다.
          </p>
        </section>
      </main>
    </div>
  )
}
