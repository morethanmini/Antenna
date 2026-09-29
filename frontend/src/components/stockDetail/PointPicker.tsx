/* 투자 포인트 고르개 — 종목 정보(B-03)와 예측 등록(C-01)이 **같은 것을** 그린다.

   ── 왜 컴포넌트로 뺐나 ───────────────────────────────────
   원래 이 격자는 InfoTab 안에만 있었고, PredictTab 의 ③ 근거 포인트는 "고른 것" 만
   요약해 보여 주면서 못 고르면 종목 정보 탭으로 보냈다. 그런데 예측을 쓰다가 근거를
   하나 더 붙이려면 탭을 옮겼다 돌아와야 했다 — 그 사이 화면이 갈리고, 돌아오면 어디까지
   적었는지 다시 찾아야 한다.

   그래서 같은 격자를 예측 화면 아래에도 둔다. **복사하지 않고 한 파일로 둔 이유**는
   이 코드베이스가 반복해 경계하는 것과 같다: 종류 라벨·출처 표기·선택 규칙이 두 벌이
   되면 한쪽만 고쳐졌을 때 두 화면이 같은 포인트를 다르게 보여 준다.

   ── 상태를 들지 않는다 ───────────────────────────────────
   무엇을 골랐는지는 부모(StockDetail)가 든다. 탭을 옮겨도 선택이 살아 있어야 하고,
   그 인계에는 API 가 없어 상태로만 이어지기 때문이다(InfoTab 머리말). */
import { useCallback, useLayoutEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { POINT_KINDS, POINT_LABEL, SOURCE_LABEL } from '../../api/stockDetail'
import type { PointGroups, PointKind } from '../../api/stockDetail'

/* ── 고를 때 화면이 튀지 않게 (QA 09-18) ──────────────────────
   "긍정요인·부정요인을 선택하면 화면이 아래로 내려간다"(도훈), "클릭하면 화면이
   위로 올라간다"(컨설). 서로 반대 방향이지만 원인은 하나다 — **이 격자 위에 있는
   것의 높이가 선택 때문에 바뀐다.**

   예측 탭(C-01) ③ 이 그렇다. 아무것도 안 골랐을 때는 안내 한 줄이고, 하나 고르면
   그 자리가 고른 근거 목록으로 바뀐다. 첫 선택에서는 안내보다 목록이 짧아 격자가
   위로 올라오고, 두 번째부터는 줄이 쌓여 아래로 밀린다. 눌러야 할 칸이 손가락
   밑에서 도망가는 것은 어느 쪽이든 같다.

   그래서 위를 건드리지 않고 **격자가 화면에서 있던 자리에 그대로 있게** 만든다.
   누르기 직전에 격자 위쪽 좌표를 재 두고, 다시 그린 직후 같은 좌표를 다시 재
   차이만큼 창을 굴린다. 사용자 눈에는 아무 일도 일어나지 않는다.

   - useLayoutEffect 다. useEffect 로 하면 브라우저가 밀린 화면을 한 번 그린 뒤에
     되돌려 깜빡인다.
   - 브라우저의 스크롤 앵커링(overflow-anchor)에 기대지 않는다. 그것은 화면 **밖**
     에서 생긴 변화만 보정하는데, 여기서 늘고 주는 것은 눈앞에 보이는 자리다.
   - 페이지가 이미 끝까지 내려가 있으면 굴릴 여지가 없어 완전히는 못 지킨다.
     그때도 지금보다는 덜 튄다. */
function useKeepInPlace(picked: number[]) {
  const ref = useRef<HTMLDivElement>(null)
  /** 누르기 직전의 격자 위쪽 좌표(뷰포트 기준). 보정할 것이 없으면 null */
  const before = useRef<number | null>(null)

  const capture = useCallback(() => {
    before.current = ref.current?.getBoundingClientRect().top ?? null
  }, [])

  useLayoutEffect(() => {
    const was = before.current
    before.current = null
    // 첫 그림이거나 우리가 일으킨 변화가 아니면 아무것도 하지 않는다
    if (was === null || !ref.current) return
    const delta = ref.current.getBoundingClientRect().top - was
    // 소수점 떨림까지 쫓아다니면 스크롤이 미세하게 덜덜거린다
    if (Math.abs(delta) >= 1) window.scrollBy(0, delta)
  }, [picked])

  return { ref, capture }
}

/* 열 제목 앞에 서는 개미 얼굴(2026-09-17). 글자만 세 줄 늘어놓으면 세 열이 한
   덩어리로 읽혀 어디가 긍정이고 어디가 위험인지 훑어서는 안 잡힌다.

   표정으로 고른다 — 긍정은 활짝 웃고, 위험은 걱정하고, 확인할 점은 갸웃한다.
   화난 얼굴을 위험에 쓰지 않은 이유는, 위험 요인이 "이 회사가 밉다" 가 아니라
   "이건 걱정된다" 이기 때문이다.

   **열 색은 우리 관례를 따른다** — 긍정은 빨강(--up), 위험은 파랑(--down)이다.
   시안은 초록/빨강이었지만 같은 화면의 등락률·예측 분포가 이미 "오르면 빨강"
   이라, 긍정에 초록을 쓰면 한 화면에서 빨강이 좋은 뜻과 나쁜 뜻을 같이 갖는다. */
const POINT_FACE: Record<PointKind, string> = {
  positive: 'happy',
  risk: 'worried',
  check: 'curious',
}

/* 본문 안 숫자를 굵게(2026-09-29). 포인트는 "영업이익은 124.39%, 순이익은 141.57% 늘고" 처럼
   한 문장에 수치가 몰려 있어, 훑어 읽을 때 숫자가 글에 묻혔다.
   **색은 입히지 않는다.** 이 포인트들은 부호 없이 "늘고 · 줄어" 로 방향을 말로 적는다 —
   글에서 방향을 짐작해 칠하면 틀릴 수 있고, 열 색이 이미 긍정·위험을 말한다.
   단위가 붙은 수치만 잡는다. "2025년" 같은 연도는 수치가 아니라 때라서 그대로 둔다.
   글자를 나눠 감쌀 뿐 HTML 을 만들지 않는다. */
const FIGURE = /(\d[\d,]*(?:\.\d+)?\s*(?:%p|%|배|조\s?원|억\s?원|만\s?원|원))/g
const figures = (text: string): ReactNode[] =>
  text.split(FIGURE).map((part, i) => (i % 2 === 1 ? <b key={i}>{part}</b> : part))

type Props = {
  /** 아직 안 왔으면 null — 그때는 종류마다 "없음" 이 선다. 부르는 쪽이 로딩 동안은 그리지 않는다 */
  points: PointGroups | null
  picked: number[]
  onPick: (id: number) => void
}

export default function PointPicker({ points, picked, onPick }: Props) {
  const { ref, capture } = useKeepInPlace(picked)

  return (
    <div className="sd-points" ref={ref}>
      {POINT_KINDS.map((kind) => {
        const list = points?.[kind] ?? []
        return (
          <div key={kind} className={`sd-point-col is-${kind}`}>
            <h3>
              <img
                className="sd-point-face"
                src={`/assets/character/face_ant/face-${POINT_FACE[kind]}.webp`}
                alt="" aria-hidden="true" width="38" height="38" loading="lazy"
              />
              {POINT_LABEL[kind]}
            </h3>
            {list.length === 0 ? (
              /* "없음" 한 단어는 불러오다 만 것인지, 정말 없는 것인지 구분이 안 된다.
                 AI 가 보고 **없다고 판정한** 것임을 말한다(2026-09-23). 세 라벨이 모두
                 받침으로 끝나(요인·점) 조사는 '이' 하나로 된다 */
              <div className="sd-point-none">
                <span className="sd-point-none-ico" aria-hidden="true">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                    strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M5 12.5l4.5 4.5L19 7.5" />
                  </svg>
                </span>
                <p>AI 판정 {POINT_LABEL[kind]}이 없어요</p>
              </div>
            ) : (
              <ul>
                {list.map((p) => {
                  /* 고른 순서. 예측 근거로 넘어가는 차례와 같다 — 무엇을 몇 개 골랐는지
                     카드에서 바로 보이게 한다(0 이면 안 고른 것) */
                  const order = picked.indexOf(p.id) + 1
                  const on = order > 0
                  return (
                    <li key={p.id}>
                      {/* 체크박스로 두는 이유 — 여러 개를 고르는 조작이고,
                          키보드·보조기술이 선택 상태를 그대로 읽는다 */}
                      <label className={on ? 'sd-point is-on' : 'sd-point'}>
                        {/* 자리를 재는 것이 먼저다 — onPick 이 부모 상태를 바꾸면
                            그 뒤로는 바뀌기 전 좌표를 다시 잴 수 없다 */}
                        <input
                          type="checkbox"
                          checked={on}
                          onChange={() => { capture(); onPick(p.id) }}
                        />
                        <span className="sd-point-text">
                          {/* 제목이 따로 없다. 본문 한 덩어리가 포인트 전체다 */}
                          <span className="sd-point-body">{figures(p.body)}</span>
                          {/* 출처 배지. 칸 **안쪽** 글 밑에 붙인다 — 칸 밖에 두면 어느
                              포인트의 출처인지 한 칸씩 밀려 보인다. 없는 포인트는
                              시세·재무 수치에서 나온 종합이라 배지도 없다 */}
                          {p.source && (
                            <span className="sd-point-src">{SOURCE_LABEL[p.source]}</span>
                          )}
                        </span>
                        {on && <span className="sd-point-order" aria-hidden="true">{order}</span>}
                      </label>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        )
      })}
    </div>
  )
}
