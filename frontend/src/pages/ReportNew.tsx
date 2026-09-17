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

   이미지 첨부를 넣지 않은 이유
   두 겹으로 막혀 있다. POST /uploads 가 아직 없고(M-07 [ANT-FE-UPLOAD]),
   그보다 먼저 서버 ReportCreateRequest 가 (title, body, visibility) 뿐이라
   fileId 를 받을 자리 자체가 없다. 업로드 모달만 만들어도 발행에 실을 곳이 없다.
   두 가지가 다 열린 뒤에 붙인다.

   이탈 경고의 범위
   beforeunload 로 새로고침 · 창 닫기 · 외부 이동을 막는다. 앱 안에서 사이드바를
   눌러 나가는 것은 막지 못한다 — react-router 의 useBlocker 는 데이터 라우터에서만
   동작하는데 이 앱은 BrowserRouter 다(main.tsx). 라우터 교체는 셸 전체에 영향이
   가는 [ANT-FE-LAYOUT] 몫이라 이 스토리에서 건드리지 않는다.
   대신 화면 안의 취소 버튼은 직접 확인을 받는다. */
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { releaseIdempotencyKey } from '../api/idempotency'
import { BODY_MAX, REPORT_CREATE_SCOPE, TITLE_MAX, createReport, reportSigningPayload } from '../api/reports'
import { requestNonce } from '../api/wallet'
import { ERROR_CODE } from '../api/errors'
import { useAuth } from '../auth/context'
import { connectAddress, hasWallet, personalSign } from '../wallet/provider'
import WalletLinkModal from '../components/wallet/WalletLinkModal'
import { errorText } from '../components/state/errorText'
import FieldError from '../components/state/FieldError'
import ImageUploadModal from '../components/upload/ImageUploadModal'
import Markdown from '../components/report/Markdown'
import '../styles/screens/report-new.css'

/* 서버는 boolean 하나를 받지만 화면은 "고르지 않음"을 구분해야 한다.
   null 이면 아직 안 골랐다는 뜻이고, 그 상태로는 발행하지 않는다. */
type Visibility = boolean | null

const VISIBILITY_OPTIONS = [
  { value: true, label: '공개', desc: '누구나 전문을 읽을 수 있습니다' },
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
  /* 이미지를 넣으면 상한을 넘는 경우. 조용히 안 넣으면 버튼이 고장 난 것처럼 보인다 */
  const [tooLong, setTooLong] = useState(false)
  /* 커서 자리에 꽂아 넣으려면 실제 요소가 있어야 한다. 탭을 옮겨도 textarea 는
     언마운트되므로, 넣기는 항상 쓰기 탭에서만 일어난다(이미지 버튼도 거기 있다). */
  const bodyRef = useRef<HTMLTextAreaElement>(null)

  const [submitting, setSubmitting] = useState(false)
  const [signing, setSigning] = useState(false)
  const [linking, setLinking] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  /* 지갑 쪽 실패는 서버 오류가 아니라 ApiError 가 아니다 — 거부·확장 미설치 등.
     ApiError 자리에 억지로 끼우지 않고 따로 문장 하나를 둔다(PredictTab 과 같은 판단). */
  const [localMsg, setLocalMsg] = useState<string | null>(null)

  /**
   * 커서 자리에 마크다운을 꽂는다. 끝에 붙이지 않는 이유 — 글 중간에 그림을 넣으려던
   * 사람이 매번 잘라 옮겨야 한다.
   *
   * 이미지는 제 줄에 둔다. 글 한가운데 끼면 문단 안 그림이 되어 폭을 밀어낸다.
   * 커서는 alt 자리(대괄호 안)에 둔다 — 설명을 바로 이어 쓸 수 있고, 안 쓰면 빈 alt 다.
   */
  function insertImage(url: string) {
    const el = bodyRef.current
    const start = el ? el.selectionStart : body.length
    const end = el ? el.selectionEnd : body.length

    const before = body.slice(0, start)
    const after = body.slice(end)
    /* 앞이 줄머리가 아니면 줄을 바꿔 준다. 뒤도 마찬가지 — 이미 개행이면 더 넣지 않는다 */
    const lead = before === '' || before.endsWith('\n') ? '' : '\n'
    const tail = after.startsWith('\n') ? '' : '\n'
    const snippet = `${lead}![](${url})${tail}`

    /* 상한을 넘기면서까지 넣지 않는다. 넣고 잘라 내면 주소가 반토막 난 채 남는다 */
    if (before.length + snippet.length + after.length > BODY_MAX) {
      setTooLong(true)
      return
    }
    setTooLong(false)
    setBody(before + snippet + after)
    edited()

    /* 값이 그려진 뒤에 커서를 옮긴다. 같은 tick 에 옮기면 React 가 값을 넣으며 끝으로 되돌린다 */
    const caret = start + lead.length + 2
    requestAnimationFrame(() => {
      el?.focus()
      el?.setSelectionRange(caret, caret)
    })
  }

  const dirty = title !== '' || body !== '' || visibility !== null
  const ready = title.trim() !== '' && body.trim() !== '' && visibility !== null

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
  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!ready || submitting) return

    if (!hasWallet() || !user?.walletLinked) {
      setLinking(true)
      return
    }

    setSubmitting(true)
    setSigning(true)
    setError(null)
    setLocalMsg(null)
    try {
      /* 서명에 들어갈 값은 지금 이 입력에서 나와야 한다. 다듬은 제목(trim)을 서버에
         그대로 보내므로 서명도 같은 값에 붙어야 SIGNER_MISMATCH 가 나지 않는다. */
      const draft = { title: title.trim(), body, visibility }

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
          <p>지갑으로 서명해 발행합니다. 발행하면 수정하거나 지울 수 없습니다</p>
        </div>

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
              aria-invalid={fieldError === 'title'}
              placeholder="무엇에 대한 리포트인가요?"
            />
            {fieldError === 'title' && <FieldError>제목을 다시 확인해 주세요.</FieldError>}
          </div>

          <div className="rn-field">
            <label className="rn-label" htmlFor="rn-body">
              본문
              <span className="rn-count num">{`${body.length.toLocaleString('ko-KR')}/${BODY_MAX.toLocaleString('ko-KR')}`}</span>
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
                className="rn-textarea" id="rn-body" ref={bodyRef}
                value={body}
                onChange={(e) => { setBody(e.target.value); setTooLong(false); edited() }}
                maxLength={BODY_MAX}
                disabled={submitting}
                rows={16}
                aria-invalid={fieldError === 'body'}
                placeholder={'근거와 판단을 적어 주세요.\n\n마크다운을 씁니다 — ## 제목, **굵게**, - 목록.\n엔터는 그대로 줄바꿈이 됩니다.'}
              />
            ) : (
              /* 빈 본문에 빈 상자만 두면 미리보기가 고장 난 줄 안다 */
              <div className="rn-preview">
                {body.trim() === ''
                  ? <p className="rn-preview-empty">아직 쓴 내용이 없습니다.</p>
                  : <Markdown text={body} />}
              </div>
            )}

            <p className="rn-hint">
              마크다운으로 씁니다. <code>## 제목</code> · <code>**굵게**</code> · <code>- 목록</code> · <code>&gt; 인용</code> 이 그대로 먹습니다.
            </p>

            {tooLong && <FieldError>본문이 상한에 닿아 이미지를 넣지 못했습니다.</FieldError>}
            {fieldError === 'body' && <FieldError>본문을 다시 확인해 주세요.</FieldError>}
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
            <p className="rn-hint">제목 · 본문 · 공개 범위를 모두 채우면 발행할 수 있습니다.</p>
          )}
          {ready && !submitting && (
            <p className="rn-hint">
              발행하면 본문 해시가 블록체인에 기록됩니다. 서명은 그 기록이 내 지갑에서 나왔음을 남깁니다.
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
            onUploaded={(img) => { insertImage(img.url); setPicking(false) }}
          />
        )}
      </div>

      {/* 미설치·미연동·주소 불일치를 한 곳에서 다룬다(M-01). 닫으면 작성 내용은 그대로 남는다 */}
      {linking && <WalletLinkModal onClose={() => setLinking(false)} />}
    </main>
  )
}
