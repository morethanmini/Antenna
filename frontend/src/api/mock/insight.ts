/* 목업 응답. 백엔드에 조회 API 가 붙으면 이 파일을 지운다.

   값은 명세서의 응답 스키마를 그대로 따른다 — 나중에 실제 응답으로 바꿔도
   화면 코드가 그대로 돌아가는 게 목적이다. 그래서 스키마에 없는 필드는
   보기 좋더라도 넣지 않는다(예: 종목의 "현재가" — 실전은 전일 종가만이다).

   지연을 조금 주는 이유: 로딩 상태를 화면에서 실제로 보게 하려는 것이다. */
import type {
  ActiveAd, IndexQuote, WalletBalance, WatchlistRow,
} from '../insight'

const delay = <T,>(value: T, ms = 320) =>
  new Promise<T>((resolve) => setTimeout(() => resolve(value), ms))

/** 시드 기반 의사난수 — 새로고침해도 같은 그래프가 나오게 한다 */
function walk(seed: number, n: number, start: number, drift: number) {
  let v = start
  let s = seed
  const out: number[] = []
  for (let i = 0; i < n; i++) {
    s = (s * 1103515245 + 12345) % 2147483648
    const noise = (s / 2147483648 - 0.5) * drift
    v = Math.max(start * 0.7, v * (1 + noise) + start * 0.0004 * (i % 7 === 0 ? 1 : -0.4))
    out.push(Math.round(v * 100) / 100)
  }
  return out
}

export const marketIndices = () => delay<{ items: IndexQuote[] }>({
  items: [
    { code: 'KOSPI', close: 2663.33, changeRate: 0.70, series: walk(11, 30, 2610, 0.012) },
    { code: 'KOSDAQ', close: 842.67, changeRate: 0.73, series: walk(23, 30, 830, 0.014) },
    { code: 'USDKRW', close: 1363.20, changeRate: -0.25, series: walk(37, 30, 1372, 0.006) },
  ],
})

export const watchlist = () => delay<{ items: WatchlistRow[] }>({
  items: [
    { stockCode: '005930', name: '삼성전자', prevClose: 78600, changeRate: 1.68, series: walk(101, 30, 76800, 0.014) },
    { stockCode: '000660', name: 'SK하이닉스', prevClose: 188700, changeRate: 1.70, series: walk(103, 30, 183000, 0.019) },
    { stockCode: '035420', name: 'NAVER', prevClose: 205000, changeRate: -0.48, series: walk(107, 30, 208000, 0.013) },
    { stockCode: '247540', name: '에코프로비엠', prevClose: 167200, changeRate: -1.36, series: walk(109, 30, 174000, 0.024) },
    { stockCode: '005380', name: '현대차', prevClose: 242000, changeRate: 0.83, series: walk(113, 30, 238000, 0.011) },
  ],
})

/* 홈 배너는 여러 장을 돌려 보여주므로 목업도 여러 장 준다.
   스키마에 문구가 없어(이미지·링크뿐) 장마다 그림만 달라진다. */
export const activeAds = () => delay<{ items: ActiveAd[] }>({
  items: [
    { id: 1, imageUrl: '/assets/character/white_ant/antenna-character-transparent.png', linkUrl: '/ads/new' },
    { id: 2, imageUrl: '/assets/character/black_ant/antenna-character-black.png', linkUrl: '/ads/new' },
    { id: 3, imageUrl: '/assets/character/white_ant/antenna-profile.png', linkUrl: '/ads/new' },
  ],
})

export const walletBalance = () => delay<WalletBalance>({
  balance: 1250,
  spent30d: 320,
  earned30d: 850,
  valuationKrw: 1213750,
})
