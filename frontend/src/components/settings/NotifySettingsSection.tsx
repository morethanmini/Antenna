/* 설정 → 알림 탭. 토글 넷 — 바꾸면 곧바로 저장한다(저장 버튼 없음).

   ── 화면을 먼저 바꾸고 서버에 알린다 ────────────────────
   토글은 눌렀을 때 바로 뒤집혀야 토글이다. 응답을 기다리면 한 박자 늦게 움직여
   두 번 누르게 된다. 실패하면 되돌리고 한 줄 알린다 — 알림 설정은 되돌려도 잃는
   것이 없어 조용히 되돌리는 것이 맞다.

   ── 전부 보낸다 ─────────────────────────────────────────
   PUT 은 다섯 값을 다 요구한다(일부만 보내면 400). 하나를 바꿔도 지금 들고 있는
   전체를 보낸다 — 그래서 먼저 GET 으로 받아 둔 값 위에서만 누를 수 있다. */
import { useCallback, useState } from 'react'
import { getSettings, updateSettings } from '../../api/settings'
import type { NotifyKey, UserSettings } from '../../api/settings'
import { useAsync } from '../../api/useAsync'
import ErrorState from '../state/ErrorState'

/* 순서가 곧 화면 순서. 자주 오는 것부터 — 판정은 내 예측마다 한 번, 반응·소식은 남이 움직일 때마다 */
const ITEMS: { key: NotifyKey; label: string; desc: string }[] = [
  { key: 'notifyVerdict', label: '판정 결과 알림', desc: '내 예측이 판정되면 알려 줍니다' },
  { key: 'notifySocial', label: '내 채널 반응 알림', desc: '새 구독자 · 내 리포트의 댓글과 좋아요를 알려 줍니다' },
  { key: 'notifyChannel', label: '구독 채널 소식 알림', desc: '구독한 채널의 리포트 발행 · 예측 등록 · 구독료 변경을 알려 줍니다' },
  { key: 'notifySeason', label: '시즌 진행 · 보상 알림', desc: '시즌 전환과 보상 지급을 알려 줍니다' },
]

export default function NotifySettingsSection() {
  const loaded = useAsync(getSettings)
  /* 서버 값 위에 사용자가 누른 것을 덮어 둔다. null 이면 아직 손대지 않은 것 — 서버 값 그대로 */
  const [draft, setDraft] = useState<UserSettings | null>(null)
  const [failed, setFailed] = useState(false)

  const current = draft ?? loaded.data

  const toggle = useCallback((key: NotifyKey) => {
    if (!current) return
    const next = { ...current, [key]: !current[key] }
    setDraft(next)
    setFailed(false)
    updateSettings(next)
      .then((saved) => setDraft(saved))
      .catch(() => { setDraft(current); setFailed(true) })
  }, [current])

  return (
    <section className="st-block">
      <h2>알림</h2>
      <p className="st-note">이미 온 알림은 남습니다.</p>

      {loaded.error && <ErrorState error={loaded.error} onRetry={loaded.reload} inline />}

      {current && (
        <ul className="st-switches">
          {ITEMS.map((item) => (
            <li key={item.key}>
              <div className="st-switch-text">
                <b id={`st-${item.key}`}>{item.label}</b>
                <span>{item.desc}</span>
              </div>
              {/* role=switch — 체크박스와 달리 "켬/끔" 으로 읽힌다. 눌러서 바로 바뀌는 것이라 폼이 아니다 */}
              <button
                type="button"
                role="switch"
                aria-checked={current[item.key]}
                aria-labelledby={`st-${item.key}`}
                className={`st-switch${current[item.key] ? ' is-on' : ''}`}
                onClick={() => toggle(item.key)}
              >
                <i aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      )}

      {failed && (
        <p className="st-note is-error" role="alert">저장하지 못해 이전 값으로 돌려 놓았습니다. 다시 눌러 주세요.</p>
      )}
    </section>
  )
}
