/* 목록 화면의 "보고 있던 상태" 를 주소에 둔다 — 필터 · 정렬 · 기간.

   useState 로 들고 있으면 상세로 갔다 뒤로 오는 순간 초기화된다. 사용자는 조건을
   다시 고르고 스크롤을 다시 내려야 했다(2026-09-22 QA: C-02 필터 · E-01 기간 ·
   F-01 정렬 셋 다 같은 증상).

   ── 기본값은 주소에 적지 않는다 ─────────────────────────
   `?stage=ALL&sort=LATEST` 처럼 아무것도 안 고른 상태가 주소에 박히면, 공유한
   링크가 "무언가 골라 둔 화면" 처럼 보인다. 기본값으로 돌아오면 키를 지운다.

   ── replace 다 ──────────────────────────────────────────
   조건을 고르는 것은 **다른 화면으로 가는 일이 아니다.** push 로 쌓으면 뒤로가기를
   고른 횟수만큼 눌러야 목록을 빠져나간다(같은 이유로 검색 화면도 replace 를 쓴다). */
import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'

/**
 * @param key      주소에 쓸 이름
 * @param fallback 아무것도 안 고른 상태. 주소에서 지워지는 값이기도 하다
 * @param allowed  받아들일 값 목록 — 주소는 사용자가 고칠 수 있어 그대로 믿지 않는다
 */
export function useUrlState<T extends string>(
  key: string,
  fallback: T,
  allowed: readonly T[],
) {
  const [params, setParams] = useSearchParams()
  const raw = params.get(key)
  const value = (allowed as readonly string[]).includes(raw ?? '') ? (raw as T) : fallback

  const set = useCallback((next: T) => {
    setParams((prev) => {
      const p = new URLSearchParams(prev)
      if (next === fallback) p.delete(key)
      else p.set(key, next)
      return p
    }, { replace: true })
  }, [key, fallback, setParams])

  return [value, set] as const
}
