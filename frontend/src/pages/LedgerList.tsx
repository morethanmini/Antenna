/* D-01 커밋 원장 · /ledger
   담당 스토리 [ANT-FE-LEDGER-LIST] · 리디자인 S15P21A507-292
   설계서 docs/화면설계서.md §3 D · §7

   봉인 한 건 단위로 읽는다 — 배치 단위가 아니다
   전에는 앵커 배치 한 줄에 "#418 · 커밋 137건" 이 전부였다. 무엇이 봉인됐는지
   알 수 없어 원장이 읽히지 않았다. 배치 줄에는 종목을 적을 수도 없다 —
   한 배치에 여러 종목의 커밋이 함께 묶이기 때문이다.
   그래서 봉인 한 건을 한 줄로 두고, 배치는 그 줄을 **묶는 머리줄**이 된다.

   배치 상세(D-02)는 그대로 남는다. 그쪽은 리프 전량으로 머클루트를 다시 접는
   검증용 뷰라 성격이 다르다 — 여기는 "최근에 무엇이 봉인됐나" 를 보는 목록이다.

   ── 리디자인에서 바뀐 것 (S15P21A507-292) ─────────────────────
   이 앱의 핵심 주장은 "예측을 체인에 봉인해 나중에 못 고친다" 인데, 그 주장을
   증명하는 화면이 리포트 피드와 CSS 상 구별되지 않았다(흰 카드 + 회색 테두리
   12px 라운드). 등록 순간에는 BlockchainScene 이 개미·블록·사슬로 이야기를 다
   해 주는데, 기록이 쌓인 결과를 보는 이 화면에는 그 어휘가 한 조각도 없었다.

   외부 조사(2026-09-18, backend/docs/원장 페이지 레퍼런스/)에서 고른 넷이
   밝음·어두움 두 패로 갈렸고, 그게 그대로 화면의 두 층이 됐다.

     머리   Bitfeed(트랜잭션 사각형이 모여 블록이 된다) + Solscan(점 격자 배경)
     본문   GitHub 커밋 목록(그룹 헤더 + 좌측 세로선) + Blockscout(해시 덩어리)

   **배치로 묶는 것이 이 리디자인의 본체다.** 전에는 줄마다 `#418` 이 반복될
   뿐이라 "여러 건이 한 번에 올라간다" 는 사실이 안 보였다. 묶어 두면 구조가
   그 말을 대신한다.

   ── 왜 앵커를 따로 부르나 ─────────────────────────────────────
   GET /commits(CommitItemResponse)에는 체인 증거가 없다. anchorId·businessDate·
   anchorStatus 까지고 확정 시각·txHash·merkleRoot 는 GET /anchors 에만 있다.
   머리 패널이 어차피 배치 목록을 필요로 하므로, 한 번의 호출이 머리 패널과
   배치 머리줄을 **동시에** 채운다. GET /commits 에 필드를 싣도록 BE 를 고치는
   길도 있었지만(리포트 피드가 간 길) 이 화면에선 이득이 없다.

   앵커를 못 찾아도 머리줄은 그려진다 — 줄 자신이 businessDate·anchorStatus 를
   들고 오기 때문이다. 앵커는 거기에 확정 시각과 건수를 얹어 줄 뿐이다.

   설계 제약
   - 상태 표시는 AnchorBadge 를 쓴다. C-01 · C-02 · M-02 와 같은 컴포넌트다.
   - 커밋 해시는 잘라서 보여만 준다. 복사 버튼(CopyHash)은 뺐다(2026-09-23) — 이 목록에서
     해시를 복사해 갈 곳이 없고, 줄마다 아이콘이 붙어 어수선했다. 머리줄의 머클루트도
     같은 이유로 뺐다. 전체 값·복사는 배치 상세(D-02)·검산(D-03)에 남아 있다.
   - 앵커는 배치 B2 가 만든다. 실시간이 아니므로 갱신 주기를 오해하지 않게 표시한다.

   아직 배치에 안 묶인 커밋은 배치 자리가 비어 온다(anchorId 가 null).
   봉인은 끝났고 다음 배치를 기다리는 중이라 실패가 아니다 — 그 줄들을 맨 위
   한 그룹으로 모으고 "다음 배치 대기" 머리줄을 준다.

   방향·목표가·작성자는 응답에 없다. 판정 전이면 작성자·구독자만 봐야 하는 값이라
   원장이 내리면 E-02·C-03 의 게이팅을 우회한다(서버 CommitItemResponse 주석).

   줄이 두 종류다 (ANT-CHAIN-15)
   리포트도 예측 커밋과 같은 머클 트리에 올라가는데(ANT-CHAIN-14) 이 화면이 예측만
   보여 주고 있었다 — 체인에 올라간 것의 절반만 보여 주는 원장은 서비스가 하는 주장을
   스스로 깎는다. 리포트 줄은 본문 없이 제목·공개범위까지만 온다. */
import { Link } from 'react-router-dom'
import { useCursorList } from '../api/useCursorList'
import { useApiQuery } from '../api/useApiQuery'
import {
  formatAnchorAgo, formatBusinessDate, shortHash,
  type Anchor, type AnchorStatus, type LedgerCommit,
} from '../api/anchors'
import { phaseOf, revealLabel } from '../api/predictions'
import type { CursorList } from '../api/types'
import AnchorBadge from '../components/AnchorBadge'
import { ANCHOR_BATCH_CYCLE } from '../components/prediction/AnchorStage'
import PredictionStatus from '../components/prediction/PredictionStatus'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/ledger.css'

/* 상태 큐브. 등록 모달(BlockchainScene)이 쓰는 그림 그대로다 — 이 서비스에서
   "봉인" 과 "체인 확정" 을 이미 뜻하는 그림이라 설명 없이 읽힌다.

   animationFrames.ts 의 CUBE 상수를 쓰지 않는 이유: 그 파일은 prep-ani2.py 가
   내는 자동 생성물이라 손으로 고치지 말라고 적혀 있고, 거기엔 anchored·failed
   둘만 들어 있다. 원장에 필요한 sealed(앵커 대기)가 빠져 있어 경로를 여기에
   적는다 — 리포트 피드(Reports.tsx)가 같은 이유로 같은 방식을 쓴다. */
const CUBE: Record<AnchorStatus | 'WAITING', string> = {
  WAITING: '/assets/blockchain-animation/cube/cube-sealed.webp',   // 자물쇠 — 봉인됐고 배치 대기
  PENDING: '/assets/blockchain-animation/cube/cube-sealed.webp',   // 배치엔 묶였고 블록 확정 대기
  CONFIRMED: '/assets/blockchain-animation/cube/cube-anchored.webp', // 빛나는 육각 — 체인 확정
  FAILED: '/assets/blockchain-animation/cube/cube-failed.webp',
}

/** 앵커를 한 번에 몇 개 받아 둘 것인가. 서버 상한이 100 이다(AnchorQueryService).
 *  ponytail: 커밋 목록을 계속 "더 보기" 하면 100회차보다 오래된 배치가 나올 수
 *  있다. 그때는 앵커를 못 찾지만 머리줄은 줄 자신의 businessDate·anchorStatus 로
 *  그려진다 — 확정 시각과 건수만 빠진다. 페이징이 필요해지면 그때 붙인다. */
const ANCHOR_PAGE = 100

/** 연속된 같은 배치를 한 묶음으로. anchorId 가 null 이면 "다음 배치 대기" 묶음이다. */
type Group = {
  anchorId: number | null
  rows: LedgerCommit[]
  /** 같은 배치가 위에 이미 한 번 나왔다 — 발행 순서 때문에 쪼개진 두 번째 이후 묶음 */
  continued: boolean
}

/**
 * 목록을 배치 묶음으로 자른다.
 *
 * **연속된 같은 anchorId 만 묶는다** — GitHub 커밋 목록이 연속된 같은 날짜를 묶는
 * 것과 같다. /commits 의 정렬은 발행 시각 내림차순이지 앵커 순서가 아니라서
 * (CommitQueryService.ORDER), 앵커 전송이 실패해 다음 회차로 넘어간 건은 발행이
 * 이른데 배치가 늦어 중간에 끼어들 수 있다. 같은 배치가 뒤에 또 나오면 묶음이
 * 하나 더 생기는데, 그건 발행 순서를 그대로 보여 주는 것이라 틀린 게 아니다.
 *
 * 서버 정렬을 배치 순으로 바꾸는 길은 막혀 있다 — 커서 계약이 이 정렬에 묶여 있어
 * 바꾸면 페이지 경계에서 줄이 빠지거나 겹친다(CommitQueryService 주석).
 *
 * "더 보기" 경계는 따로 처리하지 않아도 된다. useCursorList 가 items 를 이어 붙여
 * 주므로 누적 배열을 통째로 자르면 이어지는 묶음이 저절로 하나로 합쳐진다.
 */
function groupByAnchor(items: LedgerCommit[]): Group[] {
  const groups: Group[] = []
  /* 이미 나온 배치를 기억해 둔다. 쪼개진 두 번째 묶음에 "배치 #1 · 18건" 이 그대로
     한 번 더 적히면, 아래 줄이 둘뿐인데 18건이라 적힌 머리말이 두 개 보인다
     (2026-09-22 QA). 건수는 배치의 진실이라 바꾸지 않고, 이어진 묶음이라고 말한다. */
  const seen = new Set<number>()
  for (const row of items) {
    const last = groups[groups.length - 1]
    if (last && last.anchorId === row.anchorId) {
      last.rows.push(row)
      continue
    }
    const continued = row.anchorId !== null && seen.has(row.anchorId)
    if (row.anchorId !== null) seen.add(row.anchorId)
    groups.push({ anchorId: row.anchorId, rows: [row], continued })
  }
  return groups
}

export default function LedgerList() {
  const { items, hasNext, loading, error, loadMore, reload } = useCursorList<LedgerCommit>('/commits')
  /* 실패해도 화면을 막지 않는다 — 앵커는 머리줄을 풍부하게 할 뿐이고,
     원장 자체는 /commits 만으로 그려진다. 그래서 error 를 읽지 않는다. */
  const anchors = useApiQuery<CursorList<Anchor>>('/anchors', { size: ANCHOR_PAGE })

  const batches = anchors.data?.items ?? []
  const byId = new Map(batches.map((a) => [a.id, a]))
  const groups = groupByAnchor(items)

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          {/* "커밋 원장" 을 "확정 기록" 으로 바꿨다(2026-09-21, QA-0918 S15P21A507-310).
              원장·커밋 둘 다 이 바닥 말이라 처음 보는 사람에게 뜻이 안 닿는다 —
              화면이 실제로 답하는 것은 "내 활동이 언제 못 바꾸게 굳었나" 다. */}
          <h1>확정 기록</h1>
          <p>예측, 리포트, 구독 등 확정된 활동이 언제 기록되었는지 확인할 수 있습니다.</p>
        </div>

        <ChainNote />

        {error && <ErrorState error={error} onRetry={reload} />}

        {!error && items.length === 0 && !loading && (
          <EmptyState
            title="봉인된 커밋이 없습니다"
            hint="예측을 등록하거나 리포트를 발행하면 봉인이 만들어지고 다음 배치에 담깁니다"
          />
        )}

        {groups.map((g, i) => (
          /* 같은 배치가 떨어져 두 번 나올 수 있어(위 groupByAnchor 주석) anchorId 만으로는
             키가 겹친다. 자리(i)를 함께 쓴다 — 목록은 이어 붙기만 하고 순서가 바뀌지 않는다. */
          <section className="lg-group" key={`${g.anchorId ?? 'wait'}-${i}`}>
            <GroupHead group={g} anchor={g.anchorId === null ? undefined : byId.get(g.anchorId)} />
            <ul className="lg-rows">
              {g.rows.map((c) => (
                <li key={`${c.kind}-${c.kind === 'PREDICTION' ? c.predictionId : c.reportId}`} className="lg-row">
                  {c.kind === 'PREDICTION' ? <PredictionRow c={c} /> : <ReportRow c={c} />}
                  <code className="lg-hash" title={c.commitHash}>{shortHash(c.commitHash)}</code>
                </li>
              ))}
            </ul>
          </section>
        ))}

        {loading && <p className="lg-loading">불러오는 중…</p>}

        {hasNext && !loading && (
          <button type="button" className="lg-more" onClick={loadMore}>더 보기</button>
        )}
      </div>
    </main>
  )
}

/* ── 머리 — 얇은 체인 띠 ─────────────────────────────────────
   **처음에는 여기에 Bitfeed 식 사각형 무리를 그렸다가 걷어냈다(2026-09-18).**
   배치 하나를 커밋 수만큼의 사각형으로 그리는 그림이었는데, 본문이 배치 묶음
   구조로 바뀌면서 배치 번호·건수·상태·D-02 링크가 전부 그룹 머리줄에 이미 있게
   됐다. 같은 말을 두 번 하면서 스크롤만 170px 먹었다.

   남긴 것은 셋이다.
     어두운 바탕   "여기는 체인 영역" 을 색으로 말한다. 이게 없으면 본문은 잘
                   정리된 목록일 뿐이라 리포트 피드와 질감이 같아진다
     점 격자       Solscan · Starkscan 이 함께 쓰는 관용 배경. CSS 한 줄이다
     주기 안내     리디자인 전 .lg-note 가 하던 일. 방금 등록한 예측이 "대기" 로
                   있는 것을 고장으로 읽지 않게 하는 문장이라 빠지면 안 된다

   큐브 한 장을 왼쪽에 둔다 — 등록 모달과 같은 그림이라, 사각형 무리를 걷어내도
   스프라이트 어휘가 화면에서 사라지지 않는다.

   앵커 응답을 기다리지 않는다. 띠의 내용이 전부 상수라 첫 그림부터 자리를
   잡는다 — 데이터에 따라 나타났다 사라지면 목록이 위아래로 튄다. */
function ChainNote() {
  return (
    <section className="lg-chain">
      <img
        className="lg-chain-cube"
        src="/assets/blockchain-animation/cube/cube-anchored.webp"
        alt="" width="26" height="26"
      />
      <p className="lg-chain-text">
        <b>체인에 봉인된 기록</b>
        {/* 주기는 AnchorStage 의 상수를 쓴다. 여기에 직접 적어 두었더니 2026-09-15 에
            서버가 10분 주기로 바뀔 때 이 줄만 "매 영업일" 로 남아 운영에서 거짓을
            말했다 — 값이 두 곳에 있으면 한쪽만 고쳐진다. */}
        <span>{`체인 기록은 ${ANCHOR_BATCH_CYCLE} 배치로 묶여 올라갑니다. 방금 등록한 예측·리포트는 다음 배치에 담깁니다.`}</span>
      </p>
    </section>
  )
}

/* ── 배치 머리줄 ─────────────────────────────────────────────
   GitHub 커밋 목록의 날짜 그룹 헤더 자리다. 왼쪽 큐브가 세로선 위의 노드가 되고,
   오른쪽 AnchorBadge 가 GitHub 의 `Verified` 칩 자리를 맡는다.

   머리줄 전체가 D-02 로 가는 링크라, 전에 줄마다 반복되던 `#418` 링크는 사라진다.

   앵커(GET /anchors)를 못 찾아도 그려진다 — 줄 자신이 businessDate·anchorStatus 를
   들고 오기 때문이다. 앵커는 확정 시각과 건수를 얹어 줄 뿐이다. */
function GroupHead({ group, anchor }: { group: Group; anchor?: Anchor }) {
  const rows = group.rows.length

  /* 대기 묶음 — 실패가 아니다. 봉인은 끝났고 묶이기를 기다리는 중이라
     경고색도 배치 번호도 쓰지 않는다. */
  if (group.anchorId === null) {
    return (
      <div className="lg-head is-waiting">
        <img className="lg-node" src={CUBE.WAITING} alt="" width="26" height="26" />
        <div className="lg-head-text">
          <b>다음 배치 대기</b>
          <span>{`${ANCHOR_BATCH_CYCLE} 도는 배치가 곧 묶습니다 · ${rows}건`}</span>
        </div>
      </div>
    )
  }

  /* 줄이 들고 온 상태를 기본으로 쓴다. 앵커를 받아 왔으면 그쪽이 더 새 값이다. */
  const status = anchor?.status ?? group.rows[0].anchorStatus
  const date = group.rows[0].businessDate

  /* **배치가 묶은 건수는 이 묶음에 보이는 줄 수가 아니다.**
     목록이 발행 순서라 한 배치의 커밋이 여러 묶음으로 흩어지고, 다음 장에 더
     남아 있기도 하다. 보이는 줄을 세어 "배치 #1 · 7건" 이라고 적으면 실제로는
     18건인 배치를 7건이라고 말하게 된다 — 원장이 숫자를 틀리면 안 된다.
     앵커가 있으면 그쪽 commitCount 가 참이고, 없으면 건수를 적지 않는다. */
  const total = anchor?.commitCount

  /* 머리줄 전체를 링크로 감싸지 않는다. 배치 번호만 링크로 둔다 — 탐색기들이 블록
     번호를 링크로 두는 자리와 같다. 머클루트는 여기 그리지 않는다(2026-09-23) —
     배치 상세(D-02)에 있고, 이 목록에서는 줄마다 해시가 둘이라 읽기만 어려웠다. */
  return (
    <div className="lg-head">
      <img className="lg-node" src={CUBE[status ?? 'PENDING']} alt="" width="26" height="26" />
      <div className="lg-head-text">
        <Link className="lg-head-no num" to={`/ledger/anchors/${group.anchorId}`}>
          {`배치 #${group.anchorId}`}
        </Link>
        <span>
          {/* 확정 시각은 앵커에만 있다. 없으면 줄이 들고 온 영업일로 물러선다 */}
          {anchor?.confirmedAt
            ? formatAnchorAgo(anchor.confirmedAt)
            : date && formatBusinessDate(date)}
          {/* 쪼개진 두 번째 묶음에서는 건수를 되풀이하지 않는다 — 같은 수가 두 번
              보이면 배치가 둘인 것처럼 읽힌다. 왜 나뉘었는지를 대신 말한다.
              (목록이 발행 시각 순이라 앵커가 늦은 커밋이 사이에 끼어든다) */}
          {group.continued
            ? ' · 위 배치에서 이어집니다'
            : total !== undefined && ` · ${total.toLocaleString('ko-KR')}건`}
        </span>
      </div>
      {status && <AnchorBadge status={status} />}
    </div>
  )
}

/* 예측 줄 — 무엇을 봉인했나.
   검산 링크를 줄 끝에 붙인다. 아래 따로 줄을 두면 목록이 성기게 보인다.

   **판정 전에는 링크를 걸지 않는다.** GET /predictions/{id}/proof 는 미판정 예측을
   작성자·유효 구독자에게만 내리고 그 밖에는 403 이다(ProofService: !settled && !own &&
   !subscriber). 원장은 회원 전원이 보는 화면이라 "기준가 대기" 줄에 검산을 걸어 두면
   대부분에게 눌리는 즉시 403 인 문이 된다 — 증명을 내세우는 화면에서 가장 나쁜 실패다.

   /commits 응답에는 뷰어가 작성자·구독자인지가 없어 그 둘만 남기는 길은 없다. 작성자도
   원장에서는 링크를 잃지만 자기 예측 상세(C-03)에 같은 링크가 있어 길이 끊기지는 않는다 —
   누를 수 있는데 안 보이는 쪽이, 보이는데 막히는 쪽보다 낫다.

   경계는 phaseOf 를 쓴다. 상태 넷 중 무엇이 공개인지의 정의가 거기 한 벌로 있고
   (api/predictions.ts), 서버 게이팅이 갈리는 자리와 정확히 같다. */
function PredictionRow({ c }: { c: Extract<LedgerCommit, { kind: 'PREDICTION' }> }) {
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
      {/* "3일" 대신 공개일(ANT-PRED-12). 이 줄에는 만기일도 없어서 언제 열리는지 알 길이 없었다 */}
      <span className="lg-horizon">{c.revealDate ? `공개일 ${revealLabel(c.revealDate, c.horizon)}` : revealLabel(null, c.horizon)}</span>
      <PredictionStatus status={c.status} />

      {phaseOf(c.status) === 'JUDGED' && (
        <Link className="lg-go" to={`/ledger/verify/${c.predictionId}`}>
          검산<em aria-hidden="true">›</em>
        </Link>
      )}
    </div>
  )
}

/* 리포트 줄.
   종목 자리에 종류를, 기간 자리에 공개범위를 적는다 — 두 줄의 뼈대를 같게 두면
   목록이 한 종류처럼 읽힌다.

   링크가 둘인 이유: 제목은 리포트 본문(F-02)으로, 오른쪽 검산은 D-04 로 간다.
   예측 줄이 검산(D-03)으로 가는 자리와 같은 자리다. */
function ReportRow({ c }: { c: Extract<LedgerCommit, { kind: 'REPORT' }> }) {
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
