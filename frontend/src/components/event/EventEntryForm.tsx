/* 대회 묶음 등록 — J-01. 10종목을 한 화면에서 적고 **서명 한 번**으로 보낸다.

   ── 왜 한 화면인가 ──
   서버는 5~10 종목을 한 요청으로 받는다. "첫 참가는 5종목 이상" 을 등록 시점에
   막아야 하고, 서명이 한 번이면 지갑 팝업이 다섯 번 뜨지 않는다. 종목 상세에서
   하나씩 담아 오는 구조로 하면 그 사이에 "담아 둔 것" 을 들고 있을 자리
   (보관·복구·만료)가 필요해진다 — 대회가 한 번뿐이라 그 무게를 지지 않는다.

   ── 무엇을 막는가 ──
   서버가 거절할 것을 여기서 먼저 잡는다. 서명은 nonce 를 1회용으로 태우므로,
   400 으로 돌아올 요청에 서명을 받으면 사용자가 지갑에서 다시 서명해야 한다.
   방향·목표가 모순과 금액 범위, 최소 종목 수가 그것이다. */
import { useMemo, useState } from 'react'
import type { ApiError } from '../../api/errors'
import {
  eventEntrySigningPayload,
  registerEntries,
  type BetDirection,
  type EventEntryAccepted,
  type EventEntryItem,
  type EventHome,
} from '../../api/events'
import { newNoteSalt, targetPriceInScale } from '../../api/predictions'
import { requestNonce } from '../../api/wallet'
import { connectAddress, hasWallet, personalSign } from '../../wallet/provider'

/** 한 종목의 입력값. 안 고른 종목은 direction 이 null 이라 제출에서 빠진다 */
type Row = {
  stockCode: string
  name: string
  basePrice: number | null
  direction: BetDirection | null
  targetPrice: string
  stake: string
  note: string
  noteSalt: string
}

const初 = (e: EventHome): Row[] =>
  e.stocks.map((s) => ({
    stockCode: s.stockCode,
    name: s.name,
    basePrice: s.basePrice,
    direction: null,
    targetPrice: '',
    stake: '',
    note: '',
    /* 종목마다 새 난수다. 같은 salt 를 두 번 쓰면 같은 근거가 같은 noteHash 로 남아
       원장만 보고도 "같은 말을 두 번 했다" 를 알 수 있다(예측 등록과 같은 규칙). */
    noteSalt: newNoteSalt(),
  }))

/** 이미 건 종목은 다시 못 건다 — 서버가 409 EVENT_STOCK_ALREADY_BET 이다 */
const alreadyBet = (e: EventHome, code: string) =>
  e.stocks.find((s) => s.stockCode === code)?.mine != null

/**
 * 한 줄의 흠. null 이면 통과다.
 *
 * 방향 모순은 **기준가가 아니라 직전 종가** 로 잰다 — 서버 PredictionRegisterService
 * 가 그렇게 본다(latestClose). 기준가만 보고 막으면 서버가 통과시킬 값을 화면이 거절한다.
 */
function flaw(r: Row, e: EventHome, lastPrice: number | null): string | null {
  if (!r.direction) return null
  const target = Number(r.targetPrice)
  if (!r.targetPrice || Number.isNaN(target) || target <= 0) return '목표가를 적어주세요.'
  if (!targetPriceInScale(target)) return '목표가는 소수 둘째 자리까지만 됩니다.'
  if (lastPrice !== null) {
    if (r.direction === 'UP' && target <= lastPrice) return '오른다면 목표가가 지금보다 높아야 합니다.'
    if (r.direction === 'DOWN' && target >= lastPrice) return '내린다면 목표가가 지금보다 낮아야 합니다.'
  }
  const stake = Number(r.stake)
  if (!r.stake || Number.isNaN(stake) || !Number.isInteger(stake)) return '배팅 금액은 정수로 적어주세요.'
  if (stake < e.rules.minStake || stake > e.rules.maxStake) {
    return `${e.rules.minStake.toLocaleString('ko-KR')} ~ ${e.rules.maxStake.toLocaleString('ko-KR')} ANT 사이여야 합니다.`
  }
  return null
}

export default function EventEntryForm({
  event,
  onDone,
}: {
  event: EventHome
  onDone: () => void
}) {
  const [rows, setRows] = useState<Row[]>(() => initialRows(event))
  const [busy, setBusy] = useState<null | 'signing' | 'sending'>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<EventEntryAccepted | null>(null)

  const lastPriceOf = (code: string) =>
    event.stocks.find((s) => s.stockCode === code)?.lastPrice ?? null

  const picked = rows.filter((r) => r.direction !== null)
  const flaws = useMemo(
    () => picked.map((r) => flaw(r, event, lastPriceOf(r.stockCode))).filter(Boolean),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [rows, event],
  )
  const staked = picked.reduce((sum, r) => sum + (Number(r.stake) || 0), 0)

  /* 최소 종목 수는 **첫 참가에만** 건다. 이미 건 종목이 있으면 한 종목씩 더해도 된다 */
  const first = event.me.stockCount === 0
  const needMore = first && picked.length < event.rules.minStocks
  const canSubmit = picked.length > 0 && flaws.length === 0 && !needMore && busy === null

  const set = (code: string, patch: Partial<Row>) =>
    setRows((rs) => rs.map((r) => (r.stockCode === code ? { ...r, ...patch } : r)))

  async function submit() {
    if (!canSubmit) return
    setError(null)

    if (!hasWallet()) {
      setError('지갑이 필요합니다. 설정에서 지갑을 먼저 연동해주세요.')
      return
    }

    const items: EventEntryItem[] = picked.map((r) => ({
      stockCode: r.stockCode,
      direction: r.direction as BetDirection,
      targetPrice: Number(r.targetPrice),
      stake: Number(r.stake),
      note: r.note,
      noteSalt: r.noteSalt,
    }))

    setBusy('signing')
    try {
      const address = await connectAddress()
      /* scope 는 EVENT_ENTRY 다. 이 값이 서버 nonce 칸 sig:nonce:{userId}:event-entry 를
         정하고 payload 첫 줄의 태그도 같은 값이다 — 다른 scope 를 쓰면 서버가 못 찾는다. */
      const nonce = await requestNonce('EVENT_ENTRY')
      const payload = await eventEntrySigningPayload(event.eventId, items, nonce)
      const signature = await personalSign(payload, address)

      setBusy('sending')
      setDone(await registerEntries(event.eventId, { items, signature }))
      onDone()
    } catch (e) {
      setError(message(e))
    } finally {
      setBusy(null)
    }
  }

  if (done) {
    return (
      <section className="card ev-block" aria-live="polite">
        <header className="ev-block-head">
          <h2>등록했습니다</h2>
          <p>
            {done.entries.length}종목 · 지금 블록체인에 올리는 중입니다.
            아래 <b>내 참가 내역</b>에서 확정을 확인할 수 있습니다.
          </p>
        </header>
        <p className="ev-state is-quiet">
          {done.me.qualified
            ? `참가 자격을 채웠습니다 (${done.me.stockCount}종목).`
            : `순위에 들려면 ${event.rules.minStocks}종목 이상이어야 합니다. 지금 ${done.me.stockCount}종목입니다.`}
        </p>
      </section>
    )
  }

  return (
    <section className="card ev-block ev-entry" aria-labelledby="ev-entry-h">
      <header className="ev-block-head">
        <h2 id="ev-entry-h">예측 등록</h2>
        <p>
          방향과 목표가를 적은 종목만 등록됩니다.
          {first && ` 처음 참가할 때는 ${event.rules.minStocks}종목 이상 골라야 합니다.`}
        </p>
      </header>

      <ul className="ev-entry-rows">
        {rows.map((r) => {
          const bet = alreadyBet(event, r.stockCode)
          const last = lastPriceOf(r.stockCode)
          const bad = r.direction ? flaw(r, event, last) : null
          return (
            <li key={r.stockCode} className={bet ? 'is-done' : r.direction ? 'is-on' : ''}>
              <div className="ev-entry-head">
                <b>{r.name}</b>
                <span className="num">{last === null ? '—' : `${last.toLocaleString('ko-KR')}원`}</span>
                {bet && <em>등록함</em>}
              </div>

              {!bet && (
                <>
                  <div className="ev-entry-dir" role="group" aria-label={`${r.name} 방향`}>
                    {(['UP', 'DOWN'] as const).map((d) => (
                      <button
                        key={d}
                        type="button"
                        className={r.direction === d ? 'is-on' : ''}
                        aria-pressed={r.direction === d}
                        onClick={() => set(r.stockCode, { direction: r.direction === d ? null : d })}
                      >
                        {d === 'UP' ? '오른다' : '내린다'}
                      </button>
                    ))}
                  </div>

                  {r.direction && (
                    <div className="ev-entry-fields">
                      <label>
                        <span>목표가</span>
                        <input
                          type="number" inputMode="decimal" step="0.01" min="0"
                          value={r.targetPrice}
                          onChange={(ev) => set(r.stockCode, { targetPrice: ev.target.value })}
                        />
                      </label>
                      <label>
                        <span>배팅 (ANT)</span>
                        <input
                          type="number" inputMode="numeric" step="1"
                          min={event.rules.minStake} max={event.rules.maxStake}
                          value={r.stake}
                          onChange={(ev) => set(r.stockCode, { stake: ev.target.value })}
                        />
                      </label>
                      <label className="ev-entry-note">
                        <span>근거 (선택)</span>
                        <input
                          type="text" maxLength={5000} value={r.note}
                          onChange={(ev) => set(r.stockCode, { note: ev.target.value })}
                        />
                      </label>
                      {bad && <p className="ev-entry-bad">{bad}</p>}
                    </div>
                  )}
                </>
              )}
            </li>
          )
        })}
      </ul>

      <footer className="ev-entry-foot">
        <p className="num">
          {picked.length}종목 · {staked.toLocaleString('ko-KR')} ANT
        </p>
        {needMore && (
          <p className="ev-entry-bad">
            {event.rules.minStocks}종목 이상 골라주세요. 지금 {picked.length}종목입니다.
          </p>
        )}
        {error && <p className="ev-entry-bad" role="alert">{error}</p>}
        <button type="button" className="btn-primary" disabled={!canSubmit} onClick={submit}>
          {busy === 'signing' ? '지갑에서 서명해주세요…'
            : busy === 'sending' ? '등록하는 중…'
            : '서명하고 등록하기'}
        </button>
        {/* 서명이 한 번인 이유를 말해 둔다 — 지갑 팝업이 하나만 뜨는 게 정상이다 */}
        <p className="ev-entry-hint">고른 종목 전부를 서명 한 번으로 등록합니다.</p>
      </footer>
    </section>
  )
}

function message(e: unknown): string {
  const code = (e as ApiError | undefined)?.code
  switch (code) {
    case 'EVENT_NOT_OPEN': return '아직 등록이 열리지 않았습니다.'
    case 'EVENT_SEALED': return '등록이 마감됐습니다.'
    case 'EVENT_MIN_STOCKS': return '처음 참가할 때는 최소 종목 수를 채워야 합니다.'
    case 'EVENT_STOCK_ALREADY_BET': return '이미 등록한 종목이 들어 있습니다. 새로고침 후 다시 시도해주세요.'
    case 'EVENT_STAKE_OUT_OF_RANGE': return '배팅 금액이 범위를 벗어났습니다.'
    case 'EVENT_INSUFFICIENT_BALANCE': return 'ANT 잔액이 부족합니다.'
    /* 서명 문자열이 서버 조립본과 한 바이트라도 다르면 예외가 아니라 엉뚱한 주소가
       복원돼 이 코드가 온다. 사용자가 할 수 있는 일은 다시 시도뿐이다. */
    case 'SIGNER_MISMATCH': return '서명을 확인하지 못했습니다. 다시 시도해주세요.'
    case 'WALLET_NOT_LINKED': return '지갑을 먼저 연동해주세요.'
    case 'CHAIN_UNAVAILABLE': return '블록체인이 잠시 응답하지 않습니다. 잠시 뒤 다시 시도해주세요.'
  }
  if (e instanceof Error) return e.message
  return '등록하지 못했습니다.'
}
