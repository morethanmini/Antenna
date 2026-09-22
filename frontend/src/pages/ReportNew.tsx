/* F-03 리포트 작성 · /reports/new
   담당 스토리 [ANT-FE-REPORT-NEW]
   설계서 docs/화면설계서.md §3 F · §4 F-03 · §9.1 · §9.2

   설계 제약
   - 본문 필드는 title · body · visibility 셋이고 visibility 는 필수다.
     공개 / 구독자 전용 두 선택지를 반드시 노출한다 — 기본값으로 숨기지 않는다.
     그래서 처음에는 아무것도 고르지 않은 상태로 두고, 고르기 전에는 발행을 막는다.
   - 임시 저장(초안)을 두지 않는다. DRAFT 상태도 수정·삭제 API 도 없다.
     작성 중 이탈 시 경고만 띄운다.
   - 두지 않는 것: 분야 · 한 줄 요약 · 임시 저장.

   이미지 첨부
   서버 ReportCreateRequest 는 지금도 (title, body, visibility) 뿐이라 fileId 를 받을
   자리가 없다. 그래서 그림은 **본문 마크다운 안에** 주소로만 들어간다 — 올리기는
   POST /uploads(M-07)가 맡고, 이 화면은 받은 주소를 본문에 붙인다.
   화면에서 어떻게 다루는지는 아래 composeBody 머리말.

   이탈 경고의 범위
   beforeunload 로 새로고침 · 창 닫기 · 외부 이동을 막는다. 앱 안에서 사이드바를
   눌러 나가는 것은 막지 못한다 — react-router 의 useBlocker 는 데이터 라우터에서만
   동작하는데 이 앱은 BrowserRouter 다(main.tsx). 라우터 교체는 셸 전체에 영향이
   가는 [ANT-FE-LAYOUT] 몫이라 이 스토리에서 건드리지 않는다.
   대신 화면 안의 취소 버튼은 직접 확인을 받는다. */
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { releaseIdempotencyKey } from '../api/idempotency'
import { BODY_MAX, REPORT_CREATE_SCOPE, TITLE_MAX, createReport, reportSigningPayload } from '../api/reports'
import { requestNonce } from '../api/wallet'
import { ERROR_CODE } from '../api/errors'
import { useAuth } from '../auth/context'
import { connectAddress, hasWallet, personalSign } from '../wallet/provider'
import WalletLinkModal from '../components/wallet/WalletLinkModal'
import WalletPrelink from '../components/wallet/WalletPrelink'
import ConfirmDialog from '../components/ConfirmDialog'
import { errorText } from '../components/state/errorText'
import FieldError from '../components/state/FieldError'
import ImageUploadModal from '../components/upload/ImageUploadModal'
import Markdown from '../components/report/Markdown'
import { containsProfanity, PROFANITY_MESSAGE } from '../text/profanity'
import '../styles/screens/report-new.css'

/* 서버는 boolean 하나를 받지만 화면은 "고르지 않음"을 구분해야 한다.
   null 이면 아직 안 골랐다는 뜻이고, 그 상태로는 발행하지 않는다. */
type Visibility = boolean | null

/* ── 사진은 본문 글자에 전혀 섞지 않는다 (2026-09-23) ──────────────
   처음에는 커서 자리에 `![](/api/v1/uploads/f9f1e192-…)` 를 꽂았다. 쓰는 사람이
   개발자가 아니면 이게 무슨 글자인지 알 수 없다. 그래서 `[사진 1]` 이라는 자리표시자로
   바꿔 보았는데, 그것도 **본문에 군더더기 글자가 뜨는 것**은 마찬가지였다
   (2026-09-23 — "사진은 왜 뜨냐고, 밑에 첨부한 거 뜨잖아").

   지금은 본문 글자에 아무것도 넣지 않는다. 올린 사진은 입력칸 아래 첨부 줄에만 있고,
   **발행·미리보기 직전에 본문 끝에 붙인다.**

   ── 잃은 것: 글 중간에 그림 넣기 ──────────────────────
   사진은 항상 글 끝에 순서대로 붙는다. "이 문단 옆에 이 차트" 를 못 한다. 그 대신
   본문 입력칸에는 쓴 사람이 적은 글만 남는다. 리포트가 근거를 적는 글이고 그림은
   차트 갈무리를 덧붙이는 쪽에 가까워, 무엇을 잃는지 알고 고른 쪽이다.

   저장 형식은 그대로 마크다운이다 — 본문은 keccak256 해시로 서명돼 온체인에
   올라가므로(api/reports.ts reportSigningPayload) 서버로 가는 문자열은 바꿀 수 없다.
   바꾼 것은 **화면에 보이는 것**뿐이다. */
type BodyImage = { no: number; url: string }

/** 화면에 보이는 글 + 첨부한 사진 → 서버에 보낼 마크다운.
 *  미리보기와 발행이 **같은 함수**를 쓴다 — 미리보기가 실제와 다르면 미리보기가 아니다. */
function composeBody(text: string, images: BodyImage[]): string {
  if (images.length === 0) return text
  /* 그림은 제 줄에 둔다. 글 끝에 바로 붙이면 마지막 문단 안 그림이 되어 폭을 밀어낸다 */
  const shots = images.map((i) => `![](${i.url})`).join('\n\n')
  const body = text.trimEnd()
  return body === '' ? shots : `${body}\n\n${shots}`
}

const VISIBILITY_OPTIONS = [
  { value: true, label: '공개', desc: '누구나 전문을 볼 수 있어요' },
  { value: false, label: '구독자 전용', desc: '구독자만 본문을 읽고, 나머지는 제목만 봅니다' },
] as const

export default function ReportNew() {
  const navigate = useNavigate()
  const { user } = useAuth()

  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [visibility, setVisibility] = useState<Visibility>(null)

  /* 쓰기와 미리보기를 한 화면에 나란히 두지 않고 탭으로 가른다 — 좁은 화면에서 둘을
     나란히 놓으면 둘 다 못 읽을 폭이 되고, 리포트는 길게 쓰는 글이다. */
  const [tab, setTab] = useState<'write' | 'preview'>('write')
  const [picking, setPicking] = useState(false)
  /** 올린 사진. 본문 글자에는 없고 여기에만 있다 — 발행 때 끝에 붙는다(위 머리말) */
  const [images, setImages] = useState<BodyImage[]>([])
  /* 이미지를 넣으면 상한을 넘는 경우. 조용히 안 넣으면 버튼이 고장 난 것처럼 보인다 */
  const [tooLong, setTooLong] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  /** 발행 확인 창이 떠 있는가 */
  const [confirming, setConfirming] = useState(false)
  const [signing, setSigning] = useState(false)
  const [linking, setLinking] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  /** 화면 선검사에서 비속어가 걸린 입력란. 서버 400 의 fieldError 와 같은 자리에 붙인다 */
  const [profanityField, setProfanityField] = useState<'title' | 'body' | null>(null)
  /* 지갑 쪽 실패는 서버 오류가 아니라 ApiError 가 아니다 — 거부·확장 미설치 등.
     ApiError 자리에 억지로 끼우지 않고 따로 문장 하나를 둔다(PredictTab 과 같은 판단). */
  const [localMsg, setLocalMsg] = useState<string | null>(null)

  /**
   * 사진을 첨부 줄에 더한다. **본문 글자는 건드리지 않는다**(위 머리말).
   *
   * 번호는 다시 쓰지 않는다 — 지웠다 다시 올릴 때 번호가 당겨지면, 첨부 줄을 보며
   * "사진 2를 빼자" 고 마음먹은 사람이 다른 그림을 지우게 된다.
   */
  function addImage(url: string) {
    const no = images.reduce((max, i) => Math.max(max, i.no), 0) + 1
    const next = [...images, { no, url }]

    /* 상한은 **붙인 뒤 길이**로 잰다. 첨부 줄에는 글자가 한 자도 안 늘지만
       서버로 가는 본문에는 주소가 든 마크다운이 마흔 자 넘게 붙는다 */
    if (composeBody(body, next).length > BODY_MAX) {
      setTooLong(true)
      return
    }
    setTooLong(false)
    setImages(next)
    edited()
  }

  /** 첨부 줄에서 빼기. 본문 글자에는 아무것도 없으므로 목록에서만 덜어 내면 된다 */
  function removeImage(no: number) {
    setImages((list) => list.filter((i) => i.no !== no))
    setTooLong(false)
    edited()
  }

  /* 서버로 갈 본문. 글자 수 · 상한 · 미리보기 · 발행이 모두 이 값을 본다 —
     화면에 보이는 글자 수가 서버가 세는 수와 다르면 상한 앞에서 사용자가 속는다 */
  const composed = composeBody(body, images)
  const overflow = composed.length > BODY_MAX

  const dirty = title !== '' || body !== '' || visibility !== null
  const ready = title.trim() !== '' && body.trim() !== '' && visibility !== null && !overflow

  /* 초안이 없으므로 나가면 글이 사라진다. 발행 중에는 경고하지 않는다
     — 이미 서버로 갔고, 여기서 막으면 사용자가 더 헷갈린다. */
  useEffect(() => {
    if (!dirty || submitting) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty, submitting])

  /* 내용이 바뀌면 이전 시도의 멱등키를 버린다.
     같은 키로 다른 본문을 보내면 409 IDEMPOTENCY_KEY_REUSED 다.
     반대로 내용을 그대로 두고 다시 누르면 키가 남아 있어 중복 발행이 막힌다. */
  function edited() {
    releaseIdempotencyKey(REPORT_CREATE_SCOPE)
    setError(null)
    setProfanityField(null)
  }

  /**
   * 발행은 지갑 서명을 거친다 (ANT-CHAIN-16).
   *
   * 리포트가 온체인에 올라가는데 서명이 없으면 "이 지갑 주인이 썼다" 를 서버를 믿어야만
   * 알 수 있다. 예측 등록(C-01)과 같은 흐름이고 scope 만 REPORT_PUBLISH 다.
   *
   * 미설치와 미연동을 여기서 가르지 않는다 — M-01(WalletLinkModal)이 이미 네 갈래를
   * 각각 다른 안내로 다룬다. 여기서 문구를 또 쓰면 같은 안내가 두 곳에 생긴다.
   */
  function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!ready || submitting) return

    if (!hasWallet() || !user?.walletLinked) {
      setLinking(true)
      return
    }
    /* 비속어는 지갑 서명 **전에** 거른다 (ANT-COMMUNITY-09). 제목은 서명 payload 에, 본문은 봉인 해시에
       들어가서 서버가 글자를 고쳐 줄 수 없다 — 400 을 받으면 서명부터 다시 해야 하니 여기서 튕긴다 */
    const bad = containsProfanity(title) ? 'title' : containsProfanity(body) ? 'body' : null
    if (bad) {
      setProfanityField(bad)
      return
    }
    /* 서명으로 가기 전에 한 번 묻는다(2026-09-19, QA 09-18). 발행은 되돌릴 수 없는데 버튼 하나로
       바로 지갑 창이 떴다 — 나가는 쪽(cancel)은 묻고 되돌릴 수 없는 쪽은 안 묻던 것을 바로잡는다. */
    setConfirming(true)
  }

  async function publish() {
    setConfirming(false)
    // ready 가 이미 보장하지만 이 함수 안에서는 타입이 좁혀지지 않는다
    if (visibility === null) return
    setSubmitting(true)
    setSigning(true)
    setError(null)
    setLocalMsg(null)
    try {
      /* 서명에 들어갈 값은 지금 이 입력에서 나와야 한다. 다듬은 제목(trim)을 서버에
         그대로 보내므로 서명도 같은 값에 붙어야 SIGNER_MISMATCH 가 나지 않는다. */
      const draft = { title: title.trim(), body: composed, visibility }

      const address = await connectAddress()
      const nonce = await requestNonce('REPORT_PUBLISH')
      const signature = await personalSign(await reportSigningPayload(draft, nonce), address)
      setSigning(false)

      const { id } = await createReport(draft, signature)
      // 발행이 확정됐으니 키를 놓는다. 다음 리포트는 새 키를 받는다.
      releaseIdempotencyKey(REPORT_CREATE_SCOPE)
      // 뒤로 가기로 작성 화면에 되돌아오지 않게 replace 로 바꿔치운다
      navigate(`/reports/${id}`, { replace: true })
    } catch (e) {
      setSigning(false)
      setSubmitting(false)
      if (e instanceof ApiError) setError(e)
      else setLocalMsg(e instanceof Error ? e.message : '서명을 마치지 못했습니다.')
    }
  }

  function cancel() {
    // 초안이 없으니 나가면 사라진다. 앱 안 이동은 여기서만 확인을 받을 수 있다.
    if (dirty && !window.confirm('작성 중인 내용이 사라집니다. 나가시겠습니까?')) return
    navigate('/reports')
  }

  /* 400 은 어느 필드가 틀렸는지 field 로 온다. 화면 전체 오류가 아니라
     그 입력란 옆에 붙인다(설계서 §6). */
  const fieldError = error?.code === ERROR_CODE.INVALID_REQUEST ? error.field : undefined
  const formError = error && !fieldError ? errorText(error) : null

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>리포트 작성</h1>
          <p>지갑으로 서명해 발행해요. 발행 후에는 수정하거나 지울 수 없어요</p>
        </div>

        {/* 지갑 없이 들어왔으면 쓰기 **전에** 연동부터 권한다(ANT-FE-WALLET-PRELINK). 긴 글을 다 쓰고
            발행을 눌렀는데 지갑 확장이 없으면, 연동 창이 시키는 새로고침에 글이 통째로 사라진다 */}
        <WalletPrelink purpose="리포트를 발행하려면" />

        <form className="rn" onSubmit={submit}>
          <div className="rn-field">
            <label className="rn-label" htmlFor="rn-title">
              제목
              <span className="rn-count num">{`${title.length}/${TITLE_MAX}`}</span>
            </label>
            <input
              className="rn-input" id="rn-title" type="text"
              value={title}
              onChange={(e) => { setTitle(e.target.value); edited() }}
              maxLength={TITLE_MAX}
              disabled={submitting}
              aria-invalid={fieldError === 'title' || profanityField === 'title'}
              placeholder="무엇에 대한 리포트인가요?"
            />
            {fieldError === 'title' && <FieldError>제목을 다시 확인해 주세요.</FieldError>}
            {profanityField === 'title' && <FieldError>{PROFANITY_MESSAGE}</FieldError>}
          </div>

          <div className="rn-field">
            <label className="rn-label" htmlFor="rn-body">
              본문
              <span className="rn-count num">{`${composed.length.toLocaleString('ko-KR')}/${BODY_MAX.toLocaleString('ko-KR')}`}</span>
            </label>

            {/* 쓰기 ↔ 미리보기. 발행 뒤 화면과 **같은 컴포넌트**로 그린다 —
                미리보기가 실제와 다르면 미리보기가 아니라 거짓말이다. */}
            <div className="rn-tools">
              <div className="rn-tabs" role="tablist" aria-label="본문 보기">
                {(['write', 'preview'] as const).map((t) => (
                  <button
                    key={t} type="button" role="tab"
                    className={tab === t ? 'on' : undefined}
                    aria-selected={tab === t}
                    onClick={() => setTab(t)}
                    disabled={submitting}
                  >
                    {t === 'write' ? '작성' : '미리보기'}
                  </button>
                ))}
              </div>

              {/* 이미지 버튼은 쓰기 탭에만 둔다 — 넣을 커서가 그쪽에만 있다 */}
              {tab === 'write' && (
                <button
                  type="button" className="rn-img-btn"
                  onClick={() => setPicking(true)} disabled={submitting}
                >
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <rect x="3" y="4" width="18" height="16" rx="2" /><circle cx="8.5" cy="9.5" r="1.5" /><path d="m21 16-5-5L6 20" />
                  </svg>
                  이미지
                </button>
              )}
            </div>

            {tab === 'write' ? (
              <textarea
                className="rn-textarea" id="rn-body"
                value={body}
                onChange={(e) => { setBody(e.target.value); setTooLong(false); edited() }}
                /* maxLength 를 걸지 않는다 — 막는 기준은 raw 길이가 아니라 자리표시자를
                   바꾼 뒤 길이(overflow)다. 넘으면 아래 줄로 알리고 발행을 막는다 */
                disabled={submitting}
                rows={16}
                aria-invalid={fieldError === 'body' || profanityField === 'body'}
                placeholder={'근거와 판단을 적어 주세요.'}
              />
            ) : (
              /* 빈 본문에 빈 상자만 두면 미리보기가 고장 난 줄 안다 */
              <div className="rn-preview">
                {body.trim() === ''
                  ? <p className="rn-preview-empty">아직 쓴 내용이 없습니다.</p>
                  : <Markdown text={composed} />}
              </div>
            )}

            {/* 첨부한 사진. 본문 글자에는 아무것도 안 들어가므로, 무엇을 올렸는지
                알 수 있는 곳은 여기뿐이다. 붙는 순서도 이 순서다.
                쓰기 탭에만 둔다 — 미리보기는 그림을 이미 제자리에 그린다. */}
            {tab === 'write' && images.length > 0 && (
              <ul className="rn-shots">
                {images.map((img, i) => (
                  <li key={img.no} className="rn-shot">
                    <img src={img.url} alt="" />
                    {/* 번호는 첨부 줄에서 세는 자리 순서다. img.no(다시 쓰지 않는 번호)를
                        그대로 보이면 하나 빼는 순간 "사진 1, 사진 3" 처럼 구멍이 난다 */}
                    <span className="rn-shot-no">{`사진 ${i + 1}`}</span>
                    <button
                      type="button" className="rn-shot-x"
                      onClick={() => removeImage(img.no)}
                      disabled={submitting}
                      aria-label={`사진 ${i + 1} 빼기`}
                    >
                      빼기
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {tooLong && <FieldError>본문이 상한에 닿아 사진을 넣지 못했습니다.</FieldError>}
            {overflow && <FieldError>본문이 너무 깁니다. 줄이거나 사진을 빼 주세요.</FieldError>}
            {fieldError === 'body' && <FieldError>본문을 다시 확인해 주세요.</FieldError>}
            {profanityField === 'body' && <FieldError>{PROFANITY_MESSAGE}</FieldError>}
          </div>

          {/* 두 선택지를 반드시 노출한다. 기본값을 미리 골라 두지 않는다
              — 구독자 전용인 줄 모르고 공개로 발행하는 사고를 막는다 */}
          <fieldset className="rn-field rn-visibility" disabled={submitting}>
            <legend className="rn-label">공개 범위</legend>
            {VISIBILITY_OPTIONS.map((o) => (
              <label key={o.label} className={`rn-option ${visibility === o.value ? 'on' : ''}`}>
                <input
                  type="radio" name="visibility"
                  checked={visibility === o.value}
                  onChange={() => { setVisibility(o.value); edited() }}
                />
                <span className="rn-option-text">
                  <b>{o.label}</b>
                  <small>{o.desc}</small>
                </span>
              </label>
            ))}
            {fieldError === 'visibility' && <FieldError>공개 범위를 선택해 주세요.</FieldError>}
          </fieldset>

          {formError && (
            <div className="rn-error" role="alert">
              <b>{formError.title}</b>
              {formError.hint && <span>{formError.hint}</span>}
            </div>
          )}

          {/* 지갑 쪽 실패(거부·확장 없음). 서버 오류와 자리를 나눈다 — 사용자가 할 일이 다르다 */}
          {localMsg && (
            <div className="rn-error" role="alert">
              <b>{localMsg}</b>
            </div>
          )}

          <div className="rn-actions">
            <button type="button" className="rn-btn ghost" onClick={cancel} disabled={submitting}>
              취소
            </button>
            <button type="submit" className="rn-btn solid" disabled={!ready || submitting}>
              {signing ? '지갑에서 서명해 주세요…' : submitting ? '발행 중…' : '서명하고 발행'}
            </button>
          </div>

          {!ready && !submitting && (
            <p className="rn-hint">제목·본문·공개 범위를 채워 주세요.</p>
          )}
          {ready && !submitting && (
            <p className="rn-hint">
              발행한 글은 블록체인에 기록돼 수정하거나 지울 수 없어요. 지갑 서명으로 작성자를 확인해요.
            </p>
          )}
        </form>

        {/* 업로드 배관은 이미 있다 — purpose 만 REPORT 다(UploadFile.Purpose).
            비율 규칙을 주지 않아 원본 비율 그대로 올라간다: 리포트 그림은 차트·표 갈무리라
            정사각이나 4:1 로 자르면 읽을 수 없게 된다. */}
        {picking && (
          <ImageUploadModal
            purpose="REPORT"
            onClose={() => setPicking(false)}
            onUploaded={(img) => { addImage(img.url); setPicking(false) }}
          />
        )}
      </div>

      {/* 미설치·미연동·주소 불일치를 한 곳에서 다룬다(M-01). 닫으면 작성 내용은 그대로 남는다 */}
      {linking && <WalletLinkModal onClose={() => setLinking(false)} />}

      {confirming && (
        <ConfirmDialog
          title="리포트를 발행할까요?"
          description="발행하면 수정하거나 지울 수 없습니다. 제목·본문·공개 범위를 다시 확인해 주세요. 확인하면 지갑 서명으로 이어집니다."
          confirmLabel="서명하고 발행"
          onCancel={() => setConfirming(false)}
          onConfirm={() => { void publish() }}
        />
      )}
    </main>
  )
}
