/* I-01 신고 처리 · /admin/abuse-reports
   담당 스토리 [ANT-FE-ADMIN-MODERATION] · 서버는 ANT-ADMIN-01

   ── 이 화면의 기준 ──────────────────────────────────────
   운영자 한 명만 보는 화면이다. 시각 완성도보다 **무엇을 가리는지 보고 누를 수 있는가**가
   기준이다. 새 CSS 를 만들지 않고 admin.css 하나에 표 스타일만 둔다.

   ACCEPT 는 되돌릴 수 없다(신고가 닫힌다). 그래서 확인 대화상자를 끼운다.
   대상 글·댓글·리포트 자체는 아래 블라인드 버튼으로 언제든 되돌릴 수 있다.

   ── 대상 종류마다 가리는 경로가 다르다 (ANT-FE-ABUSE-REPORT) ──
   커뮤니티 글·댓글과 리포트·리포트 댓글은 다른 표라 id 번호 체계가 다르다. 종류를 보고
   알맞은 API 를 불러야 한다 — 리포트 id 로 커뮤니티 댓글 API 를 부르면 엉뚱한 댓글이 가려진다.
   리포트·리포트 댓글은 "리포트 열기" 로 맥락을 보고 판단한다(댓글은 앞뒤를 봐야 안다). */
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import ConfirmDialog from '../components/ConfirmDialog'
import {
  getAbuseReports, handleAbuseReport, setCommentStatus, setPostStatus,
  setReportCommentStatus, setReportStatus,
} from '../api/admin'
import type { AbuseReportItem, AbuseReportStatus, AbuseTargetType, ContentStatus } from '../api/admin'
import type { ApiError } from '../api/errors'
import '../styles/screens/admin.css'

const TABS: { key: AbuseReportStatus | 'ALL'; label: string }[] = [
  { key: 'PENDING', label: '처리 대기' },
  { key: 'ACCEPTED', label: '수락' },
  { key: 'REJECTED', label: '기각' },
  { key: 'ALL', label: '전체' },
]

const REASON_LABEL: Record<string, string> = {
  SPAM: '스팸', ABUSE: '욕설·비방', FRAUD: '사기', ETC: '기타',
}

const TARGET_LABEL: Record<string, string> = {
  POST: '글', COMMENT: '댓글', USER: '회원', REPORT: '리포트', REPORT_COMMENT: '리포트 댓글',
}

/**
 * 주격 조사를 붙인다 — 마지막 글자에 받침이 있으면 "이", 없으면 "가". "리포트가" · "댓글이" · "글이".
 * "이(가)" 로 흘리면 대상이 늘 때마다 어색한 문장이 된다(확인창이 "리포트이(가)" 로 나왔다).
 */
function withSubject(word: string) {
  const code = word.charCodeAt(word.length - 1) - 0xac00
  if (code < 0 || code > 11171) return `${word}이(가)` // 한글 음절이 아니면 판단하지 않는다
  return `${word}${code % 28 === 0 ? '가' : '이'}`
}

/** 대상 종류 → 가리기·복구 API. 회원은 가릴 대상이 없어 없다(차단은 회원 제재 화면). */
const SET_STATUS: Partial<Record<AbuseTargetType, (id: number, status: ContentStatus) => Promise<void>>> = {
  POST: setPostStatus,
  COMMENT: setCommentStatus,
  REPORT: setReportStatus,
  REPORT_COMMENT: setReportCommentStatus,
}

export default function AdminAbuse() {
  const [tab, setTab] = useState<AbuseReportStatus | 'ALL'>('PENDING')
  const [items, setItems] = useState<AbuseReportItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  /* 처리 중인 신고 id. 같은 줄을 두 번 누르는 것을 막는다 — 서버도 409 로 막지만
     응답이 오기 전에 두 번 눌리면 두 번째가 오류 토스트로 뜬다. */
  const [busy, setBusy] = useState<number | null>(null)
  const [confirming, setConfirming] = useState<AbuseReportItem | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await getAbuseReports(tab === 'ALL' ? undefined : tab)
      setItems(res.items)
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setLoading(false)
    }
  }, [tab])

  // oxlint-disable-next-line react/set-state-in-effect -- 데이터 페칭이라 상태 갱신이 목적이다.
  useEffect(() => { void load() }, [load])

  const 처리 = useCallback(async (report: AbuseReportItem, action: 'ACCEPT' | 'REJECT') => {
    setBusy(report.id)
    setError(null)
    try {
      await handleAbuseReport(report.id, action)
      await load()
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setBusy(null)
    }
  }, [load])

  const 블라인드토글 = useCallback(async (report: AbuseReportItem) => {
    const next = report.targetBlocked ? 'VISIBLE' : 'BLOCKED'
    setBusy(report.id)
    setError(null)
    try {
      const setStatus = SET_STATUS[report.targetType]
      if (!setStatus) return
      await setStatus(report.targetId, next)
      await load()
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setBusy(null)
    }
  }, [load])

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>신고 처리</h1>
          <p>접수된 신고를 보고 리포트·댓글을 가리거나 신고를 기각합니다. 회원 차단은 <a href="/admin/users">회원 제재</a>에서 합니다.</p>
        </div>

        <div className="admin-tabs" role="tablist">
          {TABS.map((t) => (
            <button
              key={t.key} type="button" role="tab"
              aria-selected={tab === t.key}
              className={`admin-tab${tab === t.key ? ' on' : ''}`}
              onClick={() => { setTab(t.key) }}
            >{t.label}</button>
          ))}
          <button type="button" className="admin-tab ghost" onClick={() => { void load() }}>새로 고침</button>
        </div>

        {error && <div className="admin-error" role="alert">{error}</div>}

        {loading && <div className="placeholder">불러오는 중…</div>}

        {!loading && items.length === 0 && (
          <div className="placeholder">이 상태의 신고가 없습니다.</div>
        )}

        {!loading && items.length > 0 && (
          <table className="admin-table">
            <thead>
              <tr>
                <th>#</th><th>대상</th><th>내용</th><th>사유</th>
                <th>신고자</th><th>접수</th><th>상태</th><th>처리</th>
              </tr>
            </thead>
            <tbody>
              {items.map((r) => (
                <tr key={r.id} className={busy === r.id ? 'busy' : undefined}>
                  <td>{r.id}</td>
                  <td>
                    <span className="admin-chip">{TARGET_LABEL[r.targetType] ?? r.targetType}</span>
                    <span className="admin-dim"> #{r.targetId}</span>
                  </td>
                  <td className="admin-body">
                    {/* 무엇을 가리는지 보지 않고 판단하지 않게, 본문을 그대로 보여 준다 */}
                    <span className={r.targetBlocked ? 'blocked' : undefined}>
                      {r.targetBody ?? '(내용 없음)'}
                    </span>
                    {r.targetBlocked && <span className="admin-chip warn">가려짐</span>}
                    {/* 가려진 뒤에도 건다 — 관리자는 가려진 리포트를 열어 보고 복구할지 정한다(다른 사람에게는 410) */}
                    {r.targetReportId !== null && (
                      <div>
                        <Link className="admin-dim" to={`/reports/${r.targetReportId}`} target="_blank" rel="noreferrer">
                          리포트 열기 ↗
                        </Link>
                      </div>
                    )}
                  </td>
                  <td>
                    {REASON_LABEL[r.reason] ?? r.reason}
                    {r.detail && <div className="admin-dim admin-detail">{r.detail}</div>}
                  </td>
                  <td>{r.reporterNickname ?? `#${r.reporterId}`}</td>
                  <td className="admin-dim">{when(r.createdAt)}</td>
                  <td>
                    <span className={`admin-chip ${r.status.toLowerCase()}`}>
                      {TABS.find((t) => t.key === r.status)?.label ?? r.status}
                    </span>
                    {r.handledAt && <div className="admin-dim">{when(r.handledAt)}</div>}
                  </td>
                  <td className="admin-actions">
                    {r.status === 'PENDING' ? (
                      <>
                        <button
                          type="button" className="se-btn solid danger" disabled={busy === r.id}
                          onClick={() => { setConfirming(r) }}
                        >수락</button>
                        <button
                          type="button" className="se-btn" disabled={busy === r.id}
                          onClick={() => { void 처리(r, 'REJECT') }}
                        >기각</button>
                      </>
                    ) : <span className="admin-dim">처리됨</span>}

                    {/* 신고 상태와 무관하게, 대상 자체는 언제든 가리고 되돌릴 수 있다 */}
                    {SET_STATUS[r.targetType] && (
                      <button
                        type="button" className="se-btn" disabled={busy === r.id}
                        onClick={() => { void 블라인드토글(r) }}
                      >{r.targetBlocked ? '복구' : '가리기'}</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {confirming && (
        <ConfirmDialog
          title="신고를 수락합니다"
          description={
            confirming.targetType === 'USER'
              ? '신고가 닫힙니다. 계정은 막히지 않습니다 — 차단은 회원 제재에서 따로 합니다.'
              : `대상 ${withSubject(TARGET_LABEL[confirming.targetType])} 가려집니다. 지워지지 않고, 나중에 복구할 수 있습니다.`
          }
          confirmLabel="수락"
          tone="danger"
          onConfirm={() => {
            const target = confirming
            setConfirming(null)
            void 처리(target, 'ACCEPT')
          }}
          onCancel={() => { setConfirming(null) }}
        />
      )}
    </main>
  )
}

/** 목록에서 시각은 분까지면 충분하다. 초까지 적으면 열 폭만 먹는다. */
function when(iso: string) {
  return new Date(iso).toLocaleString('ko-KR', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}
