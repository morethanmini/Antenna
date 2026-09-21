/* C-01 예측 입력값의 모양.

   **컴포넌트 파일이 아니라 여기 둔다.** 값(EMPTY_PREDICT_FORM)을 컴포넌트와 같은
   파일에서 내보내면 Vite Fast Refresh 가 그 모듈을 컴포넌트 모듈로 보지 못해
   갱신이 통째로 새로고침이 된다(auth/context.ts 를 AuthContext 에서 가른 것과
   같은 이유다).

   이 값을 PredictTab 이 아니라 부모(StockDetail)가 든다. 근거를 고르러 종목 정보
   탭으로 가면 PredictTab 이 언마운트되므로, 안에 두면 돌아왔을 때 적어 둔 것이
   전부 사라진다 — picked 를 부모가 들고 있는 것과 같은 이유다. */
import { HORIZON_MIN, newNoteSalt } from '../../api/predictions'
import type { CreateResult, Horizon } from '../../api/predictions'

/* 방향은 여기 없다(2026-09-19) — 목표가가 전일 종가의 어느 쪽인지로 PredictTab 이 그때그때
   계산한다. 따로 들고 있던 때에는 방향과 목표가가 어긋난 채 서명까지 갈 수 있었다(QA 09-18). */
export type PredictForm = {
  target: string
  /**
   * 예측 기간. **null 을 두지 않는다** — range 는 값이 없어도 손잡이를 어딘가에
   * 세워야 해서 예전에는 null 일 때 왼쪽 끝(3일)에 두었는데, 그러면 DOM 값이 이미
   * "3" 이라 3일로 끌어도 change 가 안 나서 3일만 고를 수 없었다. 처음부터 3일을
   * 고른 것으로 둔다.
   */
  horizon: Horizon
  note: string
  /**
   * 근거를 봉인하는 난수(ANT-PRED-02). 폼과 함께 산다 — 글자를 칠 때마다 새로 뽑으면
   * 미리보기의 noteHash·commitHash 가 매번 바뀌어 "무엇에 서명하는지" 를 볼 수 없고,
   * 서명한 값과 보낸 값이 어긋날 수도 있다. 등록이 끝나면 폼과 함께 버린다.
   */
  noteSalt: string
  /**
   * 이벤트 배팅 금액(정수 ANT 문자열). 이벤트로 들어왔을 때만 화면에 나온다.
   *
   * **커밋에도 서명에도 들어가지 않는다.** 원장에 봉인되는 것은 예측 다섯 값뿐이고,
   * 금액은 그 밖의 부수 정보다 — 섞으면 서버가 계산한 해시와 갈린다(EventBet.tsx).
   * 그래도 폼과 함께 사는 이유는 note·target 과 같다: 근거를 고르러 종목 정보 탭에
   * 다녀오는 사이에 걸어 둔 금액이 사라지면 안 된다.
   */
  bet: string
}

/**
 * 빈 폼. **상수가 아니라 함수다** — noteSalt 를 모듈 상수에 담으면 한 번 켠 브라우저의
 * 모든 예측이 같은 난수를 쓰게 되고, 그건 난수가 없는 것과 같다.
 */
export const emptyPredictForm = (): PredictForm => ({
  target: '', horizon: HORIZON_MIN, note: '',
  noteSalt: newNoteSalt(), bet: '',
})

/**
 * 대회 배팅 접수(202). 예측은 접수 시점에 저장돼 predictionId 가 오고, 소각 확정은
 * operationId 로 M-02 가 폴링한다 — PredictionQueued 와 같은 모양이지만 kind 를 따로
 * 두는 이유는 완료 화면의 말과 갈 곳이 다르기 때문이다(예측 상세가 아니라 대회로 돌아간다).
 */
export type EventResult = {
  kind: 'event'
  data: { predictionId: number; operationId: string }
}

/**
 * 등록이 끝났다는 사실. 이 값이 서 있으면 PredictTab 은 폼 대신 완료 화면을 그린다.
 *
 * **폼과 같은 이유로 부모(StockDetail)가 든다(2026-09-21, QA-0918 #330).** PredictTab 안에
 * 두었을 때는 완료 화면에서 종목 정보 탭을 한 번 다녀오면 result 가 사라지고 값이 남은
 * 폼이 되살아나 같은 예측을 두 번 등록할 수 있었다.
 */
export type PredictResult = CreateResult | EventResult
