/* B-02 종목 탐색 · /stocks
   담당 스토리 [ANT-FE-STOCKS]

   보기가 둘이다. **목록이 첫 화면이고**(2026-09-14), 마켓맵은 그 옆 탭이다.

   마켓맵은 2026-09-11에 들였다 — 열 줄짜리 목록에 "더 보기" 뿐이던 화면으로는
   "오늘 시장이 어디로 움직였나" 를 알 수 없었다. 열 줄을 읽고 또 열 줄을 부르는
   동안 전체 그림은 끝내 안 나온다. 한국경제 marketmap 을 참고해 전 종목을 한 판에
   깔고, 섹터를 눌러 파고들 수 있게 했다.

   그래도 들어오면 목록이 먼저다. PER 범위·예측 방향처럼 마켓맵으로는 못 거는
   조건이 있고, 종목별 PER·PBR·예측 현황을 숫자로 나란히 보는 일은 목록이 낫다.
   판을 보려는 사람은 탭 하나만 누르면 된다.

   필터 상태(filter)는 이 페이지가 들고 두 보기가 나눠 쓴다. 마켓맵은 그중
   관심 여부만 쓰고 누르는 즉시 적용하며, 나머지 조건과 "필터 적용" 버튼은
   목록 보기 안에 있다(StockTable). */
import { useMemo, useState } from 'react'
import { EMPTY_FILTER } from '../api/stocks'
import type { StockFilter } from '../api/stocks'
import MarketMap, { HeatLegend } from '../components/stocks/MarketMap'
import StockTable from '../components/stocks/StockTable'
import '../styles/screens/stocks.css'

/**
 * 보기 전환 탭. **적히는 차례대로** 화면에 선다(2026-09-14에 목록을 앞으로 옮겼다).
 *
 * 객체의 키 순서에 기대지 않고 배열로 두는 이유 — 순서가 화면에 그대로 드러나는
 * 값이라, 나중에 누가 키를 정렬하거나 타입을 손봤을 때 조용히 뒤바뀌면 안 된다.
 */
const VIEWS = [
  { key: 'list', label: '목록' },
  { key: 'map', label: '마켓맵' },
] as const

type View = (typeof VIEWS)[number]['key']

/** 들어왔을 때 켜져 있는 탭. 첫 칸과 같아야 한다 — 두 번째 탭이 켜진 채로 열리면
 *  사용자가 누르지도 않은 것이 눌려 있는 꼴이 된다 */
const FIRST_VIEW: View = VIEWS[0].key

export default function Stocks() {
  const [filter, setFilter] = useState<StockFilter>(EMPTY_FILTER)
  const [view, setView] = useState<View>(FIRST_VIEW)

  /* 마켓맵에는 조건을 **추려서** 넘긴다.

     섹터는 판이 스스로 고르는 축이라 목록 탭에서 걸어 둔 섹터 필터를 그대로
     물려주면 덩어리가 하나뿐인 판이 나온다. PER 범위·예측 방향도 목록 전용이다 —
     시장 전체를 깔아 보려고 들어온 화면이 앞 탭에 걸어 둔 조건 때문에 텅 비면
     안 된다. 마켓맵이 쓰는 것은 관심 여부뿐이다.

     참조가 고정돼야 MarketMap 안의 useBlock 이 매 렌더 다시 부르지 않는다. */
  const mapFilter = useMemo<StockFilter>(
    () => ({ sort: filter.sort, watchedOnly: filter.watchedOnly }),
    [filter.sort, filter.watchedOnly],
  )

  return (
    <main className="main">
      {/* 마켓맵일 때만 페이지를 세로 flex 로 세운다 — 판이 남는 높이를 모두 먹게
          하려는 것이고, 목록 탭에는 그럴 이유가 없다(stocks.css 의 st-fit).
          화면 이름인 stocks-page 는 routes.ts 가 body 에 붙이므로 쓰지 않는다. */}
      <div className={`main-inner${view === 'map' ? ' st-fit' : ''}`}>
        {/* 설명줄을 뺐다(2026-09-14). 제목이 이미 "오늘 시장은 어디로 움직였나요?"
            라, 판을 보고 종목으로 좁히라는 말을 한 줄 더 적을 이유가 없다.
            감싸던 div 도 함께 푼다 — 제목 하나뿐이면 세로로 쌓을 것이 없다. */}
        {/* 보기 전환 탭은 **제목과 같은 줄 오른쪽 끝**이다(2026-09-14). 제목 아래
            제 줄을 쓰고 있었는데, 그 줄 오른쪽이 늘 비어 판이 그만큼 내려갔다.
            .st-hero 가 space-between 이라 여기 두면 저절로 오른쪽 끝에 선다. */}
        <header className="st-hero">
          <h1>오늘 시장은 어디로 움직였나요?</h1>
          <div className="st-viewtabs" role="tablist" aria-label="보기 방식">
            {VIEWS.map((v) => (
              <button
                key={v.key} type="button" role="tab" id={`st-tab-${v.key}`}
                className={`st-viewtab${view === v.key ? ' on' : ''}`}
                aria-selected={view === v.key} aria-controls={`st-view-${v.key}`}
                onClick={() => setView(v.key)}
              >
                {v.label}
              </button>
            ))}
          </div>
        </header>

        {/* 마켓맵이 쓰는 조건 줄. **목록 탭에는 아예 내지 않는다** — 빈 상자를
            두면 margin 만큼 아래가 밀린다.

            시장(전체·KOSPI·KOSDAQ) 칩은 뺐다(2026-09-11) — 이 서비스는 코스피만
            다룬다. 고를 것이 하나뿐인 필터는 자리만 먹고, KOSDAQ 을 눌러 빈
            화면을 보는 헛걸음을 만든다.

            관심 종목만은 마켓맵에서만 내놓는다. 목록에는 제 필터 패널이 따로
            있어, 두 벌이 되면 어느 쪽이 이겼는지 알 수 없다 */}
        {view === 'map' && (
          <div className="st-views">
            <label className="st-check st-views-check">
              <input
                type="checkbox" checked={filter.watchedOnly ?? false}
                onChange={(e) => setFilter((f) => ({
                  ...f, watchedOnly: e.target.checked || undefined,
                }))}
              />
              관심 종목만
            </label>

            {/* 범례가 판 위에 제 줄을 하나 쓰고 있었다(2026-09-11). 그 줄은 왼쪽이
                늘 비어 있어 토글과 판 사이에 빈 띠만 만들었다. 같은 줄 오른쪽
                끝으로 옮겨 줄 하나를 줄이고, 그만큼 판이 높아졌다. */}
            <HeatLegend />
          </div>
        )}

        <div
          className="st-view"
          id={`st-view-${view}`} role="tabpanel" aria-labelledby={`st-tab-${view}`}
          /* 두 보기가 같은 자리에 오지만 서로 다른 컴포넌트다. key 를 나눠 두어
             탭을 옮길 때 훅 상태가 섞이지 않게 한다 */
          key={view}
        >
          {view === 'map'
            ? <MarketMap filter={mapFilter} />
            : <StockTable filter={filter} setFilter={setFilter} />}
        </div>
      </div>
    </main>
  )
}
