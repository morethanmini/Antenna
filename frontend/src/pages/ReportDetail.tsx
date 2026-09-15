/* F-02 리포트 상세 · /reports/:id
   담당 스토리 [ANT-FE-REPORT-DETAIL]
   설계서 docs/화면설계서.md §3 F · §4 F-02 · §5 · §7

   설계 제약
   - visibility=false 이고 미구독이면 200 + { preview, locked } 가 온다. 404 가 아니다
     — 없는 것처럼 보이면 구독 유인이 사라진다.
   - 서버가 잘라서 내려준 만큼만 그린다. 전문을 받아 CSS 로 가리지 않는다.
     서버가 잠긴 리포트에 body 를 아예 담지 않으므로 이 화면은 가릴 전문을 갖지 못한다.
   - 3줄 미리보기 + 페이드 + 구독 CTA 가 잠금 표현이다. 설계서 §7 의 SubscriptionGate 는
     [ANT-FE-ERROR] 가 만든 LockedCard 다 — preview·channelId 를 받아 그 셋을 그린다.
   - 구독 CTA 는 채널 프로필(E-02)로 보낸다. 결제 흐름(M-03 → 서명 → 202 → M-02)은
     그쪽 스토리 몫이라 여기서 만들지 않는다.

   채널 식별자에 대하여
   채널은 회원 1인당 하나다 — GET /channels/{userId}/reports 가 userId 를 쓴다.
   그래서 CTA 목적지는 작성자의 userId 다.

   좋아요·댓글 (ANT-REPORT-SOCIAL)
   잠긴 리포트에는 반응할 수 없다 — 서버가 403 REPORT_FORBIDDEN 을 준다. locked 를
   이미 알고 있으니 좋아요 버튼은 아예 그리지 않고, 댓글은 목록만 보여 준다(읽기는
   허용된다 — 반응이 오가는 리포트라는 사실이 미리보기와 같은 부류의 구독 유인이다). */
import Markdown from '../components/report/Markdown'
import { Link, useParams } from 'react-router-dom'
import { useApiQuery } from '../api/useApiQuery'
import {
  formatDate, likeReport, unlikeReport, type ReportDetail as Report,
} from '../api/reports'
import LikeButton from '../components/report/LikeButton'
import ReportComments from '../components/report/ReportComments'
import ErrorState from '../components/state/ErrorState'
import LockedCard from '../components/state/LockedCard'
import '../styles/screens/report-detail.css'

export default function ReportDetail() {
  const { id } = useParams<{ id: string }>()
  const report = useApiQuery<Report>(`/reports/${id}`)

  return (
    <main className="main">
      <div className="main-inner">
        <Link className="rd-back" to="/reports">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
          리포트 목록
        </Link>

        {/* 없는 리포트는 404 REPORT_NOT_FOUND 다. 잠금(200)과 다른 경로로 온다 */}
        {report.error && <ErrorState error={report.error} onRetry={report.reload} />}

        {!report.error && !report.data && (
          <p className="rd-loading">불러오는 중…</p>
        )}

        {report.data && (
          <article className="rd">
            <header className="rd-head">
              <h1 className="rd-title">{report.data.title}</h1>

              <div className="rd-meta">
                {/* 작성자 이름이 곧 채널 진입점이다 */}
                <Link className="rd-author" to={`/channels/${report.data.author.userId}`}>
                  {report.data.author.nickname}
                </Link>
                <span className="rd-dot" aria-hidden="true">·</span>
                <span>{formatDate(report.data.publishedAt)}</span>
                <span className="rd-dot" aria-hidden="true">·</span>
                <span className="num">{`조회 ${report.data.viewCount.toLocaleString('ko-KR')}`}</span>

                {/* 구독자 전용으로 발행된 글임을 알린다. 잠금 여부와 별개로 늘 붙는다
                    — 본인이나 구독자가 열어도 이 리포트가 유료라는 사실은 같다 */}
                {!report.data.visibility && (
                  <span className="rd-private">구독자 공개</span>
                )}
              </div>
            </header>

            {report.data.locked ? (
              /* title 을 직접 준다 — 공용 기본 문구는 조사를 "은(는)" 으로 흘려
                 "리포트 본문은(는) …" 이 된다. 이 화면은 label 이 고정이라 정확히 쓸 수 있다. */
              <LockedCard
                label="리포트 본문"
                title="리포트 본문은 구독자에게만 공개됩니다"
                preview={report.data.preview ?? undefined}
                channelId={String(report.data.author.userId)}
              />
            ) : (
              /* 본문은 마크다운이다(ANT-FE-REPORT-MD). 작성 화면의 미리보기와 같은
                 컴포넌트를 쓴다 — 규칙이 두 군데 있으면 미리보기가 거짓말이 된다. */
              <Markdown text={report.data.body ?? ''} className="rd-body" />
            )}

            {/* 잠긴 리포트에는 버튼을 내밀지 않는다 — 누르면 403 인 버튼은 없느니만 못하다.
                key 로 리포트 id 를 준다: 상세에서 상세로 이동하면 훅 상태가 남아 이전
                리포트의 좋아요 수가 잠깐 보인다. */}
            {!report.data.locked && <Reactions key={report.data.id} report={report.data} />}
          </article>
        )}

        {report.data && (
          <ReportComments
            key={report.data.id}
            reportId={report.data.id}
            commentCount={report.data.commentCount}
            canWrite={!report.data.locked}
            channelId={String(report.data.author.userId)}
          />
        )}
      </div>
    </main>
  )
}

/* 좋아요 줄을 따로 받는 이유는 타입이다 — report.data 를 클로저(onLike)에서 다시 읽으면
   TS 가 null 을 좁혀 주지 않는다(가변 속성 접근이라 그 사이 바뀔 수 있다고 본다).
   인자로 한 번 받아 두면 상수라 좁혀진다. 화면 분기를 늘리지 않는 가장 작은 방법이다. */
function Reactions({ report }: { report: Report }) {
  return (
    <div className="rd-reactions">
      <LikeButton
        likeCount={report.likeCount}
        liked={report.liked}
        onLike={() => likeReport(report.id)}
        onUnlike={() => unlikeReport(report.id)}
      />
    </div>
  )
}
