/* F-01 리포트 피드 · /reports
   담당 스토리 [ANT-FE-REPORTS]
   설계서 docs/화면설계서.md §3 F · §4 F-01 · §9.2

   설계 제약
   - 화면 컨트롤은 scope(ALL/SUBSCRIBED) · sort(RECENT/POPULAR) 를 넘지 않는다.
   - locked 여도 제목·작성자·발행일·조회수는 그대로 보여준다. 잠긴 것을 숨기면
     구독 유인이 사라진다(설계서 §5) — 404 처럼 그리지 않는다.
   - 커서 페이징이다. 페이지 번호를 만들지 않는다.

   두지 않는 것: 내가 쓴 리포트 탭 · 적중률 정렬 · 검증/공개/최소 적중률 필터 ·
   찜 · 댓글 수 · 근거 검증 배지. scope 는 2종뿐이고 응답 통계는 viewCount 뿐이며
   북마크 도메인이 없다.

   왜 예측 탭이 여기 있나
   예측을 읽는 길이 "한 채널(E-02)" 과 "내 것(C-02)" 뿐이라, 구독 중인 채널들이
   무엇을 걸었는지 한 줄로 볼 자리가 없었다. 커뮤니티(글 + 예측 첨부)가 개발
   범위에서 빠지면서 그 자리가 통째로 사라진 것이기도 하다. 리포트와 예측은
   "예측가가 내놓는 것" 이라는 점에서 같은 목록에 속하고, scope(전체/구독 중)도
   같은 어휘를 쓴다.

   시간순으로 섞지 않고 탭으로 가른 이유 — 두 목록의 커서가 다른 시퀀스라
   합치려면 (createdAt, kind, id) 복합 커서가 필요하다. 탭이면 각자 자기 커서를
   쓰고, 사용자가 "리포트를 보러 왔는가 예측을 보러 왔는가" 를 직접 고른다.

   정렬(최신/인기)은 리포트에만 둔다 — 예측 피드에는 viewCount 가 없다.

   섹터 칩을 두지 않는 이유
   설계서 §3 F-01 은 섹터 칩을 적어 두었지만 백엔드가 sector 를 400 으로 거절한다.
   출처 컬럼이 ERD 에 없어 필터를 구현할 수 없고(Jira S15P21A507-69 ⛔), 조용히
   무시하면 "칩을 눌렀고 걸러진 목록을 받았다"고 믿게 되기 때문이다. 칩을 그리면
   누를 때마다 400 이거나, 더 나쁘게는 안 걸러진 목록을 정답처럼 보여준다.
   설계서 §10 도 "scope 2종뿐"으로 적고 있어 문서 안에서 이미 어긋난 항목이다.
   reports.sector 가 신설되면 그때 칩을 붙인다. */
import { useUrlState } from '../urlState'
import { Link } from 'react-router-dom'
import { useCursorList } from '../api/useCursorList'
import {
  REPORT_SCOPES, REPORT_SORTS, SCOPE_LABEL, SORT_LABEL,
  type ReportFeedItem, type ReportScope, type ReportSort,
} from '../api/reports'
import type { PredictionFeedItem } from '../api/predictions'
import PredictionCard from '../components/prediction/PredictionCard'
import ReportCard from '../components/report/ReportCard'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/reports.css'

/* 무엇을 보러 왔는가. scope(전체/구독 중)와 다른 축이라 탭이 두 줄이다. */
const KINDS = ['REPORT', 'PREDICTION'] as const
type Kind = (typeof KINDS)[number]

const KIND_LABEL: Record<Kind, string> = { REPORT: '리포트', PREDICTION: '예측' }

export default function Reports() {
  /* 주소에 둔다 — 리포트를 열었다 뒤로 오면 보던 탭·정렬이 살아 있어야 한다(2026-09-22 QA) */
  const [kind, setKind] = useUrlState<Kind>('kind', 'REPORT', KINDS)
  const [scope, setScope] = useUrlState<ReportScope>('scope', 'ALL', REPORT_SCOPES)
  const [sort, setSort] = useUrlState<ReportSort>('sort', 'RECENT', REPORT_SORTS)

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head rp-page-head">
          <div>
            <h1>리포트</h1>
          </div>
          {/* F-03 진입점. 이게 없으면 /reports/new 에 갈 길이 없다 */}
          <Link className="rp-new" to="/reports/new">리포트 작성</Link>
        </div>

        {/* 무엇을 보러 왔는가 — scope 와 다른 축이라 줄을 나눈다 */}
        <div className="rp-kinds" role="tablist" aria-label="목록 종류">
          {KINDS.map((k) => (
            <button
              key={k} type="button" role="tab"
              aria-selected={kind === k}
              className={kind === k ? 'on' : ''}
              onClick={() => setKind(k)}
            >
              {KIND_LABEL[k]}
            </button>
          ))}
        </div>

        <div className="rp-controls">
          {/* 탭 — 전체 / 구독 중. 두 목록이 같은 어휘를 쓴다 */}
          <div className="rp-tabs" role="tablist" aria-label="범위">
            {REPORT_SCOPES.map((s) => (
              <button
                key={s} type="button" role="tab"
                aria-selected={scope === s}
                className={scope === s ? 'on' : ''}
                onClick={() => setScope(s)}
              >
                {SCOPE_LABEL[s]}
              </button>
            ))}
          </div>

          {/* 정렬은 리포트에만 — 예측 피드에는 viewCount 가 없어 인기순을 만들 수 없다 */}
          {kind === 'REPORT' && (
            <div className="rp-sorts" role="group" aria-label="정렬">
              {REPORT_SORTS.map((s) => (
                <button
                  key={s} type="button"
                  aria-pressed={sort === s}
                  className={sort === s ? 'on' : ''}
                  onClick={() => setSort(s)}
                >
                  {SORT_LABEL[s]}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* 훅을 조건부로 부를 수 없어 목록마다 컴포넌트를 나눈다. 덕분에 안 보는 탭은
            요청도 나가지 않는다 — 한 컴포넌트에서 훅 둘을 부르면 늘 두 번 읽는다. */}
        {kind === 'REPORT'
          ? <ReportFeed scope={scope} sort={sort} />
          : <PredictionFeed scope={scope} />}
      </div>
    </main>
  )
}

function ReportFeed({ scope, sort }: { scope: ReportScope; sort: ReportSort }) {
  const feed = useCursorList<ReportFeedItem>('/reports', { scope, sort })

  return (
    <>
      {feed.error && <ErrorState error={feed.error} onRetry={feed.reload} />}

      {!feed.error && feed.items.length === 0 && !feed.loading && (
        <EmptyState
          title={scope === 'SUBSCRIBED' ? '구독 중인 채널의 리포트가 없습니다' : '아직 발행된 리포트가 없습니다'}
          hint={scope === 'SUBSCRIBED' ? '구독한 채널의 리포트가 여기에 모여요' : '아직 리포트가 없어요'}
        />
      )}

      {feed.items.length > 0 && (
        <ul className="rp-list">
          {feed.items.map((r) => <ReportCard key={r.id} data={r} />)}
        </ul>
      )}

      <FeedTail feed={feed} />
    </>
  )
}

/* 예측 줄은 공용 PredictionCard 가 그린다 — 잠금 표현이 그 안에 들어 있어
   화면마다 게이팅 분기를 새로 짜지 않는다(그 파일 머리말). */
function PredictionFeed({ scope }: { scope: ReportScope }) {
  const feed = useCursorList<PredictionFeedItem>('/predictions/feed', { scope })

  return (
    <>
      {feed.error && <ErrorState error={feed.error} onRetry={feed.reload} />}

      {!feed.error && feed.items.length === 0 && !feed.loading && (
        <EmptyState
          title={scope === 'SUBSCRIBED' ? '구독 중인 채널의 예측이 없습니다' : '아직 등록된 예측이 없습니다'}
          hint={scope === 'SUBSCRIBED' ? '구독한 채널의 예측이 여기에 모여요' : '아직 예측이 없어요'}
        />
      )}

      {feed.items.length > 0 && (
        <ul className="rp-pred-list">
          {feed.items.map((p) => (
            <li key={p.id}>
              <PredictionCard
                data={{
                  id: p.id,
                  stockCode: p.stockCode,
                  stockName: p.stockName,
                  direction: p.direction,
                  status: p.status,
                  locked: p.locked,
                  /* 잠금을 푸는 채널 = 작성자다. 채널 경로가 /channels/{userId} 라
                     그 값이 곧 채널 id 다(C-03 의 구독 CTA 와 같은 판단). */
                  channelId: String(p.author.userId),
                  author: { userId: String(p.author.userId), nickname: p.author.nickname },
                  targetPrice: p.targetPrice,
                  errorRate: p.errorRate,
                  horizon: p.horizon,
                  revealDate: p.revealDate,
                  dueDate: p.settleDate,
                  dday: p.dday,
                  to: `/predictions/${p.id}`,
                }}
              />
            </li>
          ))}
        </ul>
      )}

      <FeedTail feed={feed} />
    </>
  )
}

/* 두 목록이 같은 꼬리를 쓴다 — 더 보기 버튼과 로딩 문구. */
function FeedTail({ feed }: { feed: { loading: boolean; items: unknown[]; hasNext: boolean; loadMore: () => void } }) {
  return (
    <>
      {feed.loading && feed.items.length > 0 && <p className="rp-more-hint">불러오는 중…</p>}
      {feed.hasNext && !feed.loading && (
        <button type="button" className="rp-more" onClick={feed.loadMore}>
          더 보기
        </button>
      )}
    </>
  )
}
