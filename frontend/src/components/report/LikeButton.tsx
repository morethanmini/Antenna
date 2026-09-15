/* 좋아요 토글 — 리포트와 댓글이 같이 쓴다 [ANT-REPORT-SOCIAL]

   설계 제약
   - 서버가 등록 응답에 갱신된 likeCount 를 담아 준다. 취소는 204 라 본문이 없어
     화면이 1을 뺀다 — 취소 뒤 정확한 수를 알려면 재조회뿐인데, 그 한 번을 아끼는 대신
     다른 사람이 같은 순간 누른 값이 반영되지 않는 정도를 감수한다.
   - 누르자마자 화면을 바꾸고(낙관적) 실패하면 되돌린다. 하트가 굳어 있으면 눌린 건지
     안 눌린 건지 알 수 없다.
   - 연타를 막는다. 등록·취소가 엇갈려 나가면 409 DUPLICATE_LIKE 가 뜨거나 수가 어긋난다.

   잠긴 리포트에는 이 버튼을 아예 그리지 않는다(호출부 판단). 서버도 403 으로 막지만,
   누를 수 없는 버튼을 내밀어 놓고 누르면 오류를 띄우는 화면이 되지 않게 한다. */
import { useState } from 'react'
import { ApiError } from '../../api/client'
import { errorText } from '../state/errorText'
import '../../styles/like-button.css'

type Props = {
  likeCount: number
  liked: boolean
  /** 등록. 갱신된 수를 돌려준다 */
  onLike: () => Promise<{ likeCount: number }>
  /** 취소. 204 라 돌려줄 수가 없다 */
  onUnlike: () => Promise<void>
  /** 댓글 줄에서는 작게 그린다 */
  small?: boolean
}

export default function LikeButton({ likeCount, liked, onLike, onUnlike, small }: Props) {
  const [count, setCount] = useState(likeCount)
  const [on, setOn] = useState(liked)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  async function toggle() {
    if (busy) return
    setBusy(true)
    setError(null)

    // 되돌릴 값을 먼저 잡아 둔다 — 아래에서 state 를 이미 바꾼 뒤다.
    const wasOn = on
    const wasCount = count
    setOn(!wasOn)
    setCount(wasOn ? Math.max(0, wasCount - 1) : wasCount + 1)

    try {
      if (wasOn) {
        await onUnlike()
      } else {
        // 등록만 정확한 수를 알려준다. 그 값으로 낙관적 추정을 덮는다.
        const { likeCount: fresh } = await onLike()
        setCount(fresh)
      }
    } catch (e) {
      setOn(wasOn)
      setCount(wasCount)
      setError(e as ApiError)
    } finally {
      setBusy(false)
    }
  }

  return (
    <span className={small ? 'like like-sm' : 'like'}>
      <button
        type="button"
        className={on ? 'like-btn on' : 'like-btn'}
        onClick={() => void toggle()}
        disabled={busy}
        aria-pressed={on}
        aria-label={on ? '좋아요 취소' : '좋아요'}
      >
        {/* 누른 상태는 채워서, 아닌 상태는 선으로 그린다 — 색만으로 가르지 않는다 */}
        <svg width={small ? 13 : 15} height={small ? 13 : 15} viewBox="0 0 24 24"
             fill={on ? 'currentColor' : 'none'} stroke="currentColor"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21.2l7.8-7.7 1-1.1a5.5 5.5 0 0 0 0-7.8z" />
        </svg>
        <span className="num">{count.toLocaleString('ko-KR')}</span>
      </button>

      {/* 오류는 버튼 옆에 한 줄로. 되돌린 뒤라 화면 숫자는 이미 정확하다 */}
      {error && <span className="like-error">{errorText(error).title}</span>}
    </span>
  )
}
