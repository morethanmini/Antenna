/* 리포트 본문 후처리(rehype). 작성자에게 새 문법을 요구하지 않고, 이미 쓰고 있는 형태를
   알아보고 더 읽기 좋게 바꿔 그린다. 알아보지 못하면 **손대지 않는다** — 원래대로 나온다.

   ■ 문자 막대 차트 → 거래량 차트(blocks.tsx VolumeChart)
   리포트에는 코드 블록 안에 █ 로 막대를 그린 표가 자주 온다.
       8/24  ███████████▌  32.45  ▼ -8.70%  ← 투매
   반 칸 글자(▌▍…)는 고정폭 서체에 없는 경우가 많아 대체 서체로 그려지고, 폭이 달라
   뒤쪽 숫자 열이 들쭉날쭉해진다. 줄 대부분이 "이름 막대 숫자 …" 꼴이면 진짜 막대로 바꾼다.
   막대 길이는 글자 수가 아니라 **숫자**로 다시 잰다 — 글자 막대는 반올림돼 있다.
   ┐ ├ ┘ 로 묶은 줄은 한 구간으로 보고 배경 띠를 깐다.

   ■ 요약표 → 요약 카드(blocks.tsx ReportSummary)
   2열 표의 왼쪽 칸이 기준일·관점·지지·저항 가운데 둘 이상을 갖고 있으면 요약표로 본다.
   이름표를 이렇게 좁게 잡은 이유 — 2열 표는 요약표 말고도 흔하다. 엉뚱한 표가 카드가 되느니
   알아보지 못하고 표로 두는 편이 낫다.

   두 부품 모두 값은 JSON 글자로 넘긴다(data-*). 부품이 React 로 그리므로 HTML 이 끼어들 틈이 없다.

   ■ 표
   - 숫자 열: 본문 칸이 모두 숫자꼴(32.45M · -8.70% · 11일 · 16.46M)이면 오른쪽 정렬한다.
     자릿수가 세로로 맞아야 크기를 눈으로 견줄 수 있다.
   - 짧은 열: 칸이 모두 12자 이하면 줄을 바꾸지 않는다. 좁은 화면에서 9/15 ~ 9/23 이 세 줄로 쪼개진다.
   - 방향: 칸 글자가 +·▲ 로 시작하면 상승, -·▼ 로 시작하면 하락 색을 준다(국내 관례 빨강/파랑). */
import type { Element, ElementContent, Root, RootContent } from 'hast'

const text = (n: RootContent | Root | ElementContent): string =>
  n.type === 'text' ? n.value
    : 'children' in n ? n.children.map((c) => text(c as ElementContent)).join('')
      : ''

function addClass(node: Element, cls: string) {
  const cur = node.properties.className
  const list = Array.isArray(cur) ? cur.map(String) : cur ? String(cur).split(' ') : []
  node.properties.className = [...list, cls]
}

/* ───────── 막대 차트 ───────── */

const BAR_LINE = /^\s*(\S+)\s+([█▉▊▋▌▍▎▏]+)\s+(-?[\d,]+(?:\.\d+)?)\s*(.*)$/
const GROUP_OPEN = /[┐┌]/
const GROUP_CLOSE = /[┘└]/

export type SummaryRow = { k: string; v: string }
export type Bar = { label: string; value: number; raw: string; change: string; dir: 'up' | 'down' | 'flat' | null; note: string; group: boolean }

function parseBars(src: string): Bar[] | null {
  const lines = src.split('\n').filter((l) => l.trim() !== '')
  const bars: Bar[] = []
  let inGroup = false
  for (const line of lines) {
    const m = line.match(BAR_LINE)
    if (!m) continue
    let rest = m[4]
    const opens = GROUP_OPEN.test(rest)
    const closes = GROUP_CLOSE.test(rest)
    if (opens) inGroup = true
    // 등락: ▲ +1.75% · ▼ -8.70% · — 0.00%
    const c = rest.match(/^([▲▼△▽—–-])?\s*([+\-−]?\d[\d.,]*%)?/)
    const change = c?.[2] ?? ''
    const sign = c?.[1] ?? ''
    const dir = /[▲△]/.test(sign) || change.startsWith('+') ? 'up'
      : /[▼▽]/.test(sign) || /^[-−]/.test(change) ? 'down'
        : change ? 'flat' : null
    rest = rest.slice(c?.[0].length ?? 0)
    const note = rest.replace(/[←┐┌┘└├┤│]/g, '').trim()
    bars.push({ label: m[1], value: Number(m[3].replace(/,/g, '')), raw: m[3], change, dir, note, group: inGroup })
    if (closes) inGroup = false
  }
  // 대부분이 막대 줄이어야 차트다. 머리글 한두 줄은 버려도 되지만 그 이상이면 다른 글이다
  if (bars.length < 3 || bars.length < lines.length * 0.8) return null
  return bars
}

/* 부품 자리표시. 태그 이름은 Markdown 의 components 가 받아 blocks.tsx 로 넘긴다 */
const block = (tagName: string, data: Record<string, string>): Element => ({
  type: 'element', tagName, properties: data, children: [],
})

/* ───────── 표 ───────── */

const NUMERIC = /^[▲▼+\-−]?\s*[+\-−]?\d[\d,]*(\.\d+)?\s*(%|[A-Za-z]{1,2}|[가-힣]{1,2}|조 원|만 주)?$/
const SHORT = 12
const SUMMARY_KEYS = [/기준|현재|종가/, /관점|의견|판단/, /지지/, /저항|목표/]

/* 2열 요약표면 행을 돌려준다. 아니면 null — 그 표는 그대로 표로 남는다 */
function summaryRows(table: Element): SummaryRow[] | null {
  const rows = rowsOf(table)
    .map((r) => r.children.filter((c): c is Element => c.type === 'element'))
    .filter((cs) => cs.every((c) => c.tagName === 'td'))
  if (rows.length < 2 || rows.some((cs) => cs.length !== 2)) return null
  const out = rows.map(([k, v]) => ({ k: text(k).trim(), v: text(v).trim() }))
  const hits = SUMMARY_KEYS.filter((re) => out.some((r) => re.test(r.k))).length
  return hits >= 2 ? out : null
}
const UP = /^[▲+]\s*\d/
const DOWN = /^[▼\-−]\s*\d/

function rowsOf(table: Element): Element[] {
  const rows: Element[] = []
  const walk = (n: Element) => n.children.forEach((c) => {
    if (c.type !== 'element') return
    if (c.tagName === 'tr') rows.push(c)
    else walk(c)
  })
  walk(table)
  return rows
}

function decorateTable(table: Element) {
  const rows = rowsOf(table)
  const cells = rows.map((r) => r.children.filter((c): c is Element => c.type === 'element'))
  const body = cells.filter((cs) => cs.every((c) => c.tagName === 'td'))
  if (body.length === 0) return
  const cols = Math.max(...cells.map((cs) => cs.length))

  for (let i = 0; i < cols; i++) {
    const vals = body.map((cs) => (cs[i] ? text(cs[i]).trim() : '')).filter(Boolean)
    if (vals.length && vals.every((v) => NUMERIC.test(v))) {
      cells.forEach((cs) => cs[i] && addClass(cs[i], 'is-num'))
    }
    // 날짜·기간·짧은 이름 열(8/24 · 9/15 ~ 9/23 · 소화)은 쪼개면 읽을 수 없다. 긴 설명 열이 폭을 양보한다
    else if (vals.length && vals.every((v) => v.length <= SHORT)) {
      cells.forEach((cs) => cs[i] && addClass(cs[i], 'is-short'))
    }
  }
  body.flat().forEach((c) => {
    const v = text(c).trim()
    if (UP.test(v)) addClass(c, 'is-up')
    else if (DOWN.test(v)) addClass(c, 'is-down')
  })
}

/* ───────── 순회 ───────── */

export default function rehypeReport() {
  return (tree: Root) => {
    const visit = (parent: Root | Element) => {
      parent.children.forEach((node, i) => {
        if (node.type !== 'element') return
        if (node.tagName === 'pre') {
          const code = node.children.find((c): c is Element => c.type === 'element' && c.tagName === 'code')
          const bars = code && parseBars(text(code))
          if (bars) { parent.children[i] = block('volume-chart', { dataBars: JSON.stringify(bars) }); return }
        }
        if (node.tagName === 'table') {
          const rows = summaryRows(node)
          if (rows) { parent.children[i] = block('report-summary', { dataRows: JSON.stringify(rows) }); return }
          decorateTable(node)
        }
        visit(node)
      })
    }
    visit(tree)
  }
}
