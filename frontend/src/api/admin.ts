/* 관리자 전용 API (I-01 신고 처리 · I-02 회원 제재 · I-04 이벤트 운영).

   ── 왜 api/events.ts 와 나눠 두나 ────────────────────────
   events.ts 는 사용자 화면이 쓰는 조회·등록이다. 관리자 호출을 거기 섞으면 일반 화면이
   실수로 import 할 수 있고, 번들에도 같이 실린다. 관리자 경로는 전부 여기 모은다.
   여기 있는 함수는 /admin/** 을 부르므로 관리자가 아니면 전부 403 ADMIN_ONLY 다.

   모든 경로는 client.ts 가 /api/v1 을 앞에 붙인다. */
import { api } from './client'

/* ── I-01 신고 처리 ───────────────────────────────────── */

export type AbuseTargetType = 'POST' | 'COMMENT' | 'USER'
export type AbuseReportStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED'

export type AbuseReportItem = {
  id: number
  /** SPAM · ABUSE · FRAUD · ETC */
  reason: string
  detail: string | null
  status: AbuseReportStatus
  targetType: AbuseTargetType
  targetId: number
  /** 글·댓글이면 본문, 회원이면 닉네임. 무엇을 가리는지 보고 판단하라고 서버가 실어 준다 */
  targetBody: string | null
  /** 지금 이미 가려져 있는가. 회원 신고면 null 이다 */
  targetBlocked: boolean | null
  reporterId: number
  reporterNickname: string | null
  handledById: number | null
  handledAt: string | null
  createdAt: string
}

/** status 를 생략하면 전체가 온다. 페이지네이션은 없다(서버도 없다). */
export function getAbuseReports(status?: AbuseReportStatus) {
  return api.get<{ items: AbuseReportItem[] }>('/admin/abuse-reports', {
    query: { status },
  })
}

/**
 * 신고 처리. ACCEPT 면 대상 글·댓글이 BLOCKED 로 내려간다.
 *
 * **회원 신고를 ACCEPT 해도 계정은 막히지 않는다** — 제재는 banUser 로 따로 누른다.
 */
export function handleAbuseReport(id: number, action: 'ACCEPT' | 'REJECT') {
  return api.patch<void>(`/admin/abuse-reports/${id}`, { action })
}

export type ContentStatus = 'VISIBLE' | 'BLOCKED'

/** 신고를 거치지 않는 직접 블라인드·복구. */
export function setPostStatus(postId: number, status: ContentStatus) {
  return api.patch<void>(`/admin/posts/${postId}/status`, { status })
}

export function setCommentStatus(commentId: number, status: ContentStatus) {
  return api.patch<void>(`/admin/comments/${commentId}/status`, { status })
}

/* ── I-02 회원 제재 ───────────────────────────────────── */

export type AdminUserItem = {
  id: number
  nickname: string
  /** USER · ADMIN */
  role: string
  /** ACTIVE · BANNED */
  status: 'ACTIVE' | 'BANNED'
  createdAt: string
}

/** q 가 비면 최근 가입 순으로 최대 50명. 닉네임 대소문자는 가리지 않는다. */
export function searchUsers(q?: string) {
  return api.get<{ items: AdminUserItem[] }>('/admin/users', {
    query: { q: q?.trim() || undefined },
  })
}

/**
 * 회원 상태 전이.
 *
 * **SUSPENDED 가 아니라 BANNED 다**(명세 v0.74). 영구 차단만 있고, 차단되면 로그인이
 * 거부되지만 기존 예측·원장은 남는다. reason 은 서버가 받기만 하고 저장하지 않는다.
 * 관리자가 자기 계정을 내리려 하면 409 ADMIN_SELF_DEMOTE 다.
 */
export function setUserStatus(userId: number, status: 'ACTIVE' | 'BANNED', reason?: string) {
  return api.patch<void>(`/admin/users/${userId}/status`, { status, reason: reason || null })
}

/* ── I-04 이벤트 운영 ─────────────────────────────────── */

export type EventPhase = 'SCHEDULED' | 'OPEN' | 'SEALED' | 'SETTLING' | 'DONE'

export type AdminEventListItem = {
  eventId: number
  slug: string | null
  title: string
  /** 원시 상태. phase 는 이것을 화면용으로 접은 값이다 */
  status: string
  phase: EventPhase
  opensAt: string
  sealsAt: string
  baseDate: string
  settleDate: string
}

/** 목록은 사용자용 조회를 그대로 쓴다 — 관리자 전용 목록 API 가 따로 없다. */
export function getEvents() {
  return api.get<{ items: AdminEventListItem[] }>('/events')
}

/** 상세도 사용자용을 쓴다. 종목·기준가·상태가 여기 들어 있다. */
export function getEventDetail(eventId: number) {
  return api.get<Record<string, unknown>>(`/events/${eventId}`)
}

/**
 * 회차 생성 요청. 서버 DTO(`EventCreateRequest`)와 이름·타입이 같아야 한다.
 *
 * **`stockCodes` 의 순서가 곧 `idx`(0~9)** 다. 컨트랙트의 종목 칸 번호와 같아서
 * 순서를 바꾸면 이미 걸린 배팅이 다른 종목을 가리킨다 — 화면에서 정렬하지 않는다.
 *
 * 기준가는 여기서 받지 않는다. 회차를 열 때 서버가 일봉에서 복사하고,
 * 비어 있으면 `setBasePrice` 로 손으로 넣는다.
 */
export type EventCreateBody = {
  /** 소문자·숫자·하이픈. 생략하면 화면 주소가 없는 점검용 회차다 */
  slug?: string
  title: string
  /** ISO-8601, 오프셋 포함(`+09:00`) */
  opensAt: string
  sealsAt: string
  /** YYYY-MM-DD */
  baseDate: string
  settleDate: string
  /** 정수 ANT 문자열. decimals 가 0 이라 10^18 이 곱해져 있지 않다 */
  minStake: string
  maxStake: string
  /** 최소 참가 종목 수 */
  minStocks: number
  /** 종목코드 6자리 배열. 순서가 idx 다 */
  stockCodes: string[]
}

/**
 * 회차 생성. 202 다 — 행을 먼저 커밋하고 온체인 openEvent 를 그 뒤에 보낸다.
 * 전송이 실패해도 회차는 남고 openEvent 로 다시 보낼 수 있다.
 */
export function createEvent(body: EventCreateBody) {
  return api.post<{ eventId: number }>('/admin/events', body)
}

/** openEvent 재전송. 회차 행은 그대로 두고 tx 만 다시 보낸다 — 다시 만들면 종목 칸 순서가 어긋난다. */
export function reopenEvent(eventId: number) {
  return api.post<void>(`/admin/events/${eventId}/open`)
}

/** 봉인. 평소에는 매분 크론이 하고, 이 길은 크론이 못 돌았거나 일찍 닫아야 할 때 쓴다. */
export function sealEvent(eventId: number) {
  return api.post<void>(`/admin/events/${eventId}/seal`)
}

/** 정산 트리거. 리빌 → 종목 판정 → 배분 → 순위 굳힘이 돈다. 되돌릴 수 없다. */
export function settleEvent(eventId: number) {
  return api.post<void>(`/admin/events/${eventId}/settle`)
}

export function getSettlementStatus(eventId: number) {
  return api.get<Record<string, unknown>>(`/admin/events/${eventId}/status`)
}

/** 기준가 수동 입력. idx 는 회차 안 종목 칸 번호다(종목 코드가 아니다 — 컨트랙트가 쓰는 순번). */
export function setBasePrice(eventId: number, idx: number, basePrice: string) {
  return api.put<void>(`/admin/events/${eventId}/stocks/${idx}/base-price`, { basePrice })
}
