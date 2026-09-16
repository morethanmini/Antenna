/* 캔들 차트가 "지금 보는 구간".

   캔들(CandleChart)과 그 아래 지표 패널(IndicatorPane)이 **같은 값으로** 계산해야
   봉 하나가 위아래 같은 x 에 선다. 그래서 상태는 두 그림의 호출부(G-04)가 들고,
   자르는 규칙은 여기 한 벌만 둔다.

   sim/indicators.ts 와 같은 자리에 있는 이유도 같다 — 그림이 아니라 계산이다. */

/** {@code null} 이면 "끝에서 기본 폭만큼". 진행일이 바뀌면 이 값으로 되돌린다 */
export type BarView = { from: number; count: number } | null

/** 처음 보이는 봉 수. 워밍업 120 + 진행분을 다 그리면 봉이 실오라기가 된다 */
export const DEFAULT_BARS = 60

/** 이보다 좁히면 봉 몇 개만 남아 추세가 안 보인다 */
export const MIN_BARS = 20

/** 가진 봉 수에 맞춰 자른다. 양 끝을 넘지 않고, 봉이 기본 폭보다 적으면 전부 보인다. */
export function resolveView(view: BarView, total: number) {
  const count = Math.max(1, Math.min(total, view?.count ?? DEFAULT_BARS))
  const from = Math.max(0, Math.min(total - count, view?.from ?? total - count))
  return { from, count }
}
