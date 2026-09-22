/* I-02 회원 제재 · /admin/users
   담당 스토리 [ANT-FE-ADMIN-MODERATION] · 서버는 ANT-ADMIN-01

   차단은 영구다(BANNED). 일시 정지는 없다 — User.Status 가 {ACTIVE, BANNED} 뿐이고
   명세도 v0.77 에서 그렇게 맞췄다. 차단해도 예측·원장은 남고 로그인만 막힌다.

   자기 계정은 서버가 409 로 막는다. 화면에서도 버튼을 아예 안 그린다 —
   눌러서 오류를 보는 것보다 못 누르는 게 낫다. */
import { useCallback, useEffect, useState } from 'react'
import ConfirmDialog from '../components/ConfirmDialog'
import { searchUsers, setUserStatus } from '../api/admin'
import type { AdminUserItem } from '../api/admin'
import type { ApiError } from '../api/errors'
import { useAuth } from '../auth/context'
import '../styles/screens/admin.css'

export default function AdminUser() {
  const { user: me } = useAuth()
  const [q, setQ] = useState('')
  const [items, setItems] = useState<AdminUserItem[]>([])
  /* 뒤가 더 있으면 서버가 마지막 id 를 준다. 그 값으로 다음 50명을 이어 붙인다 */
  const [nextBefore, setNextBefore] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [confirming, setConfirming] = useState<AdminUserItem | null>(null)

  /* 검색어는 상태로 두되 조회는 제출할 때만 한다. 글자마다 부르면 운영자 한 명이
     쓰는 화면에 불필요한 요청이 쌓인다. */
  const [submitted, setSubmitted] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await searchUsers(submitted)
      setItems(res.items)
      setNextBefore(res.nextBefore)
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setLoading(false)
    }
  }, [submitted])

  const 더보기 = useCallback(async () => {
    if (nextBefore === null) return
    setLoading(true)
    setError(null)
    try {
      const res = await searchUsers(submitted, nextBefore)
      setItems((prev) => [...prev, ...res.items])
      setNextBefore(res.nextBefore)
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setLoading(false)
    }
  }, [submitted, nextBefore])

  // oxlint-disable-next-line react/set-state-in-effect -- 데이터 페칭이라 상태 갱신이 목적이다.
  useEffect(() => { void load() }, [load])

  /* 성공해도 다시 불러오지 않는다. load() 는 첫 50명으로 되돌리기 때문에 더 보기로
     이어 붙인 목록이 사라지고 화면이 맨 위로 튄다. 서버는 바뀐 회원을 돌려주지 않으니
     그 줄의 상태만 제자리에서 고친다 — 목록·nextBefore·스크롤이 그대로 남는다. */
  const 상태변경 = useCallback(async (target: AdminUserItem, next: 'ACTIVE' | 'BANNED') => {
    setBusy(target.id)
    setError(null)
    try {
      await setUserStatus(target.id, next)
      setItems((prev) => prev.map((u) => (u.id === target.id ? { ...u, status: next } : u)))
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setBusy(null)
    }
  }, [])

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>회원 제재</h1>
          <p>차단하면 로그인이 막힙니다. 기존 예측과 원장은 그대로 남습니다.</p>
        </div>

        <form
          className="admin-search"
          onSubmit={(e) => { e.preventDefault(); setSubmitted(q) }}
        >
          <input
            type="search" value={q} placeholder="닉네임 일부 (대소문자 무관)"
            // 서버 AdminUserService.MAX_QUERY_LENGTH 와 같은 값이다 — 넘기면 400 이다
            maxLength={50}
            onChange={(e) => { setQ(e.target.value) }}
          />
          <button type="submit" className="se-btn solid">찾기</button>
          <button
            type="button" className="se-btn"
            onClick={() => { setQ(''); setSubmitted('') }}
          >전체</button>
        </form>

        {error && <div className="admin-error" role="alert">{error}</div>}

        {loading && items.length === 0 && <div className="placeholder">불러오는 중…</div>}

        {!loading && items.length === 0 && (
          <div className="placeholder">찾는 회원이 없습니다.</div>
        )}

        {items.length > 0 && (
          <table className="admin-table">
            <thead>
              <tr><th>#</th><th>닉네임</th><th>역할</th><th>상태</th><th>가입</th><th>처리</th></tr>
            </thead>
            <tbody>
              {items.map((u) => {
                const 나 = u.id === me?.id
                return (
                  <tr key={u.id} className={busy === u.id ? 'busy' : undefined}>
                    <td>{u.id}</td>
                    <td>
                      {u.nickname ?? <span className="admin-dim">(닉네임 없음)</span>}
                      {나 && <span className="admin-chip"> 나</span>}
                    </td>
                    <td>{u.role === 'ADMIN' ? <span className="admin-chip">관리자</span> : '일반'}</td>
                    <td>
                      <span className={`admin-chip ${u.status === 'BANNED' ? 'rejected' : 'accepted'}`}>
                        {u.status === 'BANNED' ? '차단됨' : '정상'}
                      </span>
                    </td>
                    <td className="admin-dim">{when(u.createdAt)}</td>
                    <td className="admin-actions">
                      {/* 자기 계정·다른 관리자는 서버가 409 로 막는다. 버튼을 안 그려 그 상황 자체를 없앤다 */}
                      {나 ? <span className="admin-dim">본인</span>
                        : u.role === 'ADMIN' ? <span className="admin-dim">관리자</span>
                        : u.status === 'BANNED' ? (
                          <button
                            type="button" className="se-btn" disabled={busy === u.id}
                            onClick={() => { void 상태변경(u, 'ACTIVE') }}
                          >차단 해제</button>
                        ) : (
                          <button
                            type="button" className="se-btn solid danger" disabled={busy === u.id}
                            onClick={() => { setConfirming(u) }}
                          >차단</button>
                        )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}

        {nextBefore !== null && (
          <div className="admin-actions">
            <button type="button" className="se-btn" disabled={loading} onClick={() => { void 더보기() }}>
              {loading ? '불러오는 중…' : '더 보기'}
            </button>
          </div>
        )}
      </div>

      {confirming && (
        <ConfirmDialog
          title={`${confirming.nickname ?? `#${confirming.id}`} 님 차단`}
          description="차단하면 로그인할 수 없어요. 예측과 원장은 남고, 나중에 차단을 풀 수 있어요."
          confirmLabel="차단"
          tone="danger"
          onConfirm={() => {
            const target = confirming
            setConfirming(null)
            void 상태변경(target, 'BANNED')
          }}
          onCancel={() => { setConfirming(null) }}
        />
      )}
    </main>
  )
}

function when(iso: string) {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: '2-digit', month: '2-digit', day: '2-digit',
  })
}
