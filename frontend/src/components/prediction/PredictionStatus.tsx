/* 예측 상태 배지. B-03 종목 상세와 C-02 내 예측이 함께 쓴다.

   상태 전이는 BASE → OPEN → HIT/MISS 다(서버 Prediction.Status). 같은 어휘와 같은
   색 결정을 화면마다 따로 두면 한쪽만 고쳐지며 갈라진다 — 실제로 "적중" 색을
   두 번 바꿨고, 그때 두 곳이었다면 한쪽이 옛 색으로 남았을 것이다.

   §7 은 잠금까지 내장한 PredictionCard 를 C-02 · C-03 · E-02 · F-04 공용으로 두라고
   하지만, 지금 카드째 빼지는 않았다. C-02 응답(dday · settleDate)과 B-03 응답
   (author · locked · accuracy)이 필드부터 다르고, C-03 · E-02 · F-04 가 아직 없어
   그쪽 요구를 모른 채 합치면 추측이 된다. 그래서 확실히 같은 것 — 상태 어휘와
   배지 — 만 먼저 뺐다. 카드는 C-03 을 만들 때 함께 정리한다. */
import type { PredictionStatus } from '../../api/predictions'

const LABEL: Record<PredictionStatus, string> = {
  BASE: '기준가 대기',
  OPEN: '판정 대기',
  HIT: '적중',
  MISS: '빗나감',
}

/* 앞의 둘은 이름만으로 차이가 잘 읽히지 않아 설명을 붙인다 —
   둘 다 "대기" 라서 무엇을 기다리는지가 구분점이다. */
const HINT: Record<PredictionStatus, string> = {
  BASE: '예측은 등록됐어요. 기준가는 다음 영업일 종가로 정해져요.',
  OPEN: '기준가가 정해졌어요. 만기일 종가가 나오면 판정해요.',
  HIT: '만기 종가가 목표가에 닿아 적중으로 판정됐습니다.',
  MISS: '만기 종가가 목표가에 닿지 못했습니다.',
}

/* 만기가 지난 OPEN 은 기다리는 것이 다르다. 위 HINT 의 "만기일 종가가 나오면" 은 만기
   **전**의 이야기인데, 만기가 지난 뒤에도 같은 말이 나왔다 — 그러면 왜 아직 안 끝났는지
   알 수 없다(2026-09-22 QA: 시세 수집이 이틀 비어 7건이 이 상태로 멈춰 있었는데 화면은
   평소와 똑같았다). 운영에서 수집이 한 번 실패해도 아무도 눈치채지 못한다. */
const OVERDUE_HINT =
  '만기일 종가를 기다리는 중이에요. 시세가 들어오면 판정해요.'

/* 이 파일은 컴포넌트만 내보낸다. 상태를 판정 여부로 가르는 일은 도메인 쪽 일이라
   api/predictions.ts 의 phaseOf 를 쓴다 — 같은 개념을 두 곳에 두지 않는다. */
export default function PredictionStatus(
  { status, overdue = false }: { status: PredictionStatus; overdue?: boolean },
) {
  const hint = status === 'OPEN' && overdue ? OVERDUE_HINT : HINT[status]
  return (
    <span className={`pred-status is-${status.toLowerCase()}`} title={hint}>
      {LABEL[status]}
    </span>
  )
}
