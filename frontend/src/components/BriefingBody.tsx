/* M-10 AI 브리핑 본문. 서버가 준 문단들을 차례대로 알아보고 부분마다 모양을 달리 그린다.

   ■ 문단 차례는 서버 프롬프트가 정한다
   BriefingGenerationService.INSTRUCTION — "본문은 이 차례로 쓴다. ① 지수와 환율 ② 업종이
   어떻게 갈렸는지 ③ 상승·하락 상위 종목이 그 업종 흐름과 맞는지 ④ 확인해 볼 점 한 문장",
   3~4개 문단, 머리말·목록·마크다운 없음.
   머리말이 없으므로 **차례와 낱말**로 알아본다. 3문단이면 둘이 한 문단에 합쳐진 것이라
   맞는 쪽 하나만 붙는다. 알아보지 못한 문단은 이름표 없이 글만 나온다 — 틀린 이름표를
   붙이느니 붙이지 않는다.

   ■ 부분마다
   - 모든 문단: 첫 문장을 굵게, 부호 달린 % 는 등락 색(국내 관례 빨강/파랑).
   - 종목 문단: "OCI홀딩스 +9.57%" 처럼 이름 바로 뒤에 등락이 붙은 것만 골라 상승·하락 칩으로.
     "상승 97개 / 하락 202개" 가 있으면 상승·하락 비율 막대.
   - 지수 문단: "코스피는 7080.92로 … 1영업일 +0.90%, 5영업일 +5.40%" 를 지수 카드로.
   - 업종 문단: "OO 평균 +4.21%" · "A와 B도 각각 평균 -2.32%, -2.24%" 를 0 선 기준 좌우 막대로.
     브리핑에 나온 업종만이라 이름표를 "브리핑에 나온 업종" 으로 단다 — 전 업종처럼 보이면 안 된다.
   - 종목 문단: 상승·하락 상위를 등락 크기 막대가 붙은 순위표로.
   - 확인해 볼 점: 따로 떼어 강조 상자로. 판단은 읽는 사람 몫이라는 문단이다.
   그림이 붙은 문단은 원문을 그림 밑에 작은 글씨로 남긴다 — 그림은 글에서 뽑은 것이라
   원문이 근거다. 숫자를 못 읽으면 그림 없이 글만 나온다.

   ■ 왜 서버 지수·업종 API 를 쓰지 않나
   /market/indices · /stocks/sectors 는 오늘 기준 최신값이다. 브리핑은 지난 기준일(targetDate)의
   글이라 그 값을 붙이면 날짜가 다른 숫자가 한 창에 섞인다. 글에 적힌 숫자만 쓴다.
   글자를 나눠 React 요소로 감쌀 뿐 HTML 을 만들지 않는다(BriefingModal 머리말). */
import type { ReactNode } from 'react'

type Kind = 'index' | 'sector' | 'movers' | 'check'

const LABEL: Record<Kind, string> = {
  index: '지수 · 환율',
  sector: '업종',
  movers: '상승 · 하락 종목',
  check: '확인해 볼 점',
}

function classify(p: string, i: number, n: number): Kind | null {
  if (i === n - 1 && /확인해 볼|확인할|살펴볼|지켜볼/.test(p)) return 'check'
  if (i === 0 && /코스피|코스닥|환율|지수/.test(p)) return 'index'
  if (/상위|상승 종목|하락 종목|개 중/.test(p)) return 'movers'
  if (/업종/.test(p)) return 'sector'
  return null
}

const sentences = (s: string) => s.split(/(?<=[.!?])\s+/).map((x) => x.trim()).filter(Boolean)

const PCT = /([+\-−]\d[\d,]*(?:\.\d+)?%)/g
const dir = (v: string) => (/^[-−]/.test(v) ? 'is-down' : 'is-up')

function marks(text: string): ReactNode[] {
  return text.split(PCT).map((part, i) =>
    i % 2 === 1 ? <b key={i} className={dir(part)}>{part}</b> : part)
}

/* 이름 바로 뒤에 등락이 붙은 것만 칩이 된다. "평균 +4.21%" · "각각 -2.32%" 처럼
   앞말이 이름이 아닌 것은 걸러 낸다 — 종목이 아닌 것을 종목처럼 보이면 안 된다 */
const NOT_NAME = /^(평균|각각|대비|전일|1영업일|5영업일|수익률|등락률|시장|지수)$/
function movers(p: string) {
  const up: [string, string][] = []
  const down: [string, string][] = []
  for (const m of p.matchAll(/([A-Za-z0-9가-힣&]{2,})\s*([+\-−]\d+(?:\.\d+)?%)/g)) {
    const name = m[1].replace(/(은|는|이|가|도|와|과)$/, '')
    if (NOT_NAME.test(name) || /^\d/.test(name)) continue
    ;(dir(m[2]) === 'is-up' ? up : down).push([name, m[2]])
  }
  return { up, down }
}

const num = (v: string) => Number(v.replace(/[,%−]/g, (c) => (c === '−' ? '-' : '')))

/* 지수 카드. 한 문장에 지수 하나일 때만 읽는다 — "코스피와 코스닥은 각각" 은 어느 값이
   어느 지수 것인지 글자로 가를 수 없어 건너뛴다 */
type IndexCard = { name: string; close: string; d1: string | null; d5: string | null }
function indices(p: string): IndexCard[] {
  const out: IndexCard[] = []
  for (const s of sentences(p)) {
    const names = s.match(/코스피|코스닥/g) ?? []
    if (new Set(names).size !== 1) continue
    const close = s.match(/(?:코스피|코스닥)[은는이가]?\s*(\d[\d,]*(?:\.\d+)?)/)?.[1]
    if (!close) continue
    const d1 = s.match(/1영업일\s*([+\-−]\d+(?:\.\d+)?%)/)?.[1] ?? null
    const d5 = s.match(/5영업일\s*([+\-−]\d+(?:\.\d+)?%)/)?.[1] ?? null
    if (!d1 && !d5) continue
    const [int, dec] = close.replace(/,/g, '').split('.')
    out.push({ name: names[0] ?? '', close: `${Number(int).toLocaleString('ko-KR')}${dec ? `.${dec}` : ''}`, d1, d5 })
  }
  return out
}

/* 업종 막대. 두 꼴만 읽는다 — "의료·정밀기기가 평균 +4.21%" · "기계·장비와 전기·가스도 각각 평균 -2.32%, -2.24%" */
function sectors(p: string): [string, number, string][] {
  const found = new Map<string, string>()
  for (const m of p.matchAll(/([가-힣A-Za-z·]+?)(?:와|과)\s*([가-힣A-Za-z·]+?)(?:도|는|은|이|가)?\s*각각\s*평균\s*([+\-−]\d+(?:\.\d+)?%)\s*,\s*([+\-−]\d+(?:\.\d+)?%)/g)) {
    found.set(m[1], m[3]); found.set(m[2], m[4])
  }
  for (const m of p.matchAll(/([가-힣A-Za-z·]+?)(?:이|가|은|는|도)\s*평균\s*([+\-−]\d+(?:\.\d+)?%)/g)) {
    if (!found.has(m[1])) found.set(m[1], m[2])
  }
  const rows = [...found].map(([k, v]) => [k, num(v), v] as [string, number, string])
  return rows.length >= 2 ? rows.sort((a, b) => b[1] - a[1]) : []
}

/* "수집 종목 299개 중 하락은 202개로 상승 97개보다" — 셋이 다 있고 합이 맞을 때만 */
function breadth(p: string) {
  const total = p.match(/(\d[\d,]*)개 중/)?.[1]
  const down = p.match(/하락[은는이가]?\s*(\d[\d,]*)개/)?.[1]
  const up = p.match(/상승[은는이가]?\s*(\d[\d,]*)개/)?.[1]
  if (!up || !down) return null
  const u = Number(up.replace(/,/g, ''))
  const d = Number(down.replace(/,/g, ''))
  const t = total ? Number(total.replace(/,/g, '')) : u + d
  if (u + d === 0 || u + d > t) return null
  return { u, d, t }
}

function Icon({ kind }: { kind: Kind }) {
  const c = { width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true }
  if (kind === 'index') return <svg {...c}><path d="M3 17l6-6 4 4 8-8" /><path d="M14 7h7v7" /></svg>
  if (kind === 'sector') return <svg {...c}><rect x="3" y="3" width="7" height="7" rx="1.5" /><rect x="14" y="3" width="7" height="7" rx="1.5" /><rect x="3" y="14" width="7" height="7" rx="1.5" /><rect x="14" y="14" width="7" height="7" rx="1.5" /></svg>
  if (kind === 'movers') return <svg {...c}><path d="M7 17V7" /><path d="M4 10l3-3 3 3" /><path d="M17 7v10" /><path d="M14 14l3 3 3-3" /></svg>
  return <svg {...c}><path d="M9 18h6" /><path d="M10 21h4" /><path d="M12 3a6 6 0 0 0-3.5 10.9c.6.4 1 1.1 1 1.8V16h5v-.3c0-.7.4-1.4 1-1.8A6 6 0 0 0 12 3z" /></svg>
}

export default function BriefingBody({ paragraphs }: { paragraphs: string[] }) {
  const n = paragraphs.length
  return (
    <div className="bfb">
      {paragraphs.map((p, i) => {
        const kind = classify(p, i, n)
        const ss = sentences(p)

        if (kind === 'check') {
          return (
            <aside key={i} className="bfb-check">
              <span className="bfb-check-icon"><Icon kind="check" /></span>
              <div>
                <p className="bfb-label">{LABEL.check}</p>
                <p className="bfb-check-text">{marks(p)}</p>
              </div>
            </aside>
          )
        }

        const mv = kind === 'movers' ? movers(p) : null
        const br = kind === 'movers' ? breadth(p) : null
        const ix = kind === 'index' ? indices(p) : []
        const sc = kind === 'sector' ? sectors(p) : []
        const hasMv = !!mv && (mv.up.length > 0 || mv.down.length > 0)
        const visual = ix.length > 0 || sc.length > 0 || !!br || hasMv
        const scMax = Math.max(...sc.map((r) => Math.abs(r[1])), 0.01)
        const mvMax = mv ? Math.max(...[...mv.up, ...mv.down].map(([, v]) => Math.abs(num(v))), 0.01) : 1
        return (
          <section key={i} className={`bfb-sec${kind ? ` is-${kind}` : ''}`}>
            {kind && (
              <p className="bfb-label">
                <span className="bfb-label-icon"><Icon kind={kind} /></span>{LABEL[kind]}
                {sc.length > 0 && <span className="bfb-label-note">브리핑에 나온 업종</span>}
              </p>
            )}

            {ix.length > 0 && (
              <div className="bfb-idx">
                {ix.map((c) => (
                  <div key={c.name} className="bfb-idx-card">
                    <span className="bfb-idx-name">{c.name}</span>
                    <strong className="bfb-idx-close">{c.close}</strong>
                    <span className="bfb-idx-chg">
                      {c.d1 && <span><em>1일</em><b className={dir(c.d1)}>{c.d1}</b></span>}
                      {c.d5 && <span><em>5일</em><b className={dir(c.d5)}>{c.d5}</b></span>}
                    </span>
                  </div>
                ))}
              </div>
            )}

            {sc.length > 0 && (
              <ul className="bfb-sectors">
                {sc.map(([name, v, raw]) => (
                  <li key={name}>
                    <span className="bfb-sc-name">{name}</span>
                    <span className="bfb-sc-track">
                      <span className={`bfb-sc-bar ${v < 0 ? 'is-down' : 'is-up'}`} style={{ width: `${(Math.abs(v) / scMax) * 50}%` }} />
                    </span>
                    <b className={`bfb-sc-val ${dir(raw)}`}>{raw}</b>
                  </li>
                ))}
              </ul>
            )}

            {!visual && <p className="bfb-lead">{marks(ss[0] ?? '')}</p>}
            {!visual && ss.length > 1 && <p className="bfb-rest">{marks(ss.slice(1).join(' '))}</p>}

            {br && (
              <div className="bfb-breadth" aria-label={`상승 ${br.u}개, 하락 ${br.d}개`}>
                <div className="bfb-breadth-bar">
                  <span className="is-up" style={{ flexGrow: br.u }} />
                  <span className="is-down" style={{ flexGrow: br.d }} />
                </div>
                <div className="bfb-breadth-legend">
                  <span className="is-up">상승 {br.u.toLocaleString('ko-KR')}</span>
                  {br.t > br.u + br.d && <span>보합 {(br.t - br.u - br.d).toLocaleString('ko-KR')}</span>}
                  <span className="is-down">하락 {br.d.toLocaleString('ko-KR')}</span>
                </div>
              </div>
            )}

            {mv && hasMv && (
              <div className="bfb-rank">
                {([['상승 상위', mv.up, 'is-up'], ['하락 상위', mv.down, 'is-down']] as const).map(([head, list, cls]) => list.length > 0 && (
                  <div key={head} className={`bfb-rank-col ${cls}`}>
                    <p className="bfb-rank-head">{head}</p>
                    <ol>
                      {list.map(([name, v], k) => (
                        <li key={name}>
                          <span className="bfb-rank-no">{k + 1}</span>
                          <span className="bfb-rank-name">{name}</span>
                          <span className="bfb-rank-track"><span style={{ width: `${(Math.abs(num(v)) / mvMax) * 100}%` }} /></span>
                          <b>{v}</b>
                        </li>
                      ))}
                    </ol>
                  </div>
                ))}
              </div>
            )}

            {/* 그림은 글에서 뽑은 것이다. 근거인 원문을 밑에 남긴다 */}
            {visual && <p className="bfb-caption">{marks(p)}</p>}
          </section>
        )
      })}
    </div>
  )
}
