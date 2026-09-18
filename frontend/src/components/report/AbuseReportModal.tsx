/* M-06 신고 모달. 설계서 §3 M · §4 M-06 · §6(모달 규칙)

   겉모습은 M-03 구독 확인 창(SubscribeModal)의 뼈대를 그대로 따른다 — backdrop → dialog →
   head / body / actions, Escape 로 닫기, 전용 스타일시트 한 벌. 확인창(ConfirmDialog)을 쓰지
   않은 이유는 그쪽이 버튼 둘짜리 알림창이어서 라디오 넷과 상세 입력이 들어갈 자리가 없고,
   억지로 넣으면 M-08 세션 만료 창까지 같은 스타일시트를 물고 있어 같이 흔들린다.

   ── "즉시 가려진다"고 쓰지 않는다 ──────────────────────
   설계서 M-06 제약이다. 블라인드는 관리자가 손으로 하는 일이라, 접수 문구가 "처리됐다"로
   읽히면 사용자는 상대 글이 계속 보이는 것을 고장으로 신고한다.

   ── 사유가 '기타'일 때만 상세를 필수로 받는다 ───────────
   서버는 detail 을 선택값으로 둔다(@Size(max=300) 뿐). 그런데 사유가 기타인데 설명이 없으면
   관리자가 반려 말고 할 수 있는 게 없다. 그래서 화면에서만 막는다 — 서버 검증을 바꾸면
   도메인 규칙이 되고 명세·스키마가 따라 움직인다. 화면의 친절과 도메인 규칙을 섞지 않는다.

   ── 포커스 가둠(focus trap)은 없다 ─────────────────────
   SubscribeModal·ConfirmDialog 와 같은 판단이다. Escape 로 언제나 닫히고, 창 안 요소가
   적어 Tab 이 새어 나가도 곧 돌아온다. */
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ABUSE_DETAIL_MAX, ABUSE_REASONS, ABUSE_REASON_LABEL, reportAbuse,
} from '../../api/abuse'
import type { AbuseReason, AbuseTargetType } from '../../api/abuse'
import type { ApiError } from '../../api/errors'
import { ERROR_CODE } from '../../api/errors'
import { errorText } from '../state/errorText'
import FieldError from '../state/FieldError'
import '../../styles/screens/abuse-report.css'

type Props = {
  targetType: AbuseTargetType
  targetId: number
  /** 창 제목 아래에 누구(무엇)를 신고하는지 밝힌다 — 잘못 누른 사람이 여기서 알아채야 한다 */
  targetLabel: string
  onClose: () => void
}

export default function AbuseReportModal({
  targetType, targetId, targetLabel, onClose,
}: Props) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const [reason, setReason] = useState<AbuseReason | null>(null)
  const [detail, setDetail] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [done, setDone] = useState(false)

  /* 보내는 중에는 닫지 않는다. 요청이 날아간 뒤 창만 사라지면 접수됐는지 아닌지를
     사용자가 알 길이 없다(같은 신고를 다시 넣으면 409 로 막힌다). */
  const close = useCallback(() => { if (!sending) onClose() }, [sending, onClose])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [close])

  /* 초점을 창 안으로 들인다. 안 그러면 키보드 사용자는 창이 떴다는 사실조차 모른 채
     뒤 화면을 계속 조작한다(ConfirmDialog 와 같은 이유). */
  useEffect(() => { dialogRef.current?.focus() }, [])

  const detailRequired = reason === 'ETC'
  const detailMissing = detailRequired && !detail.trim()
  const canSubmit = reason !== null && !detailMissing && !sending

  const submit = async () => {
    if (!canSubmit || reason === null) return
    setError(null)
    setSending(true)
    try {
      await reportAbuse({ targetType, targetId, reason, detail })
      setDone(true)
    } catch (e) {
      /* 여기서 창을 닫지 않는다. 409(이미 신고함) · 404(대상 없음) 는 사용자가 읽고
         납득해야 하는 결과이고, 닫아 버리면 아무 일도 안 일어난 것처럼 보인다. */
      setError(e as ApiError)
    } finally {
      setSending(false)
    }
  }

  const text = error ? reportErrorText(error, targetType) : null

  return (
    <div className="ab-backdrop" onClick={close} role="presentation">
      <div
        className="ab-modal" role="dialog" aria-modal="true" aria-labelledby="ab-title"
        tabIndex={-1} ref={dialogRef} onClick={(e) => e.stopPropagation()}
      >
        <div className="ab-head">
          <h2 id="ab-title">{done ? '신고가 접수됐습니다' : '신고'}</h2>
          <button type="button" className="ab-x" aria-label="닫기" onClick={close} disabled={sending}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M6 6l12 12" /><path d="M18 6 6 18" />
            </svg>
          </button>
        </div>

        {done ? (
          <div className="ab-body">
            {/* 설계서 M-06 — 가려졌다고 쓰지 않는다. 지금 일어난 일은 접수뿐이다. */}
            <p className="ab-done">관리자가 확인한 뒤 처리합니다.</p>
            <p className="ab-sub">처리 전까지 같은 대상을 다시 신고할 수 없습니다.</p>
            <div className="ab-actions">
              <button type="button" className="ab-btn solid" onClick={onClose}>닫기</button>
            </div>
          </div>
        ) : (
          <div className="ab-body">
            <p className="ab-target">{targetLabel}</p>

            <fieldset className="ab-reasons" disabled={sending}>
              <legend className="ab-legend">신고 사유</legend>
              {ABUSE_REASONS.map((value) => (
                <label key={value} className={`ab-reason${reason === value ? ' is-on' : ''}`}>
                  <input
                    type="radio" name="abuse-reason" value={value}
                    checked={reason === value}
                    onChange={() => setReason(value)}
                  />
                  <span>{ABUSE_REASON_LABEL[value]}</span>
                </label>
              ))}
            </fieldset>

            <label className="ab-detail">
              <span className="ab-legend">
                상세 내용{detailRequired ? '' : ' (선택)'}
              </span>
              <textarea
                value={detail}
                maxLength={ABUSE_DETAIL_MAX}
                rows={4}
                disabled={sending}
                placeholder={detailRequired
                  ? '어떤 점이 문제인지 적어 주세요. 관리자가 이 내용으로 판단합니다.'
                  : '판단에 도움이 될 내용을 적어 주세요.'}
                onChange={(e) => setDetail(e.target.value)}
              />
              <span className="ab-count">{detail.length} / {ABUSE_DETAIL_MAX}</span>
            </label>

            {/* 사유를 고르기 전에는 이 안내를 띄우지 않는다 — 창을 열자마자 빨간 글씨가
                보이면 잘못한 것처럼 읽힌다. */}
            <FieldError>
              {detailMissing ? '기타를 고르면 상세 내용을 적어야 합니다.' : undefined}
            </FieldError>

            {text && (
              <p className="ab-error" role="alert">
                {text.title}
                {text.hint && <span className="ab-sub">{text.hint}</span>}
              </p>
            )}

            <div className="ab-actions">
              <button type="button" className="ab-btn" onClick={close} disabled={sending}>취소</button>
              <button
                type="button" className="ab-btn solid"
                onClick={() => void submit()} disabled={!canSubmit}
              >
                {sending ? '접수 중…' : '신고하기'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/* 중복 신고는 "무엇을" 이미 신고했는지까지 말한다. 공용 문구(errorText)는
   "이미 신고한 대상입니다" 로 대상 종류를 모르고 쓰인다 — 그쪽을 고치면 글·댓글
   신고가 붙었을 때 틀린 말이 되므로, 대상을 아는 이 창에서만 바꿔 쓴다. */
const DUPLICATE_TITLE: Record<AbuseTargetType, string> = {
  USER: '이미 신고한 유저입니다',
  POST: '이미 신고한 글입니다',
  COMMENT: '이미 신고한 댓글입니다',
  REPORT: '이미 신고한 리포트입니다',
  REPORT_COMMENT: '이미 신고한 댓글입니다',
}

function reportErrorText(error: ApiError, targetType: AbuseTargetType) {
  const text = errorText(error)
  return error.code === ERROR_CODE.DUPLICATE_REPORT
    ? { ...text, title: DUPLICATE_TITLE[targetType] }
    : text
}
