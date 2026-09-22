/* B-02 필터를 **페이지 주소 쿼리**에 둔다 — /stocks?sector=전기·전자&sort=PER

   왜 주소인가 (useState 가 아니라)
   필터를 페이지 상태로만 들고 있으면 종목 상세(B-03)에 다녀오는 순간 사라진다.
   상세로 가면 이 페이지가 언마운트되고, 뒤로 오면 EMPTY_FILTER 로 새로 마운트된다 —
   "전기·전자" 를 골라 삼성전자를 보고 돌아왔는데 전체 종목이 떠 있었다.
   주소에 두면 뒤로가기·새로고침·링크 공유가 한 번에 풀린다. 서버에는 새로 받을 것이
   없다 — GET /stocks 가 이미 같은 이름의 파라미터를 받는다.

   왜 replace 인가
   briefingParam.ts · StockDetail 의 goTab 과 같은 판단이다. 필터를 다섯 번 만지고
   상세에서 돌아온 사람이 목록을 벗어나려고 뒤로가기를 여섯 번 눌러야 하면 답답하다.
   히스토리에는 "목록의 마지막 모습" 하나만 남는다.

   기본값은 주소에 적지 않는다 — 아무것도 안 고른 화면은 그냥 /stocks 다.
   market · sentiment 는 화면에 컨트롤이 없어(api/stocks.ts) 주소에도 없다.

   컴포넌트 파일이 아니라 여기 두는 이유는 briefingParam.ts 와 같다(Fast Refresh). */
import { useCallback, useMemo } from 'react'
import type { SetStateAction } from 'react'
import { useSearchParams } from 'react-router-dom'
import { EMPTY_FILTER, STOCK_SORTS } from '../../api/stocks'
import type { StockFilter, StockSort } from '../../api/stocks'

/** 이 파일이 주소에서 맡는 키. 그 밖의 키(view 등)는 건드리지 않고 그대로 둔다 */
const FILTER_KEYS = [
  'q', 'sector', 'perMin', 'perMax', 'pbrMin', 'pbrMax',
  'hasOpenPrediction', 'watchedOnly', 'sort',
] as const

/* 주소창은 누구나 고칠 수 있다. 읽을 수 없는 값은 안 건 것으로 친다 —
   perMin=abc 를 그대로 넘겨 서버 400 으로 목록을 깨뜨리지 않는다. */
const toNumber = (raw: string | null) => {
  if (raw === null || raw.trim() === '') return undefined
  const n = Number(raw)
  return Number.isFinite(n) && n >= 0 ? n : undefined
}
const toFlag = (raw: string | null) => (raw === 'true' ? true : undefined)
const isSort = (raw: string | null): raw is StockSort =>
  raw !== null && (STOCK_SORTS as readonly string[]).includes(raw)

const nonEmpty = (list: string[]) => (list.length > 0 ? list : undefined)

/** 역전된 범위(최소 > 최대)는 서버가 400 으로 막는다. 손으로 친 주소면 둘 다 버린다 */
const toRange = (min: number | undefined, max: number | undefined) =>
  min !== undefined && max !== undefined && min > max ? {} : { min, max }

export function filterFromParams(params: URLSearchParams): StockFilter {
  const q = params.get('q')
  const sort = params.get('sort')
  const per = toRange(toNumber(params.get('perMin')), toNumber(params.get('perMax')))
  const pbr = toRange(toNumber(params.get('pbrMin')), toNumber(params.get('pbrMax')))
  return {
    /* 앞뒤 공백을 다듬지 않는다 — "삼성 " 까지 친 순간 주소를 거쳐 돌아온 값이
       입력칸의 공백을 지우면 "삼성 전자" 를 칠 수 없다. 서버가 trim 한다 */
    q: q !== null && q.trim() !== '' ? q : undefined,
    sector: nonEmpty(params.getAll('sector').filter(Boolean)),
    perMin: per.min,
    perMax: per.max,
    pbrMin: pbr.min,
    pbrMax: pbr.max,
    hasOpenPrediction: toFlag(params.get('hasOpenPrediction')),
    watchedOnly: toFlag(params.get('watchedOnly')),
    sort: isSort(sort) ? sort : EMPTY_FILTER.sort,
  }
}

/** prev 의 필터 키만 갈아 끼운 새 쿼리. 기본값·빈 값은 적지 않는다 */
export function paramsWithFilter(prev: URLSearchParams, filter: StockFilter): URLSearchParams {
  const kept = [...prev.entries()].filter(
    ([key]) => !(FILTER_KEYS as readonly string[]).includes(key),
  )
  const mine = FILTER_KEYS.flatMap((key): [string, string][] => {
    const value = filter[key]
    if (value === undefined || value === false || value === '') return []
    if (key === 'sort' && value === EMPTY_FILTER.sort) return []
    /* 섹터는 같은 키로 여러 번 — 서버와 같은 모양이라 주소를 그대로 넘겨도 된다 */
    if (Array.isArray(value)) return value.map((one): [string, string] => [key, one])
    return [[key, String(value)]]
  })
  return new URLSearchParams([...kept, ...mine])
}

export function useStockFilterParams() {
  const [params, setParams] = useSearchParams()

  /* 문자열로 묶어 메모한다. StockTable 은 filter 의 **참조**가 바뀌면 목록을 처음부터
     다시 읽으므로(useCursorList), 주소가 그대로인 렌더에서는 같은 객체여야 한다. */
  const key = params.toString()
  const filter = useMemo(() => filterFromParams(new URLSearchParams(key)), [key])

  const setFilter = useCallback((next: SetStateAction<StockFilter>) => {
    setParams((prev) => paramsWithFilter(
      prev, typeof next === 'function' ? next(filterFromParams(prev)) : next,
    ), { replace: true })
  }, [setParams])

  return { filter, setFilter }
}
