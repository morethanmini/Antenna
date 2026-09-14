/* 사각 트리맵 배치 — B-02 종목 히트맵이 쓴다.
   Bruls·Huizing·van Wijk 의 squarified treemap 이다.

   왜 단순 격자가 아닌가 — 칸마다 크기가 달라야 "큰 종목이 큰 칸" 이 성립한다.
   그리고 가중치가 모두 같아도 이 알고리즘은 정사각형에 가까운 칸을 만들어,
   격자보다 이름이 덜 잘린다.

   면적은 가중치에 정비례한다. 짧은 변을 따라 줄(row)을 쌓되, 한 칸을 더 넣었을 때
   가장 나쁜 종횡비가 더 나빠지면 거기서 줄을 끊는다 — 그래야 칸이 국수처럼
   길어지지 않는다.

   **큰 것부터 넣는 것이 이 알고리즘의 전제다.** 정렬하지 않으면 큰 칸과 작은 칸이
   한 줄에 섞여, 줄 높이는 큰 칸에 맞춰지는데 작은 칸은 그 높이에 실낱 같은 폭을
   갖게 된다 — 길쭉한 사각형이 쏟아지는 원인이 이것이다. 정렬해 두면 덤으로
   **큰 칸이 좌측 상단에 모이고 오른쪽·아래로 갈수록 작아진다**. 트리맵을 읽는
   사람이 기대하는 순서라, 크기를 일일이 견주지 않아도 순위가 보인다.

   픽셀로 계산한다. 비율(%)로 두면 바깥 칸과 안쪽 칸의 기준 폭이 달라
   같은 1% 가 다른 길이가 되고, 머리글 높이 같은 고정 px 를 섞을 수 없다. */

export type Rect = { x: number; y: number; w: number; h: number }

/** 배치된 한 칸. 원래 값을 그대로 달고 나온다 */
export type Tile<T> = Rect & { item: T }

/** 배치에 넣을 한 칸. weight 가 0 이하면 자리를 주지 않는다 */
export type Weighted<T> = { item: T; weight: number }

/**
 * 한 줄에 담긴 면적들의 **가장 나쁜 종횡비**.
 * @param areas 줄에 든 칸들의 면적
 * @param side 줄이 걸쳐 있는 변의 길이(짧은 쪽)
 */
function worstRatio(areas: number[], side: number): number {
  let sum = 0
  let max = 0
  let min = Infinity
  for (const a of areas) {
    sum += a
    if (a > max) max = a
    if (a < min) min = a
  }
  if (sum <= 0 || min <= 0) return Infinity
  const s2 = sum * sum
  const d2 = side * side
  return Math.max((d2 * max) / s2, s2 / (d2 * min))
}

/**
 * 가중치에 비례하는 면적으로 box 를 채운다.
 *
 * **하나도 빠짐없이 0 이하일 때만** 똑같이 나눈다. 한둘이 0 이라고 전체를 균등으로
 * 떨어뜨리면, 값을 모르는 한 종목 때문에 나머지 300 개의 크기가 통째로 뜻을 잃는다.
 * 섞여 있을 때는 0 인 것만 빼고 나머지를 제 비율대로 그린다 — "값을 모르는 칸" 에
 * 어떤 크기를 줄지는 부르는 쪽이 정해서 넘긴다(MarketMap 의 makeWeigh).
 */
export function squarify<T>(input: Weighted<T>[], box: Rect): Tile<T>[] {
  if (input.length === 0 || box.w <= 0 || box.h <= 0) return []

  const positive = input.filter((d) => d.weight > 0)
  /* **큰 것부터.** 머리말에 적은 대로 이 정렬이 알고리즘의 전제이자, 큰 칸이
     좌측 상단에 모이게 하는 장치다. 같은 값끼리는 넘겨받은 차례를 지킨다
     (Array.sort 가 안정 정렬이다). 넘겨준 배열은 건드리지 않는다. */
  const items = (positive.length > 0
    ? positive.slice()
    : input.map((d) => ({ item: d.item, weight: 1 })))
    .sort((a, b) => b.weight - a.weight)

  const total = items.reduce((s, d) => s + d.weight, 0)
  if (total <= 0) return []

  /* 가중치를 면적으로 바꾼다. 이렇게 두면 아래에서는 면적만 다루면 된다 */
  const scale = (box.w * box.h) / total
  const rest = items.map((d) => ({ item: d.item, area: d.weight * scale }))

  const out: Tile<T>[] = []
  const free: Rect = { ...box }
  let cursor = 0

  while (cursor < rest.length) {
    const side = Math.min(free.w, free.h)
    const row: { item: T; area: number }[] = []
    let best = Infinity

    /* 나빠지기 직전까지 이 줄에 담는다 */
    while (cursor < rest.length) {
      const next = rest[cursor]
      const ratio = worstRatio([...row.map((r) => r.area), next.area], side)
      if (row.length > 0 && ratio > best) break
      row.push(next)
      best = ratio
      cursor += 1
    }

    const rowArea = row.reduce((s, d) => s + d.area, 0)
    /* 남은 자리가 마지막 줄이면 반올림 오차만큼 틈이 남는다. 마지막 줄은
       남은 자리를 통째로 쓰게 해 오른쪽·아래에 1px 띠가 생기지 않게 한다 */
    const last = cursor >= rest.length

    if (free.w >= free.h) {
      const colW = last ? free.w : rowArea / free.h
      let y = free.y
      row.forEach((d, i) => {
        const h = i === row.length - 1
          ? free.y + free.h - y
          : (d.area / rowArea) * free.h
        out.push({ item: d.item, x: free.x, y, w: colW, h })
        y += h
      })
      free.x += colW
      free.w -= colW
    } else {
      const rowH = last ? free.h : rowArea / free.w
      let x = free.x
      row.forEach((d, i) => {
        const w = i === row.length - 1
          ? free.x + free.w - x
          : (d.area / rowArea) * free.w
        out.push({ item: d.item, x, y: free.y, w, h: rowH })
        x += w
      })
      free.y += rowH
      free.h -= rowH
    }
  }

  return out
}
