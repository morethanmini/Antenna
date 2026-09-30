/* 채널 배너. 내 것이면 눌러서 바꾼다.
   담당 스토리 [ANT-FE-CHANNEL-PROFILE] · 서버는 [ANT-AUTH-05]

   ProfileAvatar 와 같은 짜임이다 — 자리는 화면이 잡고, 올리는 일은 M-07 모달이 하고,
   여기는 받은 fileId 를 PATCH /users/me 에 실어 보낸다. 다른 점 셋만 적는다.

   1. **셸을 건드리지 않는다.** 아바타는 사이드바·헤더가 같은 그림을 들고 있어
      저장 뒤 auth 컨텍스트까지 바꿔야 했다. 배너는 채널 카드에만 있다.
   2. **3:1 로 받는다**(BANNER_ASPECT). 카드 머리에 넓게 깔리는 자리라 비율이 다르면
      위아래가 잘리거나 카드 높이가 그림에 끌려간다.
   3. **없으면 빈 자리를 그린다.** 아바타에는 기본 그림이 있지만 배너는 없다 — 기본
      그림을 두면 안 올린 채널이 전부 같은 사진을 걸고 있는 것처럼 보인다. */
import { useState } from 'react'
import { api, ApiError } from '../../api/client'
import type { MyProfile } from '../../api/account'
import ImageUploadModal from '../upload/ImageUploadModal'

type Props = {
  /** 서버가 준 주소. 안 올렸으면 null */
  src: string | null
  /** 내 채널이 아니면 바꾸는 자리를 두지 않는다 */
  isMe: boolean
  /** 저장 뒤 프로필을 다시 읽는다 */
  onSaved: () => void
}

export default function ChannelBanner({ src, isMe, onSaved }: Props) {
  const [picking, setPicking] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(bannerFileId: string) {
    setSaving(true)
    setError(null)
    try {
      await api.patch<MyProfile>('/users/me', { bannerFileId })
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError && e.message ? e.message : '배너를 저장하지 못했습니다.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className={`ch-banner${src ? '' : ' is-empty'}`}>
      {src ? <img className="ch-banner-img" src={src} alt="" /> : null}

      {isMe && (
        <div className="ch-banner-acts">
          <button type="button" className="pa-btn" onClick={() => setPicking(true)} disabled={saving}>
            {saving ? '저장 중…' : src ? '배너 수정' : '배너 등록'}
          </button>
          {src && (
            <button type="button" className="pa-btn is-drop" onClick={() => void save('')} disabled={saving}>
              지우기
            </button>
          )}
        </div>
      )}
      {error && <p className="pa-error" aria-live="polite">{error}</p>}

      {picking && (
        <ImageUploadModal
          purpose="BANNER"
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
