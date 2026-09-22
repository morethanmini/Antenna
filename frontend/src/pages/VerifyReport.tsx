/* D-04 리포트 검산 · /ledger/verify/report/:reportId
   담당 스토리 [ANT-CHAIN-17] · 지라 S15P21A507-248

   설계 제약 — D-03(예측 검산)과 같다
   - "전부 클라이언트 실행 — 서버가 '검증됨'이라 말해주면 증명이 아니다."
     서버가 준 판정을 옮겨 적는 자리가 이 화면에 없다. 셋 다 브라우저가 계산한다.
   - 잠긴 단계는 실패가 아니라 아직이다 — 붉게 그리지 않는다.
   - 조각(Step · CheckLine · Verdict)은 D-03 과 같은 것을 쓴다. 두 화면이 다른 색·
     다른 아이콘이면 사용자가 둘을 다른 보증으로 읽는다.

   예측과 무엇이 다른가
   ① 이 두 겹이다. 예측의 커밋 문자열은 noteHash 가 salt 의 역할을 겸해 다섯 값이면
      끝나는데, 리포트는 bodyHash(안쪽) → reportHash(바깥) 두 번을 접는다. 그래서
      비구독자도 바깥 한 겹은 검증할 수 있다 — 본문 없이 bodyHash 만으로 조립된다.
   ③ 이 작성자 대조다. 예측의 ③은 판정 종가를 외부 원본과 맞추는 자리라 판정을
      사용자 손에 남기지만(우리가 대신 부르면 다시 우리를 믿는 일이 된다), 리포트는
      판정이 없다. 대신 서명에서 주소를 복원해 작성자 지갑과 맞춘다 — 이건 외부를
      부르지 않고 브라우저 안에서 끝나므로 기계가 판정해도 된다.

   본문을 어디서 가져오나
   proof 응답에는 본문이 없다(원장·검산은 "무엇이 언제 봉인됐나" 까지다). ①-1 과 ③은
   본문이 있어야 해서 GET /reports/{id} 를 따로 읽는다. 잠긴 리포트면 본문이 안 오고,
   그때는 그 두 줄만 잠긴다. */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { formatConfirmedAt } from '../api/anchors'
import type { ApiError } from '../api/errors'
import { ERROR_CODE } from '../api/errors'
import {
  fetchReportProof, recomputeBodyHash, recomputeReportHash, recoverSigner,
  sealPreimage, signingPreimage, type ReportProof,
} from '../api/reportProof'
import { fetchReport, type ReportDetail } from '../api/reports'
import type { ProofAnchor } from '../api/proof'
import { useAsync } from '../api/useAsync'
import { foldRoot, readChain, type ChainRead } from '../chain/commitAnchor'
import AnchorBadge from '../components/AnchorBadge'
import CopyHash from '../components/CopyHash'
import ErrorState from '../components/state/ErrorState'
import { CheckLine, Step, type Verdict } from '../components/verify/VerifyStep'
import '../styles/screens/verify.css'

export default function VerifyReport() {
  const { reportId } = useParams<{ reportId: string }>()

  const load = useCallback(() => fetchReportProof(reportId ?? ''), [reportId])
  const proof = useAsync<ReportProof>(load)

  /* 본문은 따로 읽는다. 실패해도 화면을 막지 않는다 — ①-1·③만 잠기고 나머지는 돈다.
     잠긴 리포트는 200 + body:null 로 오므로 오류가 아니라 값이 없는 것이다. */
  const loadReport = useCallback(() => fetchReport(reportId ?? ''), [reportId])
  const report = useAsync<ReportDetail>(loadReport)
  const body = report.data?.body ?? null

  const seal = useSealCheck(proof.data, body)
  const chain = useChainCheck(proof.data?.anchor ?? null, proof.data?.reportHash ?? null)
  const signer = useSignerCheck(proof.data, body)

  /* 봉인 이전 발행분은 오류 화면이 아니라 안내다. 리포트는 있고 검산할 거리가 없을 뿐이다. */
  const notSealed = proof.error?.code === ERROR_CODE.REPORT_NOT_SEALED

  return (
    <main className="main">
      <div className="main-inner">
        <Link className="vf-back" to={reportId ? `/reports/${reportId}` : '/reports'}>
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
          리포트로
        </Link>

        <div className="page-head">
          <h1>리포트 검산</h1>
          <p>이 브라우저에서 직접 검산해요</p>
        </div>

        {notSealed && (
          <div className="vf-note" role="status">
            <b>봉인 이전에 발행된 리포트입니다.</b>
            <span>
              리포트를 체인에 올리는 기능(2026-09-15) 이전에 쓰인 글이라 검산할 해시가 없습니다.
              뒤늦게 해시를 만들어 올리면 “그때 있었다” 가 아니라 “지금 만들었다” 가 되므로 소급하지 않습니다.
            </span>
          </div>
        )}

        {proof.error && !notSealed && <ErrorState error={proof.error} onRetry={proof.reload} />}

        {!proof.error && !proof.data && <p className="vf-loading">불러오는 중…</p>}

        {proof.data && (
          <>
            <header className="vf-subject">
              <div className="vf-subject-main">
                <span className="vf-subject-code">{proof.data.title}</span>
              </div>
              <div className="vf-subject-sub vf-subject-badges">
                {/* AnchorBadge 는 배치 상태 셋만 받는다. WAITING 은 "아직 배치가 없다" 라
                    배치 상태가 아니므로 여기서 문구로 적는다(D-03 과 같은 취급). */}
                {proof.data.anchorStatus === 'WAITING'
                  ? <span>다음 배치 대기</span>
                  : <AnchorBadge status={proof.data.anchorStatus} />}
                {!proof.data.visibility && <span>· 구독자 공개</span>}
              </div>
            </header>

            {/* ── ① 봉인 대조 ─────────────────────────────── */}
            <Step
              no={1}
              title="봉인 대조"
              formula="keccak256(본문 ‖ salt) = bodyHash · keccak256(봉인 문자열) = reportHash"
              verdict={seal.verdict}
            >
              <ul className="vf-checks">
                <CheckLine verdict={seal.inner} label="본문이 봉인된 그것인가">
                  {seal.innerDetail}
                </CheckLine>
                <CheckLine verdict={seal.outer} label="제목·공개범위까지 묶인 리프가 맞는가">
                  {seal.outerDetail}
                </CheckLine>
              </ul>

              {seal.preimage && (
                <details className="vf-seal-raw">
                  <summary>봉인 문자열 보기</summary>
                  <pre className="vf-preimage"><code>{seal.preimage}</code></pre>
                </details>
              )}

              <p className="vf-hint">
                {proof.data.salt
                  ? '본문과 salt 를 이 브라우저에서 다시 해시해 서버가 저장한 값과 맞췄습니다.'
                  : '구독자 전용 리포트라 salt가 없어 본문은 대조할 수 없어요. 바깥 해시는 bodyHash만으로 확인할 수 있어요.'}
              </p>
            </Step>

            {/* ── ② 배치 포함 증명 ─────────────────────────── */}
            <Step
              no={2}
              title="배치 포함 증명"
              formula="리프 + 형제 해시 → 루트 → 체인이 답한 루트와 대조"
              verdict={anchorVerdict(proof.data.anchor ?? null, chain)}
            >
              {!proof.data.anchor ? (
                <ul className="vf-checks">
                  <CheckLine verdict="wait" label="아직 배치에 담기지 않았습니다">
                    체인 기록 배치는 10분마다 돕니다. 다음 회차가 이 리포트를 묶습니다.
                  </CheckLine>
                </ul>
              ) : (
                <ul className="vf-checks">
                  <CheckLine
                    verdict={chain.localRoot ? 'pass' : 'wait'}
                    label="내 브라우저가 접은 루트"
                  >
                    {chain.localRoot ? <CopyHash value={chain.localRoot} /> : '접는 중…'}
                  </CheckLine>

                  <CheckLine
                    verdict={rootMatch(chain.localRoot, proof.data.anchor.merkleRoot)}
                    label="서버가 말한 루트와 같은가"
                  >
                    <CopyHash value={proof.data.anchor.merkleRoot} />
                  </CheckLine>

                  <CheckLine
                    verdict={chainVerdict(chain)}
                    label="그 루트가 체인에 박혀 있는가"
                  >
                    {chain.running && '체인에 묻는 중…'}
                    {chain.error && '체인에 연결하지 못했습니다'}
                    {/* confirmedAt 은 PENDING 배치에서 null 이다 — 블록 번호만 적는다 */}
                    {chain.chain && chain.chain.anchoredBlock > 0
                      ? `블록 ${chain.chain.anchoredBlock}`
                        + (proof.data.anchor.confirmedAt
                          ? ` · ${formatConfirmedAt(proof.data.anchor.confirmedAt)}`
                          : '')
                      : null}
                  </CheckLine>

                  <CheckLine
                    verdict={chain.chain === null ? 'wait' : chain.chain.included ? 'pass' : 'fail'}
                    label="컨트랙트가 직접 접어도 같은가"
                  >
                    내 자바스크립트가 틀려도 이 줄은 컨트랙트의 답입니다.
                  </CheckLine>
                </ul>
              )}

              {chain.error && (
                <button type="button" className="vf-seal-retry" onClick={chain.retry}>
                  체인 다시 확인
                </button>
              )}
            </Step>

            {/* ── ③ 작성자 대조 ───────────────────────────── */}
            <Step
              no={3}
              title="작성자 대조"
              formula="서명에서 주소 복원 = 발행자 지갑"
              verdict={signer.verdict}
            >
              {!proof.data.signature ? (
                <ul className="vf-checks">
                  <CheckLine verdict="wait" label="서명 이전에 발행된 리포트입니다">
                    발행에 지갑 서명을 받기 시작한 것은 2026-09-15 입니다. 그 전 글에는 서명이 없습니다 —
                    실패가 아니라 해당 없음입니다.
                  </CheckLine>
                </ul>
              ) : (
                <ul className="vf-checks">
                  <CheckLine verdict={signer.recovered ? 'pass' : signer.locked ? 'locked' : 'wait'} label="서명에서 복원한 주소">
                    {signer.recovered
                      ? <CopyHash value={signer.recovered} />
                      : signer.locked
                        ? '본문을 볼 수 없어 서명 문자열을 다시 만들 수 없습니다'
                        : '복원 중…'}
                  </CheckLine>
                  <CheckLine verdict={signer.match} label="발행자가 등록한 지갑과 같은가">
                    <CopyHash value={proof.data.signerAddress ?? ''} />
                  </CheckLine>
                </ul>
              )}

              <p className="vf-hint">
                서버가 알려 준 주소를 그대로 적지 않습니다. 서명 문자열을 이 브라우저에서 다시 만들고,
                서명에서 주소를 복원해 맞췄습니다.
              </p>
            </Step>
          </>
        )}
      </div>
    </main>
  )
}

/* ── ① 봉인 두 겹 ────────────────────────────────────────
   안쪽은 본문+salt 가 있어야 하고, 바깥은 bodyHash 만 있으면 된다.
   그래서 잠긴 리포트에서도 바깥 한 겹은 판정된다 — 그게 두 겹으로 나눈 이유다. */
type SealCheck = {
  verdict: Verdict
  inner: Verdict
  outer: Verdict
  innerDetail: React.ReactNode
  outerDetail: React.ReactNode
  preimage: string | null
}

function useSealCheck(proof: ReportProof | null, body: string | null): SealCheck {
  const preimage = useMemo(() => (proof ? sealPreimage(proof) : null), [proof])

  const [inner, setInner] = useState<string | null>(null)
  const [outer, setOuter] = useState<string | null>(null)

  useEffect(() => {
    if (!proof?.salt || body === null) return
    let alive = true
    void recomputeBodyHash(body, proof.salt).then((h) => { if (alive) setInner(h) })
    return () => { alive = false }
  }, [proof?.salt, body])

  useEffect(() => {
    if (!preimage) return
    let alive = true
    void recomputeReportHash(preimage).then((h) => { if (alive) setOuter(h) })
    return () => { alive = false }
  }, [preimage])

  if (!proof) {
    return { verdict: 'wait', inner: 'wait', outer: 'wait', innerDetail: null, outerDetail: null, preimage }
  }

  const innerVerdict: Verdict = !proof.salt || body === null
    ? 'locked'
    : inner === null ? 'wait' : inner === proof.bodyHash ? 'pass' : 'fail'
  const outerVerdict: Verdict = outer === null ? 'wait' : outer === proof.reportHash ? 'pass' : 'fail'

  return {
    verdict: worst([innerVerdict, outerVerdict]),
    inner: innerVerdict,
    outer: outerVerdict,
    innerDetail: <CopyHash value={proof.bodyHash} />,
    outerDetail: <CopyHash value={proof.reportHash} />,
    preimage,
  }
}

/* ── ② 브라우저 검산 ─────────────────────────────────────
   D-03 과 같은 장치다. 접기(로컬)와 체인 조회(네트워크)를 나눠 담아, 체인에 못 붙어도
   서버 응답이 스스로 앞뒤가 맞는지는 확인된다. */
type Check = { localRoot: string | null; chain: ChainRead | null; error: ApiError | null; running: boolean }
const CHECK_START: Check = { localRoot: null, chain: null, error: null, running: true }

function useChainCheck(anchor: ProofAnchor | null, leafHash: string | null) {
  const [state, setState] = useState<Check>(CHECK_START)
  const [attempt, setAttempt] = useState(0)
  const gen = useRef(0)

  useEffect(() => {
    if (!anchor || !leafHash) return
    const mine = ++gen.current
    void (async () => {
      try {
        const localRoot = await foldRoot(leafHash, anchor.merkleProof)
        if (mine === gen.current) setState((s) => ({ ...s, localRoot }))
        // 서버가 준 루트가 아니라 내가 접은 루트로 묻는다(v3, ANT-CHAIN-13)
        const chain = await readChain(anchor.contractAddress, localRoot, leafHash, anchor.merkleProof)
        if (mine === gen.current) setState((s) => ({ ...s, chain, running: false }))
      } catch (e) {
        if (mine === gen.current) setState((s) => ({ ...s, error: e as ApiError, running: false }))
      }
    })()
  }, [anchor, leafHash, attempt])

  return { ...state, retry: () => { setState(CHECK_START); setAttempt((n) => n + 1) } }
}

/* ── ③ 작성자 대조 ───────────────────────────────────────
   서명 문자열에 salt 없는 keccak256(body) 가 들어가므로 본문이 있어야 한다.
   잠긴 리포트면 복원 자체를 못 한다 — 실패가 아니라 잠김이다. */
function useSignerCheck(proof: ReportProof | null, body: string | null) {
  const [recovered, setRecovered] = useState<string | null>(null)

  useEffect(() => {
    const signature = proof?.signature
    if (!signature || !proof.signNonce || body === null) return
    let alive = true
    void (async () => {
      try {
        const preimage = await signingPreimage(proof, body)
        const address = await recoverSigner(preimage, signature)
        if (alive) setRecovered(address)
      } catch {
        // 복원 실패는 "불일치" 로 드러난다 — 여기서 문구를 또 만들지 않는다
        if (alive) setRecovered('')
      }
    })()
  }, [proof, body])

  if (!proof?.signature) {
    return { verdict: 'wait' as Verdict, match: 'wait' as Verdict, recovered: null, locked: false }
  }

  const locked = body === null
  const match: Verdict = locked
    ? 'locked'
    : recovered === null ? 'wait'
      : recovered === proof.signerAddress?.toLowerCase() ? 'pass' : 'fail'

  return { verdict: match, match, recovered: recovered || null, locked }
}

// ── 판정 합치기 ──────────────────────────────────────────

/** 한 단계 안의 줄들을 합친다. 실패가 하나라도 있으면 실패, 다음이 대기, 그다음이 잠김. */
function worst(verdicts: Verdict[]): Verdict {
  if (verdicts.includes('fail')) return 'fail'
  if (verdicts.includes('wait')) return 'wait'
  if (verdicts.includes('locked')) return 'locked'
  return 'pass'
}

function rootMatch(local: string | null, server: string): Verdict {
  if (local === null) return 'wait'
  return local.toLowerCase() === server.toLowerCase() ? 'pass' : 'fail'
}

function chainVerdict(chain: Check): Verdict {
  // 체인에 못 붙은 것은 "틀렸다" 가 아니다 — 아직 확인 못 한 것이라 붉게 그리지 않는다
  if (chain.error) return 'wait'
  if (chain.running || !chain.chain) return 'wait'
  return chain.chain.anchoredBlock > 0 ? 'pass' : 'fail'
}

function anchorVerdict(anchor: ProofAnchor | null, chain: Check): Verdict {
  if (!anchor) return 'wait'
  return worst([rootMatch(chain.localRoot, anchor.merkleRoot), chainVerdict(chain)])
}
