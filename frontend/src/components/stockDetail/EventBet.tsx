/* 천하제일 단타대회 — 예측 등록에 얹히는 배팅 (ANT-FE-EVENT-BET)

   **대회로 들어왔을 때만 나온다.** 평소 C-01 은 예측만 받는다. 대회 종목 카드가
   `?event=scalping` 으로 데려왔고 등록 창이 열려 있을 때만 PredictTab 이 끼워 넣는다
   (event/contest.ts 의 cardHref · phaseAt).

   왜 PredictTab 안에 조건부로 넣고 화면을 따로 만들지 않았나
   - 대회 예측도 결국 같은 C-01 이다. 목표가·기간·근거·내 판단·커밋 봉인·서명이
     한 글자도 다르지 않다. 화면을 복제하면 커밋 규격이 바뀔 때마다 두 곳을 고쳐야
     하고, 둘이 갈리는 순간 한쪽이 SIGNER_MISMATCH 를 낸다.
   - 그래서 **기존 네 단계는 손대지 않는다.** 배팅은 단계에 끼어들지 않고
     네 단계가 끝난 자리에 카드 하나로 얹힌다.

   ── 배당은 고정 배율이 아니다 (PredictionEventPool.sol) ──────────────
   컨트랙트가 쓰는 것은 **승자투표권(패리뮤추얼)** 이다.

       payout = stake × (poolUp + poolDown) / poolWinner      (이긴 쪽)
       payout = 0                                              (진 쪽)
       payout = stake                                          (보합 · 이긴 풀이 0 · 미리빌)

   그래서 **확정 배율이라는 것이 등록 시점에 없다.** 배율은 마감 뒤 양쪽 풀이 닫혀야
   나온다. 컨트랙트도 접수 중에는 풀 크기를 모른다 — 방향이 커밋으로 가려져 있어
   reveal 때 비로소 풀에 들어간다.

   ── 그래도 "지금 기준" 은 보여 준다 (2026-09-18) ─────────────────────
   아무 숫자도 안 보여 주면 얼마를 걸지 정할 근거가 없다. 그래서 **확정값 대신 지금
   풀로 계산한 값**을 적는다(payoutNow). 서버가 GET /events/{id} 에서 방향별 합계를
   주므로 지어낸 값이 아니다 — 컨트랙트는 몰라도 서버는 자기 DB 로 안다.

   두 가지를 지킨다.
     · 내 배팅을 **양쪽 풀에 더한 뒤** 계산한다. 빼고 재면 실제보다 큰 금액이 나온다
     · "마감까지 남이 걸면 달라진다" 를 같은 자리에 적는다 — 확정으로 읽히면 안 된다

   ── 방향은 따로 고르지 않는다 ────────────────────────────────────────
   컨트랙트 커밋이 keccak256(eventId, stockIdx, side, noteHash) 이고 side 는 위에서
/* ── 배팅 칸 ──────────────────────────────────────────────────
   ① 목표가 **안에** 선다(2026-09-18). 전에는 등록 카드 위에 따로 세웠는데,
   "어느 쪽에 얼마" 는 목표가·방향을 정하는 것과 **한 결정**이다 — 떼어 두면
   방향 버튼과 금액 칸 사이를 눈이 오간다. 배당률이 방향 버튼에 붙은 지금은
   고르는 자리와 그 값이 한 덩어리로 읽힌다.

   **대회에서는 필수다.** 서버가 종목마다 stake 를 받아 풀에 넣으므로 금액 없는
   대회 등록이라는 것이 없다(POST /events/{id}/entries). 돈을 걸지 않고 판단만
   남기고 싶으면 대회 밖에서 같은 종목에 그냥 예측하면 된다 — 그 길은 열려 있다. */
import { fetchWalletBalance, formatToken } from '../../api/wallet'
import { useBlock } from '../../api/useBlock'
import type { Direction } from '../../api/predictions'
import { payoutIfIBet, type EventPool } from '../../api/events'

/**
 * 빠른 선택 금액. 직접 입력은 이 밖의 값을 위한 칸이다.
 *
 * **단위를 작게 잡는다(2026-09-16).** 이 대회는 다섯 종목 이상에 걸어야 하므로
 * 한 종목에 거는 돈은 보유액을 다섯으로 나눈 몫 언저리여야 한다. 가입 보너스가
 * 10,000 ANT 인데 1,000·5,000·10,000 을 내밀면 두 번째만 눌러도 규칙을 못 채운다 —
 * 고르기 쉬운 값이 규칙을 깨는 값이면 그건 화면이 잘못 물어본 것이다.
 */
export const BET_PRESETS = [500, 1000, 2000] as const

/** 이 대회에 걸어야 하는 최소 종목 수. 한 종목에 몰 수 없다는 뜻이기도 하다 */
export const BET_MIN_STOCKS = 5

/**
 * 회차 한도 — **임시값이다.** 진짜는 openEvent 가 체인에 박은 minStake·maxStake 다.
 * 상한을 프리셋 최고(2,000)에 맞춰 둔다. 여기만 10만으로 열어 두면 한 종목에 전액을
 * 밀어 넣을 수 있어 "다섯 종목 이상" 이 이름뿐인 규칙이 된다.
 */
export const BET_MIN = 500
export const BET_MAX = 2000

/** 입력 문자열 → 정수 ANT. 비었거나 숫자가 아니면 0 */
export function betAmount(raw: string) {
  const n = Number(raw.replace(/[^0-9]/g, ''))
  return Number.isFinite(n) ? Math.floor(n) : 0
}

/* ── 대회 배너 ──────────────────────────────────────────────
   이 화면이 왜 평소와 다른지 맨 위에서 한 번 말한다. 배팅 카드가 폼 한가운데
   갑자기 나타나면 "내가 뭘 잘못 눌렀나" 가 되므로, 들어온 문이 대회였다는 것을
   먼저 보여 준다. 캐릭터는 치킨 한 입 — 상이 무엇인지 그림이 먼저 말한다. */
export function EventBanner({ name, closesAt }: { name: string; closesAt: string }) {
  return (
    <aside className="ev-banner">
      <div className="ev-banner-text">
        <span className="ev-badge">EVENT</span>
        <h2>예측하고, <b>치킨</b> 먹자!</h2>
        <p className="ev-banner-sub">
          <b>{name}</b> 기간에는 예측과 함께 ANT 를 걸 수 있습니다.
        </p>
        <p className="ev-banner-fine">{`맞힌 쪽이 걸린 ANT 를 모두 나눠 갖습니다 · ${closesAt} 마감`}</p>
      </div>
      {/* 장식이다. 이름·상황은 옆 글이 전부 말하므로 대체텍스트를 비운다 */}
      <img
        className="ev-banner-art"
        src="/assets/character/black_ant/antenna-character-black-chicken.png"
        alt=""
        aria-hidden="true"
      />
    </aside>
  )
}

type BetProps = {
  /** 지금 걸어 둔 금액(정수 ANT 문자열). 빈 문자열이면 걸지 않은 것이다 */
  value: string
  onChange: (next: string) => void
  /** 위에서 정해진 예측 방향. 배팅도 이쪽에 걸린다 — 따로 고르는 자리가 없다 */
  direction: Direction | null
  /** 대회 안내(J-01)로 가는 주소 */
  contestPath: string
  /**
   * 이 종목의 지금 풀. 서버가 **확정된 배팅만** 세어 준다(PENDING·FAILED 제외).
   * 회차를 못 읽었으면 null — 그때는 예상 금액을 적지 않는다.
   */
  pool?: EventPool | null
}

const SIDE_LABEL: Record<Direction, string> = { UP: '상승', DOWN: '하락' }

/* payoutNow 를 api/events.ts 의 payoutIfIBet 으로 옮겼다(2026-09-18). 같은 식을
   대회 종목 카드도 써야 하는데(이미 건 배팅이 맞으면 받을 돈), 두 곳에 적어 두면
   한쪽만 고쳤을 때 화면 둘이 다른 금액을 약속한다. 규칙과 그 이유는 그쪽 주석에 있다.

   **이 값은 지금 이 순간의 것이다.** 마감까지 남이 걸면 그대로 움직인다. 그래서 화면은
   "확정"이라고 말하지 않고 기준 시각을 함께 적는다. */

/* ── 배팅 카드 ──────────────────────────────────────────────
   **선택이다.** 대회 기간이라도 배팅 없이 예측만 등록할 수 있어야 한다 —
   예측은 판단을 남기는 일이고 배팅은 돈을 거는 일이라, 둘을 묶어 강제하면
   대회가 끝난 뒤 남는 기록이 "돈을 걸 수 있는 사람의 판단" 뿐이 된다. */
export default function EventBet({ value, onChange, direction, contestPath, pool }: BetProps) {
  const wallet = useBlock(() => fetchWalletBalance(), [])

  const amount = betAmount(value)
  const picked = amount > 0

  /* 잔액은 정수 ANT 문자열이다(decimals 0). Number 로 좁히지 않고 BigInt 로 견준다 —
     numeric(30,0) 은 안전 정수 범위를 넘을 수 있다. */
  const raw = wallet.data?.balance
  const overBalance = picked && !!raw && /^-?\d+$/.test(raw) && BigInt(amount) > BigInt(raw)

  const tooSmall = picked && amount < BET_MIN
  const tooBig = picked && amount > BET_MAX
  /* 미리 고른 금액과 같으면 그 알약이 켜진다. 직접 입력 칸은 그 밖의 값을 들고 있다 */
  const isCustom = picked && !BET_PRESETS.some((p) => p === amount)

  return (
    <section className="ev-bet" aria-labelledby="ev-bet-h">
      <header className="ev-bet-head">
        <h4 id="ev-bet-h">배팅하기 <span className="pf-req">필수</span></h4>
        <a className="ev-bet-rules" href={contestPath}>대회 안내 ›</a>
      </header>

      {/* 어느 쪽에 걸리는지 먼저 못박는다. 금액 칸만 있으면 "방향은 어디서 고르지" 가 된다 */}
      <p className="ev-bet-side">
        {direction
          ? <>위에서 고른 <b className={`is-${direction.toLowerCase()}`}>{SIDE_LABEL[direction]}</b> 쪽에 걸립니다.</>
          : <>목표가를 정하면 그 방향에 걸립니다.</>}
      </p>

      <div className="ev-bet-picks">
        {BET_PRESETS.map((p) => (
          <button
            key={p}
            type="button"
            className={`ev-bet-pick${!isCustom && amount === p ? ' is-on' : ''}`}
            aria-pressed={!isCustom && amount === p}
            /* 같은 값을 다시 누르면 푼다 — 한 번 고르면 배팅을 취소할 길이
               직접 입력 칸을 비우는 것뿐이면 걸어 둔 줄 모르고 서명한다 */
            onClick={() => onChange(amount === p ? '' : String(p))}
          >
            {p.toLocaleString('ko-KR')}
          </button>
        ))}
        <label className={`ev-bet-custom${isCustom ? ' is-on' : ''}`}>
          <span className="ev-bet-custom-label">직접 입력</span>
          <input
            type="text"
            inputMode="numeric"
            className="num"
            value={isCustom ? String(amount) : ''}
            placeholder="0"
            aria-label="배팅 금액 직접 입력"
            onChange={(e) => onChange(e.target.value.replace(/[^0-9]/g, ''))}
          />
          <span className="ev-bet-unit">ANT</span>
        </label>
      </div>

      {/* 건 돈과 받을 돈.

          **"지금 기준" 이라고 못박는다.** 패리뮤추얼이라 배율은 마감 뒤에야 확정되는데,
          그렇다고 아무 숫자도 안 보여 주면 얼마를 걸지 정할 근거가 없다. 그래서 확정값
          대신 **지금 풀로 계산한 값**을 적고, 움직인다는 사실을 같은 자리에 적는다.
          숫자를 지어내지 않으면서 판단 재료는 주는 선이다. */}
      <dl className="ev-bet-sum">
        {picked && direction && pool ? (
          <div className="ev-bet-payout">
            <dt>맞히면 (지금 기준)</dt>
            <dd>
              <b className="num">{(payoutIfIBet(pool, direction, amount) ?? amount).toLocaleString('ko-KR')} ANT</b>
              {/* 순이익을 따로 적는다 — 받을 돈만 있으면 원금이 포함된 값인지 헷갈린다 */}
              <span className="ev-bet-net num">
                {(() => {
                  const net = (payoutIfIBet(pool, direction, amount) ?? amount) - amount
                  return net > 0 ? `+${net.toLocaleString('ko-KR')} ANT` : '원금만'
                })()}
              </span>
              <span className="ev-bet-rate">
                지금 걸린 돈으로 계산한 값입니다. 마감까지 남이 걸면 달라집니다 ·
                보합이면 원금 환불 · 틀리면 잃음
              </span>
            </dd>
          </div>
        ) : (
          <div className="ev-bet-payout">
            <dt>받는 방식</dt>
            <dd>
              맞힌 사람끼리 <b>양쪽에 걸린 ANT 전부</b>를 <b>건 만큼의 비율</b>로 나눠 갖습니다.
              <span className="ev-bet-rate">
                {!direction ? '목표가를 정하면 지금 기준 받을 금액을 보여 줍니다.'
                  : !picked ? '금액을 고르면 지금 기준 받을 금액을 보여 줍니다.'
                    : '보합이면 원금 환불 · 틀리면 잃음'}
              </span>
            </dd>
          </div>
        )}
      </dl>

      {/* 반대쪽이 비어 있으면 왜 원금뿐인지 말해 준다 — 숫자만 보면 계산이 틀린 줄 안다 */}
      {picked && direction && pool && (direction === 'UP' ? pool.down : pool.up) === 0 && (
        <p className="sd-help">
          아직 <b>{direction === 'UP' ? '내린다' : '오른다'}</b>에 건 사람이 없어 가져올 돈이
          없습니다. 반대쪽에 돈이 쌓이면 받을 금액이 올라갑니다.
        </p>
      )}

      {/* 잔액은 고른 뒤에만 말한다. 아무것도 걸지 않았는데 먼저 보이면
          대회가 잔액부터 묻는 화면이 된다 */}
      {picked && wallet.data && (
        <p className="sd-help">
          보유 <b className="num">{formatToken(wallet.data.balance)} ANT</b>
          {wallet.data.syncedAt ? '' : ' · 대사 전'}
          {` · 한 종목에 한 번만 · ${BET_MIN_STOCKS}종목 이상 걸어야 합니다`}
        </p>
      )}
      {tooSmall && (
        <p className="sd-warn">{`배팅은 최소 ${BET_MIN.toLocaleString('ko-KR')} ANT 부터 걸 수 있습니다.`}</p>
      )}
      {tooBig && (
        <p className="sd-warn">{`한 번에 걸 수 있는 최대 금액은 ${BET_MAX.toLocaleString('ko-KR')} ANT 입니다.`}</p>
      )}
      {overBalance && (
        <p className="sd-warn">보유한 ANT 보다 많이 걸었습니다. 금액을 줄여 주세요.</p>
      )}
      {/* 되돌릴 수 없는 쪽에 붙는 결정이라 반드시 고지한다. 예측 자체의
          "수정·삭제 불가" 와 따로 적는다 — 잃는 것이 다르다 */}
      {picked && (
        <p className="sd-irreversible">
          배팅한 ANT 는 등록 즉시 묶이며 취소할 수 없습니다.
        </p>
      )}
    </section>
  )
}
