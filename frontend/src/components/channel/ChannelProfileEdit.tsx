/* E-02 채널 머리의 프로필. 내 채널이면 **값을 눌러 그 항목만** 고친다.
   담당 스토리 [ANT-FE-CHANNEL-PROFILE] · 서버는 [ANT-AUTH-05]

   왜 환경 설정이 아니라 여기인가
   닉네임·소개·관심 섹터·외부 채널은 **남에게 보이는 값**이다. 설정에서 고치면 내가 어떻게
   보이는지 확인하러 화면을 한 번 더 옮겨야 했다. 남이 보는 모습은 ?as=visitor 로 본다.

   읽기용 표시도 여기서 그린다
   같은 값을 화면과 이 파일이 나눠 그리면, 누를 수 있는 자리와 고쳐지는 자리가 어긋난다.
   그래서 네 항목 모두 이 컴포넌트가 읽기용·편집용을 함께 들고 있다. 남의 채널이면
   누를 수 없는 읽기용만 그린다.

   항목 하나씩 고치고 하나씩 저장한다
   PATCH /users/me 는 **보낸 필드만** 바꾼다. 그래서 소개를 고칠 때 닉네임을 같이 실어
   보내지 않는다 — 실어 보내면 칸을 연 사이 다른 기기에서 고친 값을 옛 값으로 덮는다.
   한 번에 한 항목만 열리는 것도 같은 이유다. 여러 칸을 열어 두고 하나만 저장하면
   나머지 입력은 조용히 사라진다.

   빈 값도 누를 자리를 둔다
   소개가 없으면 "아직 적지 않았습니다" 를 그린다. 아무것도 안 그리면 적을 수 있다는
   사실 자체를 모른다. 남의 채널에서는 그 자리를 통째로 뺀다 — 남의 빈칸은 알 바 아니다.

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
import {
  platformLabel, type MyProfile, type ProfileUpdate, type UserChannel,
} from '../../api/account'
import { useAuth } from '../../auth/context'
import '../../styles/profile-edit.css'

const INTRODUCE_MAX = 200
const CHANNEL_URL_MAX = 300
/** 명세 §회원 의 네 가지. 화면 아이콘을 정하는 값이라 서버 enum 과 같은 순서로 둔다 */
const PLATFORMS = ['YOUTUBE', 'BLOG', 'X', 'ETC'] as const

type Field = 'nickname' | 'introduce' | 'interests' | 'channels'

type Props = {
  profile: {
    nickname: string
    bio: string | null
    interests: string[]
    externalLinks: { label: string; url: string }[]
  }
  /** 내 채널이 아니면 누를 수 없는 읽기용만 그린다 */
  isMe: boolean
  /**
   * 닉네임을 그릴 제목 단계. E-02 는 채널 이름이 화면의 제목이라 h1, E-05 는
   * "마이페이지" 가 h1 이라 그 아래 h2 다. 문서의 제목 단계를 건너뛰면 스크린리더의
   * 목차가 어긋난다.
   */
  heading?: 'h1' | 'h2'
  /** 저장 뒤 채널을 다시 읽는다 — 머리의 값이 서버 값으로 맞춰진다 */
  onSaved: () => void
}

export default function ChannelProfileEdit({ profile, isMe, heading = 'h1', onSaved }: Props) {
  const Heading = heading
  /** 지금 열려 있는 항목. 한 번에 하나뿐이다(머리말) */
  const [open, setOpen] = useState<Field | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const { setNickname: setShellNickname } = useAuth()

  const cancel = useCallback(() => { setOpen(null); setError(null) }, [])

  /** 한 항목만 보낸다. 성공하면 채널을 다시 읽고 닫는다 */
  const save = useCallback(async (patch: ProfileUpdate) => {
    setSaving(true)
    setError(null)
    try {
      const me = await api.patch<MyProfile>('/users/me', patch)
      // 서버가 확정한 값으로 셸을 갱신한다. 화면 입력값을 그대로 믿지 않는다
      if (patch.nickname !== undefined) setShellNickname(me.nickname ?? patch.nickname)
      onSaved()
      setOpen(null)
      return true
    } catch (e) {
      if (e instanceof ApiError && e.code === ERROR_CODE.DUPLICATE_NICKNAME) {
        setError('이미 사용 중인 닉네임입니다.')
      } else {
        setError('저장하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      }
      return false
    } finally {
      setSaving(false)
    }
  }, [onSaved, setShellNickname])

  const common = { saving, error, onCancel: cancel, onSave: save }

  return (
    <>
      {/* ── 닉네임 ───────────────────────────────── */}
      {open === 'nickname' ? (
        <NicknameField {...common} current={profile.nickname} />
      ) : (
        <Line isMe={isMe} label="닉네임" onOpen={() => setOpen('nickname')}>
          <Heading>
            <Tap isMe={isMe} label="닉네임 고치기" onOpen={() => setOpen('nickname')}>
              {profile.nickname}
            </Tap>
          </Heading>
        </Line>
      )}

      {/* ── 소개 ─────────────────────────────────── */}
      {open === 'introduce' ? (
        <IntroduceField {...common} current={profile.bio} />
      ) : (profile.bio || isMe) && (
        <Line isMe={isMe} label="소개" onOpen={() => setOpen('introduce')}>
          <p className={`ch-bio${profile.bio ? '' : ' is-empty'}`}>
            <Tap isMe={isMe} label="소개 고치기" onOpen={() => setOpen('introduce')}>
              {profile.bio || '소개를 아직 적지 않았습니다'}
            </Tap>
          </p>
        </Line>
      )}

      {/* ── 관심 섹터 ────────────────────────────── */}
      {open === 'interests' ? (
        <InterestsField {...common} current={profile.interests} />
      ) : profile.interests.length > 0 ? (
        <Line isMe={isMe} label="관심 섹터" onOpen={() => setOpen('interests')}>
          <ul className="ch-interests">
            {profile.interests.map((tag) => <li key={tag}>{tag}</li>)}
          </ul>
        </Line>
      ) : isMe && (
        <Line isMe label="관심 섹터" onOpen={() => setOpen('interests')}>
          <p className="ch-bio is-empty">
            <Tap isMe label="관심 섹터 고르기" onOpen={() => setOpen('interests')}>
              관심 섹터를 아직 고르지 않았습니다
            </Tap>
          </p>
        </Line>
      )}

      {/* ── 외부 채널 ────────────────────────────── */}
      {open === 'channels' ? (
        <ChannelsField {...common} current={profile.externalLinks} />
      ) : profile.externalLinks.length > 0 ? (
        <Line isMe={isMe} label="외부 채널" onOpen={() => setOpen('channels')}>
          <ul className="ch-links">
            {profile.externalLinks.map((l) => (
              <li key={l.url}>
                {/* 외부로 나가는 링크다. 참조자 정보를 넘기지 않는다 */}
                <a href={l.url} target="_blank" rel="noreferrer noopener">
                  {l.label}
                  <ExternalIcon />
                </a>
              </li>
            ))}
          </ul>
        </Line>
      ) : isMe && (
        <Line isMe label="외부 채널" onOpen={() => setOpen('channels')}>
          <p className="ch-bio is-empty">
            <Tap isMe label="외부 채널 추가" onOpen={() => setOpen('channels')}>
              외부 채널을 아직 등록하지 않았습니다
            </Tap>
          </p>
        </Line>
      )}
    </>
  )
}

/**
 * 읽기용 한 줄. 값 오른쪽에 작은 "수정" 을 붙인다.
 *
 * 값 자체도 누르면 열리지만(Tap), 그것만으로는 **누를 수 있다는 사실이 안 보인다** —
 * 밑줄은 손을 올려야 나오고, 비어 있는 항목은 더더욱 눈에 안 띈다. 버튼을 따로 두면
 * 훑기만 해도 고칠 수 있는 자리가 보인다. 값보다 작고 흐리게 둬서 값을 가리지 않는다.
 */
function Line({ isMe, label, onOpen, children }: {
  isMe: boolean
  label: string
  onOpen: () => void
  children: React.ReactNode
}) {
  if (!isMe) return <>{children}</>
  return (
    <div className="ch-line">
      {children}
      <button type="button" className="ch-line-edit" aria-label={`${label} 수정`} onClick={onOpen}>
        수정
      </button>
    </div>
  )
}

/**
 * 읽기용 값. 내 채널이면 눌러서 그 항목을 연다.
 *
 * 남의 채널에서는 버튼이 아니라 그냥 글자다 — 누를 수 없는 것을 버튼으로 그리면
 * 키보드 초점이 아무 일도 하지 않는 자리에 걸린다.
 */
function Tap({ isMe, label, onOpen, children }: {
  isMe: boolean
  label: string
  onOpen: () => void
  children: React.ReactNode
}) {
  if (!isMe) return <>{children}</>
  return (
    <button type="button" className="ch-tap" aria-label={label} onClick={onOpen}>
      {children}
    </button>
  )
}

/* ── 항목별 편집 ──────────────────────────────────────── */

type FieldProps = {
  saving: boolean
  error: string | null
  onCancel: () => void
  onSave: (patch: ProfileUpdate) => Promise<boolean>
}

function NicknameField({ current, saving, error, onCancel, onSave }: FieldProps & { current: string }) {
  const [value, setValue] = useState(current)
  const { check, markTaken } = useNicknameCheck(value, current)
  const [local, setLocal] = useState<string | null>(null)

  const hint = {
    idle: `${NICKNAME_MAX}자까지 입력할 수 있습니다.`,
    unchanged: '현재 사용 중인 닉네임입니다.',
    checking: '확인 중…',
    available: '사용할 수 있는 닉네임입니다.',
    taken: '이미 사용 중인 닉네임입니다.',
    invalid: check.state === 'invalid' ? check.reason : '',
  }[check.state]

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const reason = validateNickname(value)
    if (reason) {
      setLocal(reason)
      return
    }
    setLocal(null)
    /* 확인과 확정 사이에 누가 먼저 가져갈 수 있다 — 최종 판정자는 서버(409)다 */
    const ok = await onSave({ nickname: value })
    if (!ok) markTaken(value)
  }

  return (
    <Row label="닉네임" saving={saving} error={local ?? error} onCancel={onCancel}
         canSave={check.state === 'available'} onSubmit={submit}>
      {/* eslint-disable-next-line jsx-a11y/no-autofocus -- 사용자가 값을 눌러 연 칸이라 초점이 여기 있어야 한다 */}
      <input
        className="ch-name-input"
        type="text"
        value={value}
        autoFocus
        maxLength={NICKNAME_MAX}
        aria-label="닉네임"
        aria-invalid={check.state === 'taken' || check.state === 'invalid'}
        onChange={(e) => { setValue(e.target.value); setLocal(null) }}
      />
      <p className={`ch-name-hint is-${check.state}`} aria-live="polite">{hint}</p>
    </Row>
  )
}

function IntroduceField({ current, saving, error, onCancel, onSave }: FieldProps & { current: string | null }) {
  const [value, setValue] = useState(current ?? '')

  return (
    <Row label="소개" saving={saving} error={error} onCancel={onCancel}
         canSave={value !== (current ?? '')}
         onSubmit={(e) => { e.preventDefault(); void onSave({ introduce: value }) }}>
      {/* eslint-disable-next-line jsx-a11y/no-autofocus -- 위와 같다 */}
      <textarea
        className="ch-edit-area"
        value={value}
        autoFocus
        rows={3}
        maxLength={INTRODUCE_MAX}
        aria-label="소개"
        placeholder="어떤 종목을 어떤 근거로 보는지 적어 주세요"
        onChange={(e) => setValue(e.target.value)}
      />
      {/* 비우고 저장하면 지운 것으로 본다(서버 User.changeIntroduce) */}
      <p className="ch-name-hint">{`${value.length} / ${INTRODUCE_MAX}자`}</p>
    </Row>
  )
}

function InterestsField({ current, saving, error, onCancel, onSave }: FieldProps & { current: string[] }) {
  const [picked, setPicked] = useState<string[]>(current)
  const load = useCallback(() => getSectorSummary(), [])
  const sectors = useAsync(load)
  /* null 은 "전체" 라 고를 수 없다(SectorSummary 머리말) */
  const options = (sectors.data?.items ?? []).map((s) => s.sector).filter((s): s is string => s !== null)
  const changed = picked.length !== current.length || picked.some((s, i) => s !== current[i])

  return (
    <Row label="관심 섹터" saving={saving} error={error} onCancel={onCancel} canSave={changed}
         onSubmit={(e) => { e.preventDefault(); void onSave({ interests: picked }) }}>
      {sectors.loading && <p className="ch-name-hint">섹터 목록을 불러오는 중…</p>}
      {sectors.error && (
        /* 목록을 못 받으면 고를 수가 없다. 자유 입력으로 열어 주지 않는다 —
           서버 어휘와 어긋난 값은 랭킹·탐색의 섹터 필터에 안 걸린다 */
        <p className="ch-name-hint is-invalid">섹터 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</p>
      )}
      {options.length > 0 && (
        <ul className="ch-edit-chips">
          {options.map((sector) => {
            const on = picked.includes(sector)
            return (
              <li key={sector}>
                <button
                  type="button"
                  className={on ? 'is-on' : undefined}
                  aria-pressed={on}
                  onClick={() => setPicked(on ? picked.filter((s) => s !== sector) : [...picked, sector])}
                >
                  {sector}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </Row>
  )
}

function ChannelsField(
  { current, saving, error, onCancel, onSave }:
  FieldProps & { current: { label: string; url: string }[] },
) {
  /* 화면은 라벨로 받았는데 서버는 platform 을 받는다. 라벨에서 되짚지 않고 ETC 로
     떨어뜨린다 — 되짚기를 만들면 라벨 문구가 바뀔 때마다 조용히 틀린다. */
  const [rows, setRows] = useState<UserChannel[]>(
    current.map((l) => ({ platform: 'ETC' as const, url: l.url })),
  )
  const [local, setLocal] = useState<string | null>(null)
  const changed = rows.length !== current.length
    || rows.some((r, i) => r.url !== current[i]?.url)
    || rows.some((r) => r.platform !== 'ETC')

  function submit(event: React.FormEvent) {
    event.preventDefault()
    /* https 가 아니면 서버가 400 을 준다. 눌러 보고 알게 하지 않는다 */
    if (rows.some((r) => !r.url.startsWith('https://'))) {
      setLocal('채널 주소는 https 로 시작해야 합니다.')
      return
    }
    setLocal(null)
    void onSave({ channels: rows })
  }

  return (
    <Row label="외부 채널" saving={saving} error={local ?? error} onCancel={onCancel}
         canSave={changed} onSubmit={submit}>
      <ul className="ch-edit-links">
        {rows.map((row, i) => (
          // 행을 지우고 더하는 목록이라 url 을 키로 쓰면 빈 행 둘이 겹친다
          // eslint-disable-next-line react/no-array-index-key
          <li key={i}>
            <select
              aria-label={`${i + 1}번째 채널 플랫폼`}
              value={row.platform}
              onChange={(e) => setRows(rows.map((r, j) => (
                j === i ? { ...r, platform: e.target.value as UserChannel['platform'] } : r
              )))}
            >
              {PLATFORMS.map((p) => <option key={p} value={p}>{platformLabel(p)}</option>)}
            </select>
            <input
              type="url"
              aria-label={`${i + 1}번째 채널 주소`}
              value={row.url}
              maxLength={CHANNEL_URL_MAX}
              placeholder="https://"
              onChange={(e) => {
                setRows(rows.map((r, j) => (j === i ? { ...r, url: e.target.value } : r)))
                setLocal(null)
              }}
            />
            <button type="button" className="ch-edit-drop" onClick={() => setRows(rows.filter((_, j) => j !== i))}>
              삭제
            </button>
          </li>
        ))}
      </ul>
      <button type="button" className="ch-edit-add" onClick={() => setRows([...rows, { platform: 'YOUTUBE', url: '' }])}>
        채널 추가
      </button>
      <p className="ch-name-hint">주소는 https 로 시작해야 합니다.</p>
    </Row>
  )
}

/** 항목 하나의 껍데기. 라벨·입력·저장·취소를 한 모양으로 둔다 */
function Row({ label, saving, error, canSave, onCancel, onSubmit, children }: {
  label: string
  saving: boolean
  error: string | null
  canSave: boolean
  onCancel: () => void
  onSubmit: (event: React.FormEvent) => void
  children: React.ReactNode
}) {
  return (
    <form className="ch-edit-row" onSubmit={onSubmit}>
      <b className="ch-edit-label">{label}</b>
      {children}
      <div className="ch-name-row">
        <button type="submit" className="ch-name-save" disabled={saving || !canSave}>
          {saving ? '저장 중…' : '저장'}
        </button>
        <button type="button" className="ch-name-cancel" onClick={onCancel} disabled={saving}>
          취소
        </button>
      </div>
      {error && <p className="ch-name-hint is-invalid" aria-live="polite">{error}</p>}
    </form>
  )
}

function ExternalIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M14 5h5v5" /><path d="M19 5l-8 8" />
      <path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" />
    </svg>
  )
}
