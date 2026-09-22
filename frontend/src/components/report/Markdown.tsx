/* 리포트 본문 렌더러. F-02 상세와 F-03 작성 미리보기가 같은 것을 쓴다.

   왜 같은 컴포넌트인가 — 쓰는 사람이 미리보기에서 본 것과 발행 뒤 화면이 다르면
   미리보기가 거짓말이 된다. 규칙은 한 군데에만 있어야 한다.

   ■ 안전
   react-markdown 은 HTML 문자열을 **만들지 않는다.** 마크다운을 파싱해 React 엘리먼트를
   바로 빚으므로 dangerouslySetInnerHTML 이 없고, 본문에 <script> 를 적어도 글자로 나온다.
   rehype-raw 같은 원시 HTML 플러그인을 붙이는 순간 그 성질이 사라지므로 붙이지 않는다.
   링크 스킴도 react-markdown 기본 허용 목록(http·https·mailto 등)에 맡긴다 —
   javascript: 는 거기서 막힌다.

   ■ 줄바꿈
   remark-breaks 를 넣는다. 마크다운 본래 규칙은 홑 줄바꿈을 무시하고 문단을 뭉치는데,
   이 서비스의 본문은 원래 white-space:pre-wrap 으로 그려지던 평문이다. 그대로 뭉치면
   **기존 리포트와 기존 작성 습관이 한 덩어리가 된다.** 엔터를 친 대로 보이는 쪽이 맞다.

   ■ 무게
   react-markdown 묶음이 작지 않아 **지연 로딩**한다. 리포트 화면에 들어온 사람만 받는다 —
   홈·랭킹까지 이 무게를 지고 갈 이유가 없다. */
import { Suspense, lazy } from 'react'
import '../../styles/markdown.css'

/* 세 묶음을 한 청크로 함께 받는다. 따로 쪼개면 왕복만 늘고, 어차피 셋이 같이 쓰인다. */
const Renderer = lazy(async () => {
  const [{ default: ReactMarkdown }, { default: remarkGfm }, { default: remarkBreaks }] =
    await Promise.all([import('react-markdown'), import('remark-gfm'), import('remark-breaks')])

  return {
    default: ({ text }: { text: string }) => (
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkBreaks]}
        components={{
          /* 바깥으로 나가는 링크는 새 탭으로. rel 은 새 탭이 이 페이지를 조작하지 못하게 막는다 —
             본문을 쓴 사람이 우리 쪽 사람이 아닐 수 있다. */
          /* react-markdown 은 컴포넌트마다 hast 노드를 `node` 로 넘긴다. 그대로 펼치면
             DOM 에 node="[object Object]" 속성이 찍히므로 셋 다 빼고 넘긴다 */
          a: ({ node: _node, children, ...props }) => (
            <a {...props} target="_blank" rel="noreferrer noopener nofollow">{children}</a>
          ),
          /* 이미지는 본문 폭을 넘지 않게 하고 늦게 불러온다. alt 가 없으면 빈 문자열로 둔다 —
             react-markdown 이 undefined 를 넘기면 스크린리더가 파일 주소를 읽는다. */
          img: ({ node: _node, alt, ...props }) => (
            <img {...props} alt={alt ?? ''} loading="lazy" className="md-img" />
          ),
          /* 표가 좁은 화면을 밀어내지 않게 가로 스크롤 상자에 담는다 */
          table: ({ node: _node, children, ...props }) => (
            <div className="md-table-wrap"><table {...props}>{children}</table></div>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    ),
  }
})

export default function Markdown({ text, className }: { text: string; className?: string }) {
  return (
    <div className={className ? `md ${className}` : 'md'}>
      {/* 받는 동안 원문을 그대로 보여 준다. 빈 화면을 두면 글이 사라진 것처럼 보이고,
          평문으로 읽어도 뜻은 통한다 — 마크다운은 원래 그러라고 만든 문법이다. */}
      <Suspense fallback={<div className="md-plain">{text}</div>}>
        <Renderer text={text} />
      </Suspense>
    </div>
  )
}
