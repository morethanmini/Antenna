/* 알림 종류 아이콘 — H-02 이름표 안에 글자와 나란히 선다.

   **글자를 대신하지 않는다.** 아이콘만 두면 "판정" 과 "예측", "리포트" 와 "댓글" 이
   서로 헷갈린다 — 아홉 종류를 그림만으로 가르기에는 뜻이 너무 가깝다. 아이콘은
   목록을 훑을 때 눈이 걸리는 표식이고, 무엇인지는 옆의 글자가 말한다.
   그래서 aria-hidden 이다. 스크린리더에는 글자만 읽힌다.

   11px 로 그려진다. 그 크기에서는 획이 가늘면 뭉개지므로 strokeWidth 를 2.4 로
   두껍게 잡았다(본문 아이콘들의 1.6~2 보다 높다). 채우기를 쓰지 않은 이유는
   이름표 바탕이 옅은 회색이라, 채우면 글자보다 아이콘이 먼저 튀기 때문이다. */
import type { NotificationType } from '../api/notifications'

/** 24×24 좌표계의 그림. 획만 쓰고 채우지 않는다 */
const PATHS: Record<NotificationType, React.ReactNode> = {
  /* 판정 — 체크 */
  VERDICT: <path d="M4 12.5 9.5 18 20 6.5" />,
  /* 모의투자 — 오르는 꺾은선 */
  SEASON: <><path d="M3 17.5 9.5 11l4 4L21 7.5" /><path d="M15.5 7.5H21v5.5" /></>,
  /* 구독 — 사람 */
  SUBSCRIBE: <><circle cx="12" cy="8" r="3.6" /><path d="M4.8 20c0-4 3.2-6.2 7.2-6.2s7.2 2.2 7.2 6.2" /></>,
  /* 구독료 — 지폐. 원 안에 원화 획을 넣는 동전은 11px 에서 안쪽이 뭉개져 동그라미
     얼룩이 되고, 같은 원 계열인 예측(과녁)과도 구별이 안 됐다(후보 일곱을 실물
     크기로 나란히 놓고 골랐다). 가로로 누운 네모 + 가운데 점은 그 크기에서도
     윤곽이 살아 있고, 다른 여덟 아이콘과 실루엣이 겹치지 않는다. */
  FEE_CHANGED: <><rect x="2.6" y="7" width="18.8" height="10" rx="2" /><circle cx="12" cy="12" r="2.4" /></>,
  /* 결제 실패 — 경고 */
  RENEW_FAILED: <><path d="M12 4.2 21.6 20.2H2.4z" /><path d="M12 10.4v3.6M12 17.2v.1" /></>,
  /* 리포트 — 문서 */
  REPORT_PUBLISHED: <><path d="M6.5 3.2h7l4.2 4.2v13.4H6.5z" /><path d="M9.6 12.4h5M9.6 16h5" /></>,
  /* 예측 — 과녁 */
  PREDICTION_REGISTERED: <><circle cx="12" cy="12" r="8.4" /><circle cx="12" cy="12" r="3.4" /></>,
  /* 댓글 — 말풍선 */
  REPORT_COMMENT: <path d="M4.2 5.2h15.6v10.6H9.6l-5.4 4.2z" />,
  /* 좋아요 — 하트 */
  REPORT_LIKE: <path d="M12 20.2S3.4 14.6 3.4 9.2A4.6 4.6 0 0 1 12 7a4.6 4.6 0 0 1 8.6 2.2c0 5.4-8.6 11-8.6 11z" />,
  /* 광고 — 배너 틀. 승인·반려 같은 그림, 말은 제목이 한다 */
  AD_APPROVED: <><rect x="2.6" y="6" width="18.8" height="12" rx="2" /><path d="M6.5 14.5 10 10.5l2.8 2.6 4.7-4.6" /></>,
  AD_REJECTED: <><rect x="2.6" y="6" width="18.8" height="12" rx="2" /><path d="M9 9.5l6 5M15 9.5l-6 5" /></>,
  /* 신고 — 방패. 처리 결과와 가려짐 둘 다 */
  REPORT_HANDLED: <><path d="M12 3 4.5 6v6c0 4.5 3.2 7.9 7.5 9 4.3-1.1 7.5-4.5 7.5-9V6z" /><path d="m9 12 2.2 2.2L15.5 10" /></>,
  CONTENT_BLOCKED: <><path d="M3 3l18 18" /><path d="M10.6 5.3A9.8 9.8 0 0 1 12 5.2c5.5 0 9 6.8 9 6.8a16 16 0 0 1-3.2 3.9M6.4 6.4A16 16 0 0 0 3 12s3.5 6.8 9 6.8a9.4 9.4 0 0 0 4.3-1" /></>,
}

/** 표에 없는 종류가 왔을 때. 알림이라는 것만 말하는 종 하나 */
const FALLBACK = <><path d="M6 9.6a6 6 0 0 1 12 0c0 4.8 1.9 5.8 1.9 5.8H4.1S6 14.4 6 9.6z" /><path d="M10 19h4" /></>

export default function NotificationTypeIcon({ type }: { type: NotificationType }) {
  return (
    <svg
      className="nt-type-ic"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {PATHS[type] ?? FALLBACK}
    </svg>
  )
}
