/* 종목 로고.
   B-02 목록과 B-01 홈 관심 종목이 같은 배지를 따로 들고 있었다. 로고 이미지가
   들어오면서 규칙이 둘로 갈리면 안 되므로 여기로 모았다(2026-09-15) —
   원래 두 파일의 주석에도 "세 번째 화면이 필요해지면 공용으로 뽑는다" 고 적어
   두었던 자리다.

   **서버가 로고 주소를 안 내려 준다.** StockListItem 에 칸이 없고 Stock 엔티티
   에도 없다. 그래서 종목코드로 파일을 찾는다 — public/assets/stock-logo/<code>.png.
   시가총액 상위 50 종목만 넣어 두었고 나머지는 파일이 없다.

   없는 종목은 <img> 가 실패하고, **밑에 깔아 둔 글자 배지**가 그대로 드러난다.
   글자를 뒤가 아니라 아래에 두는 것은 일부러다 — 이미지가 뜰 때까지 빈 네모가
   보이면 줄이 깜빡이는 것처럼 읽힌다.

   "로고가 있는 코드 목록" 을 코드에 박지 않았다. 파일을 더 넣거나 빼는 순간
   그 목록과 폴더가 조용히 어긋나기 때문이다. 대신 로고가 없는 종목마다 404 가
   한 번 난다 — 한 화면에 열 줄이라 열 번이고, 브라우저가 캐시한다.

   색은 종목코드에서 뽑아 늘 같은 색이 나온다(무작위가 아니다). */
import { useState } from 'react'

/** 로고 파일이 사는 곳. 파일 이름은 종목코드 여섯 자리다 */
const SRC = (code: string) => `/assets/stock-logo/${code}.png`

type Props = {
  code: string
  name: string
  /** 화면마다 크기·모서리가 달라 배지 클래스는 부르는 쪽이 준다(st-logo · hm-logo) */
  className: string
}

export default function StockLogo({ code, name, className }: Props) {
  const [failed, setFailed] = useState(false)

  let h = 0
  for (const ch of code) h = (h * 31 + ch.charCodeAt(0)) % 360

  return (
    /* 이름이 바로 옆에 있으므로 배지는 읽어 줄 것이 없다 */
    <span
      className={`stock-logo ${className}`} aria-hidden="true"
      style={{ background: `hsl(${h} 62% 92%)`, color: `hsl(${h} 54% 36%)` }}
    >
      {name.slice(0, 1)}
      {!failed && (
        <img
          src={SRC(code)} alt="" loading="lazy" decoding="async"
          onError={() => setFailed(true)}
        />
      )}
    </span>
  )
}
