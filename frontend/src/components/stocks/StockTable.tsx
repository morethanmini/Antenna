/* 종목 목록 보기 — B-02 의 둘째 탭. 2026-09-11 에 Stocks.tsx 에서 떼어 왔다.

   마켓맵이 첫 화면이 되면서 목록은 탭 하나로 내려갔다. 파일을 나눈 것은 줄 수
   때문이 아니라 **훅 때문**이다 — 한 파일에 두면 마켓맵을 보는 동안에도 목록의
   useCursorList 가 돌아 쓰지도 않을 첫 쪽을 불러온다. 탭이 꺼지면 이 컴포넌트가
   통째로 빠지므로 그 요청이 아예 나가지 않는다.

   컨트롤은 API 파라미터와 1:1 이다(§4 B-02) — sector · sentiment · perMin/perMax ·
   hasOpenPrediction · watchedOnly · sort(5종 고정). market 만 예외로 컨트롤을 두지
   않는다: 이 서비스는 코스피만 다뤄 고를 것이 하나뿐이다.

   실전 시세는 전일 종가만이다(§7). "현재가" 라는 말을 쓰지 않고, 기준 영업일은
   행마다 반복하지 않고 머리글에 한 번 찍는다(응답 baseDate).

   행별 UP/DOWN 집계는 응답에 실려 온다 — 행마다 /sentiment 를 부르지 않는다.
   페이지 번호는 두지 않는다(커서 페이징이라 성립하지 않는다 · §9.2).
   행을 누르면 B-03 종목 상세로 간다. */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  addWatch, EMPTY_FILTER, fetchStocks, getSectorSummary, MARKET_LABEL, removeWatch,
  SORT_LABEL, STOCK_SORTS,
} from '../../api/stocks'
import type {
  SectorSummary, Sentiment, StockFilter, StockListItem, StockListMeta, StockSort,
} from '../../api/stocks'
import { useAsync } from '../../api/useAsync'
import { useCursorList } from '../../api/useCursorList'
import EmptyState from '../state/EmptyState'
import ErrorState from '../state/ErrorState'
import StockLogo from './StockLogo'

const won = (n: number) => n.toLocaleString('ko-KR')
/* 0 에는 부호를 붙이지 않고 색도 주지 않는다 — 근거는 Watchlist.tsx(B-04). */
const signed = (n: number) => `${n > 0 ? '+' : ''}${n.toFixed(2)}%`
const toneOf = (rate: number | null) =>
  rate === null || rate === 0 ? '' : rate > 0 ? 'up' : 'down'
const dotted = (iso: string) => iso.replaceAll('-', '.')
/** 값이 없는 칸. 0 으로 그리면 "PER 0 배" 로 읽힌다 */
const DASH = '—'

const SENTIMENT_LABEL: Record<Sentiment, string> = { UP: '상승 우세', DOWN: '하락 우세' }

/* 예측 심리 막대. UP 과 DOWN 이 한 줄을 나눠 갖는다 — 두 개를 따로 그리면
   합이 100 이라는 게 보이지 않는다. 비율만으로 방향을 전달하지 않도록
   우세 쪽 라벨과 퍼센트를 함께 적는다. */
function SentimentBar({ upRatio, count }: { upRatio: number | null; count: number }) {
  if (upRatio === null || count === 0) {
    return <span className="st-senti-none">예측 없음</span>
  }
  const up = upRatio >= 50
  return (
    <span className="st-senti">
      <span className="st-senti-head">
        <b className={up ? 'up' : 'down'}>
          {up ? 'UP' : 'DOWN'} {Math.round(up ? upRatio : 100 - upRatio)}%
        </b>
        <em className="num">{count}건</em>
      </span>
      <span className="st-senti-track" aria-hidden="true">
        <i className="st-senti-up" style={{ width: `${upRatio}%` }} />
        <i className="st-senti-down" style={{ width: `${100 - upRatio}%` }} />
      </span>
    </span>
  )
}

/** 칩 하나의 폭(px). CSS .st-sector-chip 의 flex-basis 와 같아야 한다 —
 *  한 쪽 넘길 거리를 셀 때 "한 칸은 남긴다" 는 계산에 쓴다 */
const CHIP_W = 148

/** 스크롤 끝 판정 여유(px). 소수점 오차로 끝에 닿고도 1px 이 남는 일이 있다 */
const EDGE_SLACK = 1

/** 값이 오기 전에 깔아 두는 자리 칸 수. 1440px 화면에서 트랙에 들어가는 만큼이다 */
const SKELETON_CHIPS = 8

/**
 * 상단 섹터 요약 칩. 누르면 섹터 필터가 즉시 바뀐다.
 *
 * **한 줄로 눕히고 좌우 버튼으로 넘긴다**(2026-09-14). 섹터가 23 개 + 전체 = 24 칸
 * 이라 접어 두면 세 줄이 되는데, 그 띠가 목록 위를 덮어 정작 종목이 화면 밖으로
 * 밀려났다. 섹터는 훑다가 하나 고르는 값이지 한눈에 다 세는 값이 아니다.
 *
 * 넘기는 것은 브라우저 스크롤에 맡긴다(scrollBy + scroll-behavior:smooth) —
 * 직접 transform 으로 옮기면 트랙패드 가로 스크롤과 키보드 포커스 이동이 제
 * 자리를 잃는다. 버튼은 그 스크롤을 대신 눌러 주는 것뿐이다.
 */
function SectorChips({ current, onPick }: {
  current: string | undefined
  onPick: (sector: string | undefined) => void
}) {
  const load = useCallback(() => getSectorSummary(), [])
  const { data, error } = useAsync(load)

  const trackRef = useRef<HTMLDivElement>(null)
  /** 양쪽에 더 갈 데가 있는지. 없으면 그 버튼을 끈다 */
  const [more, setMore] = useState({ left: false, right: false })

  const measure = useCallback(() => {
    const el = trackRef.current
    if (!el) return
    const max = el.scrollWidth - el.clientWidth
    setMore((prev) => {
      const next = { left: el.scrollLeft > EDGE_SLACK, right: el.scrollLeft < max - EDGE_SLACK }
      return prev.left === next.left && prev.right === next.right ? prev : next
    })
  }, [])

  /* 폭이 바뀌면 넘길 데가 있는지도 바뀐다. data 를 의존성에 두는 것은 칩이
     생기기 전에 재면 늘 "넘길 데 없음" 이 나오기 때문이다. */
  useEffect(() => {
    const el = trackRef.current
    if (!el) return
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [measure, data])

  const page = useCallback((dir: -1 | 1) => {
    const el = trackRef.current
    if (!el) return
    /* 보이는 만큼 넘기되 **한 칸은 남긴다** — 방금 보던 끝 칸이 다음 쪽 처음에
       남아 있어야 어디까지 봤는지 이어진다. 좁은 화면에서 음수가 되지 않게 막는다. */
    el.scrollBy({ left: dir * Math.max(CHIP_W, el.clientWidth - CHIP_W), behavior: 'smooth' })
  }, [])

  if (error) return null
  /* 아직 안 왔으면 **같은 크기의 자리**를 그린다(2026-09-14).

     null 을 돌려주고 있었는데, 그러면 이 줄이 통째로 없다가 생기면서 아래가
     104px 밀린다. 옆의 필터 카드와 목록 패널은 정적 마크업이라 먼저 그려져
     있어, 유독 이 줄만 "가장 늦게 뜬다" 로 보였다. 재 보니 실제 지연은 140ms
     남짓이고 /stocks/sectors 가 /stocks 보다 느린 것도 아니다(둘 다 80~90ms) —
     자리를 안 잡아 둔 것이 원인이었다. */
  if (!data) return <SectorChipsSkeleton />

  return (
    <div className="st-sectors">
      <SectorNav dir={-1} disabled={!more.left} onClick={() => page(-1)} />
      {/* 스크롤 상자 자체에는 tabIndex 를 주지 않는다 — 안쪽 칩이 전부 버튼이라
          탭으로 닿고, 브라우저가 포커스 간 칩을 알아서 끌어온다. 탭 정거장을
          하나 더 만들면 키보드로 지나가기만 번거로워진다. */}
      <div
        className="st-sector-track" ref={trackRef} onScroll={measure}
        role="group" aria-label="섹터별 보기"
      >
        {data.items.map((s: SectorSummary) => {
          const on = (s.sector ?? undefined) === current
          return (
            <button key={s.sector ?? 'ALL'} type="button"
                    className={`st-sector-chip${on ? ' on' : ''}`}
                    aria-pressed={on}
                    onClick={() => onPick(s.sector ?? undefined)}>
              <span className="st-sector-name">{s.sector ?? '전체 종목'}</span>
              <span className="st-sector-meta">
                <em className="num">{s.count}개</em>
                <b className={`num ${toneOf(s.changeRate)}`}>{signed(s.changeRate)}</b>
              </span>
            </button>
          )
        })}
      </div>
      <SectorNav dir={1} disabled={!more.right} onClick={() => page(1)} />
    </div>
  )
}

/**
 * 값이 오기 전의 자리.
 *
 * 빈 상자만 두지 않고 **칩 모양을 옅게 깔아 둔다** — 같은 높이의 회색 띠만
 * 있으면 그것대로 고장 난 화면처럼 보인다. 칸이 여덟인 것은 1440px 기준으로
 * 트랙에 들어가는 수다. 모자라면 오른쪽이 조금 비고 넘치면 잘릴 뿐이라,
 * 화면 폭을 재서 맞출 만한 값이 아니다.
 *
 * 읽어 줄 내용이 없으므로 보조기기에서는 감춘다. 목록 쪽이 제 로딩을 알린다.
 */
function SectorChipsSkeleton() {
  return (
    <div className="st-sectors" aria-hidden="true">
      <SectorNav dir={-1} disabled />
      <div className="st-sector-track">
        {Array.from({ length: SKELETON_CHIPS }, (_, i) => (
          <div key={i} className="st-sector-chip st-sector-skel">
            <span className="st-skel st-skel-name" />
            <span className="st-skel st-skel-meta" />
          </div>
        ))}
      </div>
      <SectorNav dir={1} disabled />
    </div>
  )
}

/** 섹터 줄 좌우 넘김 버튼. 화살표는 MarketMap 의 "전체 섹터" 와 같은 모양이다.
 *  자리만 그릴 때는 누를 일이 없어 onClick 이 없다 */
function SectorNav({ dir, disabled, onClick }: {
  dir: -1 | 1
  disabled: boolean
  onClick?: () => void
}) {
  return (
    <button
      type="button" className="st-sector-nav" onClick={onClick} disabled={disabled}
      aria-label={dir === -1 ? '이전 섹터 보기' : '다음 섹터 보기'}
    >
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
           style={dir === 1 ? { transform: 'rotate(180deg)' } : undefined}>
        <path d="M15 5 8 12l7 7" />
      </svg>
    </button>
  )
}

type Props = {
  filter: StockFilter
  setFilter: React.Dispatch<React.SetStateAction<StockFilter>>
}

export default function StockTable({ filter, setFilter }: Props) {
  /* 적용된 필터와 편집 중인 값을 나눈다 — 좌측 패널은 "필터 적용" 을 눌러야
     반영되고, 상단 섹터 칩은 즉시 반영된다(탐색용이라 한 번에 바뀌는 게 낫다).

     draft 는 이 안에서만 산다. 마켓맵은 관심 여부만 쓰고 그것은 누르는 즉시
     적용되므로, 페이지가 들고 있어야 하는 것은 filter 뿐이다. */
  const [draft, setDraft] = useState<StockFilter>(filter)

  /* 관심 토글은 낙관적으로 먼저 바꾼다(§4 B-02). 목록은 훅이 들고 있어 직접
     고칠 수 없으므로, 바뀐 종목만 여기 얹고 렌더에서 덮어쓴다. 실패하면 되돌린다.

     어느 필터의 결과에 얹은 보정인지 함께 들고 있다 — 필터가 바뀌면 목록이
     처음부터 다시 오므로 보정도 버려야 한다. effect 로 비우면 렌더가 한 번 더
     돌아서(useCursorList 주석의 규칙) 렌더 중에 판단한다. */
  const [fix, setFix] = useState<{ for: StockFilter; map: Record<string, boolean> }>(
    { for: filter, map: {} },
  )
  const watchFix = fix.for === filter ? fix.map : {}

  const perInvalid =
    draft.perMin != null && draft.perMax != null && draft.perMin > draft.perMax

  /* filter 가 바뀔 때만 새 조회 함수를 만든다. 매 렌더 새로 만들면 훅이
     끝없이 다시 읽는다. filter 는 setFilter 로만 바뀌므로 참조가 안정적이다. */
  const source = useMemo(() => fetchStocks(filter), [filter])
  const list = useCursorList<StockListItem, StockListMeta>(source)

  const baseDate = list.meta?.baseDate ?? null
  const isEmpty = !list.loading && !list.error && list.items.length === 0

  const pickSector = (sector: string | undefined) => {
    setFilter((f) => ({ ...f, sector }))
    setDraft((d) => ({ ...d, sector }))
  }

  /** 같은 필터에 얹혀 있던 보정만 이어받아 한 종목을 덮어쓴다 */
  const putFix = (code: string, on: boolean) =>
    setFix((s) => ({
      for: filter,
      map: { ...(s.for === filter ? s.map : {}), [code]: on },
    }))

  const toggleWatch = async (s: StockListItem) => {
    const now = watchFix[s.code] ?? s.watched
    putFix(s.code, !now)
    try {
      await (now ? removeWatch(s.code) : addWatch(s.code))
    } catch {
      /* 서버가 거절하면 되돌린다 — 눌린 채로 남으면 담긴 줄 알고 넘어간다 */
      putFix(s.code, now)
    }
  }

  return (
    <>
      <SectorChips current={filter.sector} onPick={pickSector} />

      <div className="st-body">
        {/* ── 좌측 상세 필터 ───────────────────────────── */}
        <form className="card st-filter"
              onSubmit={(e) => { e.preventDefault(); if (!perInvalid) setFilter(draft) }}>
          <h2>상세 필터</h2>

          {/* 시장(전체·KOSPI·KOSDAQ) 칸은 뺐다(2026-09-11) — 이 서비스는 코스피만
              다룬다. 마켓맵 쪽 칩과 같은 이유다(Stocks.tsx). 행에 찍히는 "KOSPI"
              표시는 남긴다 — 그건 거르는 조건이 아니라 종목을 설명하는 말이다. */}
          <fieldset className="st-field">
            <legend>예측 방향</legend>
            <div className="st-chips">
              <button type="button" className={`st-chip${!draft.sentiment ? ' on' : ''}`}
                      aria-pressed={!draft.sentiment}
                      onClick={() => setDraft((d) => ({ ...d, sentiment: undefined }))}>
                전체
              </button>
              {(Object.keys(SENTIMENT_LABEL) as Sentiment[]).map((s) => (
                <button key={s} type="button"
                        className={`st-chip${draft.sentiment === s ? ' on' : ''}`}
                        aria-pressed={draft.sentiment === s}
                        onClick={() => setDraft((d) => ({ ...d, sentiment: s }))}>
                  {SENTIMENT_LABEL[s]}
                </button>
              ))}
            </div>
          </fieldset>

          <fieldset className="st-field">
            <legend>PER 범위</legend>
            <div className="st-range">
              <input type="number" inputMode="decimal" min="0" step="0.1" placeholder="최소"
                     aria-label="PER 최소" aria-invalid={perInvalid || undefined}
                     value={draft.perMin ?? ''}
                     onChange={(e) => setDraft((d) => ({
                       ...d, perMin: e.target.value === '' ? undefined : Number(e.target.value),
                     }))} />
              <span aria-hidden="true">–</span>
              <input type="number" inputMode="decimal" min="0" step="0.1" placeholder="최대"
                     aria-label="PER 최대" aria-invalid={perInvalid || undefined}
                     value={draft.perMax ?? ''}
                     onChange={(e) => setDraft((d) => ({
                       ...d, perMax: e.target.value === '' ? undefined : Number(e.target.value),
                     }))} />
            </div>
            {/* 서버는 역전을 400 으로 거절한다 — 보내기 전에 여기서 막는다 */}
            {perInvalid && (
              <p className="st-field-err" role="alert">최소가 최대보다 큽니다</p>
            )}
          </fieldset>

          <fieldset className="st-field">
            <legend>추가 조건</legend>
            <label className="st-check">
              <input type="checkbox" checked={draft.hasOpenPrediction ?? false}
                     onChange={(e) => setDraft((d) => ({
                       ...d, hasOpenPrediction: e.target.checked || undefined,
                     }))} />
              내가 진행 중인 예측 있음
            </label>
            <label className="st-check">
              <input type="checkbox" checked={draft.watchedOnly ?? false}
                     onChange={(e) => setDraft((d) => ({
                       ...d, watchedOnly: e.target.checked || undefined,
                     }))} />
              관심 종목만 보기
            </label>
          </fieldset>

          {/* 두 버튼을 한 번 묶는다 — .st-filter 의 gap:18px 이 모든 자식 사이에
              똑같이 걸려, 조건 묶음 사이만큼이나 버튼 둘 사이가 벌어져 있었다.
              한 쌍으로 읽혀야 하는 것이라 여기만 따로 좁힌다(st-actions). */}
          <div className="st-actions">
            <button type="submit" className="st-apply" disabled={perInvalid}>필터 적용</button>
            <button type="button" className="st-reset"
                    onClick={() => { setDraft(EMPTY_FILTER); setFilter(EMPTY_FILTER) }}>
              필터 초기화
            </button>
          </div>
        </form>

        {/* ── 우측 목록 ────────────────────────────────── */}
        <section className="card st-panel" aria-labelledby="st-list-h">
          <header className="st-panel-head">
            <h2 id="st-list-h">종목 리스트</h2>
            {list.items.length > 0 && (
              <span className="st-count num">{list.items.length}개 종목</span>
            )}
            {baseDate && (
              <span className="st-panel-asof">
                직전 영업일 종가 · <b className="num">{dotted(baseDate)}</b>
              </span>
            )}
            <label className="st-sort">
              <span className="st-sr">정렬</span>
              <select value={filter.sort}
                      onChange={(e) => {
                        const sort = e.target.value as StockSort
                        setFilter((f) => ({ ...f, sort }))
                        setDraft((d) => ({ ...d, sort }))
                      }}>
                {STOCK_SORTS.map((s) => (
                  <option key={s} value={s}>{SORT_LABEL[s]}</option>
                ))}
              </select>
            </label>
          </header>

          {list.error && <ErrorState error={list.error} onRetry={list.reload} inline />}

          {isEmpty && (
            <EmptyState
              title="조건에 맞는 종목이 없습니다"
              hint="필터를 줄이거나 초기화해 보세요."
            />
          )}

          {list.items.length > 0 && (
            <>
              {/* 표가 아니라 목록이다 — 행 전체가 상세로 가는 링크라야 하고
                  tr 은 링크로 감쌀 수 없다. 관심 버튼은 링크 밖 형제로 둔다. */}
              <div className="st-cols" aria-hidden="true">
                <span>종목</span>
                <span className="r">전일 종가</span>
                <span className="r">등락률</span>
                <span className="r">PER</span>
                <span className="r">PBR</span>
                {/* 관심 버튼 칸은 머리글을 두지 않는다 — .st-cols 의
                    padding-right 가 그 자리를 비운다 */}
                <span>예측 현황</span>
              </div>
              <ul className="st-list">
                {list.items.map((s) => {
                  const on = watchFix[s.code] ?? s.watched
                  return (
                    <li key={s.code} className="st-item">
                      <Link className="st-link" to={`/stocks/${s.code}`}>
                        <span className="st-id">
                          <StockLogo code={s.code} name={s.name} className="st-logo" />
                          <span className="st-id-text">
                            <b className="st-name">{s.name}</b>
                            <small className="st-sub">
                              <span className="num">{s.code}</span>
                              {s.market && <> · {MARKET_LABEL[s.market]}</>}
                              {s.sector && <> · {s.sector}</>}
                            </small>
                          </span>
                        </span>
                        <span className="st-close num">
                          {s.prevClose === null ? DASH : won(s.prevClose)}
                        </span>
                        <span className={`st-rate num ${toneOf(s.changeRate)}`}>
                          {s.changeRate === null ? DASH : signed(s.changeRate)}
                        </span>
                        <span className="st-per num">
                          {s.per === null ? DASH : `${s.per.toFixed(1)}x`}
                        </span>
                        <span className="st-per num">
                          {s.pbr === null ? DASH : `${s.pbr.toFixed(2)}x`}
                        </span>
                        <SentimentBar upRatio={s.upRatio} count={s.predictionCount} />
                      </Link>
                      <button type="button" className={`st-star${on ? ' on' : ''}`}
                              aria-pressed={on}
                              aria-label={`${s.name} 관심 ${on ? '해제' : '추가'}`}
                              onClick={() => void toggleWatch(s)}>
                        <svg width="17" height="17" viewBox="0 0 24 24" aria-hidden="true">
                          <path d="M12 3.6l2.6 5.3 5.8.8-4.2 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.2-4.1 5.8-.8z"
                                fill={on ? 'currentColor' : 'none'}
                                stroke="currentColor" strokeWidth="1.6" />
                        </svg>
                      </button>
                    </li>
                  )
                })}
              </ul>
            </>
          )}

          {list.loading && <p className="st-more-hint">불러오는 중…</p>}

          {list.hasNext && !list.loading && (
            <button type="button" className="st-more" onClick={list.loadMore}>더 보기</button>
          )}
          {!list.hasNext && list.items.length > 0 && (
            <p className="st-foot">마지막 종목까지 다 보셨습니다</p>
          )}
        </section>
      </div>
    </>
  )
}
