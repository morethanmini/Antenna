/* B-02 종목 탐색 · /stocks
   담당 스토리 [ANT-FE-STOCKS]

   2026-09-11 개편 — **히트맵이 첫 화면이다.** 전에는 열 줄짜리 목록에 "더 보기"
   였는데, 그 화면으로는 "오늘 시장이 어디로 움직였나" 를 알 수 없었다. 열 줄을
   읽고 또 열 줄을 부르는 동안 전체 그림은 끝내 안 나온다. 한국경제 marketmap 을
   참고해 전 종목을 한 판에 깔고, 섹터를 눌러 파고드는 방식으로 바꿨다.

   목록을 없애지는 않았다 — PER 범위·예측 방향처럼 히트맵으로는 못 거는 조건이
   있고, 종목별 PER·PBR·예측 현황을 숫자로 나란히 보는 일은 목록이 낫다.
   탭으로 나란히 둔다.

   필터 상태(filter)는 이 페이지가 들고 두 보기가 나눠 쓴다. 히트맵은 그중
   관심 여부만 쓰고 누르는 즉시 적용하며, 나머지 조건과 "필터 적용" 버튼은
   목록 보기 안에 있다(StockTable). */
import { useMemo, useState } from 'react'
import { EMPTY_FILTER } from '../api/stocks'
import type { StockFilter } from '../api/stocks'
import MarketMap, { HeatLegend } from '../components/stocks/MarketMap'
import StockTable from '../components/stocks/StockTable'
import '../styles/screens/stocks.css'

type View = 'map' | 'list'

const VIEW_LABEL: Record<View, string> = { map: '히트맵', list: '목록' }

export default function Stocks() {
  const [filter, setFilter] = useState<StockFilter>(EMPTY_FILTER)
  const [view, setView] = useState<View>('map')

  /* 히트맵에는 조건을 **추려서** 넘긴다.

     섹터는 판이 스스로 고르는 축이라 목록 탭에서 걸어 둔 섹터 필터를 그대로
     물려주면 덩어리가 하나뿐인 판이 나온다. PER 범위·예측 방향도 목록 전용이다 —
     시장 전체를 깔아 보려고 들어온 화면이 앞 탭에 걸어 둔 조건 때문에 텅 비면
     안 된다. 히트맵이 쓰는 것은 관심 여부뿐이다.

     참조가 고정돼야 MarketMap 안의 useBlock 이 매 렌더 다시 부르지 않는다. */
  const mapFilter = useMemo<StockFilter>(
    () => ({ sort: filter.sort, watchedOnly: filter.watchedOnly }),
    [filter.sort, filter.watchedOnly],
  )

  return (
    <main className="main">
      {/* 히트맵일 때만 페이지를 세로 flex 로 세운다 — 판이 남는 높이를 모두 먹게
          하려는 것이고, 목록 탭에는 그럴 이유가 없다(stocks.css 의 st-fit).
          화면 이름인 stocks-page 는 routes.ts 가 body 에 붙이므로 쓰지 않는다. */}
      <div className={`main-inner${view === 'map' ? ' st-fit' : ''}`}>
        {/* .st-hero 는 가로 flex 다 — 제목과 설명줄을 세로로 쌓으려면 이 div 로
            한 번 묶어야 한다. 풀면 둘이 나란히 선다. */}
        <header className="st-hero">
          <div>
            <h1>오늘 시장은 어디로 움직였나요?</h1>
            <p className="st-hero-sub">
              섹터별로 깔아 놓은 판에서 흐름을 먼저 보고, 종목으로 좁혀 들어가세요.
            </p>
          </div>
        </header>

        {/* 보기 전환과, 히트맵이 쓰는 조건 하나 */}
        <div className="st-views">
          <div className="st-tabs" role="tablist" aria-label="보기 방식">
            {(Object.keys(VIEW_LABEL) as View[]).map((v) => (
              <button
                key={v} type="button" role="tab" id={`st-tab-${v}`}
                className={`st-tab${view === v ? ' on' : ''}`}
                aria-selected={view === v} aria-controls={`st-view-${v}`}
                onClick={() => setView(v)}
              >
                {VIEW_LABEL[v]}
              </button>
            ))}
          </div>

          {/* 시장(전체·KOSPI·KOSDAQ) 칩은 뺐다(2026-09-11) — 이 서비스는 코스피만
              다룬다. 고를 것이 하나뿐인 필터는 자리만 먹고, KOSDAQ 을 눌러 빈
              화면을 보는 헛걸음을 만든다.

              관심 종목만은 히트맵에서만 내놓는다. 목록에는 제 필터 패널이 따로
              있어, 두 벌이 되면 어느 쪽이 이겼는지 알 수 없다 */}
          {view === 'map' && (
            <>
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
            </>
          )}
        </div>

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
