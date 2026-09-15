/* D-01 커밋 원장 · /ledger
   담당 스토리 [ANT-FE-LEDGER-LIST]
   설계서 docs/화면설계서.md §3 D · §7

   봉인 한 건 단위로 읽는다 — 배치 단위가 아니다
   전에는 앵커 배치 한 줄에 "#418 · 커밋 137건" 이 전부였다. 무엇이 봉인됐는지
   알 수 없어 원장이 읽히지 않았다. 배치 줄에는 종목을 적을 수도 없다 —
   한 배치에 여러 종목의 커밋이 함께 묶이기 때문이다.
   그래서 봉인 한 건을 한 줄로 두고, 배치는 그 줄에서 들어간다.

   배치 상세(D-02)는 그대로 남는다. 그쪽은 리프 전량으로 머클루트를 다시 접는
   검증용 뷰라 성격이 다르다 — 여기는 "최근에 무엇이 봉인됐나" 를 보는 목록이다.

   설계 제약
   - 상태 표시는 AnchorBadge 를 쓴다. C-01 · C-02 · M-02 와 같은 컴포넌트다.
   - 커밋 해시는 잘라 보여주더라도 전체 값을 복사할 수 있어야 한다 → CopyHash.
     체인 탐색기에 붙여 넣어 직접 확인하는 길을 막지 않는다.
   - 앵커는 배치 B2 가 만든다. 실시간이 아니므로 갱신 주기를 오해하지 않게 표시한다.

   아직 배치에 안 묶인 커밋은 배치 자리가 비어 온다(anchorId 가 null).
   봉인은 끝났고 다음 배치를 기다리는 중이라 실패가 아니다 — 빈 칸을 오류처럼
   그리지 않고 "다음 배치 대기" 라고 적는다.

   방향·목표가·작성자는 응답에 없다. 판정 전이면 작성자·구독자만 봐야 하는 값이라
   원장이 내리면 E-02·C-03 의 게이팅을 우회한다(서버 CommitItemResponse 주석).

   줄이 두 종류다 (ANT-CHAIN-15)
   리포트도 예측 커밋과 같은 머클 트리에 올라가는데(ANT-CHAIN-14) 이 화면이 예측만
   보여 주고 있었다 — 체인에 올라간 것의 절반만 보여 주는 원장은 서비스가 하는 주장을
   스스로 깎는다. 리포트 줄은 본문 없이 제목·공개범위까지만 온다. */
import { Link } from 'react-router-dom'
import { useCursorList } from '../api/useCursorList'
import { formatBusinessDate, type LedgerCommit } from '../api/anchors'
import { horizonLabel } from '../api/predictions'
import AnchorBadge from '../components/AnchorBadge'
import { ANCHOR_BATCH_CYCLE } from '../components/prediction/AnchorStage'
import CopyHash from '../components/CopyHash'
import PredictionStatus from '../components/prediction/PredictionStatus'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/ledger.css'

export default function LedgerList() {
  const { items, hasNext, loading, error, loadMore, reload } = useCursorList<LedgerCommit>('/commits')

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>커밋 원장</h1>
          <p>봉인된 예측과 리포트가 배치로 묶여 체인에 기록된 기록입니다</p>
        </div>

        {/* 배치 주기를 알려 준다 — 방금 등록한 예측이 "대기" 로 있는 것을 고장으로 읽으면 안 된다.
            주기는 AnchorStage 의 상수를 쓴다. 여기에 직접 적어 두었더니 2026-09-15 에 서버가
            10분 주기로 바뀔 때 이 줄만 "매 영업일" 로 남아 운영에서 거짓을 말했다 — 값이 두
            곳에 있으면 한쪽만 고쳐진다. */}
        <p className="lg-note">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="9" /><path d="M12 7.5V12l3 1.8" />
          </svg>
          {`앵커는 ${ANCHOR_BATCH_CYCLE} 배치로 묶여 올라갑니다. 방금 등록한 예측·리포트는 다음 배치에 담깁니다.`}
        </p>

        {error && <ErrorState error={error} onRetry={reload} />}

        {!error && items.length === 0 && !loading && (
          <EmptyState
            title="봉인된 커밋이 없습니다"
            hint="예측을 등록하거나 리포트를 발행하면 봉인이 만들어지고 다음 배치에 담깁니다"
          />
        )}

        {items.length > 0 && (
          <ul className="lg-list">
            {items.map((c) => (
              <li key={`${c.kind}-${c.kind === 'PREDICTION' ? c.predictionId : c.reportId}`} className="lg-item">
                {c.kind === 'PREDICTION' ? <PredictionHead c={c} /> : <ReportHead c={c} />}

                {/* ── 어디에 기록됐나 ───────────────────── */}
                <dl className="lg-fields">
                  <div className="lg-field">
                    <dt>커밋</dt>
                    <dd><CopyHash value={c.commitHash} /></dd>
                  </div>

                  {c.anchorId !== null ? (
                    <div className="lg-field">
                      <dt>배치</dt>
                      <dd className="lg-batch">
                        {/* routes.ts 의 D-02 경로가 /ledger/anchors/:id 다 */}
                        <Link className="num" to={`/ledger/anchors/${c.anchorId}`}>{`#${c.anchorId}`}</Link>
                        {c.businessDate && (
                          <span className="lg-date">{formatBusinessDate(c.businessDate)}</span>
                        )}
                        {c.anchorStatus && <AnchorBadge status={c.anchorStatus} />}
                      </dd>
                    </div>
                  ) : (
                    <div className="lg-field">
                      <dt>배치</dt>
                      {/* 실패가 아니다. 봉인은 끝났고 묶이기를 기다리는 중이다 */}
                      <dd className="lg-waiting">다음 배치 대기</dd>
                    </div>
                  )}
                </dl>
              </li>
            ))}
          </ul>
        )}

        {loading && <p className="lg-loading">불러오는 중…</p>}

        {hasNext && !loading && (
          <button type="button" className="lg-more" onClick={loadMore}>더 보기</button>
        )}
      </div>
    </main>
  )
}

/* 예측 줄의 머리 — 무엇을 봉인했나.
   검산 링크를 머리줄 끝에 붙인다. 아래 따로 줄을 두면 카드가 비어 보인다. */
function PredictionHead({ c }: { c: Extract<LedgerCommit, { kind: 'PREDICTION' }> }) {
  return (
    <div className="lg-what">
      {c.stockName || c.stockCode ? (
        <>
          <b className="lg-stock">{c.stockName ?? c.stockCode}</b>
          {/* 이름이 있을 때만 코드를 덧붙인다. 이름이 없으면 위가 이미 코드다 */}
          {c.stockName && c.stockCode && <em className="lg-code num">{c.stockCode}</em>}
        </>
      ) : (
        /* 종목이 지워진 예측. 빈칸으로 두면 데이터가 깨진 것처럼 읽힌다 */
        <b className="lg-stock is-none">종목 없음</b>
      )}
      <span className="lg-horizon">{horizonLabel(c.horizon)}</span>
      <PredictionStatus status={c.status} />

      <Link className="lg-go" to={`/ledger/verify/${c.predictionId}`}>
        검산<em aria-hidden="true">›</em>
      </Link>
    </div>
  )
}

/* 리포트 줄의 머리.
   종목 자리에 종류를, 기간 자리에 공개범위를 적는다 — 두 줄의 뼈대를 같게 두면
   목록이 한 종류처럼 읽힌다.

   링크가 둘인 이유: 제목은 리포트 본문(F-02)으로, 오른쪽 검산은 D-04 로 간다.
   예측 줄이 검산(D-03)으로 가는 자리와 같은 자리다. */
function ReportHead({ c }: { c: Extract<LedgerCommit, { kind: 'REPORT' }> }) {
  return (
    <div className="lg-what">
      <b className="lg-stock">리포트</b>
      <Link className="lg-title" to={`/reports/${c.reportId}`}>{c.title}</Link>
      <span className="lg-horizon">{c.visibility ? '공개' : '구독자 전용'}</span>

      <Link className="lg-go" to={`/ledger/verify/report/${c.reportId}`}>
        검산<em aria-hidden="true">›</em>
      </Link>
    </div>
  )
}
