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

   그래서 **"맞히면 몇 배" 를 등록 시점에 적을 수 없다.** 배율은 마감 뒤 양쪽 풀이
   확정돼야 나오고, 접수 중에는 컨트랙트조차 풀 크기를 모른다 — 방향이 커밋으로
   가려져 있어 reveal 때 비로소 풀에 들어간다. 화면이 "최대 N ANT" 를 약속하면
   그건 근거 없는 숫자다. 규칙만 적고 금액은 정산이 말하게 둔다.

   ── 방향은 따로 고르지 않는다 ────────────────────────────────────────
   컨트랙트 커밋이 keccak256(eventId, stockIdx, side, noteHash) 이고 side 는 위에서
   고른 예측 방향(UP·DOWN)이다. 배팅 카드가 방향을 다시 물으면 예측과 배팅이
   엇갈린 사람이 생기는데, 컨트랙트에는 그런 자리가 없다. 여기서는 **금액만** 받고,
   어느 쪽에 걸리는지는 ①에서 정해진 것을 그대로 비춰 준다.

   ── 아직 없는 것 ─────────────────────────────────────────────────────
   - 배팅 금액은 커밋 문자열에도 예측 서명 문자열에도 들어가지 않는다. 그 다섯 값은
     예측의 봉인이고, 배팅은 컨트랙트가 제 커밋을 따로 만든다.
   - 접수 API 가 아직 없다(POST /predictions 에 칸이 없고 배팅 전용 경로도 없다).
     금액은 화면 상태로만 산다 — 붙일 자리는 PredictTab.submit() 에 표시해 뒀다.
   - minStake·maxStake 는 회차를 열 때 openEvent 로 체인에 박히는 값이다. 그걸
     읽어 오는 API 가 없어 아래 상수를 임시로 쓴다. 서버가 내려주면 지운다. */
import { fetchWalletBalance, formatToken } from '../../api/wallet'
import { useBlock } from '../../api/useBlock'
import type { Direction } from '../../api/predictions'

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
  /** ①에서 정해진 예측 방향. 배팅도 이쪽에 걸린다 — 따로 고르는 자리가 없다 */
  direction: Direction | null
  /** 대회 안내(J-01)로 가는 주소 */
  contestPath: string
}

const SIDE_LABEL: Record<Direction, string> = { UP: '상승', DOWN: '하락' }

/* ── 배팅 카드 ──────────────────────────────────────────────
   **선택이다.** 대회 기간이라도 배팅 없이 예측만 등록할 수 있어야 한다 —
   예측은 판단을 남기는 일이고 배팅은 돈을 거는 일이라, 둘을 묶어 강제하면
   대회가 끝난 뒤 남는 기록이 "돈을 걸 수 있는 사람의 판단" 뿐이 된다. */
export default function EventBet({ value, onChange, direction, contestPath }: BetProps) {
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
        <h3 id="ev-bet-h">
          <span className="ev-bet-mark" aria-hidden="true">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor">
              <path d="M3 7.5 7.5 11 12 4.5 16.5 11 21 7.5V18a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18z" />
            </svg>
          </span>
          배팅하기 <span className="ev-bet-only">(대회 전용)</span>
          <span className="pf-opt">선택</span>
        </h3>
        <a className="ev-bet-rules" href={contestPath}>대회 안내 ›</a>
      </header>

      {/* 어느 쪽에 걸리는지 먼저 못박는다. 금액 칸만 있으면 "방향은 어디서 고르지" 가 된다 */}
      <p className="ev-bet-side">
        {direction
          ? <>①에서 고른 <b className={`is-${direction.toLowerCase()}`}>{SIDE_LABEL[direction]}</b> 쪽에 걸립니다.</>
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

      {/* 건 돈과 받는 방식. **배율을 적지 않는다** — 맞혔을 때 얼마가 되는지는
          양쪽 풀이 닫혀야 나오고, 지금 적는 숫자는 무엇이든 지어낸 값이다. */}
      <dl className="ev-bet-sum">
        <div>
          <dt>배팅 금액</dt>
          <dd className="num">{picked ? `${amount.toLocaleString('ko-KR')} ANT` : '—'}</dd>
        </div>
        <div className="ev-bet-payout">
          <dt>받는 방식</dt>
          <dd>
            맞힌 사람끼리 <b>양쪽에 걸린 ANT 전부</b>를 <b>건 만큼의 비율</b>로 나눠 갖습니다.
            <span className="ev-bet-rate">
              배율은 마감 뒤 확정 · 보합이면 원금 환불 · 틀리면 잃음
            </span>
          </dd>
        </div>
      </dl>

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
