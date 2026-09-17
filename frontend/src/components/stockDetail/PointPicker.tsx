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
import { POINT_KINDS, POINT_LABEL, SOURCE_LABEL } from '../../api/stockDetail'
import type { PointGroups } from '../../api/stockDetail'

type Props = {
  /** 아직 안 왔으면 null — 그때는 종류마다 "없음" 이 선다 */
  points: PointGroups | null
  picked: number[]
  onPick: (id: number) => void
}

export default function PointPicker({ points, picked, onPick }: Props) {
  return (
    <div className="sd-points">
      {POINT_KINDS.map((kind) => {
        const list = points?.[kind] ?? []
        return (
          <div key={kind} className={`sd-point-col is-${kind}`}>
            <h3>{POINT_LABEL[kind]}</h3>
            {list.length === 0 ? (
              <p className="sd-point-none">없음</p>
            ) : (
              <ul>
                {list.map((p) => {
                  const on = picked.includes(p.id)
                  return (
                    <li key={p.id}>
                      {/* 체크박스로 두는 이유 — 여러 개를 고르는 조작이고,
                          키보드·보조기술이 선택 상태를 그대로 읽는다 */}
                      <label className={on ? 'sd-point is-on' : 'sd-point'}>
                        <input type="checkbox" checked={on} onChange={() => onPick(p.id)} />
                        {/* 제목이 따로 없다. 본문 한 덩어리가 포인트 전체다 */}
                        <span className="sd-point-body">{p.body}</span>
                      </label>
                      {/* 근거 문서가 있으면 원문으로 갈 길을 준다.
                          없는 포인트는 시세·재무 수치에서 나온 종합이라 링크가 없다 */}
                      {p.source && (
                        <span className="sd-point-src">{SOURCE_LABEL[p.source]}</span>
                      )}
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
