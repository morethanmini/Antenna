/* E-02 채널 머리의 닉네임. 내 채널이면 여기서 바로 고친다.
   담당 스토리 [ANT-FE-CHANNEL-PROFILE]

   왜 환경 설정이 아니라 여기인가
   닉네임은 **남에게 보이는 값**이다. 설정에서 고치면 내가 어떻게 보이는지 확인하러
   화면을 한 번 더 옮겨야 했다 — 보이는 자리에서 고치면 결과가 그 자리에 바로 뜬다.

   고칠 수 있는 것은 닉네임뿐이다
   소개·관심 섹터·외부 채널은 **서버가 받지 않는다.** PATCH /users/me 의 본문이
   NicknameRequest 하나이고(UserController), MeResponse 에는 interests·channels 가
   아예 없다. 입력란을 먼저 만들면 저장 못 하는 칸이 생기고 사용자는 저장했다고 믿는다.
   서버가 열리면 이 파일에 나란히 붙인다.

   중복 확인은 A-02 와 같은 훅을 쓴다(useNicknameCheck). 최종 판정은 서버다 —
   확인과 확정 사이에 누가 먼저 가져갈 수 있어 409 를 따로 받는다. */
import { useState } from 'react'
import { api, ApiError } from '../../api/client'
import { ERROR_CODE } from '../../api/errors'
import { NICKNAME_MAX, useNicknameCheck, validateNickname } from '../../api/useNicknameCheck'
import type { MeResponse } from '../../auth/session'
import { useAuth } from '../../auth/context'

type Props = {
  nickname: string
  /** 내 채널이 아니면 제목만 그린다 */
  isMe: boolean
  /** 저장 뒤 채널을 다시 읽는다 — 머리의 이름이 서버 값으로 맞춰진다 */
  onSaved: () => void
}

export default function ChannelNickname({ nickname, isMe, onSaved }: Props) {
  const { setNickname } = useAuth()
  const [editing, setEditing] = useState(false)

  if (!isMe) return <h1>{nickname}</h1>

  return editing
    ? <Form current={nickname} onClose={() => setEditing(false)} onSaved={onSaved} setShell={setNickname} />
    : (
      <div className="ch-name">
        <h1>{nickname}</h1>
        <button type="button" className="ch-name-edit" onClick={() => setEditing(true)}>
          이름 바꾸기
        </button>
      </div>
    )
}

function Form({ current, onClose, onSaved, setShell }: {
  current: string
  onClose: () => void
  onSaved: () => void
  setShell: (v: string) => void
}) {
  const [value, setValue] = useState(current)
  const { check, markTaken } = useNicknameCheck(value, current)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const reason = validateNickname(value)
    if (reason) {
      setError(reason)
      return
    }
    setSaving(true)
    setError(null)
    try {
      const me = await api.patch<MeResponse>('/users/me', { nickname: value })
      // 서버가 확정한 값으로 셸을 갱신한다. 화면 입력값을 그대로 믿지 않는다
      setShell(me.nickname ?? value)
      onSaved()
      onClose()
    } catch (e) {
      if (e instanceof ApiError && e.code === ERROR_CODE.DUPLICATE_NICKNAME) {
        markTaken(value)
        setError('이미 사용 중인 닉네임입니다.')
      } else {
        setError('닉네임을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      }
    } finally {
      setSaving(false)
    }
  }

  const hint = {
    idle: `${NICKNAME_MAX}자까지 입력할 수 있습니다.`,
    unchanged: '현재 사용 중인 닉네임입니다.',
    checking: '확인 중…',
    available: '사용할 수 있는 닉네임입니다.',
    taken: '이미 사용 중인 닉네임입니다.',
    invalid: check.state === 'invalid' ? check.reason : '',
  }[check.state]

  return (
    <form className="ch-name-form" onSubmit={(e) => void submit(e)}>
      <div className="ch-name-row">
        {/* eslint-disable-next-line jsx-a11y/no-autofocus -- 사용자가 버튼으로 연 입력이라 초점이 여기 있어야 한다 */}
        <input
          className="ch-name-input"
          type="text"
          value={value}
          autoFocus
          maxLength={NICKNAME_MAX}
          aria-label="닉네임"
          aria-describedby="ch-name-hint"
          aria-invalid={check.state === 'taken' || check.state === 'invalid'}
          onChange={(e) => { setValue(e.target.value); setError(null) }}
        />
        <button type="submit" className="ch-name-save" disabled={saving || check.state !== 'available'}>
          {saving ? '저장 중…' : '저장'}
        </button>
        <button type="button" className="ch-name-cancel" onClick={onClose} disabled={saving}>
          취소
        </button>
      </div>
      <p className={`ch-name-hint is-${check.state}`} id="ch-name-hint" aria-live="polite">
        {error ?? hint}
      </p>
    </form>
  )
}
