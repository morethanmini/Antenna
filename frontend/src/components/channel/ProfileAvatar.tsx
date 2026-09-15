/* 프로필 사진. 내 것이면 눌러서 바꾼다.
   담당 스토리 [ANT-FE-CHANNEL-PROFILE] · 서버는 [ANT-AUTH-05]

   왜 ChannelProfileEdit 와 한 파일이 아닌가
   사진은 카드에서 **값들과 다른 자리**에 있다 — E-02 는 이름·소개 왼쪽, E-05 는 카드 왼쪽이다.
   네 값과 함께 그리려면 두 화면의 헤더 배치를 사진에 맞춰 바꿔야 했다. 대신 화면이 자기
   자리에 놓고, 클래스 이름만 넘긴다.

   올리는 일은 M-07 모달이 한다
   ImageUploadModal 이 로컬 검증·진행률·서버 거절을 이미 다 그린다(그 파일 머리말). 여기서는
   purpose=PROFILE 로 열고, 받은 fileId 를 PATCH /users/me 에 실어 보내기만 한다.

   정사각으로 받는다
   동그랗게 잘라 그리므로 가로세로가 다르면 한쪽이 잘린다. 모달이 자를 수 있게 비율만
   넘긴다(PROFILE_ASPECT) — 잘라 주는 것이 "다시 골라 주세요" 보다 낫다. */
import { useState } from 'react'
import { api, ApiError } from '../../api/client'
import type { MyProfile } from '../../api/account'
import { DEFAULT_AVATAR, useAuth } from '../../auth/context'
import ImageUploadModal from '../upload/ImageUploadModal'
import '../../styles/profile-avatar.css'

type Props = {
  /** 서버가 준 주소. 안 올렸으면 null 이고 기본 그림을 쓴다 */
  src: string | null
  /** 내 사진이 아니면 바꾸는 자리를 두지 않는다 */
  isMe: boolean
  /** 화면마다 크기·모양이 달라 자리 클래스는 화면이 준다 */
  className: string
  /** 저장 뒤 프로필을 다시 읽는다 */
  onSaved: () => void
}

export default function ProfileAvatar({ src, isMe, className, onSaved }: Props) {
  const { setAvatar } = useAuth()
  const [picking, setPicking] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(avatarFileId: string) {
    setSaving(true)
    setError(null)
    try {
      const me = await api.patch<MyProfile>('/users/me', { avatarFileId })
      /* 셸의 아바타도 같이 바꾼다 — 사이드바·헤더가 옛 그림을 들고 있으면
         저장이 안 된 것처럼 보인다. 서버가 확정한 값을 쓴다 */
      setAvatar(me.avatarUrl ?? DEFAULT_AVATAR)
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError && e.message ? e.message : '사진을 저장하지 못했습니다.')
    } finally {
      setSaving(false)
    }
  }

  const img = <img className={className} src={src ?? DEFAULT_AVATAR} alt="" />

  if (!isMe) return img

  return (
    <div className="pa">
      {img}
      <div className="pa-acts">
        <button type="button" className="pa-btn" onClick={() => setPicking(true)} disabled={saving}>
          {saving ? '저장 중…' : '사진 수정'}
        </button>
        {/* 기본 그림으로 되돌리는 길. 올린 적이 없으면 되돌릴 것도 없다 */}
        {src && (
          <button type="button" className="pa-btn is-drop" onClick={() => void save('')} disabled={saving}>
            지우기
          </button>
        )}
      </div>
      {error && <p className="pa-error" aria-live="polite">{error}</p>}

      {picking && (
        <ImageUploadModal
          purpose="PROFILE"
          onClose={() => setPicking(false)}
          onUploaded={(image) => {
            setPicking(false)
            void save(image.fileId)
          }}
        />
      )}
    </div>
  )
}
