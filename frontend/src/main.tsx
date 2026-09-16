import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'

/* 셸 CSS. 화면별 CSS 는 각 화면 스토리에서 함께 가져온다.

   **App 보다 먼저 가져와야 한다.** App 을 먼저 import 하면 그 아래로 딸려 오는
   화면별 CSS 가 app.css 보다 앞서 번들에 들어가, 우선순위가 같을 때 공용 셸이
   화면별 규칙을 이겨 버린다 — 기준을 먼저 깔고 화면이 덮는 것이 맞는 순서다.
   (겹치는 선택자는 지금 한 건도 없어서 순서를 바꿔도 달라지는 화면은 없다.) */
import './styles/app.css'

import App from './App'
import { AuthProvider } from './auth/AuthContext'
// 전 화면이 공유하는 오류·빈 상태·잠금 표현
import './styles/state.css'
import './styles/prediction.css'

createRoot(document.getElementById('root')!).render(
  <BrowserRouter>
    <AuthProvider>
      <App />
    </AuthProvider>
  </BrowserRouter>
)
