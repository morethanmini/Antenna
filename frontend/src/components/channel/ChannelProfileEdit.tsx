/* E-02 채널 머리의 프로필. 내 채널이면 여기서 바로 고친다.
   담당 스토리 [ANT-FE-CHANNEL-PROFILE] · 서버는 [ANT-AUTH-05]

   왜 환경 설정이 아니라 여기인가
   닉네임·소개·관심 섹터·외부 채널은 **남에게 보이는 값**이다. 설정에서 고치면 내가 어떻게
   보이는지 확인하러 화면을 한 번 더 옮겨야 했다 — 보이는 자리에서 고치면 결과가 그 자리에
   바로 뜬다. 남이 보는 모습은 ?as=visitor 로 확인한다.

   한 번에 저장한다
   PATCH /users/me 가 네 필드를 한 트랜잭션으로 받는다. 그래서 닉네임이 409 로 막히면
   소개도 저장되지 않는다 — 화면이 "저장" 한 번으로 받아들이는 것과 서버의 단위가 같다.
   보내는 것은 **바뀐 필드뿐이다.** 안 바뀐 값을 같이 실어 보내면, 내가 폼을 연 사이 다른
   기기에서 고친 값을 옛 값으로 덮는다.

   배열 둘은 전체 교체다
   관심 섹터·외부 채널은 부분 수정 API 가 없다(명세 §회원). 목록을 통째로 보낸다.

   섹터 어휘는 서버가 준다
   ERD 가 user_interests.sector 를 "stocks.sector 와 같은 어휘" 로 못 박았다. 자유 입력으로
   두면 랭킹·종목 탐색의 섹터 필터와 어긋나 아무 데도 안 걸리는 값이 쌓인다. 그래서
   GET /stocks/sectors 가 주는 목록에서 고른다(B-02·G-09 와 같은 출처). */
import { useCallback, useState } from 'react'
import { api, ApiError } from '../../api/client'
import { ERROR_CODE } from '../../api/errors'
import { NICKNAME_MAX, useNicknameCheck, validateNickname } from '../../api/useNicknameCheck'
import { getSectorSummary } from '../../api/stocks'
import { useAsync } from '../../api/useAsync'
import { platformLabel, type MyProfile, type UserChannel } from '../../api/account'
import { useAuth } from '../../auth/context'

const INTRODUCE_MAX = 200
const CHANNEL_URL_MAX = 300
/** 명세 §회원 의 네 가지. 화면 아이콘을 정하는 값이라 서버 enum 과 같은 순서로 둔다 */
const PLATFORMS = ['YOUTUBE', 'BLOG', 'X', 'ETC'] as const

type Props = {
  profile: {
    nickname: string
    bio: string | null
    interests: string[]
    externalLinks: { label: string; url: string }[]
  }
  /** 내 채널이 아니면 읽기 전용이다 */
  isMe: boolean
  /** 폼을 펼친 채로 연다 — 미리보기의 "프로필 수정" 에서 넘어온 경우 */
  startOpen?: boolean
  /** 폼이 닫혔다(저장했거나 취소했다). 주소의 edit 표시를 지우는 자리다 */
  onExit?: () => void
  /** 저장 뒤 채널을 다시 읽는다 — 머리의 값이 서버 값으로 맞춰진다 */
  onSaved: () => void
}

export default function ChannelProfileEdit(
  { profile, isMe, startOpen = false, onExit, onSaved }: Props,
) {
  const [editing, setEditing] = useState(startOpen)

  if (!isMe) return <h1>{profile.nickname}</h1>

  if (editing) {
    const close = () => {
      setEditing(false)
      onExit?.()
    }
    return <Form profile={profile} onClose={close} onSaved={onSaved} />
  }

  return (
    <div className="ch-name">
      <h1>{profile.nickname}</h1>
      <button type="button" className="ch-name-edit" onClick={() => setEditing(true)}>
        프로필 수정
      </button>
    </div>
  )
}

function Form({ profile, onClose, onSaved }: {
  profile: Props['profile']
  onClose: () => void
  onSaved: () => void
}) {
  const { setNickname: setShellNickname } = useAuth()

  const [nickname, setNickname] = useState(profile.nickname)
  const [introduce, setIntroduce] = useState(profile.bio ?? '')
  const [interests, setInterests] = useState<string[]>(profile.interests)
  const [channels, setChannels] = useState<UserChannel[]>(
    /* 화면은 라벨로 받았는데 서버는 platform 을 받는다. 라벨에서 되짚지 않고
       ETC 로 떨어뜨린다 — 되짚기를 만들면 라벨 문구가 바뀔 때마다 조용히 틀린다.
       채널은 E-02 가 채널 응답으로 그리므로, 고치는 순간 사용자가 다시 고른다. */
    profile.externalLinks.map((l) => ({ platform: 'ETC' as const, url: l.url })),
  )

  const { check, markTaken } = useNicknameCheck(nickname, profile.nickname)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadSectors = useCallback(() => getSectorSummary(), [])
  const sectors = useAsync(loadSectors)
  /* null 은 "전체" 라 고를 수 없다(SectorSummary 머리말) */
  const options = (sectors.data?.items ?? [])
    .map((s) => s.sector)
    .filter((s): s is string => s !== null)

  const nicknameChanged = nickname !== profile.nickname
  const introduceChanged = introduce !== (profile.bio ?? '')
  const interestsChanged = !sameList(interests, profile.interests)
  const channelsChanged = !sameList(
    channels.map((c) => c.url),
    profile.externalLinks.map((l) => l.url),
  ) || channels.some((c) => c.platform !== 'ETC')

  const dirty = nicknameChanged || introduceChanged || interestsChanged || channelsChanged
  const nicknameBlocked = nicknameChanged && check.state !== 'available'

  async function submit(event: React.FormEvent) {
    event.preventDefault()

    if (nicknameChanged) {
      const reason = validateNickname(nickname)
      if (reason) {
        setError(reason)
        return
      }
    }
    const badUrl = channels.find((c) => !c.url.startsWith('https://'))
    if (badUrl) {
      setError('채널 주소는 https 로 시작해야 합니다.')
      return
    }

    /* 바뀐 필드만 싣는다 — 안 보낸 필드를 서버가 건드리지 않는다(ProfileUpdateRequest) */
    const patch: Record<string, unknown> = {}
    if (nicknameChanged) patch.nickname = nickname
    if (introduceChanged) patch.introduce = introduce
    if (interestsChanged) patch.interests = interests
    if (channelsChanged) patch.channels = channels

    setSaving(true)
    setError(null)
    try {
      const me = await api.patch<MyProfile>('/users/me', patch)
      // 서버가 확정한 값으로 셸을 갱신한다. 화면 입력값을 그대로 믿지 않는다
      if (nicknameChanged) setShellNickname(me.nickname ?? nickname)
      onSaved()
      onClose()
    } catch (e) {
      if (e instanceof ApiError && e.code === ERROR_CODE.DUPLICATE_NICKNAME) {
        markTaken(nickname)
        /* 서버가 넷을 한 트랜잭션으로 묶었다 — 소개까지 통째로 안 들어갔다.
           "닉네임만 실패" 로 읽히면 사용자가 소개는 저장된 줄 알고 창을 닫는다. */
        setError('이미 사용 중인 닉네임입니다. 다른 항목도 저장되지 않았습니다.')
      } else {
        setError('프로필을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      }
    } finally {
      setSaving(false)
    }
  }

  const nicknameHint = {
    idle: `${NICKNAME_MAX}자까지 입력할 수 있습니다.`,
    unchanged: '현재 사용 중인 닉네임입니다.',
    checking: '확인 중…',
    available: '사용할 수 있는 닉네임입니다.',
    taken: '이미 사용 중인 닉네임입니다.',
    invalid: check.state === 'invalid' ? check.reason : '',
  }[check.state]

  return (
    <form className="ch-edit" onSubmit={(e) => void submit(e)}>
      {/* ── 닉네임 ───────────────────────────────── */}
      <div className="ch-edit-row">
        <label htmlFor="ch-edit-nickname">닉네임</label>
        {/* eslint-disable-next-line jsx-a11y/no-autofocus -- 사용자가 버튼으로 연 폼이라 초점이 첫 칸에 있어야 한다 */}
        <input
          id="ch-edit-nickname"
          className="ch-name-input"
          type="text"
          value={nickname}
          autoFocus
          maxLength={NICKNAME_MAX}
          aria-describedby="ch-edit-nickname-hint"
          aria-invalid={check.state === 'taken' || check.state === 'invalid'}
          onChange={(e) => { setNickname(e.target.value); setError(null) }}
        />
        <p className={`ch-name-hint is-${check.state}`} id="ch-edit-nickname-hint" aria-live="polite">
          {nicknameHint}
        </p>
      </div>

      {/* ── 소개 ─────────────────────────────────── */}
      <div className="ch-edit-row">
        <label htmlFor="ch-edit-introduce">소개</label>
        <textarea
          id="ch-edit-introduce"
          className="ch-edit-area"
          value={introduce}
          rows={3}
          maxLength={INTRODUCE_MAX}
          placeholder="어떤 종목을 어떤 근거로 보는지 적어 주세요"
          onChange={(e) => { setIntroduce(e.target.value); setError(null) }}
        />
        <p className="ch-name-hint">{`${introduce.length} / ${INTRODUCE_MAX}자`}</p>
      </div>

      {/* ── 관심 섹터 ────────────────────────────── */}
      <fieldset className="ch-edit-row">
        <legend>관심 섹터</legend>
        {sectors.loading && <p className="ch-name-hint">섹터 목록을 불러오는 중…</p>}
        {sectors.error && (
          /* 목록을 못 받으면 고를 수가 없다. 자유 입력으로 열어 주지 않는다 —
             서버 어휘와 어긋난 값이 들어가면 랭킹·탐색의 섹터 필터에 안 걸린다 */
          <p className="ch-name-hint is-invalid">
            섹터 목록을 불러오지 못했습니다. 관심 섹터는 이번에 고칠 수 없습니다.
          </p>
        )}
        {options.length > 0 && (
          <ul className="ch-edit-chips">
            {options.map((sector) => {
              const on = interests.includes(sector)
              return (
                <li key={sector}>
                  <button
                    type="button"
                    className={on ? 'is-on' : undefined}
                    aria-pressed={on}
                    onClick={() => setInterests(
                      on ? interests.filter((s) => s !== sector) : [...interests, sector],
                    )}
                  >
                    {sector}
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </fieldset>

      {/* ── 외부 채널 ────────────────────────────── */}
      <fieldset className="ch-edit-row">
        <legend>외부 채널</legend>
        <ul className="ch-edit-links">
          {channels.map((channel, i) => (
            // 행을 지우고 더하는 목록이라 url 을 키로 쓰면 빈 행 둘이 겹친다
            // eslint-disable-next-line react/no-array-index-key
            <li key={i}>
              <select
                aria-label={`${i + 1}번째 채널 플랫폼`}
                value={channel.platform}
                onChange={(e) => setChannels(channels.map((c, j) => (
                  j === i ? { ...c, platform: e.target.value as UserChannel['platform'] } : c
                )))}
              >
                {PLATFORMS.map((p) => <option key={p} value={p}>{platformLabel(p)}</option>)}
              </select>
              <input
                type="url"
                aria-label={`${i + 1}번째 채널 주소`}
                value={channel.url}
                maxLength={CHANNEL_URL_MAX}
                placeholder="https://"
                onChange={(e) => { setChannels(channels.map((c, j) => (
                  j === i ? { ...c, url: e.target.value } : c
                ))); setError(null) }}
              />
              <button type="button" className="ch-edit-drop" onClick={() => setChannels(channels.filter((_, j) => j !== i))}>
                삭제
              </button>
            </li>
          ))}
        </ul>
        <button
          type="button"
          className="ch-edit-add"
          onClick={() => setChannels([...channels, { platform: 'YOUTUBE', url: '' }])}
        >
          채널 추가
        </button>
        {/* https 만 받는 이유를 미리 말한다 — 저장 눌러 400 을 받고 알게 하지 않는다 */}
        <p className="ch-name-hint">주소는 https 로 시작해야 합니다.</p>
      </fieldset>

      <div className="ch-name-row">
        <button type="submit" className="ch-name-save" disabled={saving || !dirty || nicknameBlocked}>
          {saving ? '저장 중…' : '저장'}
        </button>
        <button type="button" className="ch-name-cancel" onClick={onClose} disabled={saving}>
          취소
        </button>
      </div>
      {error && <p className="ch-name-hint is-invalid" aria-live="polite">{error}</p>}
    </form>
  )
}

/** 순서까지 같아야 같은 것으로 본다 — 서버가 넣은 순서대로 내려주므로 순서 변경도 저장거리다 */
function sameList(a: string[], b: string[]) {
  return a.length === b.length && a.every((v, i) => v === b[i])
}
