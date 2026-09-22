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
import { useState } from 'react'
import { useUrlState } from '../urlState'
import { Link } from 'react-router-dom'
import { useCursorList } from '../api/useCursorList'
import {
  REPORT_SCOPES, REPORT_SORTS, SCOPE_LABEL, SORT_LABEL, formatDate,
  type ReportFeedItem, type ReportScope, type ReportSort,
} from '../api/reports'
import type { PredictionFeedItem } from '../api/predictions'
import { DEFAULT_AVATAR } from '../auth/context'
import PredictionCard from '../components/prediction/PredictionCard'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/reports.css'

/* 무엇을 보러 왔는가. scope(전체/구독 중)와 다른 축이라 탭이 두 줄이다. */
const KINDS = ['REPORT', 'PREDICTION'] as const
type Kind = (typeof KINDS)[number]

const KIND_LABEL: Record<Kind, string> = { REPORT: '리포트', PREDICTION: '예측' }

/* 본문에 이미지가 없는 리포트의 표지. id 로 갈라 두 장을 번갈아 쓴다 — 한 장만 쓰면
   피드가 같은 그림의 벽이 된다.

   앵커 애니메이션의 큐브를 그대로 쓴다. 새 그림을 만들지 않았고, 이 서비스에서
   "봉인된 글" 을 이미 뜻하는 그림이라 표지로 읽힌다. draft(초안)·failed(앵커 실패)
   큐브는 빼 두었다 — 발행된 리포트에 얹으면 상태를 잘못 말한다. */
const FALLBACK_THUMB = [
  '/assets/blockchain-animation/cube/cube-sealed.webp',
  '/assets/blockchain-animation/cube/cube-anchored.webp',
]

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
            <p>예측가가 내놓은 리서치와 예측을 모아 봅니다</p>
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

/* 카드 표지.
   상태를 두는 이유는 **주소가 살아 있는지 미리 알 수 없어서**다. 업로드 파일이 지워졌거나
   S3 키가 바뀌면 서버는 여전히 주소를 내리는데(users.avatar_file_id 에 FK 를 걸지 않은 것과
   같은 사정) 화면에는 빈 칸만 남는다. 빈 칸은 "이미지를 못 불러왔다" 로 읽히고, 격자에서는
   한 칸만 비어 판이 고장 난 것처럼 보인다. 기본 표지로 떨어뜨리면 그냥 그림 없는 글이 된다. */
function ReportThumb({ id, src, children }: { id: number; src?: string | null; children?: React.ReactNode }) {
  const [broken, setBroken] = useState(false)
  const fallback = !src || broken

  return (
    <div className={fallback ? 'rp-thumb is-fallback' : 'rp-thumb'}>
      <img
        src={fallback ? FALLBACK_THUMB[id % FALLBACK_THUMB.length] : src}
        alt="" loading="lazy" decoding="async"
        onError={() => setBroken(true)}
      />
      {children}
    </div>
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
          hint={scope === 'SUBSCRIBED' ? '채널을 구독하면 여기에 모입니다' : '첫 리포트를 기다리고 있습니다'}
        />
      )}

      {feed.items.length > 0 && (
        <ul className="rp-list">
          {feed.items.map((r) => (
            <li key={r.id} className="rp-card">
              {/* 잠겼어도 상세로 보낸다 — F-02 가 200 + locked 로 응답한다(404 아님) */}
              <Link className="rp-link" to={`/reports/${r.id}`}>
                <ReportThumb id={r.id} src={r.thumbnailUrl}>
                  {r.locked && (
                    <span className="rp-lock">
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
                        <rect x="4" y="10" width="16" height="10" rx="2" />
                        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
                      </svg>
                      구독 전용
                    </span>
                  )}
                </ReportThumb>

                <div className="rp-body">
                  {/* 표지와 본문의 경계선에 걸친다 — 카드 주인이 누구인지 먼저 읽힌다 */}
                  <img className="rp-avatar" src={r.author.avatarUrl ?? DEFAULT_AVATAR} alt="" loading="lazy" />
                  <p className="rp-author">{r.author.nickname}</p>
                  <h2 className="rp-title">{r.title}</h2>
                  {/* 서버가 발췌를 아직 안 내리면 줄 자체를 두지 않는다 — 빈 줄을 남기면
                      카드마다 높이가 들쭉날쭉해져 격자가 어긋난 것처럼 보인다 */}
                  {r.summary && <p className="rp-summary">{r.summary}</p>}

                  {/* 작성자가 위로 올라가 한 줄이 비었다. 날짜와 통계를 그 줄에 합친다 —
                      통계가 늘 공개라는 이 화면의 규칙(설계서 §5)은 그대로다. 카드 전체가
                      링크라 그 안에 버튼을 겹칠 수 없어 수만 보여준다. */}
                  <div className="rp-foot">
                    <span className="rp-date">{formatDate(r.publishedAt)}</span>
                    <span className="rp-dot" aria-hidden="true">·</span>
                    <span className="num">{`조회 ${r.viewCount.toLocaleString('ko-KR')}`}</span>
                    <span className="rp-dot" aria-hidden="true">·</span>
                    <span className={r.liked ? 'rp-liked num' : 'num'}>
                      {`좋아요 ${r.likeCount.toLocaleString('ko-KR')}`}
                    </span>
                    <span className="rp-dot" aria-hidden="true">·</span>
                    <span className="num">{`댓글 ${r.commentCount.toLocaleString('ko-KR')}`}</span>
                  </div>
                </div>
              </Link>
            </li>
          ))}
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
          hint={scope === 'SUBSCRIBED' ? '채널을 구독하면 여기에 모입니다' : '첫 예측을 기다리고 있습니다'}
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
