/* B-03 두 번째 안쪽 페이지 — C-01 예측 등록.

   설계서는 C-01 을 /predict?code= 라는 별도 라우트로 두지만, 화면은 종목 상세 안에서
   탭으로 전환된다. 종목을 보다가 바로 예측하는 흐름이라 페이지를 갈아 끼우면
   방금 읽던 근거가 사라지기 때문이다. 라우트는 그대로 살아 있고(딥링크·뒤로가기),
   그쪽으로 들어와도 이 탭이 열린다.

   설계 제약(§4 C-01)
   - direction 은 UP/DOWN 둘뿐이고 horizon 은 **3~90 캘린더일**이다(결정 B5, 구간은 2026-09-14 확장).
     임의 마감일이 없다. 화면설계서는 아직 5·10·20·60 거래일로 적혀 있는데, DB CHECK·ERD·
     API 명세 v0.38 이 모두 앞의 값이라 그쪽이 낡았다.
   - targetPrice 는 등록 후 불변이다. 그래서 등록 전에 "수정·삭제 불가" 를 반드시 고지한다.
   - note 는 **필수**이고 5000자 이하이며 구독자 전용이다(서버 @NotBlank).
     커밋 문자열에는 noteHash 만 들어간다.
   - evidencePointIds 는 B-03 투자 포인트에서 인계받은 것만 쓴다. 리포트·재무지표는 근거가 못 된다.
   - 기준가는 배치 B2 가 기준일 종가로 확정한다. 등록 직후 basePrice 가 비어 있고,
     빈 값을 0 으로 그리지 않는다.
   - 응답: 201 슬롯 내 · 401 서명 불일치 · 409 오늘 슬롯 소진.
     202 소각 경로는 소각할 토큰(ANT-CHAIN-03)이 아직 없어 서버에 없다(명세 v0.41).

   왼쪽 패널 (설계 변경 2026-09-10)
   - 개별 예측 목록이 아니라 **구간별 인원(호가창)** 이다. 누가 걸었는지는 이 화면에서
     보여주지 않고, 개인은 작성자 채널(E-02)에서만 본다. PredictionDepth 참고.

   커밋 규격이 정해진 뒤 바뀐 것 (ANT-PRED-02)
   - commitHash 를 **이 화면이 직접 계산한다.** 커밋 salt 가 없어지고(09-09) 유일한 난수인
     noteSalt 를 클라이언트가 만들게 되면서, 미리보기가 원장에 남을 값과 같은 해시를 보여 줄
     수 있게 됐다. 규격·이유는 api/predictions.ts "커밋 봉인" 절.
   - 서명 대상이 지갑 연동용 문자열이 아니라 예측 내용이다(결정 B3). 연동용에 서명하면
     서버가 SIGNER_MISMATCH 로 거절한다. */
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Panel } from './Block'
import PredictionDepth from './PredictionDepth'
import PointPicker from './PointPicker'
import { useBlock } from '../../api/useBlock'
import WalletLinkModal from '../wallet/WalletLinkModal'
import ConfirmDialog from '../ConfirmDialog'
import OperationModal from '../operation/OperationModal'
import { formatToken } from '../../api/wallet'
import CommitProgressModal, { type CommitPhase } from '../prediction/CommitProgressModal'
import SlideToSign from '../prediction/SlideToSign'
import EventBet, { EventBanner, betAmount, BET_MIN, BET_MAX } from './EventBet'
import { CONTEST, CONTEST_HORIZON, CONTEST_PATH, dayStamp, stamp } from '../../event/contest'
import ErrorState from '../state/ErrorState'
import { ApiError } from '../../api/errors'
import { POINT_KINDS, POINT_LABEL, getPoints } from '../../api/stockDetail'
import type { InvestPoint, PointKind, StockSummary } from '../../api/stockDetail'
import {
  DIRECTIONS, HORIZON_MAX, HORIZON_MIN, HORIZON_TICKS, NOTE_MAX,
  commitHash, commitPayload, createPrediction, getSlots, horizonLabel, newNoteSalt, noteHash,
  predictionSigningPayload, targetPriceInScale,
} from '../../api/predictions'
import type {
  CommitFields, CreateResult, Direction, Horizon, PredictionDraft,
} from '../../api/predictions'
import { requestNonce } from '../../api/wallet'
import type { PredictForm } from './predictForm'
import { connectAddress, hasWallet, personalSign } from '../../wallet/provider'
import { useAuth } from '../../auth/context'

const DIRECTION_LABEL: Record<Direction, string> = { UP: '오른다', DOWN: '내린다' }

/** 남은 글자를 알리기 시작하는 지점. 이만큼 남았을 때부터 보인다 */
const NOTE_WARN_LEFT = 500

/* 등락률 고르개에 놓을 값. 왼쪽 분포표가 5% 폭이라 같은 눈금을 쓴다 — 두 곳의
   눈금이 다르면 "+10% 에 열네 명" 을 보고 +10% 를 골랐는데 다른 칸에 떨어진다. */
const PCT_CHOICES = [20, 15, 10, 5, -5, -10, -15, -20] as const

/* 커밋 문자열과 서명 문자열이 같은 다섯 값을 쓴다. 한 자리에서 꺼내야 둘이 갈리지 않는다.
   draft 를 그대로 펼치지 않는 이유 — note·noteSalt·evidencePointIds 는 등록 본문에만
   들어가고 해시 대상에는 없다. 무엇이 봉인되는지 이 함수가 그대로 보여 준다. */
const commitFields = (d: PredictionDraft, hash: string): CommitFields => ({
  stockCode: d.stockCode,
  direction: d.direction,
  targetPrice: d.targetPrice,
  horizon: d.horizon,
  noteHash: hash,
})

/* 기간 버튼에 붙일 만기일.

   horizon 은 **캘린더일**이다(결정 B5). 서버가 만기일 = 기준일 + horizon 일로 잡고,
   기준일은 등록 시점의 "다음 평일" 이다. 휴장일을 세지 않으므로 클라이언트가 서버와
   똑같이 계산할 수 있다 — 예전처럼 어긋나지 않는다. */
function dueLabel(horizon: Horizon) {
  const d = new Date()
  // 기준일 = 다음 평일. 토·일에 등록하면 월요일이 기준일이다
  do { d.setDate(d.getDate() + 1) } while (d.getDay() === 0 || d.getDay() === 6)
  d.setDate(d.getDate() + horizon)
  return `${d.getMonth() + 1}.${d.getDate()}`
}

type Props = {
  code: string
  summary: StockSummary | null
  /** InfoTab 에서 고른 근거 포인트. 이 인계에는 API 가 없다 */
  picked: number[]
  onPick: (id: number) => void
  onGoInfo: () => void
  form: PredictForm
  onForm: (next: (prev: PredictForm) => PredictForm) => void
  /**
   * 대회(J-01)로 들어왔는가. 참일 때만 배너와 배팅 카드가 붙는다 — 회차 id·등록
   * 창·대회 종목 셋을 부모가 이미 확인했다(StockDetail). 평소 경로로 온 사람에게는
   * 이 화면에 배팅이라는 말 자체가 없다.
   */
  eventMode?: boolean
}

export default function PredictTab({
  code, summary, picked, onPick, onGoInfo, form, onForm, eventMode = false,
}: Props) {
  const { user, setWalletLinked } = useAuth()

  const slots = useBlock(() => getSlots(), [])
  const points = useBlock(() => getPoints(code), [code])

  /* 부모가 들고 있는 값을 그대로 쓴다. 호출부는 지역 state 때와 똑같이
     setDirection(d) 처럼 값만 넘기면 된다. */
  const { direction, target, horizon, note } = form
  const setTarget = (v: string) => onForm((f) => ({ ...f, target: v }))
  const setHorizon = (v: Horizon) => onForm((f) => ({ ...f, horizon: v }))

  /* 대회에서는 기간을 고를 수 없다. **화면만 고정으로 그리면 보낼 값이 빈 채로
     남아** 등록 버튼이 영영 잠긴다 — 상태도 같이 못 박는다.
     대회로 들어왔다가 평소 경로로 나가면 사람이 고른 값이 없으므로 그대로 둔다.
     사람이 고른 값을 지우는 쪽이 더 놀랍다. */
  useEffect(() => {
    if (eventMode && horizon !== CONTEST_HORIZON) setHorizon(CONTEST_HORIZON)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eventMode, horizon])
  const setNote = (v: string) => onForm((f) => ({ ...f, note: v }))
  const setBet = (v: string) => onForm((f) => ({ ...f, bet: v }))
  /* 사람이 직접 고른 방향. 이때부터 목표가가 방향을 바꾸지 않는다 */
  const setDirection = (v: Direction) =>
    onForm((f) => ({ ...f, direction: v, directionTouched: true }))

  /* ③의 투자 포인트 격자를 펼쳤는가. **기본은 접힘** — 포인트가 많은 종목이면 격자가
     폼을 반으로 갈라 ④ 내 판단이 화면 밖으로 밀린다.
     한 번 펼치면 종목을 옮겨도 펼친 채로 둔다. 근거를 고르며 종목을 견주는 사람에게
     매번 다시 누르게 할 이유가 없다 — 탭 이동으로 언마운트되지도 않는다. */
  const [pickerOpen, setPickerOpen] = useState(false)

  const [linking, setLinking] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  /* 슬롯을 넘긴 등록은 서명 전에 한 번 묻는다 — 서명 문자열에는 금액이 없어(명세 "무료·소각 공통")
     이 확인 창이 곧 소각 동의다. 확인하면 sign() 으로 이어진다. */
  const [confirmingBurn, setConfirmingBurn] = useState(false)
  /* 202 로 접수된 소각 작업. M-02 가 폴링하고 확정되면 예측 상세로 보낸다 */
  const [burnOp, setBurnOp] = useState<string | null>(null)
  const [failure, setFailure] = useState<ApiError | null>(null)
  const [result, setResult] = useState<CreateResult | null>(null)
  /* 지갑 승인 뒤 서버 응답까지 빈 시간이 있다. 그 사이 화면이 그대로면 승인이
     먹혔는지 알 수 없고, 응답이 오는 순간 완료 화면이 튀어나온다.
     null 이면 모달을 띄우지 않는다 — 아직 서명 흐름에 들어가지 않은 상태다. */
  const [phase, setPhase] = useState<CommitPhase | null>(null)
  /** 지갑 창을 닫거나 거부했을 때처럼 서버까지 못 간 실패 */
  const [localMsg, setLocalMsg] = useState<string | null>(null)

  const targetPrice = Number(target.replace(/[^0-9.]/g, ''))
  const priceEntered = target !== '' && Number.isFinite(targetPrice) && targetPrice > 0
  /* 소수 셋째 자리 이하는 서버가 반올림하지 않고 400 으로 거절한다(numeric(14,2)).
     여기서 조용히 잘라 서명하면 서명한 값과 서버가 해시한 값이 달라진다. */
  const priceTooPrecise = priceEntered && !targetPriceInScale(targetPrice)
  const validTarget = priceEntered && !priceTooPrecise

  /* 목표가가 전일 종가 대비 몇 %인지. 방향과 어긋나면 미리 알려 준다 —
     "오른다" 를 고르고 종가보다 낮은 목표가를 넣는 실수가 잦다.

     자릿수가 넘쳐 등록이 막힌 값(priceTooPrecise)에도 비율은 보여 준다 — 비율이
     깜빡이며 사라지면 자릿수 문제인지 입력이 지워진 건지 알 수 없다. */
  const gap = useMemo(() => {
    if (!priceEntered || !summary?.prevClose) return null
    return ((targetPrice - summary.prevClose) / summary.prevClose) * 100
  }, [priceEntered, targetPrice, summary?.prevClose])

  /* 방향을 고르지 않고 목표가부터 적는 사람이 있다. 전일 종가보다 높으면 상승,
     낮으면 하락으로 미리 세워 준다.

     직접 고르기 전까지는 계속 따라간다 — 한 번만 정하면 "9" 까지 친 순간의
     값으로 하락이 박히고, "90000" 을 마저 쳐도 그대로 남는다.
     같은 값(gap 0)이면 방향을 정할 수 없으므로 비운다.
     directionTouched 가 서면 이 효과는 더 이상 손대지 않는다. */
  useEffect(() => {
    if (form.directionTouched) return
    const next: Direction | null = gap === null || gap === 0 ? null : gap > 0 ? 'UP' : 'DOWN'
    onForm((f) => (f.direction === next ? f : { ...f, direction: next }))
  }, [form.directionTouched, gap, onForm])

  const directionMismatch =
    direction !== null && gap !== null &&
    ((direction === 'UP' && gap < 0) || (direction === 'DOWN' && gap > 0))

  /* 근거(note)도 필수다 — 서버가 @NotBlank 로 받아 비우면 400 이다(명세: note *).
     커밋의 noteHash 가 봉인의 핵심이라, 근거 없는 예측은 규격상 만들 수 없다. */
  const draft: PredictionDraft | null = useMemo(() => {
    if (!direction || !horizon || !validTarget || !note.trim()) return null
    return {
      stockCode: code, direction, targetPrice, horizon,
      note, noteSalt: form.noteSalt, evidencePointIds: picked,
    }
  }, [code, direction, horizon, validTarget, targetPrice, note, form.noteSalt, picked])

  /* 못 채운 항목을 발판 위에 나열하던 줄은 뺐다(2026-09-10). 단계마다 "필수" 를
     붙였으므로 같은 말이 두 곳에 있었고, 발판 바로 위에서 문구가 나타났다 사라지며
     그때마다 발판이 아래위로 움직였다. */

  /* 등락률 고르개. 목표가를 직접 치는 사람도 있어 기본은 닫혀 있다 */
  const [pctOpen, setPctOpen] = useState(false)


  /* 고른 비율로 목표가를 채운다. 원 단위로 반올림한다 — 소수 셋째 자리가 남으면
     서버가 400 으로 거절하고(numeric(14,2)), 호가 단위가 아닌 값도 사람이 읽기 어렵다. */
  const applyPct = (pct: number) => {
    const bases = summary?.prevClose
    if (!bases) return
    setTarget(String(Math.round(bases * (1 + pct / 100))))
    /* 비율로 골랐으면 방향도 그 비율이 정한다. 직접 고른 방향이 있어도 덮는다 —
       +5% 를 누른 순간 사용자의 뜻은 상승이다. */
    onForm((f) => ({ ...f, direction: pct > 0 ? 'UP' : 'DOWN', directionTouched: true }))
    setPctOpen(false)
  }

  /* 미리보기 세 값(noteHash → 커밋 문자열 → commitHash)은 비동기라 상태로 들고 있는다.
     ethers 를 동적 import 하기 때문인데, 그 대신 이 화면에 들어오기 전에는 받지 않는다.

     무엇으로 만든 값인지 함께 들고 있는 이유 — 입력을 계속 고치면 계산이 끝나기 전에
     다음 글자가 들어온다. 값만 저장하면 그 사이에 옛 입력의 해시가 지금 입력의 것처럼
     보이고, 그건 "무엇에 서명하는지" 를 틀리게 보여 주는 것이다. 지금 입력과 짝이 맞을
     때만 값으로 인정한다. */
  /* 여섯 값을 JSON 으로 잇는다 — 구분자를 문자 하나로 두면 근거 본문에 그 문자가
     들어갔을 때 서로 다른 입력이 같은 열쇠가 된다. */
  const previewKey = draft && JSON.stringify(
    [draft.stockCode, draft.direction, draft.targetPrice, draft.horizon, draft.note, draft.noteSalt],
  )

  const [preview, setPreview] = useState<
    { key: string; noteHash: string; payload: string; commitHash: string } | null
  >(null)

  useEffect(() => {
    if (!draft || !previewKey) return
    let alive = true
    void (async () => {
      const nh = await noteHash(draft.note, draft.noteSalt)
      const payload = commitPayload(commitFields(draft, nh))
      const ch = await commitHash(payload)
      if (alive) setPreview({ key: previewKey, noteHash: nh, payload, commitHash: ch })
    })()
    return () => { alive = false }
  }, [draft, previewKey])

  const shown = preview?.key === previewKey ? preview : null

  /* 서버가 열 셋으로 나눠 주므로 한 자루에 담아 id 로 찾는다.
     어느 열에서 왔는지는 배지로 보여야 해서 kind 를 함께 기억한다. */
  const pointById = useMemo(() => {
    const map = new Map<number, InvestPoint & { kind: PointKind }>()
    for (const kind of POINT_KINDS) {
      for (const p of points.data?.[kind] ?? []) map.set(p.id, { ...p, kind })
    }
    return map
  }, [points.data])

  const remaining = slots.data?.remaining ?? 0
  const overSlot = slots.data ? remaining <= 0 : false
  /** 슬롯을 넘겼을 때 1건에 태우는 ANT. 서버 금액표에서 온다(slots.overCost) */
  const overCost = slots.data ? formatToken(slots.data.overCost) : null

  /* 이벤트 배팅. 이벤트가 아니면 폼에 값이 있어도 없는 셈 친다 — 배너로 들어왔다가
     주소에서 event 를 지우고 새로고침하면 화면에 보이지도 않는 금액이 걸린 채
     서명 발판의 문구만 "예측하고 배팅하기" 로 남는다. */
  const bet = eventMode ? betAmount(form.bet) : 0
  /* 한도를 벗어나면 등록을 막는다. 카드가 경고를 띄우고 있는데 발판이 열려 있으면
     걸린 줄 알고 서명한다 — 배팅은 취소할 수 없는 쪽에 붙는 결정이다.
     컨트랙트도 StakeOutOfRange 로 되돌리지만, 거기까지 가면 서명은 이미 끝난 뒤다. */
  const betOutOfRange = bet > 0 && (bet < BET_MIN || bet > BET_MAX)

  async function submit() {
    if (!draft) return
    setFailure(null)
    setLocalMsg(null)

    /* 지갑이 준비되지 않았으면 M-01 을 띄운다. 모달이 성공을 올려 주면 이 함수를
       다시 부른다 — 사용자가 같은 버튼을 두 번 누르지 않게 한다.

       미설치와 미연동을 여기서 가르지 않는다. **M-01 이 이미 네 갈래(미설치 ·
       서명 거부 · 주소 불일치 · 이미 연동됨)를 각각 다른 안내로 다룬다.**
       여기서 미설치만 따로 걸러 문구를 쓰면 같은 안내가 두 곳에 생기고, 그때는
       모달을 아예 열지 않아 설치 안내로 이어지지도 않는다.
       티켓도 "지갑이 연동되지 않은 상태로 진입하면 M-01 을 먼저 띄운다" 다. */
    if (!hasWallet() || !user?.walletLinked) {
      setLinking(true)
      return
    }

    /* 슬롯을 넘겼으면 소각 동의를 먼저 받는다. 서명 창까지 간 뒤에 금액을 알게 하지 않는다 */
    if (overSlot && !confirmingBurn) {
      setConfirmingBurn(true)
      return
    }
    setConfirmingBurn(false)

    setSubmitting(true)
    setPhase('signing')
    try {
      /* 미리보기 값을 쓰지 않고 여기서 다시 계산한다. 미리보기는 화면에 보이는 것이고,
         서명에 들어갈 값은 지금 이 draft 에서 나와야 한다 — 계산이 끝나기 전에 눌렀을
         때 옛 입력에 서명하는 길을 아예 만들지 않는다. */
      const hash = await noteHash(draft.note, draft.noteSalt)

      const address = await connectAddress()
      /* scope 는 PREDICTION 이다(결정 B4). 이 값이 서버 nonce 칸 sig:nonce:{userId}:prediction
         을 정하고, PredictionCreateRequest.scope() 도 같은 값을 돌려준다. 소각(PREDICTION_BURN)
         은 슬롯을 넘겼을 때 M-02 가 쓰는 다른 칸이라 여기서 쓰면 서버가 못 찾는다. */
      const nonce = await requestNonce('PREDICTION')
      const payload = predictionSigningPayload(commitFields(draft, hash), nonce)
      const signature = await personalSign(payload, address)
      // 지갑 승인이 끝났다. 여기서부터가 사용자가 기다리는 구간이다
      setPhase('committing')
      /* 배팅 금액은 아직 싣지 않는다. POST /predictions 에 betAmount 가 없어
         지금 얹으면 400 이고, 커밋·서명 문자열에는 애초에 들어가지 않는다
         (EventBet.tsx 머리말). 서버가 칸을 열면 draft 옆에 나란히 넘긴다. */
      const created = await createPrediction(draft, signature)
      setResult(created)
      if (created.kind === 'queued') {
        /* 소각으로 접수됐다(202). 커밋 진행 창 대신 M-02 로 넘긴다 — 여기서 기다리는 것은
           앵커가 아니라 소각 tx 확정이고, 그 폴링·문구·실패 처리는 M-02 가 이미 갖고 있다 */
        setPhase(null)
        setBurnOp(created.data.operationId)
      } else {
        setPhase('settled')
      }
      /* 다음 예측은 새 난수로 봉인한다. 같은 noteSalt 를 두 번 쓰면 같은 근거가 같은
         noteHash 로 남아, 원장만 보고도 "같은 말을 두 번 했다" 를 알 수 있다. */
      onForm((f) => ({ ...f, noteSalt: newNoteSalt() }))
    } catch (e) {
      /* 실패하면 모달을 걷는다. 오류는 폼 쪽에서 보여 준다 — 진행 모달에
         오류까지 담으면 "무엇이 어디까지 갔나" 와 "무엇이 잘못됐나" 가 한 창에
         섞인다. */
      setPhase(null)
      if (e instanceof ApiError) setFailure(e)
      else if (e && typeof e === 'object' && 'code' in e) {
        setFailure(new ApiError({
          code: String((e as { code: unknown }).code), message: '',
        }, Number((e as { status?: number }).status ?? 0)))
      } else setLocalMsg(e instanceof Error ? e.message : '서명을 마치지 못했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  /* 진행 모달. 완료 화면과 입력 화면 두 갈래가 같은 것을 띄우므로 한 번만
     만들어 둔다. 닫으면 뒤에 있는 완료 화면이 그대로 남는다 — 커밋 해시와
     다음 걸음이 거기 적혀 있어 잃을 것이 없다. */
  const progress = phase && (
    <CommitProgressModal phase={phase} result={result} onClose={() => setPhase(null)} />
  )

  /* ── 등록 완료 ────────────────────────────────────────── */
  if (result) {
    return (
      <div className="sd-grid">
        {progress}
        <section className="sd-block sd-done" data-span="12">
          <span className="sd-done-mark" aria-hidden="true">
            <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                 strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 12.5 9.5 18 20 6.5" />
            </svg>
          </span>

          {result.kind === 'created' ? (
            <>
              <h2>예측을 등록했습니다</h2>
              {/* 기준가는 다음 영업일 종가로 배치가 채운다. 아직 없는 값을
                  0 으로 그리지 않고, 언제 정해지는지 말로 알린다 */}
              <p className="sd-done-sub">
                기준가는 다음 영업일 종가로 확정됩니다. 그때까지 상태는 <b>BASE</b> 입니다.
              </p>
              <dl className="sd-commit">
                <div><dt>커밋 해시</dt><dd className="num">{result.data.commitHash}</dd></div>
              </dl>
              <p className="sd-done-cta">
                <Link className="sd-cta" to="/me/predictions">내 예측에서 보기</Link>
              </p>
            </>
          ) : (
            <>
              {/* 슬롯을 넘겨 소각으로 접수했다(202). 예측은 이미 저장됐고 소각 tx 확정만 남았다 */}
              <h2>예측을 등록했습니다</h2>
              <p className="sd-done-sub">
                무료 슬롯을 넘겨 {overCost ?? ''} ANT 소각으로 등록됩니다. 소각이 체인에서 확정되면 지갑 내역에 남습니다.
              </p>
              <dl className="sd-commit">
                <div><dt>작업 번호</dt><dd className="num">{result.data.operationId}</dd></div>
              </dl>
              <p className="sd-done-cta">
                <Link className="sd-cta" to={`/predictions/${result.data.predictionId}`}>예측 상세 보기</Link>
              </p>
            </>
          )}
        </section>
      </div>
    )
  }

  /* ── 입력 ──────────────────────────────────────────────
     왼쪽은 남의 예측, 오른쪽은 내 등록. 종목을 볼 때 "다들 어떻게 봤나" 를
     먼저 확인하고 내 판단을 적는 흐름이라 둘을 나란히 둔다. */
  return (
    <div className="sd-grid">
      {/* 대회 배너는 **격자를 가로질러 맨 위**에 선다. 한 열 안에 두면 폭이
          절반이라 글이 두 줄로 접히고, 오른쪽 등록 카드만 길어 보인다.
          대회로 들어오면 머리의 "오늘 판정" 자리를 접어(StockDetail) 위가 빈다. */}
      {eventMode && <EventBanner name={CONTEST.name} closesAt={stamp(CONTEST.closesAt)} />}

      <div className="sd-col" data-span="6">
        <PredictionDepth code={code} />
      </div>

      <Panel
        title="예측 등록하기"
        note={slots.data ? `오늘 ${remaining}/${slots.data.freeLimit}회` : undefined}
        span={6}
      >
        {overSlot && (
          /* 무료 슬롯을 넘기면 등록마다 ANT 를 태운다(ANT-PRED-10). 발판 라벨에도 같은 금액을
             적고, 밀면 서명 전에 확인 창이 한 번 더 묻는다 */
          <p className="sd-slot-warn">
            오늘 무료 슬롯을 모두 썼습니다. 지금부터는 <b>1건당 {overCost} ANT</b> 를 소각하고 등록합니다.
            무료 슬롯은 내일 다시 채워집니다.
          </p>
        )}

        <ol className="pf-steps">
          {/* ① 목표가 — 방향과 한 걸음으로 합쳤다(2026-09-10).
              방향은 목표가가 전일 종가의 어느 쪽인지로 정해진다. 따로 묻던 시절에는
              "오른다" 를 고르고 낮은 목표가를 넣는 모순이 잦았다. 버튼은 남겨 둔다 —
              값을 넣기 전에 어느 쪽을 보는지 먼저 정하는 사람이 있다. */}
          <li className="pf-step">
            <h3><i className="pf-no">1</i>목표가 <span className="pf-req">필수</span></h3>
            <div className="pf-price-row">
              <div className="sd-target">
                {/* 등락률로 목표가를 채운다. 전일 종가가 없으면(거래정지) 계산할 수
                    없으므로 잠근다 — 누르면 아무 일도 안 나는 버튼을 두지 않는다. */}
                <button
                  type="button"
                  className={`pf-pct-btn${pctOpen ? ' is-open' : ''}`}
                  disabled={!summary?.prevClose}
                  aria-expanded={pctOpen}
                  aria-haspopup="true"
                  onClick={() => setPctOpen((v) => !v)}
                >
                  <b className={gap === null ? '' : `num ${gap >= 0 ? 'up' : 'down'}`}>
                    {gap === null ? '등락률(%)' : `${gap > 0 ? '+' : ''}${gap.toFixed(2)}%`}
                  </b>
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                       strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M6 9l6 6 6-6" />
                  </svg>
                </button>

                <input
                  type="text"
                  inputMode="numeric"
                  className="num"
                  value={target}
                  placeholder={summary?.prevClose ? String(summary.prevClose) : '0'}
                  aria-label="목표가"
                  aria-describedby="pf-target-help"
                  onChange={(e) => setTarget(e.target.value)}
                />
                <span className="sd-unit">원</span>

                {pctOpen && (
                  <div className="pf-pct-menu" role="menu" aria-label="등락률로 목표가 채우기">
                    {PCT_CHOICES.map((p) => (
                      <button
                        key={p}
                        type="button"
                        role="menuitem"
                        className={`pf-pct-item is-${p > 0 ? 'up' : 'down'}`}
                        onClick={() => applyPct(p)}
                      >
                        {`${p > 0 ? '+' : ''}${p}%`}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* 방향은 가격 **오른쪽**이다. 목표가를 넣으면 이쪽이 저절로 정해지므로,
                  읽는 순서(값 → 결과)와 놓인 순서가 같아야 한다. */}
              <div className="pf-dirs">
                {DIRECTIONS.map((d) => (
                  <button
                    key={d}
                    type="button"
                    className={`pf-dir is-${d.toLowerCase()} ${direction === d ? 'is-on' : ''}`}
                    aria-pressed={direction === d}
                    onClick={() => setDirection(d)}
                  >
                    {d === 'UP' ? '상승' : '하락'}
                  </button>
                ))}
              </div>
            </div>
            {/* 실전 시세는 전일 종가뿐이다 — "현재가" 라는 말을 만들지 않는다(§7 legal) */}
            {/* 비율은 등락률 버튼이 이미 보여 준다(`+10.00%`). 여기에 또 적으면 같은
                값이 한 줄 안에 두 번 나온다. 이 줄이 할 일은 0% 가 무엇인지 밝히는 것뿐. */}
            <p id="pf-target-help" className="sd-help">
              {summary?.prevClose
                ? <>전일 종가 기준 <b className="num">{summary.prevClose.toLocaleString('ko-KR')}원</b></>
                : '이 종목은 전일 종가가 없어 대비 비율을 계산할 수 없습니다.'}
            </p>
            {/* 자릿수는 방향 어긋남보다 먼저 알린다 — 이건 고치지 않으면 등록 자체가 막힌다 */}
            {priceTooPrecise && (
              <p className="sd-warn">
                목표가는 <b>소수 둘째 자리까지</b> 입력할 수 있습니다. 봉인되는 값이라 서버가
                임의로 반올림하지 않습니다.
              </p>
            )}
            {directionMismatch && (
              <p className="sd-warn">
                {`방향은 "${DIRECTION_LABEL[direction]}" 인데 목표가가 반대쪽입니다. 다시 확인해 주세요.`}
              </p>
            )}
          </li>

          {/* ② 기간 — 3~90 일 연속 구간이다(2026-09-14). 눈금 네 개 고정이던 때는 range 를
              **눈금 번호**로 썼는데, 이제 값 자체를 싣는다. 눈금은 3·30·60·90 참조선으로만
              남긴다 — 화살표키 한 칸이 1일이라 손잡이가 눈금에 붙지 않는다.
              처음 값은 3일이다(2026-09-17). "안 고름" 상태를 두면 손잡이는 3일 자리에
              서 있는데 값은 null 이라, 3일로 끌어도 DOM 값이 그대로여서 change 가 나지
              않았다 — 3일만 고를 수 없는 칸이었다.
              native range 를 쓰는 이유는 접근성이다. 직접 만든 끌개는 키보드·스크린리더가
              못 쓰는데, range 는 화살표키·Home/End 가 그냥 된다. */}
          <li className="pf-step">
            <h3>
              <i className="pf-no">2</i>예측 기간{' '}
              {eventMode
                ? <span className="pf-fixed">고정</span>
                : <span className="pf-req">필수</span>}
            </h3>

            {/* 대회는 모두가 같은 날 판정된다. 기간이 사람마다 다르면 줄을 세울 수
                없으므로 고르는 자리를 아예 없앤다 — 끌개를 두고 못 움직이게만 하면
                왜 안 되는지 알 수 없어 몇 번 더 당겨 보게 된다.

                **만기는 계산하지 않는다.** dueLabel 은 "오늘의 다음 평일 + 기간" 으로
                잡는데 대회 만기는 정해진 날짜다. 계산에 맡기면 등록한 날에 따라
                사람마다 다른 날이 찍힌다 — 9/21 이어야 할 것이 9/20 으로 나왔었다. */}
            {eventMode ? (
              <p className="pf-fixed-line">
                <b className="num">{horizonLabel(CONTEST_HORIZON)}</b>
                <span>만기 <b>{dayStamp(CONTEST.targetDate)}</b> 종가</span>
              </p>
            ) : (
            <div className="pf-line">
              <input
                type="range"
                className="pf-line-input"
                min={HORIZON_MIN}
                max={HORIZON_MAX}
                step={1}
                value={horizon}
                aria-label="예측 기간"
                aria-valuetext={`${horizonLabel(horizon)} · ${dueLabel(horizon)} 만기`}
                onChange={(e) => setHorizon(Number(e.target.value))}
              />
              <div className="pf-line-ticks" aria-hidden="true">
                {HORIZON_TICKS.map((h) => (
                  <span
                    key={h}
                    className={`pf-tick${horizon === h ? ' is-on' : ''}`}
                    style={{ left: `${((h - HORIZON_MIN) / (HORIZON_MAX - HORIZON_MIN)) * 100}%` }}
                  >
                    <i />
                    <b>{horizonLabel(h)}</b>
                  </span>
                ))}
              </div>
            </div>
            )}

            {/* 대회에서는 안내를 두지 않는다 — 바로 위 고정 줄이 기간과 만기를
                이미 말하고 있어, 같은 말을 한 번 더 길게 적는 셈이다 */}
            {!eventMode && (
              <p className="sd-help">
                <b className="num">{horizonLabel(horizon)}</b> · 만기 <b className="num">{dueLabel(horizon)}</b> · 기준일(다음 평일)부터 캘린더일로 셉니다. 기준가는 기준일 종가로 확정됩니다.
              </p>
            )}
          </li>

          {/* ③ 근거 포인트 — B-03 종목 정보에서 인계받는다. 근거 본문과 같이
              구독자 전용이다(§5 개정 2026-09-10). */}
          <li className="pf-step">
            {/* "구독자 전용" 은 대회에서 뗀다. 처음 온 사람에게 이 말은 "나는 못
                쓰는 칸인가" 로 읽히는데, 대회에서는 그 구분을 설명할 자리가 없다 */}
            <h3><i className="pf-no">3</i>근거 포인트 <span className="pf-opt">{eventMode ? '선택' : '선택 · 구독자 전용'}</span></h3>
            {points.error ? (
              <ErrorState error={points.error} onRetry={points.retry} inline />
            ) : picked.length === 0 ? (
              /* 탭을 옮기라고 하지 않는다 — 아래 버튼이 같은 격자를 펼친다 */
              <p className="pf-none">아래 <b>근거 포인트 선택하기</b>에서 고르면 여기에 모입니다.</p>
            ) : (
              <ul className="sd-picked">
                {picked.map((id) => {
                  const p = pointById.get(id)
                  /* 포인트 목록이 아직 안 왔거나 종목이 바뀌어 사라진 id 면 이름을 모른다.
                     그때도 줄은 남겨야 사용자가 빼기라도 할 수 있다. */
                  const label = p?.body ?? `포인트 ${id}`
                  return (
                    <li key={id}>
                      {p && (
                        <span className={`sd-pt-side is-${p.kind}`}>{POINT_LABEL[p.kind]}</span>
                      )}
                      <b>{label}</b>
                      <button type="button" className="sd-pick-off" onClick={() => onPick(id)}
                              aria-label={`${label} 근거에서 빼기`}>
                        ×
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}

            {/* 투자 포인트를 **여기에 그대로 둔다**(2026-09-17). 전에는 고른 것만 요약하고
                못 고르면 종목 정보 탭으로 보냈는데, 예측을 쓰다 근거를 하나 더 붙이려면
                탭을 옮겼다 돌아와야 했다 — 그 사이 화면이 갈리고 돌아오면 어디까지
                적었는지 다시 찾는다. 같은 격자를 InfoTab 과 **한 컴포넌트로** 쓴다
                (PointPicker) — 복사하면 라벨·출처·선택 규칙이 두 벌이 된다.

                로딩·오류는 위 분기가 이미 맡는다. 목록이 안 왔으면 종류마다 "없음" 이
                서고, 오류면 이 자리까지 오지 않는다. */}
            {!points.error && (
              <div className="pf-points">
                {/* 접어 둔다. 포인트가 열 개 넘게 오는 종목이면 격자가 폼을 반으로
                    가르고 ④ 내 판단이 화면 밖으로 밀린다 — 근거는 **더할 수도 있는**
                    것이고 목표가·기간·판단이 이 폼의 본줄기다. 눌러야 나오게 두면
                    필요한 사람만 펼친다. */}
                <button
                  type="button"
                  className="pf-points-toggle"
                  onClick={() => setPickerOpen((v) => !v)}
                  aria-expanded={pickerOpen}
                  aria-controls="pf-point-picker"
                >
                  근거 포인트 선택하기
                  <span className="num">{picked.length > 0 && `${picked.length}개 선택됨`}</span>
                  <span className="pf-points-caret" aria-hidden="true">{pickerOpen ? '▴' : '▾'}</span>
                </button>

                {/* hidden 으로 감추지 않고 아예 안 그린다 — 포인트가 많으면 접힌 채로도
                    체크박스 수십 개가 초점 순서에 남는다 */}
                {pickerOpen && (
                  <div id="pf-point-picker">
                    <PointPicker points={points.data} picked={picked} onPick={onPick} />
                    <p className="pf-points-foot">
                      {/* 전문을 보려면 여전히 종목 정보가 낫다 — 여기는 고르는 자리다 */}
                      <button type="button" className="sd-link" onClick={onGoInfo}>
                        종목 정보에서 자세히 보기
                      </button>
                    </p>
                  </div>
                )}
              </div>
            )}
          </li>

          {/* ④ 내 판단 — 평문이다. payload 에는 noteHash 만 들어가므로 서식을 넣지 않는다.

              **선택으로 바꾸지 않았다.** 서버 PredictionCreateRequest 가 @NotBlank 로
              받고(명세도 note *), 비우고 부르면 400 "근거를 입력해주세요." 가 온다 —
              실제로 눌러 확인했다. 서버가 풀어 주면 그때 pf-opt 로 바꾼다. */}
          <li className="pf-step">
            <h3><i className="pf-no">4</i>내 판단 <span className="pf-req">{eventMode ? '필수' : '필수 · 구독자 전용'}</span></h3>
            {/* 남은 글자는 **칸 안쪽**에 겹쳐 띄운다. 칸 아래 한 줄로 두면 4,500 자를
                넘는 순간 그 줄이 생기며 아래가 통째로 밀린다 — 글을 쓰는 중에 발판이
                움직이는 것이라, 자리를 차지하지 않는 곳에 놓는다. */}
            <div className="sd-note-wrap">
            <textarea
              className="sd-note"
              value={note}
              maxLength={NOTE_MAX}
              /* 두 줄로 연다. 세 줄이면 빈 칸이 카드에서 가장 큰 덩어리가 되는데,
                 여기 적는 글은 대개 한두 줄이고 길게 쓰는 사람은 늘려 쓰면 된다
                 (resize:vertical). */
              rows={2}
              placeholder="왜 이렇게 보는지 적어 두면, 만기 뒤에 스스로 판단을 되짚을 수 있습니다."
              onChange={(e) => setNote(e.target.value)}
            />
            {/* 평소에는 적지 않는다(2026-09-16). 5,000 자는 여기 적는 글의 길이와
                거리가 멀어 늘 띄워 두면 평생 볼 일 없는 숫자가 칸마다 붙어 있다.
                한계가 눈앞일 때만 말해 준다 — maxLength 로 조용히 막히면
                왜 안 써지는지 알 길이 없다. */}
            {NOTE_MAX - note.length <= NOTE_WARN_LEFT && (
              <span className="sd-note-left num" role="status">
                {`${NOTE_MAX - note.length}자 남음`}
              </span>
            )}
            </div>
          </li>
        </ol>

        {/* 배팅은 **단계에 끼어들지 않는다.** ①~④ 는 이벤트든 아니든 같은 네 개이고,
            배팅은 그 뒤에 얹히는 이벤트 전용 카드다. 번호 사이에 밀어 넣으면 같은
            화면의 단계 수가 경로에 따라 달라진다.
            자리가 발판 바로 위인 것도 뜻이 있다 — 돈을 거는 결정은 서명 직전에
            마지막으로 한 번 더 보이는 게 맞다. */}
        {eventMode && (
          <EventBet
            value={form.bet}
            onChange={setBet}
            direction={direction}
            contestPath={CONTEST_PATH}
          />
        )}

        {/* 커밋 미리보기 + 서명 */}
        <div className="pf-sign">
          {draft ? (
            <>
              {/* 커밋 문자열을 그대로 펼쳐 보여 준다(SignConfirm, §7). 커밋 salt 가 없어져
                  (09-09) 이 브라우저가 원장에 남을 commitHash 를 직접 계산할 수 있다 —
                  값이 서로 다르면 등록 전에 알아챌 수 있어야 하므로 둘 다 적는다. */}
              <p className="sd-help">
                아래 내용에 서명합니다. 커밋 해시는 이 브라우저가 계산한 값이며, 등록 뒤 원장에 남는 값과 같습니다.
              </p>
              <pre className="sd-payload num">{shown ? shown.payload : '커밋 문자열을 만드는 중…'}</pre>
              <dl className="sd-commit">
                <div>
                  <dt>근거 본문 해시</dt>
                  <dd className="num">{shown?.noteHash ?? '계산 중…'}</dd>
                </div>
                <div>
                  <dt>커밋 해시</dt>
                  <dd className="num">{shown?.commitHash ?? '계산 중…'}</dd>
                </div>
              </dl>

              {/* 되돌릴 수 없는 동작이라 반드시 고지한다 */}
              <p className="sd-irreversible">
                등록한 예측은 수정하거나 삭제할 수 없습니다. 목표가와 기간을 다시 확인해 주세요.
              </p>

            </>
          ) : null}

          {/* 발판은 **늘 보인다**(2026-09-10). 조건이 안 찼을 때 아예 숨기면 다 채운
              뒤에야 발판이 나타나 화면이 아래로 밀리고, 그전까지는 이 카드가 어떻게
              끝나는지 알 수 없다. 못 누르는 상태로 자리를 지키게 한다. */}
          <SlideToSign
            onConfirm={submit}
            disabled={!draft || betOutOfRange}
            busy={submitting}
            label={
              overSlot ? `${overCost} ANT 소각하고 등록하기`
                : bet > 0 ? '예측하고 배팅하기'
                  : '밀어서 등록하기'
            }
            busyLabel="서명중입니다…"
          />

          {/* 누른 뒤에 생기는 것이라 발판 **아래** 에 둔다. 위에 두면 눌렀을 때
              시선이 발판에 있어 문구가 나타난 줄 모르고, 스크롤 위치에 따라 화면
              밖에 있기도 하다 — 실제로 "눌러도 아무 일이 없다" 는 보고가 그래서
              나왔다. role=alert 로 읽어 주는 순서도 맞춘다. */}
          {(failure || localMsg) && (
            <div className="sd-submit-msg" role="alert">
              {failure && <ErrorState error={failure} inline />}
              {localMsg && <p className="sd-warn">{localMsg}</p>}
            </div>
          )}
        </div>
      </Panel>

      {progress}

      {/* 소각 동의. 확인하면 submit 이 confirmingBurn 을 보고 서명으로 넘어간다 */}
      {confirmingBurn && (
        <ConfirmDialog
          title={`${overCost} ANT 를 사용해 추가로 등록할까요?`}
          description={`오늘 무료 슬롯 ${slots.data?.freeLimit ?? 3}건을 모두 썼습니다. 이 예측은 ${overCost} ANT 를 소각하고 등록되며, 소각은 되돌릴 수 없습니다.`}
          confirmLabel={`${overCost} ANT 소각하고 등록`}
          tone="danger"
          onCancel={() => setConfirmingBurn(false)}
          onConfirm={() => { void submit() }}
        />
      )}

      {/* 소각 접수(202) 폴링 — 확정되면 예측 상세로 */}
      {burnOp && (
        <OperationModal operationId={burnOp} kind="PREDICTION_BURN" onClose={() => setBurnOp(null)} />
      )}

      {/* 지갑이 없으면 먼저 연동하고, 끝나면 하던 등록을 이어 간다 */}
      {linking && (
        <WalletLinkModal
          onClose={() => setLinking(false)}
          onLinked={() => {
            setWalletLinked(true)
            setLinking(false)
            void submit()
          }}
        />
      )}
    </div>
  )
}
