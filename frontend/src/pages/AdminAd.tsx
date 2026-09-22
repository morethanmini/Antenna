/* I-05 광고 승인 · /admin/ads
   서버는 ANT-ADMIN-03

   ── 이 화면에서 승인 버튼은 곧 돈이다 ─────────────────────
   승인을 누르는 순간 신청자 지갑에서 게재료 소각 tx 가 나간다. 되돌릴 수 없다.
   그래서 승인에는 확인 대화상자를 끼우고, 금액과 신청자 가용잔액을 같은 줄에 보여 준다 —
   운영자가 "돈이 있나" 를 누르기 전에 볼 수 있어야 헛수고가 없다.

   반려는 아무것도 태우지 않는다. 신청자가 묶어 뒀던 가용잔액이 그 자리에서 풀린다.

   burnPending(소각 대기)은 심사 대기와 상태가 같다(둘 다 PENDING). 서버가 따로 내려 주는
   플래그로 구분한다 — 안 그러면 승인 직후 목록에서 또 승인을 누르게 된다(서버는 409 로 막는다). */
import { useCallback, useEffect, useState } from 'react'
import ConfirmDialog from '../components/ConfirmDialog'
import { getAdminAds, handleAd } from '../api/admin'
import type { AdStatus, AdminAdItem } from '../api/admin'
import type { ApiError } from '../api/errors'
import { formatToken } from '../api/wallet'
import '../styles/screens/admin.css'

const TABS: { key: AdStatus | 'ALL'; label: string }[] = [
  { key: 'PENDING', label: '심사 대기' },
  { key: 'ACTIVE', label: '게재 중' },
  { key: 'REJECTED', label: '반려·취소' },
  { key: 'ALL', label: '전체' },
]

type Confirm = { kind: 'APPROVE' | 'REJECT'; ad: AdminAdItem }

export default function AdminAd() {
  const [tab, setTab] = useState<AdStatus | 'ALL'>('PENDING')
  const [items, setItems] = useState<AdminAdItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [confirming, setConfirming] = useState<Confirm | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await getAdminAds(tab === 'ALL' ? undefined : tab)
      setItems(res.items)
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setLoading(false)
    }
  }, [tab])

  // oxlint-disable-next-line react/set-state-in-effect -- 데이터 페칭이라 상태 갱신이 목적이다.
  useEffect(() => { void load() }, [load])

  const 처리 = useCallback(async (ad: AdminAdItem, action: 'APPROVE' | 'REJECT') => {
    setBusy(ad.id)
    setError(null)
    setNote(null)
    try {
      await handleAd(ad.id, action)
      setNote(action === 'APPROVE'
        ? `#${ad.id} 승인 — 게재료 ${formatToken(ad.price)} ANT 소각 요청을 보냈어요. 체인 확정 후 반영돼요.`
        : `#${ad.id} 반려 — 아무것도 태우지 않았습니다.`)
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
          <h1>광고 승인</h1>
          <p><b>승인하면 신청자 지갑에서 게재료가 소각돼요.</b> 소각은 되돌릴 수 없어요. 반려하면 소각되지 않아요.</p>
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
        {note && <div className="admin-note" role="status">{note}</div>}

        {loading && <div className="placeholder">불러오는 중…</div>}
        {!loading && items.length === 0 && <div className="placeholder">이 상태의 광고가 없습니다.</div>}

        {!loading && items.length > 0 && (
          <table className="admin-table">
            <thead>
              <tr>
                <th>#</th><th>배너</th><th>신청자</th><th>기간</th>
                <th>게재료</th><th>가용잔액</th><th>상태</th><th>처리</th>
              </tr>
            </thead>
            <tbody>
              {items.map((ad) => {
                /* 잔액이 게재료보다 적으면 승인해도 409 다. 버튼을 막진 않는다 — 그 사이 충전했을 수 있다 */
                const short = ad.advertiserAvailable !== null && BigInt(ad.advertiserAvailable) < BigInt(ad.price)
                return (
                  <tr key={ad.id} className={busy === ad.id ? 'busy' : undefined}>
                    <td>{ad.id}</td>
                    <td className="admin-body">
                      {/* 무엇을 승인하는지 보고 누르게 배너를 그대로 보여 준다 */}
                      <a href={ad.linkUrl} target="_blank" rel="noreferrer noopener">
                        <img src={ad.imageUrl} alt="" style={{ width: 200, height: 50, objectFit: 'cover', display: 'block' }} />
                      </a>
                      <div className="admin-dim">{ad.linkUrl}</div>
                    </td>
                    <td>{ad.advertiserNickname ?? `#${ad.advertiserId}`}</td>
                    <td>
                      {ad.days}일
                      <div className="admin-dim">{when(ad.startsAt)} → {when(ad.endsAt)}</div>
                      {ad.status === 'PENDING' && <div className="admin-dim">승인한 날부터 계산해요</div>}
                    </td>
                    <td>{formatToken(ad.price)} ANT</td>
                    <td>
                      {ad.status !== 'PENDING' ? <span className="admin-dim">—</span>
                        : ad.advertiserAvailable === null ? <span className="admin-dim">확인 불가</span>
                        : <span className={short ? 'admin-chip warn' : undefined}>{formatToken(ad.advertiserAvailable)} ANT</span>}
                    </td>
                    <td>
                      <span className={`admin-chip ${chipOf(ad)}`}>{labelOf(ad)}</span>
                      {ad.txHash && <div className="admin-dim">{ad.txHash.slice(0, 10)}…</div>}
                    </td>
                    <td className="admin-actions">
                      {ad.status === 'PENDING' && !ad.burnPending ? (
                        <>
                          <button
                            type="button" className="se-btn solid danger" disabled={busy === ad.id}
                            onClick={() => { setConfirming({ kind: 'APPROVE', ad }) }}
                          >승인 (소각)</button>
                          <button
                            type="button" className="se-btn" disabled={busy === ad.id}
                            onClick={() => { setConfirming({ kind: 'REJECT', ad }) }}
                          >반려</button>
                        </>
                      ) : <span className="admin-dim">{ad.burnPending ? '소각 확정 대기' : '처리됨'}</span>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {confirming && (
        <ConfirmDialog
          title={confirming.kind === 'APPROVE'
            ? `#${confirming.ad.id} 승인`
            : `#${confirming.ad.id} 반려`}
          description={confirming.kind === 'APPROVE'
            ? `${confirming.ad.advertiserNickname ?? '신청자'} 님의 지갑에서 ${formatToken(confirming.ad.price)} ANT가 소각돼요. 되돌릴 수 없고, 지금부터 ${confirming.ad.days}일간 게재돼요.`
            : '아무것도 태우지 않습니다. 신청자가 묶어 뒀던 잔액이 풀립니다.'}
          confirmLabel={confirming.kind === 'APPROVE' ? '승인하고 소각' : '반려'}
          tone="danger"
          onConfirm={() => {
            const c = confirming
            setConfirming(null)
            void 처리(c.ad, c.kind)
          }}
          onCancel={() => { setConfirming(null) }}
        />
      )}
    </main>
  )
}

function labelOf(ad: AdminAdItem) {
  if (ad.status === 'PENDING') return ad.burnPending ? '소각 대기' : '심사 대기'
  if (ad.status === 'ACTIVE') return '게재 중'
  if (ad.status === 'REJECTED') return '반려·취소'
  return '끝남'
}

function chipOf(ad: AdminAdItem) {
  if (ad.status === 'ACTIVE') return 'accepted'
  if (ad.status === 'REJECTED') return 'rejected'
  return ''
}

function when(iso: string) {
  return new Date(iso).toLocaleDateString('ko-KR', { month: '2-digit', day: '2-digit' })
}
