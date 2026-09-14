/* C-02 내 예측 현황 · /me/predictions
   담당 스토리 [ANT-FE-PREDICT-LIST]
   설계서 docs/화면설계서.md §3 C · §4 C-02 · §7

   GET /predictions/me (ANT-PRED-06) 에 붙어 있다.

   이 화면이 답하는 질문은 하나다 — "내 예측이 지금 어디까지 왔나".
   그래서 목록을 상태값이 아니라 **살아가는 단계**로 가른다:

     기록 예정 → 예측 진행 → 결과 확인

   서버 status(BASE·OPEN·HIT·MISS)와 anchorStatus 를 조합해 만든 단계다
   (stageOf 참고). 세 칸이 서로 겹치지 않고 합이 전체와 같아, 사용자가 자기
   예측을 셋 중 어디에 있는지로만 생각하면 된다.

   ── 왜 전량을 받아 두는가 ────────────────────────────────
   단계는 서버 거르개 어휘(ALL·PENDING·HIT·MISS)에 없다. anchorStatus 로 거를
   수단이 없고, HIT·MISS 를 한 번에 받는 어휘도 없다. 그래서 status 를 붙이지
   않은 전량을 커서 끝까지 받아 두고, 거르기·세기·정렬을 전부 화면에서 한다.

   전량을 받으므로 세 칸의 건수가 **정확하다** — 불러온 만큼만 센 값이 아니다.
   그리고 칸을 바꿔도 다시 부르지 않는다.

   무한정 받지는 않는다(LOAD_CAP). 슬롯이 하루 단위라(SlotStatus 주석) 오래 쓴
   계정은 수백 건이 될 수 있고, 그때는 상한에서 멈추고 "더 보기" 를 준다.
   상한에 걸린 동안에는 건수를 적지 않는다 — 다 못 센 숫자를 적으면 거짓말이다.

   설계 제약
   - 응답 필드는 명세가 못 박은 아홉 개에 stockName · anchorStatus 를 더한
     열한 개뿐이다. **그 밖의 값을 화면에서 만들어내지 않는다.**
     시안의 예측가 · 현재가 · 확신도 · 정확도 점수 · 등록 일시는 이 응답에 없어
     그리지 않았다. 가격 슬라이더도 같은 이유로 뺐다(기준가·현재가가 없다).
     대신 그 자리에 **기간 진행 바**를 둔다 — 그건 지어낸 값이 아니다.
     서버가 `만기일 = 기준일 + horizon 일`로 잡으므로(Prediction 엔티티),
     기준일과 진행률이 settleDate · horizon · dday 에서 정확히 나온다.
   - **수정·삭제 버튼을 두지 않는다.** 해당 API 가 없고, 예측은 등록 후 불변이다. */
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useCursorList } from '../api/useCursorList'
import { fetchMyPredictions, hitRatePercent, horizonLabel, phaseOf } from '../api/predictions'
import type { MyPrediction, MyPredictionMeta } from '../api/predictions'
import { ANCHOR_BATCH_TIME, isAnchored } from '../components/prediction/AnchorStage'
import EmptyState from '../components/state/EmptyState'
import ErrorState from '../components/state/ErrorState'
import '../styles/screens/predict-list.css'

/* 전량 적재 상한. 넘으면 멈추고 "더 보기" 로 넘긴다 */
const LOAD_CAP = 300

const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const day = (iso: string) => iso.replace(/-/g, '.').slice(2)

/* 종목 로고를 서버가 주지 않는다. 빈 동그라미를 두면 자리만 먹으므로 이름
   첫 글자를 딴다 — 없는 이미지를 어디선가 끌어오지 않는다. */
const monogram = (p: MyPrediction) =>
  p.stockName?.trim().slice(0, 1) ?? p.stockCode?.slice(0, 2) ?? '?'

/* ── 단계 ────────────────────────────────────────────────
   판정이 끝났으면 앵커가 어디까지 갔든 "결과 확인" 이다 — 그 사람이 보고
   싶은 것은 맞혔는지이지, 기록 절차가 아니다. */
const STAGES = ['ALL', 'ANCHOR', 'OPEN', 'DONE'] as const
type Stage = (typeof STAGES)[number]

const STAGE_LABEL: Record<Stage, string> = {
  ALL: '전체',
  ANCHOR: '기록 예정',
  OPEN: '예측 진행',
  DONE: '결과 확인',
}

const stageOf = (p: MyPrediction): Exclude<Stage, 'ALL'> =>
  phaseOf(p.status) === 'JUDGED' ? 'DONE'
    : isAnchored(p.anchorStatus) ? 'OPEN'
      : 'ANCHOR'

const SORTS = ['LATEST', 'DUE'] as const
type Sort = (typeof SORTS)[number]
const SORT_LABEL: Record<Sort, string> = { LATEST: '최신순', DUE: '만기 임박순' }

/* 비어 있는 이유가 칸마다 다르다. "없습니다" 한 문구로 뭉치면 무엇을 하면
   채워지는지 알려 줄 수 없다. */
const EMPTY: Record<Stage, { title: string; hint: string }> = {
  ALL: {
    title: '아직 등록한 예측이 없습니다',
    hint: '종목을 고르고 방향과 목표가를 정해 첫 예측을 남겨 보세요.',
  },
  ANCHOR: {
    title: '블록체인 기록을 기다리는 예측이 없습니다',
    hint: `등록한 예측은 매일 ${ANCHOR_BATCH_TIME}에 도는 배치가 한 번에 묶어 기록합니다.`,
  },
  OPEN: {
    title: '진행 중인 예측이 없습니다',
    hint: '블록체인 기록이 끝나고 만기일을 기다리는 예측이 이곳에 모입니다.',
  },
  DONE: {
    title: '결과가 나온 예측이 없습니다',
    hint: '만기 영업일 종가가 나오면 적중 여부가 자동으로 정해집니다.',
  },
}

/* 등록한 사람이 다음에 무엇을 기다리면 되는지 세 걸음으로 못 박는다.
   두 번째 걸음의 시각이 이 화면의 핵심 안내다 — 앵커 배치는 매일 00:05 KST 에
   전날 등록분을 한 번에 묶는다(백엔드 AnchorScheduler, 주말 포함). */
const STEPS = [
  {
    title: '예측 등록',
    body: '투자 아이디어를 등록해요.',
    icon: <><path d="M8 4h6l4 4v12H8z" /><path d="M14 4v4h4" /><path d="M11 13h4M11 16h4" /></>,
  },
  {
    title: '블록체인 기록',
    /* "수정할 수 없다" 는 여기 적지 않는다. 예측을 등록할 때(C-01 봉인 모달)
       이미 그 말을 하고 확인까지 받는다 — 다 끝난 뒤에 또 경고하면 이 화면이
       무언가 잘못됐다고 말하는 것처럼 읽힌다. 대기 카드의 안내에는 남긴다. */
    body: `매일 ${ANCHOR_BATCH_TIME}에 하루치를 한 번에 묶어 기록해요.`,
    icon: <><path d="M12 3 4 7.2v9.6L12 21l8-4.2V7.2Z" /><path d="M4 7.2 12 11.5l8-4.3" /><path d="M12 11.5V21" /></>,
  },
  {
    title: '결과 확인',
    body: '예측 기간이 끝나면 실제 결과와 비교해요.',
    icon: <><path d="M5 20V11M12 20V5M19 20v-6" /></>,
  },
]

function Icon({ children, size = 15 }: { children: React.ReactNode; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  )
}

/* ── 기간 진행 ───────────────────────────────────────────
   시안의 가격 슬라이더(예측가 → 현재가 → 목표가)는 못 그린다. 기준가도 현재가도
   이 응답에 없다. 대신 **시간**으로 같은 자리를 채운다 — 서버가 만기일을
   `기준일 + horizon 일`로 잡으므로(Prediction 엔티티) 두 값이 정확히 나온다.

   dday 는 오늘부터 만기까지의 일수라, 흐른 날은 horizon - dday 다. */
function elapsed(p: MyPrediction) {
  if (p.dday === null || p.horizon <= 0) return null
  return Math.min(1, Math.max(0, (p.horizon - p.dday) / p.horizon))
}

/** 기준일 = 만기일 - horizon 일. 등록 즉시 확정되는 값이라 되짚어도 정확하다 */
function baseDateOf(p: MyPrediction) {
  if (p.settleDate === null) return null
  const d = new Date(`${p.settleDate}T00:00:00`)
  d.setDate(d.getDate() - p.horizon)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/* ── 오른쪽 칸 ───────────────────────────────────────────
   단계마다 사용자가 지금 알고 싶은 것 하나만 크게 둔다. 기다리는 사람에게는
   "언제 기록되나", 진행 중인 사람에게는 "며칠 남았나", 끝난 사람에게는
   "맞혔나" 다. 세 개를 한 칸에 다 늘어놓으면 무엇도 눈에 안 들어온다. */
function Panel({ p, count = 1 }: { p: MyPrediction; count?: number }) {
  const stage = stageOf(p)
  /* 여러 줄을 대표하는 칸이면 몇 건을 말하는지 밝힌다 — 안 밝히면 위의 줄들
     중 어느 것을 가리키는지 알 수 없다 */
  const many = count > 1

  if (stage === 'ANCHOR') {
    /* 대기가 정상이라는 것을 말로 못 박는다. 이 문장이 없으면 등록이 덜 끝난
       줄 안다 — 등록은 서버가 받은 순간 이미 끝났고, 앵커는 하루 한 번 도는
       배치가 나중에 묶는 별개의 일이다. */
    const copy = p.anchorStatus === 'PENDING'
      ? { lead: '블록 확정을 기다리는 중이에요.', sub: '배치가 트랜잭션을 보냈어요. 잠시 뒤 기록이 확정됩니다.' }
      : p.anchorStatus === 'FAILED'
        ? { lead: '기록에 실패해 다시 시도해요.', sub: '다음 회차 배치가 재시도합니다. 예측 자체는 그대로 남아 있어요.' }
        : {
            /* 한 배치가 하루치를 통째로 묶으므로, 묶인 줄들은 정말로 함께 올라간다 */
            lead: many
              ? `다가오는 ${ANCHOR_BATCH_TIME}에\n이 ${count}건이 한 번에 기록될 예정이에요.`
              : `다가오는 ${ANCHOR_BATCH_TIME}에\n블록체인에 기록될 예정이에요.`,
            sub: '기록 후에는 예측 내용을 수정할 수 없어요.',
          }

    return (
      <div className={`mp-panel is-anchor is-${p.anchorStatus.toLowerCase()}`}>
        <span className="mp-panel-art" aria-hidden="true">
          <Icon size={19}><path d="M12 3 4 7.2v9.6L12 21l8-4.2V7.2Z" /><path d="M4 7.2 12 11.5l8-4.3" /><path d="M12 11.5V21" /></Icon>
          <i />
          <Icon size={19}><path d="M12 3 4 7.2v9.6L12 21l8-4.2V7.2Z" /><path d="M4 7.2 12 11.5l8-4.3" /><path d="M12 11.5V21" /></Icon>
        </span>
        <div>
          <b>{copy.lead}</b>
          <span>{copy.sub}</span>
        </div>
      </div>
    )
  }

  if (stage === 'OPEN') {
    const ratio = elapsed(p)
    const base = baseDateOf(p)

    /* 기준가 대기(BASE)는 기준일이 아직 없어 남은 일수를 셀 수 없다.
       0 으로 그리면 "오늘 만기" 로 읽힌다 */
    if (p.dday === null) {
      return (
        <div className="mp-panel is-open is-base">
          <p className="mp-until">기준가 확정 후</p>
          <p className="mp-wait">다음 영업일 종가로 기준가가 정해지면<br />남은 기간이 표시돼요.</p>
        </div>
      )
    }

    return (
      <div className="mp-panel is-open">
        <p className="mp-until">{many ? `${count}건 모두 결과까지` : '결과까지'}</p>
        <p className="mp-dday num">{p.dday === 0 ? '오늘 만기' : `D-${p.dday}`}</p>
        {ratio !== null && (
          <>
            <div className="mp-bar" role="presentation">
              <span style={{ '--p': `${Math.round(ratio * 100)}%` } as React.CSSProperties} />
            </div>
            <div className="mp-bar-ends num">
              <span>{base ? day(base) : '기준일'}<i>시작</i></span>
              <span>{p.settleDate ? day(p.settleDate) : '미정'}<i>만기</i></span>
            </div>
          </>
        )}
      </div>
    )
  }

  const hit = p.status === 'HIT'
  return (
    <div className={`mp-panel is-done ${hit ? 'is-hit' : 'is-miss'}`}>
      <span className="mp-trophy" aria-hidden="true">
        {hit
          ? <Icon size={18}><path d="M7 4h10v5a5 5 0 0 1-10 0z" /><path d="M7 6H4v1a3 3 0 0 0 3 3M17 6h3v1a3 3 0 0 1-3 3" /><path d="M12 14v3M9 20h6" /></Icon>
          : <Icon size={18}><circle cx="12" cy="12" r="9" /><path d="M9 15c.9-.9 4.1-.9 5 0" /><path d="M9 9.5h.01M15 9.5h.01" /></Icon>}
      </span>
      <div>
        <b>{hit ? '예측 성공' : '예측 실패'}</b>
        <span>{hit ? '목표가에 도달했어요!' : '목표가에 닿지 못했어요.'}</span>
      </div>
      <Link className="mp-cta" to={`/predictions/${p.id}`}>
        결과 보기
        <Icon size={14}><path d="m9 6 6 6-6 6" /></Icon>
      </Link>
    </div>
  )
}

/* 오른쪽 칸을 어디에 둘지 가르는 규칙 — 이 화면의 배치는 이 한 줄에서 나온다.

     한 예측에 대한 값(D-day · 결과)   → 그 줄의 오른쪽 칸
     카드 전체에 대한 안내(앵커 배치)  → 카드 아래 한 줄

   앵커는 예측 하나의 사정이 아니다. 배치가 하루치를 통째로 묶으므로 이 카드의
   예측들은 **정말로 함께** 올라간다. 그래서 건수와 상관없이 — 한 건짜리
   카드에서도 — 카드 아래 한 줄로 낸다. 한 건일 때만 오른쪽 상자로 두면 같은
   안내가 카드마다 다른 모양이 되어, 목록을 훑을 때 눈이 계속 다시 맞춰야 한다.

   D-day 는 값이 같아도 예측마다의 사정이라 합치지 않는다. 결과 칸은 그 위에
   적중·빗나감이 갈리고 안에 그 예측 하나로 가는 버튼이 있어 애초에 대표할 수 없다. */
function isFooterNotice(items: MyPrediction[]) {
  return items.every((p) => stageOf(p) === 'ANCHOR')
    && new Set(items.map((p) => p.anchorStatus)).size === 1
}

function StageBadge({ p }: { p: MyPrediction }) {
  const stage = stageOf(p)
  return (
    <span className={`mp-stage is-${stage.toLowerCase()}`}>
      {stage === 'ANCHOR' ? (
        <><Icon size={13}><circle cx="12" cy="12" r="9" /><path d="M12 7.5V12l3 1.8" /></Icon>블록체인 기록 예정</>
      ) : stage === 'OPEN' ? (
        <><Icon size={13}><path d="M4 17l5-6 4 3 6-8" /><path d="M15 6h4v4" /></Icon>예측 기간 진행 중</>
      ) : (
        <><Icon size={13}><circle cx="12" cy="12" r="9" /><path d="m8.5 12.2 2.4 2.4 4.6-4.9" /></Icon>결과 확인 가능</>
      )}
    </span>
  )
}

function DirBadge({ p }: { p: MyPrediction }) {
  const up = p.direction === 'UP'
  return (
    <span className={`mp-dir ${up ? 'up' : 'down'}`}>
      <Icon size={12}>{up ? <path d="M12 19V6m0 0-5.5 5.5M12 6l5.5 5.5" /> : <path d="M12 5v13m0 0 5.5-5.5M12 18l-5.5-5.5" />}</Icon>
      {up ? '상승 예측' : '하락 예측'}
    </span>
  )
}

function Badges({ p }: { p: MyPrediction }) {
  return (
    <div className="mp-badges">
      <StageBadge p={p} />
      <DirBadge p={p} />
    </div>
  )
}

/* 숫자는 이름표를 달아 세운다. 라벨이 없으면 85,000 이 목표가인지 무엇인지
   줄마다 다시 읽어야 한다.

   같은 종목을 여러 건 걸면 목표가만 다른 경우가 흔하다(같은 날 같은 기간에
   14,535 · 13,770). 그래서 묶은 카드에서는 목표가를 앞세워 굵게 둔다 —
   그 줄들을 가르는 값이 목표가 하나뿐일 때가 많다. */
function Figures({ p }: { p: MyPrediction }) {
  const stage = stageOf(p)
  return (
    <dl className="mp-figures">
      <div>
        <dt>목표가</dt>
        <dd className="num">{won(p.targetPrice)}</dd>
      </div>
      <div>
        <dt>예측 기간</dt>
        <dd className="num">{horizonLabel(p.horizon)}</dd>
      </div>
      <div>
        <dt>만기일</dt>
        {/* 기준가 배치가 만기일을 채우기 전에는 비어 있다 */}
        <dd className="num">{p.settleDate === null ? '미정' : day(p.settleDate)}</dd>
      </div>
      {stage === 'DONE' && (
        <div>
          <dt>목표가 오차</dt>
          <dd className="num">
            {p.errorRate === null ? (
              <span className="mp-none">—</span>
            ) : (
              /* 목표가에 가깝게 맞힌 건은 강조한다 — 이 서비스가 파는 값어치다 */
              <span className={Math.abs(p.errorRate) <= 3 ? 'mp-err is-near' : 'mp-err'}>
                {`${p.errorRate > 0 ? '+' : ''}${p.errorRate}%`}
              </span>
            )}
          </dd>
        </div>
      )}
    </dl>
  )
}

/* 진행·결과 칸에서는 기록이 끝났다는 사실을 한 줄로만 남긴다 —
   이미 지난 일이라 크게 둘 이유가 없다 */
function AnchoredLine({ p }: { p: MyPrediction }) {
  if (stageOf(p) === 'ANCHOR' || !isAnchored(p.anchorStatus)) return null
  return (
    <p className="mp-anchored">
      <Icon size={13}><circle cx="12" cy="12" r="9" /><path d="m8.5 12.2 2.4 2.4 4.6-4.9" /></Icon>
      블록체인 기록 완료
    </p>
  )
}

/* 수정·삭제는 두지 않는다(§4 C-02). 판정 건은 오른쪽 칸의 "결과 보기" 가 같은
   곳으로 가므로 겹쳐 두지 않는다 — 다만 그 칸을 묶어서 감춘 줄에는 길이
   없어지므로 그때는 여기서 낸다. */
function MoreLink({ p, force }: { p: MyPrediction; force?: boolean }) {
  const stage = stageOf(p)
  if (stage === 'DONE' && !force) return null
  return (
    <Link className="mp-more" to={`/predictions/${p.id}`}>
      {stage === 'ANCHOR' ? '기록 상태 보기' : stage === 'DONE' ? '결과 보기' : '상세 보기'}
      <Icon size={14}><path d="m9 6 6 6-6 6" /></Icon>
    </Link>
  )
}

/* ── 종목 한 장 ──────────────────────────────────────────
   같은 종목에 건 예측은 한 카드로 묶는다. 따로 두면 로고 · 종목명 · 코드 ·
   안내 문구가 통째로 반복되어, 화면에서 실제로 다른 값(목표가)이 가장 작은
   글씨로 묻힌다.

   묶어도 예측 하나하나는 여전히 독립된 커밋이다 — 줄마다 제 상세로 가는 길을
   남긴다. 합치는 것은 **카드 전체를 두고 하는 말**뿐이다(isFooterNotice). */
function StockCard({ items }: { items: MyPrediction[] }) {
  const head = items[0]
  const stage = stageOf(head)

  /* 앵커 안내는 카드 아래 한 줄로 낸다 — 한 건짜리 카드에서도 그렇다 */
  const shared = isFooterNotice(items)

  /* 한 건짜리에도 같은 구조를 쓴다. 예전에는 한 건이면 종목을 왼쪽 칸에 세운
     가로 세 칸이었는데, 그 칸이 190px 를 먹어 같은 화면 폭에서 한 건짜리만
     150px 쯤 더 필요했다. 그래서 1150px 에서 카카오(한 건)의 D-day 는 아래로
     내려가고 삼성전자(두 건)의 D-day 는 오른쪽에 남는 일이 벌어졌다 —
     같은 값이 카드마다 다른 자리에 서면 훑을 때 눈이 계속 다시 맞춰야 한다.

     종목을 머리로 올리면 값이 쓰는 폭이 카드마다 같아져 접히는 시점도 같아진다. */
  return (
    <li className={`mp-card is-${stage.toLowerCase()}`}>
      <div className="mp-group-head">
        <Who p={head} />
        <div className="mp-group-meta">
          {/* 줄이 전부 같은 단계면 배지도 머리에 한 번만 단다. 줄마다 달면
              "블록체인 기록 예정" 이 여섯 번 반복되면서, 정작 줄을 가르는
              값(목표가·방향)보다 크게 보인다. */}
          {shared && <StageBadge p={head} />}
          {/* 한 건짜리에 "예측 1건" 은 말해 주는 게 없다 */}
          {items.length > 1 && <span className="mp-count">{`예측 ${items.length}건`}</span>}
        </div>
      </div>

      <ul className="mp-rows">
        {items.map((p) => (
          <li key={p.id} className={`mp-row is-${stageOf(p).toLowerCase()}${shared ? ' is-shared' : ''}`}>
            {/* 단계가 같은 묶음은 한 줄짜리 표로 편다 — 방향 · 숫자 · 링크가
                줄마다 같은 자리에 서야 위아래로 견줄 수 있다.
                단계가 섞인 묶음은 줄마다 제 칸이 붙으므로 예전처럼 쌓는다. */}
            {shared ? (
              <>
                <DirBadge p={p} />
                <Figures p={p} />
              </>
            ) : (
              <div className="mp-body">
                <Badges p={p} />
                <Figures p={p} />
                <AnchoredLine p={p} />
              </div>
            )}
            {!shared && <Panel p={p} />}
            {/* 묶인 칸에서는 결과 줄도 제 길을 잃으므로 링크를 낸다 */}
            <MoreLink p={p} force={shared} />
          </li>
        ))}
      </ul>

      {/* 합친 안내. 기록 대기라면 이 줄들은 정말로 한 배치에 함께 올라간다 */}
      {shared && <Panel p={head} count={items.length} />}
    </li>
  )
}

/* 종목명을 앞세우고 코드를 아래에 둔다. 이름으로 알아보고 코드로 정확히 짚는다.

   종목이 지워지면 서버가 코드와 이름을 함께 null 로 내린다. 갈 곳이 없으므로
   링크가 아닌 자리로 그린다 — /stocks/null 로 보내면 눌러서 404 를 만난다. */
function Who({ p }: { p: MyPrediction }) {
  return (
    <div className="mp-who">
      <span className="mp-logo" aria-hidden="true">{monogram(p)}</span>
      {p.stockCode === null ? (
        <p className="mp-stock is-gone"><b>삭제된 종목</b></p>
      ) : (
        <Link className="mp-stock" to={`/stocks/${p.stockCode}`}>
          <b>{p.stockName ?? p.stockCode}</b>
          {p.stockName && <span className="num">{p.stockCode}</span>}
        </Link>
      )}
    </div>
  )
}

export default function PredictList() {
  const [stage, setStage] = useState<Stage>('ALL')
  const [sort, setSort] = useState<Sort>('LATEST')

  /* filter 를 붙이지 않은 전량을 받는다. 단계는 화면이 가르므로 서버 거르개가
     필요 없고, 칸을 바꿔도 다시 부르지 않는다.

     **useMemo 로 굳혀야 한다.** 매 렌더 새 함수를 넘기면 훅의 load 가 매번
     새로 만들어져 effect 가 끝없이 다시 읽는다 — 실제로 그랬다(3초에 247회).
     Stocks.tsx 가 같은 이유로 같은 처리를 해 두었다. */
  const fetcher = useMemo(() => fetchMyPredictions('ALL'), [])
  const list = useCursorList<MyPrediction, MyPredictionMeta>(fetcher)

  /* 커서 끝까지 이어서 받는다. 세 칸의 건수가 정확하려면 전량이 있어야 한다.
     loadMore 가 loading·hasNext 를 스스로 막으므로 여기서 겹쳐 부를 일은 없다. */
  const capped = list.items.length >= LOAD_CAP
  useEffect(() => {
    if (list.hasNext && !list.loading && !list.error && !capped) list.loadMore()
  }, [list, capped])

  /* 다 받기 전에는 목록을 그리지 않는다. 반쯤 받은 상태로 칸을 그리면 건수가
     늘어나는 것이 보이고, 그 숫자를 믿을 수 없게 된다. */
  const settling = list.loading || (list.hasNext && !capped)

  const groups = useMemo(() => {
    const g: Record<Exclude<Stage, 'ALL'>, MyPrediction[]> = { ANCHOR: [], OPEN: [], DONE: [] }
    for (const p of list.items) g[stageOf(p)].push(p)
    return g
  }, [list.items])

  const count: Record<Stage, number> = {
    ALL: list.items.length,
    ANCHOR: groups.ANCHOR.length,
    OPEN: groups.OPEN.length,
    DONE: groups.DONE.length,
  }

  const shown = useMemo(() => {
    const base = stage === 'ALL' ? list.items : groups[stage]
    /* 최신순은 서버가 준 순서 그대로다 — 등록 시각이 응답에 없어 화면에서
       다시 세울 수 없고, 세울 이유도 없다. 만기순만 화면에서 정렬한다. */
    if (sort === 'LATEST') return base
    return [...base].sort((a, b) => {
      /* 만기일이 아직 없는 건(기준가 대기)은 끝으로 민다 — 0 으로 치면
         가장 임박한 것처럼 맨 위로 올라온다 */
      if (a.dday === null) return b.dday === null ? 0 : 1
      if (b.dday === null) return -1
      return a.dday - b.dday
    })
  }, [stage, sort, list.items, groups])

  /* 정렬이 끝난 뒤에 종목으로 묶는다. 묶음의 자리는 **그 종목이 처음 나온
     자리**라서, 최신순이든 만기순이든 정렬이 뒤집히지 않는다 — 만기순이면
     가장 임박한 건이 그 종목을 위로 끌어올리고, 나머지는 그 아래 붙는다.

     종목이 지워진 예측(stockCode === null)은 서로 다른 종목일 수 있으므로
     묶지 않는다. 코드가 없어 같은 종목이라고 말할 근거가 없다. */
  const cards = useMemo(() => {
    const out: MyPrediction[][] = []
    const at = new Map<string, MyPrediction[]>()
    for (const p of shown) {
      if (p.stockCode === null) { out.push([p]); continue }
      const bucket = at.get(p.stockCode)
      if (bucket) bucket.push(p)
      else { const fresh = [p]; at.set(p.stockCode, fresh); out.push(fresh) }
    }
    return out
  }, [shown])

  const judged = list.meta?.judgedCount ?? null
  const hitRate = list.meta?.hitRate ?? null

  return (
    <main className="main">
      <div className="main-inner">
        <header className="mp-head">
          <div>
            <h1>내 예측 현황</h1>
            <p>등록한 예측이 지금 어디까지 왔는지 한눈에 확인해보세요.</p>
          </div>
          {/* 적중률은 판정이 끝난 건에 대한 값이다. 판정 건이 없으면 아예 두지 않는다 —
              0% 로 그리면 다 틀린 것처럼 보인다 */}
          {hitRate !== null && (
            <p className="mp-hit">
              <b className="num">{`${hitRatePercent(hitRate)}%`}</b>
              <span>{`판정 ${judged}건 적중률`}</span>
            </p>
          )}
        </header>

        {/* 세 걸음 안내. 이 화면에 처음 온 사람은 "왜 아직 블록에 없지" 를 묻는데,
            그 답(자정 배치)이 카드 안쪽에만 있으면 목록이 빈 사람은 못 읽는다. */}
        <ol className="mp-flow">
          {STEPS.map((s, i) => (
            <li key={s.title}>
              <span className="mp-flow-ic" aria-hidden="true"><Icon size={19}>{s.icon}</Icon></span>
              <div>
                <b>{`${i + 1}. ${s.title}`}</b>
                <span>{s.body}</span>
              </div>
            </li>
          ))}
        </ol>

        <div className="mp-toolbar">
          <div className="mp-filters" role="group" aria-label="단계 필터">
            {STAGES.map((s) => (
              <button
                key={s}
                type="button"
                className={s === stage ? 'is-on' : undefined}
                aria-pressed={s === stage}
                onClick={() => setStage(s)}
              >
                {STAGE_LABEL[s]}
                {/* 상한에 걸려 다 못 받았으면 건수를 적지 않는다 — 다 못 센
                    숫자를 적으면 거짓말이 된다 */}
                {!settling && !capped && <span className="mp-n num">{count[s]}</span>}
              </button>
            ))}
          </div>

          <label className="mp-sort">
            <span className="sr-only">정렬</span>
            <select value={sort} onChange={(e) => setSort(e.target.value as Sort)}>
              {SORTS.map((s) => <option key={s} value={s}>{SORT_LABEL[s]}</option>)}
            </select>
            <Icon size={14}><path d="m6 9 6 6 6-6" /></Icon>
          </label>
        </div>

        {list.error ? (
          <ErrorState error={list.error} onRetry={list.reload} />
        ) : settling ? (
          <div className="mp-skel" aria-hidden="true">
            {Array.from({ length: 4 }, (_, i) => <span key={i} />)}
          </div>
        ) : shown.length === 0 ? (
          <EmptyState
            title={EMPTY[stage].title}
            hint={EMPTY[stage].hint}
            action={{ label: '종목 탐색으로', to: '/stocks' }}
          />
        ) : (
          <>
            <ul className="mp-list">
              {cards.map((items) => <StockCard key={items[0].id} items={items} />)}
            </ul>

            {/* 상한에 걸렸을 때만 나온다. 그 전에는 화면이 알아서 다 받는다 */}
            {capped && list.hasNext && (
              <button type="button" className="mp-load" disabled={list.loading} onClick={list.loadMore}>
                {list.loading ? '불러오는 중…' : '더 보기'}
              </button>
            )}
          </>
        )}
      </div>
    </main>
  )
}
