/* 리포트 본문 전용 부품. rehypeReport 가 알아본 형태를 여기서 그린다.
   Markdown 의 지연 청크에 같이 실려 온다 — 리포트 화면 밖에서는 받지 않는다.

   값은 rehype 가 원문 글자에서 뽑아 JSON 으로 넘긴다. HTML 을 만들지 않고 React 로만
   그리므로 본문에 무엇이 적혀 있어도 글자로 나온다. */
import { useEffect, useRef, useState } from 'react'
import type { Bar, SummaryRow } from './rehypeReport'

function parse<T>(raw: unknown): T | null {
  if (typeof raw !== 'string') return null
  try { return JSON.parse(raw) as T } catch { return null }
}

/* 컨테이너 폭을 px 로 잰다. SVG 를 viewBox 로 늘리면 휴대폰에서 글자가 4px 까지 줄어든다 —
   실제 폭으로 다시 그려야 글자 크기가 그대로다 */
function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [w, setW] = useState(0)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setW(Math.round(e.contentRect.width)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, w] as const
}

/* ───────── 거래량 차트 ─────────
   세로 막대 = 거래량, 색 = 그날 등락(국내 관례 빨강/파랑). 점선 = 구간 평균.
   메모(← 투매)는 막대 위 말풍선, ┐├┘ 로 묶은 구간은 배경 띠. */

const H = 230
const PAD = { top: 34, right: 8, bottom: 26, left: 8 }

export function VolumeChart(props: Record<string, unknown>) {
  const bars = parse<Bar[]>(props['data-bars'])
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  if (!bars || bars.length === 0) return null

  const n = bars.length
  const max = Math.max(...bars.map((b) => b.value)) * 1.08
  const avg = bars.reduce((s, b) => s + b.value, 0) / n
  const plotW = Math.max(0, width - PAD.left - PAD.right)
  const plotH = H - PAD.top - PAD.bottom
  const step = plotW / n
  const bw = Math.max(3, step * 0.62)
  const x = (i: number) => PAD.left + step * i + (step - bw) / 2
  const y = (v: number) => PAD.top + plotH * (1 - v / max)
  // 날짜 글자는 약 30px 마다 하나. 메모가 달린 날은 늘 보인다
  const every = Math.max(1, Math.ceil(34 / (step || 1)))

  // 묶음 구간: 이어진 group 줄을 한 띠로 합친다
  const bands: { from: number; to: number; note: string }[] = []
  bars.forEach((b, i) => {
    if (!b.group) return
    const last = bands[bands.length - 1]
    if (last && last.to === i - 1) { last.to = i; if (b.note) last.note = b.note }
    else bands.push({ from: i, to: i, note: b.note })
  })
  const inBand = (i: number) => bands.some((g) => i >= g.from && i <= g.to)

  // 날짜 글자: 메모가 달린 날을 먼저 세우고, 나머지는 그 근처를 비켜 일정 간격으로 채운다
  const noted = bars.map((b, i) => (b.note && !inBand(i) ? i : -1)).filter((i) => i >= 0)
  const showX = new Set(noted)
  for (let i = 0; i < n; i += every) {
    if (noted.every((j) => Math.abs(j - i) >= every)) showX.add(i)
  }

  const top = bars.reduce((a, b) => (b.value > a.value ? b : a))
  const h = hover !== null ? bars[hover] : null

  return (
    <figure
      className="md-vchart"
      ref={ref}
      role="img"
      aria-label={`거래량 막대 차트. ${bars[0].label}부터 ${bars[n - 1].label}까지 ${n}개, 최대 ${top.label} ${top.raw}, 평균 ${avg.toFixed(2)}`}
    >
      {width > 0 && (
        <svg width={width} height={H} onMouseLeave={() => setHover(null)}>
          {bands.map((g) => (
            <g key={g.from}>
              <rect className="vc-band" x={PAD.left + step * g.from} y={PAD.top - 6}
                width={step * (g.to - g.from + 1)} height={plotH + 6} rx={6} />
              {g.note && (
                <text className="vc-band-note" x={PAD.left + step * (g.from + (g.to - g.from + 1) / 2)} y={PAD.top - 12}
                  textAnchor="middle">{g.note}</text>
              )}
            </g>
          ))}

          <line className="vc-base" x1={PAD.left} x2={width - PAD.right} y1={y(0)} y2={y(0)} />

          {bars.map((b, i) => (
            <g key={i}>
              <rect className={`vc-bar${b.dir ? ` is-${b.dir}` : ''}${hover === i ? ' is-hover' : ''}`}
                x={x(i)} y={y(b.value)} width={bw} height={Math.max(1, y(0) - y(b.value))} rx={Math.min(3, bw / 3)} />
              {showX.has(i) && (
                <text className={`vc-x${b.note ? ' has-note' : ''}`} x={x(i) + bw / 2} y={H - 8} textAnchor="middle">{b.label}</text>
              )}
              {b.note && !inBand(i) && (
                <g className="vc-note">
                  <line x1={x(i) + bw / 2} x2={x(i) + bw / 2} y1={y(b.value) - 3} y2={y(b.value) - 9} />
                  <text x={Math.min(Math.max(x(i) + bw / 2, 40), width - 40)} y={y(b.value) - 13} textAnchor="middle">{b.note}</text>
                </g>
              )}
              {/* 칸 전체를 잡는 투명 사각형. 막대가 가늘어도 마우스가 쉽게 걸린다 */}
              <rect className="vc-hit" x={PAD.left + step * i} y={PAD.top} width={step} height={plotH}
                onMouseEnter={() => setHover(i)} />
            </g>
          ))}

          <line className="vc-avg" x1={PAD.left} x2={width - PAD.right} y1={y(avg)} y2={y(avg)} />
        </svg>
      )}

      {/* 범례 줄 오른쪽에 마우스를 올린 막대의 값을 적는다. 차트 위에 띄우면 메모·구간 이름을 가린다 */}
      <figcaption className="vc-legend">
        <span><i className="is-up" />상승일</span>
        <span><i className="is-down" />하락일</span>
        <span><i className="is-flat" />보합</span>
        <span><i className="is-avg" />{`구간 평균 ${avg.toFixed(2)}`}</span>
        <span className="vc-read" aria-live="polite">
          {h
            ? <><b>{h.label}</b>{h.raw}{h.change && <em className={h.dir ? `is-${h.dir}` : undefined}>{h.change}</em>}</>
            : <span className="vc-read-hint">막대에 마우스를 올려 보세요</span>}
        </span>
      </figcaption>
    </figure>
  )
}

/* ───────── 요약 카드 ─────────
   "항목 | 내용" 요약표를 지표 카드로. 기준가·지지·저항이 다 있으면 가격 위치 막대를 붙인다. */

const toNum = (s: string) => {
  // 285,500원 · 262,000 — 네 자리 이상인 첫 숫자를 가격으로 본다(날짜 2026-09-23 은 건너뛴다)
  const m = s.replace(/\d{4}-\d{2}-\d{2}/g, '').match(/\d{1,3}(?:,\d{3})+|\d{4,}/)
  return m ? Number(m[0].replace(/,/g, '')) : null
}

const stance = (v: string) =>
  /확대|매수|긍정|상향|강세/.test(v) ? 'up' : /축소|매도|부정|하향|약세/.test(v) ? 'down' : 'flat'

/* 괄호 안 메모 — "262,000원 (9/21 갭 하단 · 20일선)" → "9/21 갭 하단 · 20일선" */
const note = (v: string) => v.match(/\(([^)]*)\)\s*$/)?.[1] ?? ''
/* 날짜 안의 - 에서 줄이 바뀌지 않게(2026- / 09-23). 줄바꿈 없는 하이픈으로 바꾼다 */
const nbHyphen = (v: string) => v.replace(/(\d)-(\d)/g, '$1‑$2')

const won = (n: number) => `${n.toLocaleString('ko-KR')}원`
const pct = (a: number, b: number) => {
  const p = (b / a - 1) * 100
  return `${p > 0 ? '+' : ''}${p.toFixed(1)}%`
}

export function ReportSummary(props: Record<string, unknown>) {
  const rows = parse<SummaryRow[]>(props['data-rows'])
  if (!rows) return null

  const find = (re: RegExp) => rows.find((r) => re.test(r.k))
  const cur = find(/기준|현재|종가/)
  const sup = find(/지지/)
  const res = find(/저항|목표/)
  const now = cur && toNum(cur.v)
  const lo = sup && toNum(sup.v)
  const hi = res && toNum(res.v)
  const gauge = now && lo && hi && lo < hi
    ? { now, lo, hi, loNote: note(sup.v), hiNote: note(res.v) }
    : null
  // 게이지가 지지·저항을 이미 보여 주므로 카드에서는 뺀다 — 같은 숫자가 두 번 나오면 무엇을 봐야 할지 흐려진다
  const tiles = gauge ? rows.filter((r) => r !== sup && r !== res) : rows

  return (
    <section className="md-summary" aria-label="리포트 요약">
      <dl className="ms-grid">
        {tiles.map((r) => {
          const isStance = /관점|의견|판단/.test(r.k)
          // "2026-09-23 종가 285,500원" → 큰 글자 285,500원 · 작은 글자 2026-09-23 종가
          const price = r === cur ? toNum(r.v) : null
          const sub: [string, string, string] | null = price
            ? ['', won(price), r.v.replace(/\d{1,3}(?:,\d{3})+원?|\d{4,}원/, '').trim()]
            : (r.v.match(/^(.*?)\s*\(([^)]*)\)\s*$/) as [string, string, string] | null)
          return (
            <div key={r.k} className={`ms-tile${isStance ? ' is-stance' : ''}`}>
              <dt>{r.k}</dt>
              <dd>
                {isStance
                  ? <span className={`ms-badge is-${stance(r.v)}`}>{sub ? sub[1] : r.v}</span>
                  : <span className="ms-main">{nbHyphen(sub ? sub[1] : r.v)}</span>}
                {sub && <span className="ms-sub">{sub[2]}</span>}
              </dd>
            </div>
          )
        })}
      </dl>

      {gauge && <PriceGauge {...gauge} />}
    </section>
  )
}

/* 지지 — 현재 — 저항을 한 줄에. 양끝에 여유를 두어 현재가가 끝을 넘어가도 선 안에 남는다 */
function PriceGauge({ now, lo, hi, loNote, hiNote }: { now: number; lo: number; hi: number; loNote: string; hiNote: string }) {
  const min = Math.min(lo, now)
  const max = Math.max(hi, now)
  const span = max - min || 1
  const at = (v: number) => `${8 + ((v - min) / span) * 84}%`

  return (
    <div className="ms-gauge">
      {/* 현재가는 막대 위, 지지·저항은 아래 — 현재가가 한쪽 끝에 붙어도 이름표끼리 겹치지 않는다 */}
      <div className="mg-labels is-top">
        <span className="is-now" style={{ left: at(now) }}><b>현재</b>{won(now)}</span>
      </div>
      <div className="mg-track">
        <span className="mg-range" style={{ left: at(lo), width: `calc(${at(hi)} - ${at(lo)})` }} />
        <span className="mg-pin is-lo" style={{ left: at(lo) }} />
        <span className="mg-pin is-hi" style={{ left: at(hi) }} />
        <span className="mg-now" style={{ left: at(now) }} />
      </div>
      <div className="mg-labels">
        <span className="is-lo" style={{ left: at(lo) }}>
          <b>지지</b><strong>{won(lo)}</strong><em>{pct(now, lo)}</em>{loNote && <small>{loNote}</small>}
        </span>
        <span className="is-hi" style={{ left: at(hi) }}>
          <b>저항</b><strong>{won(hi)}</strong><em>{pct(now, hi)}</em>{hiNote && <small>{hiNote}</small>}
        </span>
      </div>
    </div>
  )
}
