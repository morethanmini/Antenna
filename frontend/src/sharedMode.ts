/* 두 모드(인사이트 · 모의투자)가 같이 쓰는 화면이 "어느 모드에서 들어왔나" 를 주소에 싣는 규칙.

   환경 설정(H-03) · 마이페이지(E-05) · 지갑(H-01) · 확정 기록(D-01~D-04)은 어느 상단바에서도
   열리는데, 라우트 표(routes.ts)는 한 화면에 모드 하나만 적을 수 있다. 그래서 모의투자에서
   톱니를 누르면 셸이 인사이트 모드로 그려져 토글이 넘어가 버렸다(2026-09-22). 자물쇠도
   같은 결함이었다(2026-09-23). 정적인 표가 답할 수 없는 값이라 ?event= 처럼 주소로 받는다.

   ── 기본값(인사이트)은 주소에 적지 않는다 ─────────────
   urlState.ts 와 같은 규칙. `/settings` 는 그대로 인사이트고, 모의투자에서 열 때만
   `/settings?mode=sim` 이다. 공유한 링크가 "무언가 골라 둔 화면" 처럼 보이지 않는다.

   ── 받는 쪽은 App.tsx 의 Page 하나다 ─────────────────
   routes.ts 에서 sharedMode 를 켠 화면만 이 값을 읽는다. 다른 화면은 무시한다 —
   종목 탐색이 모의투자 상단바를 달고 나오면 안 된다. */
import { useCallback } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { Mode } from './Layout'

export const MODE_PARAM = 'mode'

/** 주소에서 읽은 값을 Mode 로 받아도 되는가 — 주소는 사용자가 고칠 수 있어 그대로 믿지 않는다 */
export const isMode = (v: unknown): v is Mode => v === 'insight' || v === 'sim'

/** 공유 화면으로 가는 주소. 셸처럼 지금 모드를 이미 아는 곳에서 쓴다 */
export function sharedHref(path: string, mode: Mode): string {
  if (mode !== 'sim') return path
  return `${path}${path.includes('?') ? '&' : '?'}${MODE_PARAM}=sim`
}

/** 공유 화면 **안에서** 다른 공유 화면으로 가는 링크에 쓴다 — 지금 주소의 모드를 그대로 잇는다.
    마이페이지의 '지갑 · 토큰' · '설정' 이 그렇다. 여기서 끊기면 모의투자 → 마이페이지까지는
    유지되다가 설정에서 인사이트로 넘어간다. */
export function useSharedHref() {
  const [params] = useSearchParams()
  const raw = params.get(MODE_PARAM)
  const mode: Mode = isMode(raw) ? raw : 'insight'
  return useCallback((path: string) => sharedHref(path, mode), [mode])
}
