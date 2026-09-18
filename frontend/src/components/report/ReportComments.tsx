/* F-02 리포트 댓글 [ANT-REPORT-SOCIAL]

   설계 제약
   - 오래된 순이다. 대화가 위에서 아래로 읽힌다 — 커서도 id 가 커지는 방향이라
     목록 순서와 어긋나지 않는다(서버 규칙 그대로).
   - 잠긴 리포트에서도 목록은 읽힌다. 쓰기만 막힌다(403 REPORT_FORBIDDEN) —
     반응이 오가는 리포트라는 사실이 통계와 같은 부류의 구독 유인이라서다.
     그래서 canWrite 가 false 면 입력란 대신 안내 한 줄을 둔다.
   - 작성 성공 후 목록을 통째로 다시 읽는다. 새 댓글을 손으로 이어 붙이면 그 사이
     다른 사람이 단 댓글이 빠진 채로 남고, 커서가 어디까지 왔는지도 어긋난다.
   - 대댓글은 없다. 서버가 부모 id 를 받지 않는다.
   - 신고(M-06)는 줄마다 오른쪽 끝 ⋯ 에 접어 둔다. 내 댓글에는 그리지 않는다.
     승인되면 서버가 BLOCKED 로 내려 목록에서 빠진다 — 화면이 따로 지우지 않는다.

   개수는 서버 commentCount 로 그린다 — items.length 가 아니다
   목록은 커서 페이징이라 items 에는 불러온 만큼만 있다. 20건 넘는 리포트에서
   items.length 를 쓰면 "댓글 20" 으로 보이다가 더 보기를 누를 때마다 늘어난다.
   작성 성공 시에는 재조회 없이 1을 더한다 — 총 개수를 주는 경로가 따로 없고,
   상세(GET /reports/{id})를 다시 부르면 조회수가 함께 올라간다. 그 사이 다른
   사람이 단 댓글만큼 어긋날 수 있지만, 다음 진입에서 서버 값으로 되돌아온다. */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { useCursorList } from '../../api/useCursorList'
import {
  COMMENT_MAX, createComment, formatDateTime, likeComment, unlikeComment,
  type ReportComment,
} from '../../api/reports'
import { ERROR_CODE } from '../../api/errors'
import ErrorState from '../state/ErrorState'
import { errorText } from '../state/errorText'
import LikeButton from './LikeButton'
import AbuseMenu from './AbuseMenu'
import { useAuth } from '../../auth/context'
import '../../styles/report-comments.css'

type Props = {
  reportId: number
  /** 서버가 센 총 개수. BLOCKED 는 빠진 값이다 */
  commentCount: number
  /** 이 리포트에 댓글을 달 수 있는가. 잠긴 리포트면 false */
  canWrite: boolean
  /** 잠금을 푸는 채널. 안내에서 구독 진입점으로 쓴다 */
  channelId: string
}

export default function ReportComments({ reportId, commentCount, canWrite, channelId }: Props) {
  const list = useCursorList<ReportComment>(`/reports/${reportId}/comments`)
  const { user } = useAuth()
  const [count, setCount] = useState(commentCount)

  function afterCreate() {
    setCount((c) => c + 1)
    list.reload()
  }

  return (
    <section className="rc" aria-labelledby="rc-heading">
      <h2 className="rc-heading" id="rc-heading">
        댓글
        {count > 0 && <span className="rc-count num">{count.toLocaleString('ko-KR')}</span>}
      </h2>

      {canWrite
        ? <CommentForm reportId={reportId} onDone={afterCreate} />
        : (
          <p className="rc-gate">
            구독자만 댓글을 남길 수 있습니다.
            {' '}
            <Link to={`/channels/${channelId}`}>채널 보러 가기</Link>
          </p>
        )}

      {list.error && <ErrorState error={list.error} onRetry={list.reload} />}

      {!list.error && list.items.length === 0 && !list.loading && (
        <p className="rc-empty">아직 댓글이 없습니다.</p>
      )}

      {list.items.length > 0 && (
        <ul className="rc-list">
          {list.items.map((c) => (
            <li key={c.id} className="rc-item">
              <div className="rc-item-head">
                <Link className="rc-author" to={`/channels/${c.author.userId}`}>
                  {c.author.nickname}
                </Link>
                <span className="rc-dot" aria-hidden="true">·</span>
                <span className="rc-when">{formatDateTime(c.createdAt)}</span>
                {user?.id !== c.author.userId && (
                  <AbuseMenu
                    small
                    className="rc-menu"
                    targetType="REPORT_COMMENT"
                    targetId={c.id}
                    targetLabel={`${c.author.nickname} 님의 댓글을 신고합니다.`}
                    menuLabel="댓글 메뉴"
                  />
                )}
              </div>

              <p className="rc-body">{c.body}</p>

              {/* 댓글 좋아요도 리포트 잠금을 따른다 — 서버가 403 을 주고, 여기서도 숨긴다 */}
              {canWrite && (
                <LikeButton
                  small
                  likeCount={c.likeCount}
                  liked={c.liked}
                  onLike={() => likeComment(c.id)}
                  onUnlike={() => unlikeComment(c.id)}
                />
              )}
            </li>
          ))}
        </ul>
      )}

      {list.loading && list.items.length > 0 && <p className="rc-more-hint">불러오는 중…</p>}
      {list.hasNext && !list.loading && (
        <button type="button" className="rc-more" onClick={list.loadMore}>
          댓글 더 보기
        </button>
      )}
    </section>
  )
}

function CommentForm({ reportId, onDone }: { reportId: number; onDone: () => void }) {
  const [body, setBody] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (submitting || body.trim().length === 0) return

    setSubmitting(true)
    setError(null)
    try {
      await createComment(reportId, body.trim())
      setBody('')
      onDone()
    } catch (err) {
      setError(err as ApiError)
    } finally {
      setSubmitting(false)
    }
  }

  /* 400 은 어느 필드가 틀렸는지 field 로 온다. 입력란이 하나라 그 옆에 붙인다. */
  const fieldError = error?.code === ERROR_CODE.INVALID_REQUEST
  const formError = error && !fieldError ? errorText(error) : null

  return (
    <form className="rc-form" onSubmit={(e) => void submit(e)}>
      <textarea
        className="rc-input"
        value={body}
        onChange={(e) => { setBody(e.target.value); setError(null) }}
        maxLength={COMMENT_MAX}
        rows={3}
        disabled={submitting}
        aria-invalid={fieldError}
        aria-label="댓글"
        placeholder="이 리포트에 대해 남길 말이 있나요?"
      />

      <div className="rc-form-foot">
        <span className="rc-form-count num">{`${body.length}/${COMMENT_MAX}`}</span>
        <button
          type="submit"
          className="rc-submit"
          disabled={submitting || body.trim().length === 0}
        >
          {submitting ? '올리는 중…' : '댓글 남기기'}
        </button>
      </div>

      {fieldError && <p className="rc-form-error">댓글을 다시 확인해 주세요.</p>}
      {formError && (
        <p className="rc-form-error">
          {formError.title}
          {formError.hint && <span className="rc-form-hint">{formError.hint}</span>}
        </p>
      )}
    </form>
  )
}
