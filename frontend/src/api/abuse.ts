/* M-06 신고. API 명세서 §신고 · 설계서 §3 M · §4 M-06.

   POST /abuse-reports  201 { id } — JWT. 자기 신고 400 · 대상 없음 404 · 중복 409

   ── 멱등 키를 붙이지 않는다 ────────────────────────────
   서버 AbuseReportController 가 Idempotency-Key 를 아예 받지 않는다. 같은 신고가 두 번
   들어오면 (신고자, 대상, PENDING) 유니크 제약이 잡아 409 DUPLICATE_REPORT 로 돌아온다.
   즉 중복 방지는 이미 서버 쪽에 있고, 키를 얹으면 저장소만 하나 더 쓰는 꼴이다.

   ── targetType 이 셋인데 지금은 USER 만 쓴다 ────────────
   설계서 M-06 의 호출 위치는 F-04(커뮤니티 피드) · F-05(글 상세) · E-02(채널 프로필) 셋인데
   앞의 둘은 화면이 아직 없다(routes.ts 에 /posts 계열이 없다). 타입만 셋 다 열어 둔다 —
   피드 화면이 생기면 같은 모달에 targetType 만 바꿔 넘기면 된다.

   주의: 여기의 COMMENT 는 커뮤니티 댓글(post_comments)이다. 리포트 댓글(report_comments)은
   표가 다르고 id 공간도 다르다 — 그걸 신고하려면 서버 TargetType 에 항목이 먼저 생겨야 한다. */
import { api } from './client'

export const ABUSE_TARGET_TYPES = ['POST', 'COMMENT', 'USER'] as const
export type AbuseTargetType = (typeof ABUSE_TARGET_TYPES)[number]

export const ABUSE_REASONS = ['SPAM', 'ABUSE', 'FRAUD', 'ETC'] as const
export type AbuseReason = (typeof ABUSE_REASONS)[number]

/** 화면에 그대로 나가는 문구. 서버 enum 과 1:1 이라 여기 한 곳에서만 번역한다 */
export const ABUSE_REASON_LABEL: Record<AbuseReason, string> = {
  SPAM: '스팸 · 광고',
  ABUSE: '욕설 · 비방',
  FRAUD: '사기 · 허위 정보',
  ETC: '기타',
}

/** 서버 AbuseReportCreateRequest 의 @Size(max = 300) 과 같은 수 */
export const ABUSE_DETAIL_MAX = 300

export type AbuseReportDraft = {
  targetType: AbuseTargetType
  targetId: number
  reason: AbuseReason
  /** 비우면 보내지 않는다 — 서버가 선택값으로 둔다 */
  detail?: string
}

export function reportAbuse(draft: AbuseReportDraft) {
  return api.post<{ id: number }>('/abuse-reports', {
    targetType: draft.targetType,
    targetId: draft.targetId,
    reason: draft.reason,
    /* 공백만 적어 보낸 것은 안 적은 것으로 친다. 빈 문자열을 그대로 보내면 관리자 화면에
       빈 칸이 근거처럼 남는다. */
    detail: draft.detail?.trim() ? draft.detail.trim() : null,
  })
}
