/* M-02 문구표 · 결과 경로. [ANT-FE-OPERATION]

   티켓 제약이 "kind 4종으로 문구만 분기한다. 구조를 분기하지 않는다" 라, 갈리는 것을 전부
   이 표 하나에 모았다. 컴포넌트 쪽에는 분기가 없다 — 새 kind 가 생기면 여기에 한 줄 는다.

   컴포넌트 파일이 아니라 여기 있는 이유는 Fast Refresh 다(react/only-export-components). */
import type { OperationKind, OperationResource } from '../../api/operations'

type Copy = {
  /**
   * PENDING 일 때. **무엇을 기다리는지** 말한다.
   *
   * 티켓이 "로딩 스피너로 뭉뚱그리지 말고" 라고 못박은 자리다. "처리 중" 은 사용자가
   * 무엇을 기다리는지도, 얼마나 걸릴지도 짐작할 수 없게 만든다.
   */
  waiting: string
  done: string
  failed: string
  /** 결과로 보내는 버튼 */
  go: string
}

export const OPERATION_COPY: Record<OperationKind, Copy> = {
  PREDICTION_BURN: {
    waiting: '블록체인에서 예측 등록을 확인 중이에요',
    done: '예측을 등록했어요',
    failed: '블록체인에 예측을 등록하지 못했어요',
    go: '예측 보기',
  },
  SUBSCRIBE: {
    waiting: '블록체인에서 결제를 확인 중이에요',
    done: '구독이 시작됐어요',
    failed: '블록체인 결제에 실패했어요',
    go: '내 구독 보기',
  },
  AD: {
    waiting: '블록체인에서 배너 등록을 확인 중이에요',
    done: '배너를 등록했어요',
    failed: '블록체인에 배너를 등록하지 못했어요',
    go: '홈에서 보기',
  },
  SEASON_JOIN: {
    waiting: '블록체인에서 참가 신청을 확인 중이에요',
    done: '시즌에 참가했어요',
    failed: '블록체인 참가 신청에 실패했어요',
    go: '시즌으로 가기',
  },
  /* 대회 배팅(ANT-EVENT-08). **배팅 한 건 = 작업 한 건**이라 묶음으로 등록해도 종목
     수만큼 이 문구가 따로 뜬다.

     **갈 곳은 예측 상세(C-03)다.** 서버가 이 작업에 ResourceType.PREDICTION 을 달아
     보내므로(EventEntryService) 아래 resourcePath 가 /predictions/{id} 를 돌려준다.
     배팅도 결국 예측 한 건으로 남기 때문이다 — 그래서 버튼 이름도 예측 등록
     (PREDICTION_BURN)과 같은 말을 쓴다.

     C-01 대회 경로(PredictTab, eventPath)가 접수 뒤 이 kind 로 OperationModal 을 띄운다
     (2026-09-19). 그전에는 PREDICTION_BURN 을 넘겨 소각 문구가 떴다. */
  EVENT_BET: {
    waiting: '블록체인에서 배팅을 확인 중이에요',
    done: '배팅을 등록했어요',
    failed: '블록체인 배팅에 실패했어요',
    go: '예측 보기',
  },
}

/**
 * 실패 화면에서 가장 먼저 궁금한 것. kind 와 무관하게 같은 문장이라 따로 둔다.
 *
 * 네 흐름 모두 토큰을 쓰는 작업이고, 체인에서 실패했다는 것은 트랜잭션이 되돌아갔다는
 * 뜻이라 차감도 없다. 이 한 줄이 없으면 사용자는 돈이 사라졌는지부터 걱정한다.
 */
export const REFUND_NOTE = '토큰은 소각되지 않았어요.'

/**
 * SUCCEEDED 일 때 갈 곳. "사용자가 스스로 결과를 찾아가게 두지 않는다"(티켓 제약).
 *
 * SEASON_PARTICIPANT 만 null 이다 — resource.id 가 **참가 id** 라서 시즌 id 를 모르면
 * /sim/{시즌}/play 를 만들 수 없다. 그 화면(G-03)은 시즌 id 를 이미 쥐고 있으므로
 * OperationModal 의 to 로 넘겨 덮는다. 여기서 억지로 짐작하면 없는 주소로 보낸다.
 */
export function resourcePath(resource?: OperationResource): string | null {
  if (!resource) return null
  switch (resource.type) {
    case 'PREDICTION':
      return `/predictions/${resource.id}`
    // 구독 단건 라우트가 없다. 목록(E-03)이 방금 생긴 구독을 맨 위에 보여준다
    case 'SUBSCRIPTION':
      return '/me/subscriptions'
    // 광고 상세 화면은 없다. 배너가 실제로 걸리는 곳이 홈(B-01)이라 거기서 확인시킨다
    case 'AD':
      return '/'
    case 'SEASON_PARTICIPANT':
      return null
  }
}
