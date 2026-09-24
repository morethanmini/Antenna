/* 리포트 카드 — 리포트 피드(F-01)와 채널 리포트 탭(E-02)이 같이 쓴다.
   원래 Reports.tsx 안에 있던 것을 꺼냈다(2026-09-25). 채널 탭이 제목만 늘어놓은 줄
   목록이라 피드와 같은 글이 화면마다 다르게 보였다.

   잠금 규칙은 피드와 같다 — 잠긴 글은 서버가 표지·발췌를 싣지 않으므로 기본 표지
   (블록체인 큐브) + 자물쇠 배지로 그려진다. 제목·통계는 그대로 보인다(설계서 §5). */
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { formatDate, type Author } from '../../api/reports'
import { DEFAULT_AVATAR } from '../../auth/context'
import '../../styles/report-card.css'

/* 본문에 이미지가 없는 리포트의 표지. id 로 갈라 두 장을 번갈아 쓴다 — 한 장만 쓰면
   피드가 같은 그림의 벽이 된다.

   앵커 애니메이션의 큐브를 그대로 쓴다. 새 그림을 만들지 않았고, 이 서비스에서
   "봉인된 글" 을 이미 뜻하는 그림이라 표지로 읽힌다. draft(초안)·failed(앵커 실패)
   큐브는 빼 두었다 — 발행된 리포트에 얹으면 상태를 잘못 말한다. */
const FALLBACK_THUMB = [
  '/assets/blockchain-animation/cube/cube-sealed.webp',
  '/assets/blockchain-animation/cube/cube-anchored.webp',
]

export type ReportCardData = {
  id: number
  title: string
  locked: boolean
  publishedAt: string
  viewCount: number
  likeCount: number
  liked: boolean
  commentCount: number
  thumbnailUrl?: string | null
  summary?: string | null
  /** 없으면 작성자 줄을 그리지 않는다 — 채널 화면은 전부 같은 사람이다 */
  author?: Author
}

/* 카드 표지.
   상태를 두는 이유는 **주소가 살아 있는지 미리 알 수 없어서**다. 업로드 파일이 지워졌거나
   S3 키가 바뀌면 서버는 여전히 주소를 내리는데(users.avatar_file_id 에 FK 를 걸지 않은 것과
   같은 사정) 화면에는 빈 칸만 남는다. 빈 칸은 "이미지를 못 불러왔다" 로 읽히고, 격자에서는
   한 칸만 비어 판이 고장 난 것처럼 보인다. 기본 표지로 떨어뜨리면 그냥 그림 없는 글이 된다. */
function ReportThumb({ id, src, children }: { id: number; src?: string | null; children?: ReactNode }) {
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

export default function ReportCard({ data: r }: { data: ReportCardData }) {
  return (
    <li className="rp-card">
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

        <div className={r.author ? 'rp-body' : 'rp-body is-anon'}>
          {r.author && (
            <>
              {/* 표지와 본문의 경계선에 걸친다 — 카드 주인이 누구인지 먼저 읽힌다 */}
              <img className="rp-avatar" src={r.author.avatarUrl ?? DEFAULT_AVATAR} alt="" loading="lazy" />
              <p className="rp-author">{r.author.nickname}</p>
            </>
          )}
          <h2 className="rp-title">{r.title}</h2>
          {/* 서버가 발췌를 안 내리면 줄 자체를 두지 않는다 — 빈 줄을 남기면
              카드마다 높이가 들쭉날쭉해져 격자가 어긋난 것처럼 보인다 */}
          {r.summary && <p className="rp-summary">{r.summary}</p>}

          {/* 통계가 늘 공개라는 규칙(설계서 §5)은 그대로다. 카드 전체가
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
  )
}
