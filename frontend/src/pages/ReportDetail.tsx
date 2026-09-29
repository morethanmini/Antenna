/* F-02 리포트 상세 · /reports/:id
   담당 스토리 [ANT-FE-REPORT-DETAIL]
   설계서 docs/화면설계서.md §3 F · §4 F-02 · §5 · §7

   설계 제약
   - visibility=false 이고 미구독이면 200 + { locked } 가 온다(본문·미리보기 없음). 404 가 아니다
     — 없는 것처럼 보이면 구독 유인이 사라진다.
   - 전문을 받아 CSS 로 가리지 않는다. 서버가 잠긴 리포트에 body 를 아예 담지 않으므로
     이 화면은 가릴 전문을 갖지 못한다.
   - 잠금 문구 + 구독 CTA 가 잠금 표현이다. 미리보기는 없다 — 짧은 글은 앞 3줄이 곧
     전문이라 폐지했다(2026-09-17). 설계서 §7 의 SubscriptionGate 는 [ANT-FE-ERROR] 가
     만든 LockedCard 다.
   - 구독 CTA 는 채널 프로필(E-02)로 보낸다. 결제 흐름(M-03 → 서명 → 202 → M-02)은
     그쪽 스토리 몫이라 여기서 만들지 않는다.

   채널 식별자에 대하여
   채널은 회원 1인당 하나다 — GET /channels/{userId}/reports 가 userId 를 쓴다.
   그래서 CTA 목적지는 작성자의 userId 다.

   좋아요·댓글 (ANT-REPORT-SOCIAL)
   잠긴 리포트에는 반응할 수 없다 — 서버가 403 REPORT_FORBIDDEN 을 준다. locked 를
   이미 알고 있으니 좋아요 버튼은 아예 그리지 않고, 댓글은 목록만 보여 준다(읽기는
   허용된다 — 반응이 오가는 리포트라는 사실이 제목·통계와 같은 부류의 구독 유인이다).

   신고 (M-06 · ANT-FE-ABUSE-REPORT)
   헤더 오른쪽 위의 ⋯ 에 접어 둔다(채널 프로필과 같은 자리). 내 리포트에는 그리지 않는다. 신고가 승인돼 가려진
   리포트는 410 REPORT_BLOCKED 로 오고, 아래 ErrorState 가 그 문구를 그린다.

   읽기 폭과 목차
   본문 한 줄이 화면 폭을 다 쓰면 한글은 줄을 놓친다. 화면 전체(뒤로가기·카드·댓글)를
   한 폭으로 묶고, 제목이 둘 이상인 글은 넓은 화면에서 오른쪽에 목차를 세운다.
   목차가 없는 글은 폭을 더 좁혀 빈 오른쪽이 남지 않게 한다. */
import { useMemo } from 'react'
import Markdown from '../components/report/Markdown'
import ReportToc from '../components/report/ReportToc'
import { outline } from '../components/report/outline'
import { Link, useParams } from 'react-router-dom'
import { useApiQuery } from '../api/useApiQuery'
import {
  formatDate, likeReport, unlikeReport, type ReportDetail as Report,
} from '../api/reports'
import LikeButton from '../components/report/LikeButton'
import ReportComments from '../components/report/ReportComments'
import AbuseMenu from '../components/report/AbuseMenu'
import { useAuth } from '../auth/context'
import ErrorState from '../components/state/ErrorState'
import LockedCard from '../components/state/LockedCard'
import '../styles/screens/report-detail.css'

export default function ReportDetail() {
  const { id } = useParams<{ id: string }>()
  const report = useApiQuery<Report>(`/reports/${id}`)
  const { user } = useAuth()
  const body = report.data?.locked ? '' : report.data?.body ?? ''
  const toc = useMemo(() => outline(body), [body])

  return (
    <main className="main">
      <div className={`main-inner rd-page${toc.length ? '' : ' no-toc'}`}>
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

        {/* 신고로 가려진 리포트를 관리자가 보는 중. 다른 사람에게는 410 안내가 뜬다 */}
        {report.data?.blocked && (
          <p className="rd-blocked" role="status">
            <b>신고로 가려진 리포트입니다.</b>
            관리자에게만 보입니다. 되돌리려면 신고 처리 화면에서 <Link to="/admin/abuse-reports">복구</Link>를 누르세요.
          </p>
        )}

        {report.data && (
          <article className="rd">
            <header className={`rd-head${user?.id !== report.data.author.userId ? ' has-menu' : ''}`}>
              {/* 신고 ⋯ 는 헤더 오른쪽 위 — 채널 프로필(E-02)과 같은 자리다. 내 리포트에는 없다 */}
              {user?.id !== report.data.author.userId && (
                <AbuseMenu
                  className="rd-menu"
                  targetType="REPORT"
                  targetId={report.data.id}
                  targetLabel={`「${report.data.title}」 리포트를 신고합니다.`}
                  menuLabel="리포트 메뉴"
                />
              )}
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

                {/* 작성자 증명(ANT-CHAIN-16). 서명 이전 발행분은 키가 아예 없어 배지도 없다 —
                    "서명이 비었다" 가 아니라 "그 기능 이전 글" 이라 빈 배지를 그리지 않는다.
                    배지를 누르면 검산(D-04)으로 간다 — 배지는 우리 주장이고, 확인은 거기서 한다. */}
                {report.data.signerAddress && (
                  <Link
                    className="rd-signed"
                    to={`/ledger/verify/report/${report.data.id}`}
                    title={report.data.signerAddress}
                  >
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M20 6L9 17l-5-5" />
                    </svg>
                    지갑 서명
                  </Link>
                )}

              </div>
            </header>

            {report.data.locked ? (
              /* title 을 직접 준다 — 공용 기본 문구는 조사를 "은(는)" 으로 흘려
                 "리포트 본문은(는) …" 이 된다. 이 화면은 label 이 고정이라 정확히 쓸 수 있다. */
              <LockedCard
                label="리포트 본문"
                title="리포트 본문은 구독자만 볼 수 있어요"
                channelId={String(report.data.author.userId)}
              />
            ) : (
              /* 본문은 마크다운이다(ANT-FE-REPORT-MD). 작성 화면의 미리보기와 같은
                 컴포넌트를 쓴다 — 규칙이 두 군데 있으면 미리보기가 거짓말이 된다. */
              <div className={toc.length ? 'rd-layout has-toc' : 'rd-layout'}>
                <Markdown text={body} className="rd-body" />
                {toc.length > 0 && <ReportToc items={toc} />}
              </div>
            )}

            {/* 잠긴 리포트에는 버튼을 내밀지 않는다 — 누르면 403 인 버튼은 없느니만 못하다.
                key 로 리포트 id 를 준다: 상세에서 상세로 이동하면 훅 상태가 남아 이전
                리포트의 좋아요 수가 잠깐 보인다. */}
            {/* 가려진 리포트에는 관리자도 반응하지 않는다 — 서버가 410 을 준다 */}
            {!report.data.locked && !report.data.blocked && <Reactions key={report.data.id} report={report.data} />}

            {/* 검산 진입점. 배지가 없는(서명 이전) 리포트에도 길을 둔다 — 봉인·앵커는 그 글에도
                있을 수 있고, 없으면 검산 화면이 "봉인 이전 발행분" 이라고 말해 준다. */}
            <Link className="rd-verify" to={`/ledger/verify/report/${report.data.id}`}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M12 3l7 3v6c0 4.2-2.8 7.6-7 9-4.2-1.4-7-4.8-7-9V6z" />
                <path d="m9 12 2 2 4-4" />
              </svg>
              이 리포트가 조작되지 않았는지 직접 확인하기
            </Link>
          </article>
        )}

        {report.data && (
          <ReportComments
            key={report.data.id}
            reportId={report.data.id}
            commentCount={report.data.commentCount}
            canWrite={!report.data.locked && !report.data.blocked}
            blocked={report.data.blocked === true}
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
