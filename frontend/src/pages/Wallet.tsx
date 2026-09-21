/* H-01 지갑·토큰 · /me/wallet
   담당 스토리 [ANT-FE-WALLET]
   설계서 docs/화면설계서.md §3 H · §4 H-01 · §9.1 · §9.2

   설계 제약
   - 잔액 원천은 온체인, DB 는 캐시다. syncedAt 을 반드시 병기한다 —
     숫자만 보이면 실시간 잔액으로 오해한다. syncedAt 이 없으면(서버가 막 떴을 때) "대사 전".
   - 필터 사유는 서버 TokenReason 어휘(10종)뿐이다. 임의 사유 라벨을 만들지 않는다. 목록에 어휘 밖 원문이 오면 "기타"로 그린다.
   - 보너스·보상은 서버·배치가 지급한다. 수령 버튼을 두지 않는다.
   - 충전·구매 버튼도 없다. 토큰 유입은 가입 보너스와 성과 보상 mint 뿐이다. */
import { useState } from 'react'
import { useApiQuery } from '../api/useApiQuery'
import { useCursorList } from '../api/useCursorList'
import {
  LEDGER_REASONS, REASON_LABEL, formatDateTime, formatToken, reasonLabel,
  type LedgerEntry, type LedgerReason, type WalletStatus,
} from '../api/wallet'
import { useWalletBalance } from '../api/walletBalance'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import WalletLinkModal from '../components/wallet/WalletLinkModal'
import '../styles/screens/wallet.css'

export default function Wallet() {
  const [reason, setReason] = useState<LedgerReason | null>(null)
  // M-01 은 라우트가 없는 모달이라 이 화면이 열고 닫는다
  const [linking, setLinking] = useState(false)

  const status = useApiQuery<WalletStatus>('/wallet')
  /* 상단 칩 · 홈 카드와 같은 저장소다(ANT-FE-WALLET-SYNC) — 이 화면의 잔액과 칩이 어긋나지 않는다 */
  const balance = useWalletBalance()
  const ledger = useCursorList<LedgerEntry>('/wallet/ledger', { reason: reason ?? undefined })

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>지갑 · 토큰</h1>
          <p>연동 상태와 ANT 잔액, 획득·사용 내역을 확인합니다</p>
        </div>

        {/* ── 연동 상태 + 잔액 ─────────────────────────── */}
        {status.error && <ErrorState error={status.error} onRetry={status.reload} />}

        {/* 설계 제약 — 미연동이면 M-01 지갑 연동으로 보낸다.
            M-01 은 라우트가 없는 모달이라 이 화면이 직접 띄운다. */}
        {!status.error && status.data && !status.data.linked && (
          <section className="wallet-unlinked">
            <p className="wallet-unlinked-title">아직 지갑을 연동하지 않았습니다</p>
            <p className="wallet-unlinked-hint">
              지갑을 연동해야 예측을 등록하고 토큰을 받을 수 있습니다
            </p>
            <button type="button" className="state-cta" onClick={() => setLinking(true)}>
              지갑 연동하기
            </button>
          </section>
        )}

        {/* 연동하면 GET /wallet 이 바뀐다 — 다시 불러 주소·잔액 칸으로 넘어가게 한다.
            balance 도 함께 되살린다. 미연동 상태에서 이미 실패해 둔 조회다. */}
        {linking && (
          <WalletLinkModal
            onClose={() => setLinking(false)}
            onLinked={() => { status.reload(); balance.reload() }}
          />
        )}

        {!status.error && status.data?.linked && (
          <section className="wallet-card">
            <div className="wallet-address">
              <span className="wallet-label">연동 지갑</span>
              <code>{status.data.walletAddress}</code>
            </div>

            {/* GET /wallet/balance 가 열렸다(ANT-TOKEN-04). 이제 실패는 진짜 장애라 다시 시도를 준다. */}
            {balance.error ? (
              <ErrorState error={balance.error} onRetry={balance.reload} inline />
            ) : balance.data ? (
              <div className="wallet-balance">
                <span className="wallet-label">보유 잔액</span>
                <p className="wallet-amount">
                  <b className="num">{formatToken(balance.data.balance)}</b>
                  <small>{balance.data.symbol}</small>
                </p>
                {/* 대사 시각 — 이게 없으면 실시간 잔액으로 읽힌다. null 이면 아직 체인을 끝까지 못 읽은 것 */}
                <p className="wallet-synced">
                  {balance.data.syncedAt
                    ? `온체인 대사 ${formatDateTime(balance.data.syncedAt)} 기준`
                    : '온체인 대사 전'}
                </p>
              </div>
            ) : (
              <div className="wallet-balance"><span className="wallet-label">잔액을 불러오는 중…</span></div>
            )}
          </section>
        )}

        {/* ── 획득 · 사용 내역 ─────────────────────────── */}
        <div className="wallet-ledger-head">
          <h2>획득 · 사용 내역</h2>
          <div className="wallet-filters" role="group" aria-label="사유 필터">
            <button
              type="button" className={reason === null ? 'on' : ''}
              aria-pressed={reason === null}
              onClick={() => setReason(null)}
            >전체</button>
            {LEDGER_REASONS.map((r) => (
              <button
                key={r} type="button" className={reason === r ? 'on' : ''}
                aria-pressed={reason === r}
                onClick={() => setReason(r)}
              >{REASON_LABEL[r]}</button>
            ))}
          </div>
        </div>

        {ledger.error && <ErrorState error={ledger.error} onRetry={ledger.reload} />}

        {/* 미연동 회원도 서버는 빈 목록(200)을 준다(ANT-TOKEN-04). "내역이 없다" 가 아니라 할 일을 알려 준다 */}
        {!ledger.error && ledger.items.length === 0 && !ledger.loading && (
          status.data && !status.data.linked ? (
            <EmptyState title="지갑을 연동해 주세요" hint="연동하면 가입 보너스가 여기에 쌓입니다" />
          ) : (
            <EmptyState
              title={reason ? `${REASON_LABEL[reason]} 내역이 없습니다` : '아직 내역이 없습니다'}
              hint={reason ? '다른 사유로 확인해 보세요' : '가입 보너스와 성과 보상이 여기에 쌓입니다'}
            />
          )
        )}

        {ledger.items.length > 0 && (
          <ul className="wallet-ledger">
            {ledger.items.map((e, i) => {
              const gain = !e.delta.startsWith('-')
              return (
                <li key={`${e.createdAt}-${i}`} className="wallet-entry">
                  <span className="wallet-entry-reason">{reasonLabel(e.reason)}</span>
                  <span className="wallet-entry-date">{formatDateTime(e.createdAt)}</span>
                  <span className={`wallet-entry-delta num ${gain ? 'gain' : 'spend'}`}>
                    {`${gain ? '+' : ''}${formatToken(e.delta)}`}
                  </span>
                  {/* 온체인 기록이 있으면 해시를 보여 준다. 배치 지급 등은 null 일 수 있다 */}
                  <span className="wallet-entry-tx num">
                    {e.txHash ? `${e.txHash.slice(0, 10)}…${e.txHash.slice(-6)}` : '—'}
                  </span>
                </li>
              )
            })}
          </ul>
        )}

        {ledger.loading && ledger.items.length > 0 && (
          <p className="wallet-more-hint">불러오는 중…</p>
        )}

        {ledger.hasNext && !ledger.loading && (
          <button type="button" className="wallet-more" onClick={ledger.loadMore}>
            더 보기
          </button>
        )}
      </div>
    </main>
  )
}
