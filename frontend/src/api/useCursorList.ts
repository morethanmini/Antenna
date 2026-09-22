/* 모든 목록 화면이 쓰는 커서 페이징 훅.
   설계서 §7 — { items, nextCursor, hasNext } 계약 하나로 통일한다.

   페이징 세 필드 밖의 값은 meta 로 함께 넘긴다. 목록 전체에 한 번만 해당하는
   값을 행마다 싣지 않으려고 서버가 응답 루트에 두는 경우가 있다
   — 예: GET /stocks 의 baseDate(종가 기준 영업일, 명세서 v0.9).
   두 번째 타입 인자를 주지 않으면 meta 는 빈 객체 타입이라 기존 호출부는 그대로다.

   첫 인자에 경로 대신 함수를 줄 수도 있다. 백엔드가 아직 없어 목업으로 그리는
   화면도 같은 페이징 규칙을 쓰게 하려는 것이다 — 목업 함수를 주고, 백엔드가
   붙으면 경로 문자열로 바꾸면 화면 코드는 그대로다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './client'
import type { ApiError } from './errors'
import type { CursorList } from './types'

type State<T, M> = {
  items: T[]
  hasNext: boolean
  loading: boolean
  error: ApiError | null
  /** 첫 페이지 응답의 페이징 밖 필드. 아직 한 번도 못 읽었으면 null */
  meta: M | null
}

type Query = Record<string, string | number | boolean | undefined>

/** 경로 대신 넘길 수 있는 조회 함수. cursor 는 query 에 섞여 들어온다. */
export type CursorFetcher<T, M> = (query: Query) => Promise<CursorList<T> & M>

type Cached = {
  /** 어느 조회 조건의 결과인지. 조건이 바뀌면 되살리지 않고 처음부터 읽는다 */
  queryKey: string
  items: unknown[]
  hasNext: boolean
  meta: unknown
  cursor: string | number | null
  scrollY: number
}

/* 화면을 떠나도 목록을 들고 있는 자리 (2026-09-23).

   종목 탐색(B-02)에서 "더 보기" 로 예순 줄을 펼치고 한 종목에 다녀오면, 목록이 다시
   열 줄로 돌아가 처음부터 눌러 내려야 했다. 화면을 떠나면 훅의 상태가 사라지고
   App 이 화면마다 맨 위로 올리기 때문이다(App.tsx).

   되살리는 값은 받아 둔 줄·커서·스크롤 위치다. 탭 하나가 열려 있는 동안만 산다 —
   모듈 변수라 새로고침하면 비고, 조회 조건이 바뀌면 그 자리에서 버려진다. 종가는
   하루 단위라 몇 분 전 값이 남아 있어도 화면이 틀리지 않는다. 늘 새로 읽어야 하는
   목록은 cacheKey 를 주지 않으면 된다 — 기존 화면은 그대로다. */
const cache = new Map<string, Cached>()

/** 들고 있는 목록 수. 필터를 이리저리 바꾸면 조합마다 한 벌씩 쌓여, 오래된 것부터 버린다 */
const CACHE_MAX = 5

function keep(key: string, entry: Cached) {
  cache.delete(key)
  cache.set(key, entry)
  for (const old of cache.keys()) {
    if (cache.size <= CACHE_MAX) break
    cache.delete(old)
  }
}

export function useCursorList<T, M = Record<string, never>>(
  source: string | CursorFetcher<T, M>,
  query?: Record<string, string | number | boolean | undefined>,
  /** 주면 화면을 떠났다 돌아올 때 받아 둔 줄과 스크롤 위치를 되살린다 */
  cacheKey?: string,
) {
  const [state, setState] = useState<State<T, M>>({
    items: [], hasNext: false, loading: true, error: null, meta: null,
  })
  // 서버가 목록마다 문자열·숫자 커서를 섞어 쓴다(types.ts CursorList 주석)
  const cursor = useRef<string | number | null>(null)
  // 쿼리 객체는 매 렌더 새로 만들어지므로 문자열로 굳혀 의존성에 넣는다
  const queryKey = JSON.stringify(query ?? {})

  /* 화면을 떠난 뒤 도착한 응답은 버린다. 목록은 await 하나를 사이에 두고
     setState 하므로, 그 사이에 언마운트되면 죽은 컴포넌트를 깨우면서 방금 받은
     페이지가 클로저째 붙들려 남는다. 전량 적재(C-02) 처럼 연달아 부르는 화면은
     드나들 때마다 그게 쌓인다. StrictMode 의 두 번째 마운트를 위해 들어올 때
     다시 세운다. */
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  /* 상태는 전부 await 뒤에서 건드린다.
     effect 안에서 동기로 setState 하면 렌더가 한 번 더 도는 걸 피하기 위해서다.
     loading 을 세우는 건 사용자 조작(loadMore·reload)에서만 한다. */
  const load = useCallback(async (reset: boolean) => {
    try {
      const parsed = JSON.parse(queryKey) as Query
      const q: Query = { ...parsed, cursor: reset ? undefined : cursor.current ?? undefined }
      const page = typeof source === 'string'
        ? await api.get<CursorList<T> & M>(source, { query: q })
        : await source(q)
      if (!alive.current) return
      cursor.current = page.nextCursor
      /* 페이징 세 필드를 덜어낸 나머지가 meta 다 */
      const { items, nextCursor: _next, hasNext, ...rest } = page
      setState((s) => {
        const next = {
          items: reset ? items : [...s.items, ...items],
          hasNext,
          loading: false,
          error: null,
          meta: rest as M,
        }
        if (cacheKey) {
          /* 돌아왔을 때 되살릴 값. 스크롤은 떠날 때 채운다 */
          keep(cacheKey, {
            queryKey,
            items: next.items,
            hasNext,
            meta: next.meta,
            cursor: cursor.current,
            scrollY: cache.get(cacheKey)?.scrollY ?? 0,
          })
        }
        return next
      })
    } catch (e) {
      if (!alive.current) return
      setState((s) => ({ ...s, loading: false, error: e as ApiError }))
    }
  }, [source, queryKey, cacheKey])

  useEffect(() => {
    const kept = cacheKey ? cache.get(cacheKey) : undefined
    if (kept && kept.queryKey === queryKey) {
      /* 들고 있던 것을 그대로 돌려준다. 서버를 다시 부르지 않는다 —
         부르면 첫 쪽만 오므로 펼쳐 둔 줄이 다시 접힌다. */
      cursor.current = kept.cursor
      setState({
        items: kept.items as T[],
        hasNext: kept.hasNext,
        loading: false,
        error: null,
        meta: kept.meta as M,
      })
      /* App 의 window.scrollTo(0, 0) 은 부모 effect 라 이 effect 보다 늦게 돈다.
         그려진 뒤로 미뤄 그 위에 얹는다. */
      if (kept.scrollY > 0) {
        requestAnimationFrame(() => window.scrollTo(0, kept.scrollY))
      }
      return
    }
    cursor.current = null
    void load(true)
  }, [load, cacheKey, queryKey])

  /* 떠나는 순간의 스크롤 위치를 적어 둔다 */
  useEffect(() => {
    if (!cacheKey) return
    return () => {
      const kept = cache.get(cacheKey)
      if (kept) kept.scrollY = window.scrollY
    }
  }, [cacheKey])

  /* 최신 상태를 ref 로 비춰 둔다. loadMore 가 state 를 직접 읽으면 페이지를
     받을 때마다 새 함수가 되고, 그 함수를 의존성에 넣은 화면의 effect 가 매번
     다시 돈다 — C-02 가 그랬다. 요청이 곱절로 나가지는 않았다(아래 loadMore 의
     hasNext·loading 가드가 막는다. 브라우저 실측: 29건 계정에서 고치기 전후 모두
     3회). 줄이는 것은 헛도는 effect 와, 그 effect 가 붙들고 있는 클로저다.

     렌더 중에 쓰지 않고 effect 에서 맞춘다 — loadMore 를 부르는 쪽도 전부
     커밋 뒤(effect·이벤트 핸들러)라 한 박자 늦어도 보는 값은 같다. */
  const snap = useRef(state)
  useEffect(() => { snap.current = state }, [state])

  /** 다음 페이지를 이어 붙인다 */
  const loadMore = useCallback(() => {
    if (!snap.current.hasNext || snap.current.loading) return
    setState((s) => ({ ...s, loading: true }))
    void load(false)
  }, [load])

  /** 처음부터 다시 읽는다 */
  const reload = useCallback(() => {
    cursor.current = null
    setState((s) => ({ ...s, loading: true, error: null }))
    void load(true)
  }, [load])

  return { ...state, loadMore, reload }
}
