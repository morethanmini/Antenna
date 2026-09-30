/* 리포트 본문 목차. F-02 상세에서 본문 오른쪽에 붙는다.

   ■ 목차를 원문에서 뽑는 이유
   본문 렌더러(Markdown)는 지연 로딩이라 언제 DOM 에 제목이 생기는지 이 컴포넌트가 모른다.
   원문 줄에서 ATX 제목(#·##·###)을 읽으면 렌더를 기다리지 않아도 된다.
   제목 요소의 id 는 렌더러가 **원문 줄 번호**로 붙이고(headingId), 여기서도 같은 줄 번호로
   가리키므로 둘이 따로 놀지 않는다. 밑줄식(=== ---) 제목은 목차에서 빠질 뿐 본문은 그대로다.

   ■ 지금 읽는 섹션
   IntersectionObserver 대신 스크롤 때마다 제목 위치를 잰다 — 옵저버는 대상 요소가
   있어야 걸 수 있는데, 지연 로딩 때문에 처음엔 없다. 제목 수가 많지 않아 비용은 작다. */
import { useEffect, useState } from 'react'
import type { TocItem } from './outline'

/* 제목 위쪽이 이 선을 넘으면 "읽는 중" 이다. 네비바(72px) 바로 아래쯤 */
const READ_LINE = 120

export default function ReportToc({ items }: { items: TocItem[] }) {
  const [active, setActive] = useState<string | null>(null)

  useEffect(() => {
    let frame = 0
    const measure = () => {
      frame = 0
      // 바닥까지 내려왔으면 화면에 들어온 마지막 제목이 읽는 중이다 — 마지막 섹션이 짧으면
      // 그 제목은 페이지가 끝나서 READ_LINE 까지 올라오지 못한다
      const atBottom = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2
      const line = atBottom ? window.innerHeight : READ_LINE
      let current: string | null = null
      for (const it of items) {
        const el = document.getElementById(it.id)
        if (el && el.getBoundingClientRect().top <= line) current = it.id
      }
      setActive(current ?? items[0]?.id ?? null)
    }
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(measure) }

    measure()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
      if (frame) cancelAnimationFrame(frame)
    }
  }, [items])

  const go = (e: React.MouseEvent, id: string) => {
    const el = document.getElementById(id)
    if (!el) return
    // 주소창 해시를 바꾸지 않는다 — 라우터 경로에 #sec-12 가 붙어 공유 링크가 지저분해진다
    e.preventDefault()
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
    setActive(id)
  }

  return (
    <nav className="rd-toc" aria-label="리포트 목차">
      <p className="rd-toc-title">목차</p>
      <ol>
        {items.map((it) => (
          <li key={it.id} className={it.depth ? 'is-sub' : undefined}>
            <a
              href={`#${it.id}`}
              title={it.text}
              className={active === it.id ? 'is-active' : undefined}
              aria-current={active === it.id ? 'location' : undefined}
              onClick={(e) => go(e, it.id)}
            >
              {it.text}
            </a>
          </li>
        ))}
      </ol>
    </nav>
  )
}
