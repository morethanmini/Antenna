/* 예측 한 건이 블록체인까지 몇 걸음 남았는지. C-02 내 예측 현황이 쓴다.

   components/AnchorBadge.tsx 와 이름이 비슷하지만 다른 것을 그린다. 저쪽은
   **배치**(AnchorBatch)의 상태라 어휘가 셋(PENDING·CONFIRMED·FAILED)이고,
   이쪽은 **예측 한 건**의 상태라 넷이다 — 커밋이 아직 어느 배치에도 안 묶인
   WAITING 이 하나 더 있다. 두 어휘를 한 컴포넌트로 합치면 WAITING 을 PENDING
   으로 뭉개게 되고, 그러면 "아직 안 묶임" 과 "묶여서 블록 기다림" 이 같은
   말로 보인다. 사용자가 알고 싶은 건 정확히 그 차이다.

   문구를 여기 한 곳에 둔다. api/proof.ts 의 PROOF_STATUS_LABEL 은 검산 화면용
   짧은 말('앵커 대기')이라, 목록에서 처음 보는 사람에게는 무엇을 기다리는지가
   안 읽힌다 — 그래서 같은 상태에 다른 문장을 준다. */
import type { ProofAnchorStatus } from '../../api/proof'

/* 앵커 배치 주기. 화면 문구가 이 값을 여러 번 쓴다.

   서버 AnchorScheduler 의 cron 과 짝이다 — 2026-09-15 에 매일 00:05 에서
   **10분마다**로 바뀌었다(서버 설정 ANCHOR_CRON).

   시각이 아니라 주기라서 문장이 달라진다. 전에는 "자정 5분에 도는 배치가
   오늘 등록분을 한 번에 묶는다" 였는데, 이제 "곧 다음 회차가 묶는다" 다 —
   "오늘 등록분" 이라는 말도 더는 맞지 않는다(하루치를 모으지 않는다).

   **서버 cron 을 바꾸면 이 파일도 같이 바꾼다.** 사용자에게 보이는 문구라,
   어긋나면 앱이 거짓을 말한다. */
export const ANCHOR_BATCH_CYCLE = '10분마다'

const LABEL: Record<ProofAnchorStatus, string> = {
  WAITING: '블록체인 기록 예정',
  PENDING: '블록 확정 대기',
  CONFIRMED: '블록체인 기록 완료',
  FAILED: '기록 실패 · 재시도',
}

/* 세 상태의 뜻이 서로 달라 색만으로 구분하지 않는다 — 아이콘과 글자를 함께 둔다.
   색각 이상이 있어도, 흑백으로 캡쳐해도 읽혀야 한다(AnchorBadge 와 같은 규칙). */
const ICON: Record<ProofAnchorStatus, React.ReactNode> = {
  // 시계 — 아직 배치를 기다린다
  WAITING: <><circle cx="12" cy="12" r="9" /><path d="M12 7.5V12l3 1.8" /></>,
  // 상자 — 배치에는 묶였고 블록 확정을 기다린다
  PENDING: <><path d="M12 3 4 7.2v9.6L12 21l8-4.2V7.2Z" /><path d="M4 7.2 12 11.5l8-4.3" /><path d="M12 11.5V21" /></>,
  // 체크 — 체인에 올라 확정됐다
  CONFIRMED: <><circle cx="12" cy="12" r="9" /><path d="m8.5 12.2 2.4 2.4 4.6-4.9" /></>,
  // 느낌표 — 전송이 실패해 서버가 다시 시도한다
  FAILED: <><circle cx="12" cy="12" r="9" /><path d="M12 7.6v5" /><path d="M12 15.9v.3" /></>,
}

/* 대기가 정상이라는 것을 말로 못 박는다. 이 한 줄이 없으면 "기록 예정" 을
   보고 등록이 덜 끝난 줄 안다 — 등록은 서버가 받은 순간 이미 끝났고,
   앵커는 주기적으로 도는 배치가 나중에 묶는 별개의 일이다. */
const HINT: Record<ProofAnchorStatus, string> = {
  WAITING: `등록은 이미 끝났어요. ${ANCHOR_BATCH_CYCLE} 도는 배치가 곧 묶어서 기록합니다.`,
  PENDING: '배치가 트랜잭션을 보냈고 블록 확정을 기다리는 중입니다. 잠시 뒤 확정으로 바뀝니다.',
  CONFIRMED: '이 예측은 블록체인에 기록됐습니다. 이제 누구도, 나조차도 내용을 바꿀 수 없어요.',
  FAILED: '체인 기록 전송이 실패해 서버가 다음 회차에 다시 시도합니다. 예측 자체는 그대로 남아 있어요.',
}

/** 더 기다리면 바뀌는가. 두 묶음으로 가르는 기준을 여기 하나로 둔다 */
export const isAnchored = (s: ProofAnchorStatus) => s === 'CONFIRMED'

export const ANCHOR_HINT = HINT

export default function AnchorStage({ status }: { status: ProofAnchorStatus }) {
  return (
    <span className={`anchor-stage is-${status.toLowerCase()}`} title={HINT[status]}>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
           strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        {ICON[status]}
      </svg>
      {LABEL[status]}
    </span>
  )
}
