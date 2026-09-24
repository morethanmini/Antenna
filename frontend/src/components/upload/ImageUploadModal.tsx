/* M-07 이미지 업로드 (모달 · 라우트 없음)
   담당 스토리 [ANT-FE-UPLOAD]
   호출 위치: F-03 리포트 작성 · F-04 글 작성 · H-04 광고 등록
   설계서 docs/화면설계서.md §3 M · §4 F-03 · H-04 · §6

   설계 제약
   - 호출하는 화면은 fileId 만 받는다. 외부 URL 입력란을 두지 않는다.
   - 화면별 제약을 모달이 파라미터로 받는다 — H-04 배너는 고정 비율이 있다.
   - 업로드 규격 위반은 400 이다. 인라인 오류로 처리한다(화면 전체를 오류로 덮지 않는다).
   - 중간에 끊겼을 때 어느 단계에서 실패했는지 구분해 복구할 수 있어야 한다.

   ── 티켓의 3단계(presigned URL)는 쓰지 않는다 ────────────────
   구현된 백엔드는 서버로 직접 multipart 를 받는다(api/uploads.ts 머리말). 발급 단계가
   없으므로 "발급 실패 / 전송 실패" 구분은 그 형태로 성립하지 않는다. 대신 사용자가 할 일이
   서로 다른 셋으로 가른다.

     고르기 전 걸림 (로컬 검증)  → 다른 파일을 골라야 한다. 업로드를 시작하지도 않는다
     전송이 끊김                → 같은 파일로 다시 시도하면 된다
     서버가 규격 위반으로 거절   → 다른 파일을 골라야 한다

   ── 로컬에서는 거르지 않고 **맞춘다** (QA 09-21) ─────────────
   서버가 최종 판정자인 것은 그대로다. 전에는 서버가 거절할 그림을 여기서 미리 거절했는데,
   사용자에게 남는 것은 "왜 안 올라가지" 뿐이었다. 지금은 브라우저가 읽을 수 있는 그림이면
   서버 규격에 맞게 바꿔서(자르기 · 줄이기 · 형식 바꾸기) 보내고, 결과가 이상하게 보일 것
   같으면 **막지 않고 경고**한다. 남는 거절은 브라우저가 못 읽는 파일뿐이다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { ApiError, CLIENT_ERROR_CODE } from '../../api/errors'
import {
  ACCEPT_ATTR, ASPECT_BY_PURPOSE, MAX_EDGE_BY_PURPOSE, UPLOAD_HINT,
  checkSize, cropToAspect, formatAspect, formatBytes,
  measure, needsRedraw, precheck, tooThinToCrop, uploadImage, warningsFor,
  type AspectRule, type CropRect, type CroppedImage, type UploadPurpose,
  type UploadWarning, type UploadedImage,
} from '../../api/uploads'
import CropStage from './CropStage'
import { errorText } from '../state/errorText'
import '../../styles/screens/image-upload.css'

/* pick     파일을 고르기 전
   crop     비율이 정해진 화면에서 어디를 남길지 고르는 중
   ready    골랐고 로컬 검증을 통과했다. 미리보기가 보인다
   sending  전송 중. 진행률이 돈다
   done     서버가 fileId 를 줬다 */
type Step = 'pick' | 'crop' | 'ready' | 'sending' | 'done'

type Picked = {
  file: File
  width: number
  height: number
  previewUrl: string
  /** 자르거나 줄였다면 손대기 전 크기. 화면이 그 사실을 반드시 알려야 한다 */
  croppedFrom?: { width: number; height: number }
  /** 무엇을 했는지 — 문구가 갈린다 */
  did?: CroppedImage['did']
  /** 자르기 UI 를 거치지 않고 가운데를 잡았다(너무 가는 그림). 안내 문구가 달라진다 */
  auto?: boolean
  /** 막지 않고 알리는 것. 올리기 버튼은 그대로 살아 있다 */
  warnings: UploadWarning[]
}

/* 경고 문구. "안 된다" 가 아니라 "이렇게 보인다" 를 말한다 — 그대로 갈지는 사용자가 정한다 */
const WARNING_TEXT: Record<UploadWarning, string> = {
  CROP_LOSS: '그림의 절반 넘게 잘려 나갑니다. 미리보기를 확인해 주세요',
  AD_SMALL: '배너보다 작은 그림이에요. 늘리면 흐려질 수 있어요',
  STRETCHED: '너무 작은 그림이라 늘려서 넣었습니다. 깨져 보입니다',
  TALL: '세로로 아주 긴 그림입니다. 본문이 그만큼 길어집니다',
  FLAT: '가로로 아주 납작한 그림입니다. 본문에서 가는 줄처럼 보입니다',
  GIF_STILL: 'GIF 는 첫 장면만 올라갑니다. 움직임은 사라집니다',
}

type Props = {
  /** 서버가 무엇에 쓰는 그림인지 알아야 한다. AD 는 비율 제약이 함께 걸린다 */
  purpose: UploadPurpose
  onClose: () => void
  /** 호출부는 fileId 만 쓰면 된다. 미리보기가 필요할 때를 위해 나머지도 함께 준다 */
  onUploaded: (image: UploadedImage) => void
  /** 기본은 purpose 가 정한다. 화면이 다른 제약을 걸어야 하면 여기로 덮는다 */
  aspect?: AspectRule
}

/** 'image/webp' → 'WebP'. 바꾼 형식을 사람이 읽는 이름으로 */
function formatType(mime: string) {
  return ({ 'image/webp': 'WebP', 'image/png': 'PNG', 'image/jpeg': 'JPG' } as Record<string, string>)[mime] ?? mime
}

export default function ImageUploadModal({ purpose, onClose, onUploaded, aspect }: Props) {
  const rule = aspect ?? ASPECT_BY_PURPOSE[purpose]
  /* 56px 자리에 4000px 사진을 저장해 둘 이유가 없다. 용도가 정한다(MAX_EDGE_BY_PURPOSE) */
  const maxEdge = MAX_EDGE_BY_PURPOSE[purpose]

  const [step, setStep] = useState<Step>('pick')
  /* 자르기 단계가 쥐고 있는 원본. 자르고 나면 picked 가 결과를 들고 이 값은 버린다 */
  const [source, setSource] = useState<{ file: File; size: { width: number; height: number } } | null>(null)
  const [picked, setPicked] = useState<Picked | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [progress, setProgress] = useState(0)
  const [dragging, setDragging] = useState(false)

  const dialogRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  /* 미리보기 URL 은 브라우저 메모리를 잡는다. 새 파일로 바꾸거나 창을 닫을 때 놓아 준다 */
  const previewRef = useRef<string | null>(null)

  const releasePreview = useCallback(() => {
    if (previewRef.current) {
      URL.revokeObjectURL(previewRef.current)
      previewRef.current = null
    }
  }, [])

  /* 전송 중에는 닫지 못하게 한다 — 창을 닫아도 요청은 살아 있어 사용자가
     "취소한 줄 알았는데 올라갔다" 를 겪는다. 취소 버튼으로만 멈춘다. */
  const closable = step !== 'sending'
  const close = useCallback(() => {
    if (!closable) return
    releasePreview()
    onClose()
  }, [closable, onClose, releasePreview])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [close])

  // 모달이 열리면 초점을 안으로 들인다. 없으면 탭이 뒤 화면을 돌아다닌다.
  useEffect(() => { dialogRef.current?.focus() }, [])

  // 언마운트될 때도 미리보기를 놓는다 — 호출부가 조건부 렌더로 지울 수 있다
  useEffect(() => releasePreview, [releasePreview])

  async function choose(file: File) {
    setError(null)

    // ① 디코딩 전에 막는 것은 SVG 하나다(precheck 머리말). 형식·용량은 막지 않고 맞춘다
    const early = precheck(file)
    if (early) { setError(early); return }

    // ② 브라우저가 읽는지가 관문이다. 크기와 비율도 여기서 안다
    let measured
    try {
      measured = await measure(file)
    } catch (e) {
      setError(e as ApiError)
      return
    }
    URL.revokeObjectURL(measured.previewUrl)
    releasePreview()
    const src = { file, size: { width: measured.width, height: measured.height } }

    /* ③ 너무 가는 그림(50×100000)은 자르기 화면에서 0.16px 폭으로 그려져 네모를 잡을 수 없다.
       그 단계를 건너뛰고 가운데를 잡는다 — 비율이 없는 화면이면 원본 그대로다.
       거절하지 않는다. 대신 얼마나 잘렸는지, 어떻게 보일지를 경고로 알린다. */
    if (tooThinToCrop(src.size)) {
      await finalize(src, undefined, true)
      return
    }

    /* ④ 어디를 남길지 사용자가 고른다(CropStage). 비율이 정해진 화면(광고 8:5 · 프로필 1:1)은
       네모가 그 비율로 잠기고, 리포트 본문처럼 비율이 없는 화면은 자유롭게 잡는다.
       손대지 않고 넘기면 — 비율이 있으면 예전처럼 가운데, 없으면 원본 그대로다. */
    setSource(src)
    setStep('crop')
  }

  /**
   * 고른 그림을 올릴 파일로 만든다. 세 길이 여기로 모인다 — 고른 자리로 자르기(rect 있음),
   * 원본 그대로(rect 없음 · 비율 없음), 너무 가늘어 자동으로 가운데 잡기(auto).
   *
   * 다시 그릴 일이 없으면 원본을 그대로 쓴다. 캔버스를 거치면 PNG 는 오히려 용량이 늘 수 있고
   * 리포트 본문 그림은 원본 화질이어야 한다. 다시 그려야 하는 경우는 넷이다 — 자를 때, 형식이
   * 서버 것이 아닐 때, 10MB·8천만 화소를 넘을 때, 쓰는 자리보다 클 때(needsRedraw).
   */
  async function finalize(src: NonNullable<typeof source>, rect: CropRect | undefined, auto: boolean) {
    setError(null)
    const { file, size } = src
    // 비율이 있는 화면은 rect 가 없어도 가운데를 자른다. 비율이 없으면 rect 가 있을 때만 자른다
    const crops = rule != null || rect != null
    try {
      let next: Picked
      if (crops || needsRedraw(file, size, maxEdge)) {
        const cut = await cropToAspect(file, size, crops ? rule : undefined, maxEdge, rect)
        /* 결과가 규격 안인지 마지막으로 본다. 비율은 이제 구조적으로 맞지만(cropToAspect),
           서버와 같은 잣대로 한 번 재 두면 그 전제가 깨졌을 때 올리기 전에 안다 */
        const stillBad = checkSize(cut.width, cut.height, crops ? rule : undefined)
        if (stillBad) throw stillBad
        next = {
          file: cut.file, width: cut.width, height: cut.height,
          previewUrl: URL.createObjectURL(cut.file), croppedFrom: cut.from, did: cut.did, auto,
          warnings: warningsFor(purpose, file, cut, cut, auto),
        }
      } else {
        next = {
          file, width: size.width, height: size.height, previewUrl: URL.createObjectURL(file),
          warnings: warningsFor(purpose, file, size),
        }
      }
      releasePreview()
      previewRef.current = next.previewUrl
      setPicked(next)
      setSource(null)
      setStep('ready')
    } catch (e) {
      setError(e as ApiError)
    }
  }

  async function start() {
    if (!picked) return
    setError(null)
    setProgress(0)
    setStep('sending')

    const controller = new AbortController()
    abortRef.current = controller

    try {
      const image = await uploadImage(picked.file, purpose, {
        onProgress: setProgress,
        signal: controller.signal,
      })
      setStep('done')
      onUploaded(image)
    } catch (e) {
      const err = e as ApiError
      /* 취소는 실패가 아니다. 고른 파일을 그대로 두고 확인 단계로 돌린다 —
         다시 누르면 같은 파일로 바로 보낸다. */
      if (err.code === CLIENT_ERROR_CODE.CLIENT_UPLOAD_ABORTED) {
        setStep('ready')
        return
      }
      setError(err)
      setStep('ready')
    } finally {
      abortRef.current = null
    }
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files?.[0]
    if (file) void choose(file)
  }

  const text = error ? errorText(error) : null

  /* body 로 내보낸다 — ConfirmDialog 와 같은 이유다. 밤 화면의 유리 카드(마이페이지 머리 카드)에
     backdrop-filter 가 걸려 있어, 그 안의 ProfileAvatar 에서 띄우면 position:fixed 가 화면이 아니라
     카드에 맞춰진다. 창이 카드 높이에 갇혀 자르기 안내·버튼이 잘려 보였다(QA 09-24). */
  return createPortal(
    <div className="iu-backdrop" onClick={close} role="presentation">
      <div
        className="iu-modal" role="dialog" aria-modal="true" aria-labelledby="iu-title"
        tabIndex={-1} ref={dialogRef} onClick={(e) => e.stopPropagation()}
      >
        <div className="iu-head">
          <h2 id="iu-title">
            {step === 'done' ? '이미지를 올렸습니다' : step === 'crop' ? '자를 자리 고르기' : '이미지 올리기'}
          </h2>
          {closable && (
            <button type="button" className="iu-x" aria-label="닫기" onClick={close}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M6 6l12 12" /><path d="M18 6 6 18" />
              </svg>
            </button>
          )}
        </div>

        <div className="iu-body">
          {/* ── 고르기 ───────────────────────────────── */}
          {step === 'pick' && (
            <>
              {/* 클릭으로도, 끌어다 놓기로도 고를 수 있게 한다 */}
              <button
                type="button"
                className={`iu-drop ${dragging ? 'is-over' : ''}`}
                onClick={() => inputRef.current?.click()}
                onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
                onDragLeave={() => setDragging(false)}
                onDrop={onDrop}
              >
                <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M12 16V4" /><path d="m7 9 5-5 5 5" />
                  <path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
                </svg>
                <b>끌어다 놓거나 눌러서 고르세요</b>
                {/* 안내는 크기 하나뿐이다(api/uploads.ts 의 UPLOAD_HINT). 형식 변환도
                    자르기도 이 창이 알아서 하고 다음 단계에서 결과를 보여 주므로,
                    고르기 전에 미리 읽힐 이유가 없다. */}
                <span>{UPLOAD_HINT}</span>
              </button>

              <input
                ref={inputRef} type="file" className="sr-only"
                accept={ACCEPT_ATTR}
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  // 같은 파일을 다시 고를 수 있게 값을 비운다 — 안 비우면 change 가 안 뜬다
                  e.target.value = ''
                  if (file) void choose(file)
                }}
              />
            </>
          )}

          {/* ── 자르기 ───────────────────────────────── */}
          {step === 'crop' && source && (
            <CropStage
              file={source.file}
              size={source.size}
              aspect={rule}
              onCancel={() => { setSource(null); setError(null); setStep('pick') }}
              onDone={(rect) => void finalize(source, rect, false)}
              /* 비율이 잠긴 화면은 자르지 않고 넘어갈 수 없다 — 규격이 곧 게재 조건이다 */
              onSkip={rule ? undefined : () => void finalize(source, undefined, false)}
            />
          )}

          {/* ── 고른 뒤 · 전송 중 ─────────────────────── */}
          {picked && step !== 'pick' && (
            <div className="iu-preview">
              <img src={picked.previewUrl} alt="" />
              <dl className="iu-facts">
                <div>
                  <dt>파일</dt>
                  <dd className="iu-name">{picked.file.name}</dd>
                </div>
                <div>
                  <dt>크기</dt>
                  <dd className="num">{`${picked.width} × ${picked.height}`}</dd>
                </div>
                <div>
                  <dt>용량</dt>
                  <dd className="num">{formatBytes(picked.file.size)}</dd>
                </div>
              </dl>
              {/* 손댔으면 반드시 말한다. 모르는 사이에 그림이 바뀌는 쪽이 거절보다 나쁘다.
                  자르기와 줄이기는 사용자가 잃는 것이 달라 문구를 가른다 — 자르기는 화면
                  밖으로 나간 부분이 없어지고, 줄이기는 크게 볼 때만 차이가 난다. */}
              {picked.croppedFrom && picked.did && (
                <p className="iu-cropped">
                  {[
                    picked.did.cropped && (rule
                      ? `${formatAspect(rule)} 에 맞춰 ${picked.auto ? '가운데를' : '고른 자리로'} 잘랐습니다`
                      : '고른 자리로 잘랐습니다'),
                    /* 줄인 이유가 둘이다. 쓰는 자리가 작아서(프로필)와 서버 상한을 넘어서 */
                    picked.did.shrank && (maxEdge != null ? '화면에 쓰는 크기로 줄였습니다' : '올릴 수 있는 크기로 줄였습니다'),
                    picked.did.converted && `${formatType(picked.file.type)} 로 바꿨습니다`,
                  ].filter(Boolean).join(' · ')}
                  <span className="num">
                    {`${picked.croppedFrom.width} × ${picked.croppedFrom.height} → ${picked.width} × ${picked.height}`}
                  </span>
                </p>
              )}
              {/* 경고는 막지 않는다 — 올리기 버튼은 그대로다. 오류(iu-error)와 색을 달리해
                  "안 된다" 가 아니라 "이렇게 보인다" 로 읽히게 한다 */}
              {picked.warnings.length > 0 && step === 'ready' && (
                <ul className="iu-warn" role="status">
                  {picked.warnings.map((w) => <li key={w}>{WARNING_TEXT[w]}</li>)}
                </ul>
              )}
            </div>
          )}

          {step === 'sending' && (
            <div className="iu-progress" aria-live="polite">
              {/* 진행률은 XHR 업로드 이벤트에서 온다. fetch 로는 읽을 수 없다 */}
              <div
                className="iu-bar" role="progressbar"
                aria-valuemin={0} aria-valuemax={100}
                aria-valuenow={Math.round(progress * 100)}
              >
                <span style={{ width: `${Math.round(progress * 100)}%` }} />
              </div>
              <p className="iu-progress-text num">{`${Math.round(progress * 100)}%`}</p>
            </div>
          )}

          {step === 'done' && (
            <p className="iu-done">이제 그림을 글에 넣을 수 있어요.</p>
          )}

          {/* 규격 위반은 화면을 덮지 않고 여기 인라인으로 (설계 제약) */}
          {text && (
            <p className="iu-error" role="alert">
              <b>{text.title}</b>
              {text.hint && <span>{text.hint}</span>}
            </p>
          )}
        </div>

        <div className="iu-actions">
          {/* 자르기 단계의 버튼은 CropStage 안에 있다 — 확대 슬라이더 바로 밑이라야 손이 덜 간다 */}
          {step === 'pick' && (
            <button type="button" className="iu-btn" onClick={close}>취소</button>
          )}

          {step === 'ready' && (
            <>
              <button
                type="button" className="iu-btn"
                onClick={() => { releasePreview(); setPicked(null); setError(null); setStep('pick') }}
              >
                다시 고르기
              </button>
              <button type="button" className="iu-btn solid" onClick={() => void start()}>
                {/* 전송 실패 뒤에는 같은 파일로 다시 보내는 것이라 문구를 바꾼다 */}
                {error ? '다시 올리기' : '올리기'}
              </button>
            </>
          )}

          {step === 'sending' && (
            <button type="button" className="iu-btn" onClick={() => abortRef.current?.abort()}>
              취소
            </button>
          )}

          {step === 'done' && (
            <button type="button" className="iu-btn solid" onClick={close}>확인</button>
          )}
        </div>
      </div>
    </div>,
    document.body,
  )
}
