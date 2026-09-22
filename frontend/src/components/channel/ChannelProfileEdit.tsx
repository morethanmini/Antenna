/* E-02 채널 머리의 프로필. 내 채널이면 **수정 하나로 네 항목을 함께** 고친다.
   담당 스토리 [ANT-FE-CHANNEL-PROFILE] · 서버는 [ANT-AUTH-05]

   왜 환경 설정이 아니라 여기인가
   닉네임·소개·관심 섹터·외부 채널은 **남에게 보이는 값**이다. 설정에서 고치면 내가 어떻게
   보이는지 확인하러 화면을 한 번 더 옮겨야 했다. 남이 보는 모습은 ?as=visitor 로 본다.

   읽기용 표시도 여기서 그린다
   같은 값을 화면과 이 파일이 나눠 그리면, 누를 수 있는 자리와 고쳐지는 자리가 어긋난다.
   그래서 네 항목 모두 이 컴포넌트가 읽기용·편집용을 함께 들고 있다. 남의 채널이면
   누를 수 없는 읽기용만 그린다.

   수정 자리는 하나다
   한때 항목마다 "수정" 을 달아 하나씩 저장했는데(2026-09-15), 프로필 카드에 작은 버튼이
   넷씩 붙어 값보다 버튼이 먼저 읽혔다. 프로필은 한자리에서 훑어보고 한 번에 손보는
   것이라, 닉네임 옆의 하나로 폼 전체를 연다.

   보내는 것은 바뀐 필드뿐이다
   PATCH /users/me 는 **보낸 필드만** 바꾼다. 안 고친 값까지 실어 보내면 폼을 연 사이
   다른 기기에서 고친 값을 옛 값으로 덮는다. 폼이 넷을 함께 들고 있어도 요청은 그렇지 않다.

   다만 저장은 한 덩어리다 — 서버가 넷을 한 트랜잭션으로 처리하므로 닉네임이 409 로
   막히면 소개도 저장되지 않는다. 그 사실을 오류 문구에 그대로 적는다.

   빈 값도 자리를 둔다
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
import { profanityError } from '../../text/profanity'
import { getSectorSummary } from '../../api/stocks'
import { useAsync } from '../../api/useAsync'
import {
  channelLabel, detectPlatform, withScheme, type MyProfile, type ProfileUpdate, type UserChannel,
} from '../../api/account'
import { useAuth } from '../../auth/context'
import '../../styles/profile-edit.css'

const INTRODUCE_MAX = 200
const CHANNEL_URL_MAX = 300
const LABEL_MAX = 30
/** 외부 채널 수 상한. 서버(UserDtos.MAX_CHANNELS)와 같은 값 — 넘기면 400 이다 */
const MAX_CHANNELS = 2
/** 명세 §회원 의 네 가지. 화면 아이콘을 정하는 값이라 서버 enum 과 같은 순서로 둔다 */
const PLATFORMS = ['YOUTUBE', 'BLOG', 'X', 'ETC'] as const
/** 고르는 자리에서는 ETC 가 "기타" 가 아니라 할 일이다 — 고르면 이름 칸이 열린다 */
const PLATFORM_OPTION: Record<(typeof PLATFORMS)[number], string> = {
  YOUTUBE: '유튜브', BLOG: '블로그', X: 'X', ETC: '직접 입력',
}

type Profile = {
  nickname: string
  bio: string | null
  interests: string[]
  externalLinks: UserChannel[]
}

type Props = {
  profile: Profile
  /** 내 채널이 아니면 누를 수 없는 읽기용만 그린다 */
  isMe: boolean
  /**
   * 닉네임을 그릴 제목 단계. E-02 는 채널 이름이 화면의 제목이라 h1, E-05 는
   * "마이페이지" 가 h1 이라 그 아래 h2 다. 문서의 제목 단계를 건너뛰면 스크린리더의
   * 목차가 어긋난다.
   */
  heading?: 'h1' | 'h2'
  /**
   * 값이 있는 자리를 아무 데나 눌러도 편집이 열린다(E-05 마이페이지).
   *
   * E-02 채널은 켜지 않는다 — 그쪽은 **남에게 보여 주는 화면**이라 훑어보다 잘못 눌러
   * 폼이 열리면 당황스럽고, 카드 안에 밖으로 나가는 링크가 함께 있다.
   */
  clickToEdit?: boolean
  /** 저장 뒤 채널을 다시 읽는다 — 머리의 값이 서버 값으로 맞춰진다 */
  onSaved: () => void
}

export default function ChannelProfileEdit(
  { profile, isMe, heading = 'h1', clickToEdit = false, onSaved }: Props,
) {
  const Heading = heading
  const [editing, setEditing] = useState(false)

  if (editing) {
    return (
      <Form
        /* 저장하면 채널을 다시 읽는다 — 그때 서버 값으로 폼을 다시 세우려고 key 를 건다 */
        key={`${profile.nickname}|${profile.bio ?? ''}|${profile.interests.join()}`}
        profile={profile}
        heading={heading}
        onClose={() => setEditing(false)}
        onSaved={onSaved}
      />
    )
  }

  /* 링크·버튼에서 시작한 클릭은 지나간다 — 외부 채널 링크를 누른 사람은 그리로 가려는
     것이지 프로필을 고치려는 것이 아니다. 키보드는 아래 "수정" 버튼이 맡는다:
     이 덩어리를 role="button" 으로 만들면 카드 전체가 하나의 거대한 버튼으로 읽힌다. */
  const open = clickToEdit && isMe
    ? (event: React.MouseEvent) => {
        if ((event.target as HTMLElement).closest('a, button')) return
        setEditing(true)
      }
    : undefined

  return (
    <div className={open ? 'ch-read is-tappable' : 'ch-read'} onClick={open}>
      {/* ── 닉네임 · 수정 자리는 여기 하나뿐이다 ───── */}
      <div className={isMe ? 'ch-line' : undefined}>
        <Heading>{profile.nickname}</Heading>
        {isMe && (
          <button
            type="button"
            className="ch-line-edit"
            aria-label="프로필 수정"
            onClick={() => setEditing(true)}
          >
            수정
          </button>
        )}
      </div>

      {/* ── 소개 ───────────────────────────────────
          여기만 이름표가 없다. 이름 바로 아래의 문단이라 그 자체로 소개로 읽히고,
          아래 둘(칩 줄·링크 줄)과 달리 무엇인지 헷갈릴 여지가 없다. */}
      {(profile.bio || isMe) && (
        <p className={`ch-bio${profile.bio ? '' : ' is-empty'}`}>
          {profile.bio || '소개를 아직 적지 않았습니다'}
        </p>
      )}

      {/* ── 관심 섹터 ────────────────────────────── */}
      {(profile.interests.length > 0 || isMe) && (
        <Field label="관심 섹터">
          {profile.interests.length > 0 ? (
            <ul className="ch-interests">
              {profile.interests.map((tag) => <li key={tag}>{tag}</li>)}
            </ul>
          ) : (
            <p className="ch-bio is-empty">아직 고르지 않았습니다</p>
          )}
        </Field>
      )}

      {/* ── 외부 채널 ────────────────────────────── */}
      {(profile.externalLinks.length > 0 || isMe) && (
        <Field label="외부 채널">
          {profile.externalLinks.length > 0 ? (
            <ul className="ch-links">
              {profile.externalLinks.map((l) => (
                <li key={l.url}>
                  {/* 외부로 나가는 링크다. 참조자 정보를 넘기지 않는다 */}
                  <a href={l.url} target="_blank" rel="noreferrer noopener">
                    {channelLabel(l)}
                    <ExternalIcon />
                  </a>
                </li>
              ))}
            </ul>
          ) : (
            <p className="ch-bio is-empty">아직 등록하지 않았습니다</p>
          )}
        </Field>
      )}
    </div>
  )
}

/**
 * 읽기용 한 줄 — 왼쪽에 이름표, 오른쪽에 값.
 *
 * 이름표가 없으면 소개 문단과 칩 줄과 링크 줄이 그냥 이어져 보여 무엇이 무엇인지
 * 모른다. 이름표는 값이 아니므로 작고 흐리게 두고, 값의 **첫 줄**에 맞춘다 —
 * 소개가 세 줄이면 가운데 정렬은 이름표를 문단 한가운데로 내려보낸다.
 */
function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="ch-field">
      <span className="ch-field-label">{label}</span>
      <div className="ch-field-value">{children}</div>
    </div>
  )
}

/* ── 편집 폼 — 네 항목을 함께 ─────────────────────────── */

function Form({ profile, heading, onClose, onSaved }: {
  profile: Profile
  heading: 'h1' | 'h2'
  onClose: () => void
  onSaved: () => void
}) {
  const { setNickname: setShellNickname } = useAuth()

  const [nickname, setNickname] = useState(profile.nickname)
  const [introduce, setIntroduce] = useState(profile.bio ?? '')
  const [interests, setInterests] = useState<string[]>(profile.interests)
  /* 서버가 주는 모양 그대로다 — 라벨에서 플랫폼을 되짚던 때와 달리 잃는 정보가 없다 */
  const [rows, setRows] = useState<UserChannel[]>(profile.externalLinks)

  const { check, markTaken } = useNicknameCheck(nickname, profile.nickname)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadSectors = useCallback(() => getSectorSummary(), [])
  const sectors = useAsync(loadSectors)
  /* null 은 "전체" 라 고를 수 없다(SectorSummary 머리말) */
  const options = (sectors.data?.items ?? []).map((s) => s.sector).filter((s): s is string => s !== null)

  const nicknameChanged = nickname !== profile.nickname
  const introduceChanged = introduce !== (profile.bio ?? '')
  const interestsChanged = !sameList(interests, profile.interests)
  const channelsChanged = rows.length !== profile.externalLinks.length
    || rows.some((r, i) => r.url !== profile.externalLinks[i]?.url)
    || rows.some((r, i) => r.platform !== profile.externalLinks[i]?.platform)
    || rows.some((r, i) => (r.label ?? null) !== (profile.externalLinks[i]?.label ?? null))

  const dirty = nicknameChanged || introduceChanged || interestsChanged || channelsChanged
  const nicknameBlocked = nicknameChanged && check.state !== 'available'

  const nicknameHint = {
    idle: `${NICKNAME_MAX}자까지 입력할 수 있습니다.`,
    unchanged: '현재 사용 중인 닉네임입니다.',
    checking: '확인 중…',
    available: '사용할 수 있는 닉네임입니다.',
    taken: '이미 사용 중인 닉네임입니다.',
    invalid: check.state === 'invalid' ? check.reason : '',
  }[check.state]

  async function submit(event: React.FormEvent) {
    event.preventDefault()

    if (nicknameChanged) {
      const reason = validateNickname(nickname)
      if (reason) {
        setError(reason)
        return
      }
    }
    /* 소개·채널 이름도 남에게 보이는 글자다 — 서버 @NoProfanity 가 400 을 주기 전에 여기서 튕긴다.
       바꾼 필드만 본다: 예전에 저장된 값 때문에 다른 항목 저장까지 막히면 안 된다 (ANT-COMMUNITY-09) */
    const badText =
      (introduceChanged && profanityError(introduce)) ||
      (channelsChanged && rows.map((r) => profanityError(r.label)).find(Boolean)) ||
      null
    if (badText) {
      setError(badText)
      return
    }
    /* 스킴을 안 적은 주소는 https:// 를 붙여 준다(withScheme). blur 에서도 붙이지만
       Enter 로 제출하면 blur 가 먼저 오지 않으므로 여기서 한 번 더 — 화면에도 붙은 값을 보인다 */
    const channels = rows.map((r) => (r.url === withScheme(r.url) ? r : { ...r, url: withScheme(r.url) }))
    if (channels.some((c, i) => c !== rows[i])) setRows(channels)
    /* 그래도 https 가 아니면(http:// 를 직접 적은 경우) 서버가 400 을 준다. 눌러 보고 알게 하지 않는다 */
    if (channels.some((r) => !r.url.startsWith('https://'))) {
      setError('채널 주소는 https 로 시작해야 합니다.')
      return
    }

    /* 바뀐 필드만 싣는다 — 안 보낸 필드를 서버가 건드리지 않는다(ProfileUpdateRequest) */
    const patch: ProfileUpdate = {}
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
           "닉네임만 실패" 로 읽히면 소개는 저장된 줄 알고 창을 닫는다. */
        setError('이미 사용 중인 닉네임입니다. 다른 항목도 저장되지 않았습니다.')
      } else {
        setError('프로필을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <form className="ch-edit" onSubmit={(e) => void submit(e)}>
      {/* 제목 단계를 읽기용과 맞춘다 — 편집 중이라고 목차가 바뀌면 안 된다 */}
      <p className="ch-edit-title" role={heading === 'h1' ? 'heading' : undefined} aria-level={1}>
        프로필 수정
      </p>

      {/* ── 닉네임 ───────────────────────────────── */}
      <div className="ch-edit-row">
        <label className="ch-edit-label" htmlFor="ch-edit-nickname">닉네임</label>
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
        <label className="ch-edit-label" htmlFor="ch-edit-introduce">소개</label>
        <textarea
          id="ch-edit-introduce"
          className="ch-edit-area"
          value={introduce}
          rows={3}
          maxLength={INTRODUCE_MAX}
          placeholder="어떤 종목을 어떤 근거로 보는지 적어 주세요"
          onChange={(e) => setIntroduce(e.target.value)}
        />
        {/* 비우고 저장하면 지운 것으로 본다(서버 User.changeIntroduce) */}
        <p className="ch-name-hint">{`${introduce.length} / ${INTRODUCE_MAX}자`}</p>
      </div>

      {/* ── 관심 섹터 ────────────────────────────── */}
      <fieldset className="ch-edit-row">
        <legend className="ch-edit-label">관심 섹터</legend>
        {sectors.loading && <p className="ch-name-hint">섹터 목록을 불러오는 중…</p>}
        {sectors.error && (
          /* 목록을 못 받으면 고를 수가 없다. 자유 입력으로 열어 주지 않는다 —
             서버 어휘와 어긋난 값은 랭킹·탐색의 섹터 필터에 안 걸린다 */
          <p className="ch-name-hint is-invalid">섹터 목록을 불러오지 못했습니다. 관심 섹터는 이번에 고칠 수 없습니다.</p>
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
        <legend className="ch-edit-label">외부 채널</legend>
        <ul className="ch-edit-links">
          {rows.map((row, i) => (
            // 행을 지우고 더하는 목록이라 url 을 키로 쓰면 빈 행 둘이 겹친다
            // eslint-disable-next-line react/no-array-index-key
            <li key={i}>
              <select
                aria-label={`${i + 1}번째 채널 플랫폼`}
                value={row.platform}
                onChange={(e) => setRows(rows.map((r, j) => {
                  if (j !== i) return r
                  const platform = e.target.value as UserChannel['platform']
                  /* 표에 있는 곳으로 되돌리면 직접 붙인 이름은 버린다 — 유튜브인데
                     이름이 "브런치" 로 남아 있으면 아이콘과 글자가 어긋난다 */
                  return { ...r, platform, label: platform === 'ETC' ? r.label : null }
                }))}
              >
                {PLATFORMS.map((p) => <option key={p} value={p}>{PLATFORM_OPTION[p]}</option>)}
              </select>
              {/* 표에 없는 곳은 이름을 직접 붙인다. 비우면 '기타' 로 보인다(channelLabel) */}
              {row.platform === 'ETC' && (
                <input
                  type="text"
                  className="ch-edit-name"
                  aria-label={`${i + 1}번째 채널 이름`}
                  value={row.label ?? ''}
                  maxLength={LABEL_MAX}
                  placeholder="이름 (예: 브런치)"
                  onChange={(e) => setRows(rows.map((r, j) => (
                    j === i ? { ...r, label: e.target.value } : r
                  )))}
                />
              )}
              {/* type="url" 이 아니다 — 브라우저 기본 검증이 "youtube.com" 을 "URL을 입력하세요" 로
                  막아 submit 까지 오지 못했다(2026-09-23). 스킴은 우리가 붙이므로(withScheme)
                  글자 칸으로 두고 키패드만 url 로 연다 */}
              <input
                type="text"
                inputMode="url"
                aria-label={`${i + 1}번째 채널 주소`}
                value={row.url}
                maxLength={CHANNEL_URL_MAX}
                placeholder="https://"
                onChange={(e) => {
                  setRows(rows.map((r, j) => (j === i ? withUrl(r, e.target.value) : r)))
                  setError(null)
                }}
                /* 칸을 떠날 때 https:// 를 붙여 보인다 — 저장 눌러서야 바뀌면 뭐가 붙었는지 모른다 */
                onBlur={() => setRows(rows.map((r, j) => (
                  j === i && r.url !== withScheme(r.url) ? { ...r, url: withScheme(r.url) } : r
                )))}
              />
              <button type="button" className="ch-edit-drop" onClick={() => setRows(rows.filter((_, j) => j !== i))}>
                삭제
              </button>
            </li>
          ))}
        </ul>
        {/* 상한에 닿으면 끈다(2026-09-19, QA 09-18). 전에는 개수 제한이 없어 몇 개든 늘어났다 */}
        {/* 새 행은 "직접 입력" 으로 시작한다 — 유튜브로 시작하면 다른 곳을 적는 사람이
            매번 바꿔야 했다. 표에 있는 곳은 주소를 치면 withUrl 이 알아서 고른다 */}
        <button
          type="button"
          className="ch-edit-add"
          disabled={rows.length >= MAX_CHANNELS}
          onClick={() => setRows([...rows, { platform: 'ETC', label: null, url: '' }])}
        >
          채널 추가
        </button>
        {/* https 만 받는 이유와 개수 제한을 미리 말한다 — 저장 눌러 400 을 받고 알게 하지 않는다 */}
        <p className="ch-name-hint">{`주소는 https 로 시작해야 합니다. 채널은 ${MAX_CHANNELS}개까지 등록할 수 있습니다.`}</p>
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

/**
 * 주소를 고치면서 플랫폼도 따라 고른다.
 *
 * 표에 있는 호스트(유튜브·X·블로그)면 그 플랫폼으로 바꾸고, 직접 붙인 이름은 버린다 —
 * 아이콘은 유튜브인데 글자가 "브런치" 면 어긋난다(플랫폼 select 의 onChange 와 같은 규칙).
 *
 * 표에 없는 주소가 되면 두 갈래다. 지금 플랫폼이 **이전 주소에서 되짚은 값**이면
 * (유튜브 주소를 지우고 브런치 주소를 치는 중) "직접 입력" 으로 되돌린다. 사람이 select 에서
 * 직접 고른 값이면 — 자기 도메인 블로그를 BLOG 로 골라 둔 경우 — 건드리지 않는다.
 */
function withUrl(row: UserChannel, url: string): UserChannel {
  const detected = detectPlatform(url)
  if (detected) {
    return detected === row.platform ? { ...row, url } : { ...row, url, platform: detected, label: null }
  }
  const cameFromUrl = row.platform !== 'ETC' && detectPlatform(row.url) === row.platform
  return cameFromUrl ? { ...row, url, platform: 'ETC' } : { ...row, url }
}

/** 순서까지 같아야 같은 것으로 본다 — 서버가 넣은 순서대로 내려주므로 순서 변경도 저장거리다 */
function sameList(a: string[], b: string[]) {
  return a.length === b.length && a.every((v, i) => v === b[i])
}

function ExternalIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M14 5h5v5" /><path d="M19 5l-8 8" />
      <path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" />
    </svg>
  )
}
