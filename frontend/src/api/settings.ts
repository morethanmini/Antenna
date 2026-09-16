/* 환경 설정 — H-03 알림 탭. API 명세서 §회원 settings (ANT-NOTIFY-02)

   GET /users/me/settings   행이 없으면 전부 기본값(알림 넷 on · BEGINNER)
   PUT /users/me/settings   전부 보낸다 — 일부만 보내면 400. 갱신값을 돌려준다

   알림 토글은 넷이다. 명세는 셋(판정 · 시즌 · 구독·구독료)이었는데 알림이 7종으로 늘어
   "내 채널 반응"과 "구독 채널 소식"으로 갈랐다(2026-09-16). 어떤 알림이 어느 토글에
   걸리는지는 서버 NotificationGate 가 정한다 — 화면은 라벨만 안다.

   chartView 는 받아 두기만 한다. 아직 그 값을 읽는 화면이 없어 트레이딩 뷰 탭은 잠긴 채다. */
import { api } from './client'

export type ChartView = 'BEGINNER' | 'ADVANCED'

export type UserSettings = {
  /** 판정 결과 — VERDICT */
  notifyVerdict: boolean
  /** 시즌 진행·보상 — SEASON */
  notifySeason: boolean
  /** 내 채널 반응 — 새 구독자 · 내 리포트 댓글 · 좋아요 */
  notifySocial: boolean
  /** 구독 채널 소식 — 리포트 발행 · 예측 등록 · 구독료 변경 */
  notifyChannel: boolean
  chartView: ChartView
}

export type NotifyKey = Exclude<keyof UserSettings, 'chartView'>

export function getSettings() {
  return api.get<UserSettings>('/users/me/settings')
}

export function updateSettings(settings: UserSettings) {
  return api.put<UserSettings>('/users/me/settings', settings)
}
