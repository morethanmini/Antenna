/* C-04 예측 포트폴리오 · /me/portfolio
   담당 스토리 [ANT-FE-PORTFOLIO]
   설계서 docs/화면설계서.md §3 C · §4 C-04

   GET /predictions/me/portfolio (ANT-PRED-06) 하나에 붙는다. 응답이 숫자 여덟 개라
   화면도 그만큼이다.

   ── 두지 않는 것 (설계서 §4 C-04) ─────────────────────────
   AI 유형 분석문 · 섹터별·기간별 적중률 · 근거 사용 패턴 · 확신도 캘리브레이션 ·
   대표 Forecast · 공유 카드 · 누적 수익률 · 120영업일 추이.
   재료가 없거나(실전에는 체결이 없어 수익률을 낼 수 없다) 표본이 모자라 숫자가
   거짓말을 한다.

   ── C-02 내 예측과 무엇이 다른가 ──────────────────────────
   저쪽은 "내 예측 하나하나가 지금 어디까지 왔나" 를 목록으로 답한다. 여기는
   "지금까지 통틀어 어땠나" 를 숫자로 답한다. 그래서 목록을 전량 받아 세지 않고
   서버 집계 한 번만 부른다 — 예측이 늘어도 읽는 행이 늘지 않는다. */
import { Link } from 'react-router-dom'
import { useApiQuery } from '../api/useApiQuery'
import type { Portfolio as PortfolioSummary } from '../api/predictions'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/forecast.css'

/** 판정 전에는 적중률·평균 오차가 null 로 온다 — 0 으로 그리면 "오차 0%" 라는 거짓말이 된다. */
const metric = (value: number | null, suffix: string) =>
  value === null ? '—' : `${value.toFixed(1)}${suffix}`

export default function Portfolio() {
  const q = useApiQuery<PortfolioSummary>('/predictions/me/portfolio')
  const d = q.data

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>예측 포트폴리오</h1>
          <p>판정이 끝난 예측을 통틀어 어땠는지 봅니다</p>
        </div>

        {q.error && <ErrorState error={q.error} onRetry={q.reload} />}
        {!q.error && q.loading && <p className="fc-state">불러오는 중…</p>}

        {/* 예측이 없어도 200 이다. 0 만 늘어놓기보다 무엇을 하면 되는지 알려 준다 */}
        {!q.error && !q.loading && d && d.total === 0 && (
          <div className="fc-none">
            <p>아직 등록한 예측이 없습니다.</p>
            <p className="fc-none-hint">종목을 하나 골라 첫 예측을 남겨 보세요.</p>
            <Link className="state-cta" to="/stocks">종목 탐색으로</Link>
          </div>
        )}

        {!q.error && !q.loading && d && d.total > 0 && (
          <>
            {/* ── 성적 ─────────────────────────────────── */}
            <section className="fc-card">
              <h2>판정 성적</h2>
              <dl className="fc-metrics">
                <div>
                  <dt>적중률</dt>
                  <dd className="num big">{metric(d.hitRate, '%')}</dd>
                  <small>적중 {d.hit} · 빗나감 {d.miss}</small>
                </div>
                <div>
                  <dt>평균 오차</dt>
                  <dd className="num big">{metric(d.avgError, '%')}</dd>
                  <small>목표가와 실제 종가의 차이</small>
                </div>
              </dl>
              {/* 분모가 판정된 것뿐임을 적는다 — 대기가 많을수록 적중률이 낮아진다고 오해한다 */}
              <p className="fc-note">
                대기 중인 {d.pending}건은 아직 셈에 들어가지 않았습니다.
              </p>
            </section>

            {/* ── 상태 분포 ────────────────────────────── */}
            <section className="fc-card">
              <h2>상태 분포</h2>
              <ul className="fc-dist">
                <li><b className="num">{d.pending}</b><span>판정 대기</span></li>
                <li><b className="num up">{d.hit}</b><span>적중</span></li>
                <li><b className="num down">{d.miss}</b><span>빗나감</span></li>
                <li><b className="num">{d.total}</b><span>전체</span></li>
              </ul>
              <Link className="fc-more" to="/me/predictions">내 예측 하나씩 보기 ›</Link>
            </section>

            {/* ── 온체인 기록 ──────────────────────────── */}
            <section className="fc-card">
              <h2>온체인 기록</h2>
              <ul className="fc-dist">
                <li><b className="num">{d.anchored}</b><span>체인에 기록됨</span></li>
                <li><b className="num">{d.unanchored}</b><span>기록 대기</span></li>
              </ul>
              {/* 기록 대기를 실패로 읽지 않게 한다 — 앵커는 하루 한 번 도는 배치다 */}
              <p className="fc-note">
                봉인된 예측은 하루 한 번 배치로 묶여 체인에 올라갑니다. 대기는 실패가 아닙니다.
              </p>
              <Link className="fc-more" to="/ledger">커밋 원장에서 확인 ›</Link>
            </section>
          </>
        )}
      </div>
    </main>
  )
}
