/* 오류 표현. 설계서 §6 — code 로 분기하고 서버 message 는 노출하지 않는다.

   403 은 여기로 오지 않는다. 잠금은 오류가 아니라 구독 유도라서 LockedCard 가 맡는다.
   404 도 "빈 상태" 로 그리는 편이 맞을 때가 많다 — EmptyState 를 쓴다. */
import type { ApiError } from '../../api/errors'
import { errorText } from './errorText'

type Props = {
  error: ApiError
  /** 다시 시도할 수 있으면 넘긴다. 재시도 가능한 오류에서만 버튼이 보인다 */
  onRetry?: () => void
  /** 화면 전체를 채울지, 카드 안에 들어갈지 */
  inline?: boolean
}

export default function ErrorState({ error, onRetry, inline }: Props) {
  const text = errorText(error)

  return (
    <div className={inline ? 'state state-inline' : 'state'} role="alert">
      <p className="state-title">{text.title}</p>
      {text.hint && <p className="state-hint">{text.hint}</p>}

      {onRetry && text.retryable && (
        <button type="button" className="state-cta" onClick={onRetry}>
          다시 시도
        </button>
      )}

      {/* 원인 추적용 — **개발 빌드에서만** 보인다. 서버 message 는 어디서도 나가지 않는다.
          배포본에서도 보였던 탓에 "체인 기록을 찾을 수 없습니다" 아래에 ANCHOR_NOT_FOUND 가
          한 줄 더 붙었다(2026-09-22 QA). 사람 말로 이미 설명한 것을 코드로 한 번 더 말하면
          고칠 수 있는 것처럼 읽히고, 내부 어휘가 그대로 드러난다. */}
      {import.meta.env.DEV && <p className="state-code">{error.code}</p>}
    </div>
  )
}
