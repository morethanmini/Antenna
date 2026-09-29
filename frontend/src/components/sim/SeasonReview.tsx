/* G-08 AI 복기 본문. 서버가 준 글자 한 덩어리를 네 단락 카드로 나눠 그린다.

   ■ 단락 규칙은 서버 프롬프트가 정한다
   SeasonReviewService.INSTRUCTION — "정확히 네 단락. 각 단락은 '시장 흐름:', '잘한 판단:',
   '아쉬운 판단:', '개선 제안:' 으로 시작한다", 단락 사이는 빈 줄(API 명세 v0.83).
   이 머리말로 단락을 알아본다. 예전 세 단락 복기(시장 흐름 없음)도 같은 머리말이라 그대로 된다.
   머리말을 하나도 못 찾으면 **원문을 그대로** 보여 준다 — 모델이 형식을 어겨도 글은 읽혀야 한다.

   ■ 카드마다
   - 첫 문장은 굵게 올린다. 1,200~1,500자 줄글에서 단락이 무엇을 말하는지 첫 줄만 읽어도 알게.
   - 개선 제안은 문장마다 번호 카드로 — "해 볼 행동 두세 가지" 로 만들라고 시킨 단락이다.
   - 글 안의 D+n 은 칩, 부호 달린 %·금액은 등락 색(국내 관례 빨강/파랑), 그냥 금액은 굵게.
   모두 글자를 나눠 React 로 감쌀 뿐이다 — HTML 을 만들지 않는다. */
import type { ReactNode } from 'react'

type Kind = 'market' | 'good' | 'miss' | 'next'

const SECTIONS: { kind: Kind; label: string; hint: string }[] = [
  { kind: 'market', label: '시장 흐름', hint: '이번 판에 시장은 이렇게 움직였어요' },
  { kind: 'good', label: '잘한 판단', hint: '이어 가면 좋은 선택' },
  { kind: 'miss', label: '아쉬운 판단', hint: '다르게 해 볼 수 있었던 선택' },
  { kind: 'next', label: '개선 제안', hint: '다음 판에 해 볼 것' },
]

const HEAD = new RegExp(`^\\s*(${SECTIONS.map((s) => s.label).join('|')})\\s*[:：]\\s*`)

function parse(review: string) {
  const out: { kind: Kind; text: string }[] = []
  for (const para of review.split(/\n\s*\n/)) {
    const m = para.match(HEAD)
    const text = para.slice(m ? m[0].length : 0).trim()
    if (m) out.push({ kind: SECTIONS.find((s) => s.label === m[1])!.kind, text })
    // 머리말 없는 단락은 앞 단락에 붙인다 — 모델이 한 단락 안에서 빈 줄을 한 번 더 넣는 때가 있다
    else if (out.length && text) out[out.length - 1].text += `\n${text}`
  }
  return out
}

/* 문장 나누기. "0.049%" 처럼 마침표 뒤에 공백이 없으면 문장 끝이 아니다 */
const sentences = (s: string) => s.split(/(?<=[.!?])\s+/).map((x) => x.trim()).filter(Boolean)

/* 글 안의 숫자 표시 */
const TOKEN = /(D\+\d+(?:\s*~\s*D\+\d+)?)|([+\-−]\d[\d,]*(?:\.\d+)?%)|([+\-−]?\d[\d,]*원)/g

/* 모델이 30014733원 처럼 쉼표 없이 쓸 때가 있다. 다섯 자리 이상이면 쉼표를 넣어 읽히게 한다 */
const comma = (won: string) => won.replace(/\d{5,}/, (d) => Number(d).toLocaleString('ko-KR'))
const sign = (v: string) => (/^[-−]/.test(v) ? 'is-down' : /^\+/.test(v) ? 'is-up' : '')

function marks(text: string): ReactNode[] {
  const out: ReactNode[] = []
  let last = 0
  for (const m of text.matchAll(TOKEN)) {
    const i = m.index ?? 0
    if (i > last) out.push(text.slice(last, i))
    if (m[1]) out.push(<span key={i} className="rv-day">{m[1].replace(/\s+/g, '')}</span>)
    else if (m[2]) out.push(<b key={i} className={sign(m[2])}>{m[2]}</b>)
    // 부호 달린 금액(실현손익 +110원)은 등락 색, 그냥 금액(매수가 14,250원)은 굵게만
    else out.push(<b key={i} className={sign(m[3]) || 'rv-won'}>{comma(m[3])}</b>)
    last = i + m[0].length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

function Icon({ kind }: { kind: Kind }) {
  const common = { width: 16, height: 16, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2.2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true }
  if (kind === 'market') return <svg {...common}><path d="M3 17l6-6 4 4 8-8" /><path d="M14 7h7v7" /></svg>
  if (kind === 'good') return <svg {...common}><path d="M20 6L9 17l-5-5" /></svg>
  if (kind === 'miss') return <svg {...common}><path d="M12 8v5" /><path d="M12 16.5v.5" /><circle cx="12" cy="12" r="9" /></svg>
  return <svg {...common}><path d="M9 18h6" /><path d="M10 21h4" /><path d="M12 3a6 6 0 0 0-3.5 10.9c.6.4 1 1.1 1 1.8V16h5v-.3c0-.7.4-1.4 1-1.8A6 6 0 0 0 12 3z" /></svg>
}

export default function SeasonReview({ review }: { review: string }) {
  const parts = parse(review)
  if (parts.length === 0) return <p className="rv-plain">{review}</p>

  return (
    <div className="rv">
      {parts.map(({ kind, text }, idx) => {
        const meta = SECTIONS.find((s) => s.kind === kind)!
        const ss = sentences(text.replace(/\n/g, ' '))
        return (
          <section key={idx} className={`rv-sec is-${kind}`} aria-label={meta.label}>
            <header className="rv-head">
              <span className="rv-icon"><Icon kind={kind} /></span>
              <h4>{meta.label}</h4>
              <span className="rv-hint">{meta.hint}</span>
            </header>

            {kind === 'next' ? (
              <ol className="rv-steps">
                {ss.map((s, i) => (
                  <li key={i}><span className="rv-no">{i + 1}</span><p>{marks(s)}</p></li>
                ))}
              </ol>
            ) : (
              <>
                <p className="rv-lead">{marks(ss[0] ?? '')}</p>
                {ss.length > 1 && <p className="rv-body">{marks(ss.slice(1).join(' '))}</p>}
              </>
            )}
          </section>
        )
      })}
    </div>
  )
}
