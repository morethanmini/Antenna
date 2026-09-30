/* 리포트 원문에서 목차를 뽑는다. 쓰는 곳 — ReportToc(목차), Markdown(제목 id).
   이유와 규칙은 ReportToc.tsx 머리말에 있다. */
export type TocItem = { id: string; text: string; depth: 0 | 1 }

export const headingId = (line: number) => `sec-${line}`

/* 목차에 넣는 최소 제목 수. 하나뿐이면 목차가 아니라 제목 반복이다 */
const MIN_ITEMS = 2

/* 제목 줄의 인라인 문법을 걷어 글자만 남긴다. 완벽할 필요는 없다 — 목차는 길잡이다 */
function plain(s: string) {
  return s
    .replace(/\s+#+\s*$/, '')                  // 닫는 # (## 제목 ##)
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1') // 링크·이미지 → 글자
    .replace(/(\*\*|__|\*|`|~~)/g, '')
    .trim()
}

export function outline(md: string): TocItem[] {
  const found: { line: number; level: number; text: string }[] = []
  let fence: string | null = null

  md.split('\n').forEach((raw, i) => {
    const f = raw.match(/^\s{0,3}(`{3,}|~{3,})/)
    if (f) {
      // 같은 종류의 울타리로만 닫힌다 — 코드 안의 # 은 제목이 아니다
      if (fence === null) fence = f[1][0]
      else if (f[1][0] === fence) fence = null
      return
    }
    if (fence !== null) return

    const h = raw.match(/^\s{0,3}(#{1,3})\s+(.+)$/)
    if (h) {
      const text = plain(h[2])
      if (text) found.push({ line: i + 1, level: h[1].length, text })
    }
  })

  if (found.length < MIN_ITEMS) return []

  // 글마다 제일 큰 제목이 # 일 수도 ## 일 수도 있다. 가장 큰 것을 첫 층으로 맞춘다
  const top = Math.min(...found.map((h) => h.level))
  return found
    .filter((h) => h.level - top <= 1)
    .map((h) => ({ id: headingId(h.line), text: h.text, depth: (h.level - top) as 0 | 1 }))
}
