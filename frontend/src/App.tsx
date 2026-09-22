import { useEffect } from 'react'
import { Navigate, Route, Routes, useSearchParams } from 'react-router-dom'
import Layout from './Layout'
import RequireAccess from './auth/RequireAccess'
import ErrorBoundary from './components/state/ErrorBoundary'
import { ROUTES, type PageMeta } from './routes'
import { MODE_PARAM, isMode } from './sharedMode'

/* 화면 한 장. 셸을 씌울지와 body 속성만 여기서 정하고,
   내용은 meta.element(각 화면 컴포넌트)가 그린다. */
function Page({ meta }: { meta: PageMeta }) {
  const Element = meta.element
  const [params] = useSearchParams()

  /* 두 모드가 같이 쓰는 화면(routes.ts 의 sharedMode)은 **들어온 모드를 주소로 받는다.**
     환경 설정을 모의투자 상단바에서 열었는데 토글이 인사이트로 넘어가 있었다(2026-09-22).
     아래 ?event= 와 같은 이유로 routes.ts 의 표를 고치지 않는다 — 같은 화면이 주소에
     따라 다른 자리에 속한다. sharedMode 가 아닌 화면은 주소의 mode 를 무시한다.
     주소 규칙은 sharedMode.ts 머리말. */
  const modeParam = params.get(MODE_PARAM)
  const mode = meta.sharedMode && isMode(modeParam) ? modeParam : meta.mode

  /* 대회로 들어온 화면은 **아무 메뉴도 칠하지 않는다**(2026-09-18).
     종목 상세는 routes.ts 에서 nav:'stocks' 인데, 대회 종목 카드를 눌러 들어오면
     "종목 탐색으로 들어와 있다" 고 말하는 셈이다 — 실제로 있던 자리는 대회다.
     대회는 사이드바·네비에 메뉴가 없어(들어오는 길이 링크와 홈 배너뿐이다)
     대신 칠할 항목도 없다. 그래서 빈 문자열을 넘겨 어느 것도 안 맞게 둔다.

     routes.ts 를 고치지 않는 이유 — 같은 화면이 주소에 따라 다른 자리에 속하는
     것이라, 정적인 표가 답할 수 있는 값이 아니다.

     다른 모드로 들어온 공유 화면도 같다. 표의 nav 키는 **기본 모드 기준**이라 —
     지갑(H-01)의 'portfolio' 는 인사이트 줄에서 '내 예측' 인데, 모의투자 줄에서는
     같은 키가 '투자 포트폴리오' 라 엉뚱한 메뉴에 불이 들어간다. */
  const nav = params.get('event') || mode !== meta.mode ? '' : (meta.nav ?? 'home')

  useEffect(() => {
    document.title = `${meta.title} · ANTENNA`
    if (mode) document.body.dataset.mode = mode
    else {
      delete document.body.dataset.mode
      // 셸이 없는 화면(로그인)은 body 클래스를 직접 관리한다
      document.body.className = meta.bodyClass ?? ''
    }
    window.scrollTo(0, 0)
  }, [meta, mode])

  /* 가드와 오류 경계를 모두 셸 안쪽에 둔다 — 403 이나 렌더 예외를 만나도
     사이드바가 남아 다른 화면으로 갈 수 있다.
     비로그인 리다이렉트는 렌더 중에 일어나 셸이 그려지기 전에 빠져나간다.

     key 에 경로를 주면 다른 화면으로 옮길 때 리마운트되어 오류 상태가 풀린다. */
  const body = (
    <ErrorBoundary key={meta.path}>
      <RequireAccess access={meta.access}>
        <Element />
      </RequireAccess>
    </ErrorBoundary>
  )

  return mode
    ? <Layout mode={mode} nav={nav} bodyClass={meta.bodyClass ?? ''}>{body}</Layout>
    : body
}

export default function App() {
  return (
    <Routes>
      {ROUTES.map((meta) => (
        <Route key={meta.path} path={meta.path} element={<Page meta={meta} />} />
      ))}
      {/* 없는 경로는 홈으로. 404 화면(A-04)은 [ANT-FE-ERROR] 에서 붙인다 */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
