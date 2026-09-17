/* I-04 이벤트 회차 운영 · /admin/events
   담당 스토리 [ANT-FE-ADMIN-EVENT] · 서버는 ANT-EVENT-04 · 06 (이미 있다)

   ── 이 화면이 왜 필요한가 ────────────────────────────────
   관리자 API 6종이 열려 있는데 부르는 화면이 없었다. 회차를 열고 봉인하고 정산하는 일을
   전부 수동 요청으로 해야 했다. 운영자 한 명만 보는 화면이라 **동작이 기준**이다.

   ── 되돌릴 수 없는 버튼 ──────────────────────────────────
   봉인(seal)과 정산(settle)은 온체인 트랜잭션을 보낸다. 실수로 누르면 되돌릴 수 없어
   확인 대화상자를 끼운다. 생성과 기준가 수정은 고칠 여지가 있어 바로 실행한다.

   종목 순서가 곧 컨트랙트의 종목 칸 번호(idx)다. 입력한 차례를 그대로 보낸다 —
   화면에서 정렬하면 이미 걸린 배팅이 다른 종목을 가리킨다. */
import { useCallback, useEffect, useState } from 'react'
import ConfirmDialog from '../components/ConfirmDialog'
import {
  createEvent, getEvents, getSettlementStatus, reopenEvent,
  sealEvent, setBasePrice, settleEvent,
} from '../api/admin'
import type { AdminEventListItem, EventCreateBody } from '../api/admin'
import type { ApiError } from '../api/errors'
import '../styles/screens/admin.css'

type Danger = { kind: 'seal' | 'settle'; event: AdminEventListItem }

const PHASE_LABEL: Record<string, string> = {
  SCHEDULED: '생성됨', OPEN: '접수 중', SEALED: '봉인됨', SETTLING: '정산 중', DONE: '끝남',
}

export default function AdminEvent() {
  const [items, setItems] = useState<AdminEventListItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [busy, setBusy] = useState<number | null>(null)
  const [danger, setDanger] = useState<Danger | null>(null)
  /* 정산 상태는 회차마다 따로 연다. 목록을 그릴 때 전부 부르면 회차 수만큼 요청이 나간다. */
  const [status, setStatus] = useState<Record<number, string>>({})

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await getEvents()
      setItems(res.items)
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setLoading(false)
    }
  }, [])

  // oxlint-disable-next-line react/set-state-in-effect -- 데이터 페칭이라 상태 갱신이 목적이다.
  useEffect(() => { void load() }, [load])

  /** 운영 버튼 공통 — 202 라 목록을 다시 읽어도 상태가 아직 안 바뀌어 있을 수 있다. */
  const 실행 = useCallback(async (eventId: number, what: string, fn: () => Promise<unknown>) => {
    setBusy(eventId)
    setError(null)
    setNote(null)
    try {
      await fn()
      setNote(`${what} 요청을 보냈습니다. 체인 확정은 인덱서가 반영하므로 잠시 뒤 새로 고치세요.`)
      await load()
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setBusy(null)
    }
  }, [load])

  const 정산상태보기 = useCallback(async (eventId: number) => {
    setBusy(eventId)
    setError(null)
    try {
      const res = await getSettlementStatus(eventId)
      setStatus((s) => ({ ...s, [eventId]: JSON.stringify(res, null, 2) }))
    } catch (e) {
      setError((e as ApiError).message)
    } finally {
      setBusy(null)
    }
  }, [])

  return (
    <main className="main">
      <div className="main-inner">
        <div className="page-head">
          <h1>이벤트 회차 운영</h1>
          <p>회차를 만들고, 봉인하고, 정산합니다. <b>봉인과 정산은 온체인 트랜잭션이라 되돌릴 수 없습니다.</b></p>
        </div>

        {error && <div className="admin-error" role="alert">{error}</div>}
        {note && <div className="admin-note" role="status">{note}</div>}

        <CreateForm
          onCreated={(id) => { setNote(`회차 ${id} 를 만들었습니다. 접수 시각이 되면 크론이 OPEN 으로 올립니다.`); void load() }}
          onError={(m) => { setError(m) }}
        />

        <h2 className="admin-h2">회차 목록</h2>
        {loading && <div className="placeholder">불러오는 중…</div>}
        {!loading && items.length === 0 && <div className="placeholder">만들어진 회차가 없습니다.</div>}

        {!loading && items.map((e) => (
          <div key={e.eventId} className={`admin-card${busy === e.eventId ? ' busy' : ''}`}>
            <div className="admin-card-head">
              <b>#{e.eventId} {e.title}</b>
              <span className="admin-chip">{PHASE_LABEL[e.phase] ?? e.phase}</span>
              {e.slug && <span className="admin-dim">/events/{e.slug}</span>}
            </div>
            <div className="admin-dim">
              접수 {when(e.opensAt)} → 봉인 {when(e.sealsAt)} · 기준일 {e.baseDate} · 판정일 {e.settleDate}
            </div>

            <div className="admin-actions">
              <button type="button" className="se-btn" disabled={busy === e.eventId}
                onClick={() => { void 실행(e.eventId, 'openEvent 재전송', () => reopenEvent(e.eventId)) }}
              >openEvent 재전송</button>

              <button type="button" className="se-btn solid danger" disabled={busy === e.eventId}
                onClick={() => { setDanger({ kind: 'seal', event: e }) }}
              >봉인</button>

              <button type="button" className="se-btn solid danger" disabled={busy === e.eventId}
                onClick={() => { setDanger({ kind: 'settle', event: e }) }}
              >정산</button>

              <button type="button" className="se-btn" disabled={busy === e.eventId}
                onClick={() => { void 정산상태보기(e.eventId) }}
              >정산 상태</button>
            </div>

            <BasePriceRow
              eventId={e.eventId}
              onDone={() => { setNote('기준가를 넣었습니다.') }}
              onError={(m) => { setError(m) }}
            />

            {/* 정산 상태는 필드가 많고 화면이 운영자 전용이라 원본 JSON 을 그대로 보여 준다.
                예쁘게 접으면 무엇이 빠졌는지 보이지 않는다. */}
            {status[e.eventId] && <pre className="admin-pre">{status[e.eventId]}</pre>}
          </div>
        ))}
      </div>

      {danger && (
        <ConfirmDialog
          title={danger.kind === 'seal' ? '접수를 봉인합니다' : '정산을 시작합니다'}
          description={danger.kind === 'seal'
            ? '더 이상 배팅을 받지 않고 온체인 seal 트랜잭션을 보냅니다. 되돌릴 수 없습니다.'
            : '리빌 → 종목 판정 → 풀 배분 → 순위 굳힘이 실행됩니다. 되돌릴 수 없습니다.'}
          confirmLabel={danger.kind === 'seal' ? '봉인' : '정산'}
          tone="danger"
          onConfirm={() => {
            const d = danger
            setDanger(null)
            void 실행(
              d.event.eventId,
              d.kind === 'seal' ? '봉인' : '정산',
              () => (d.kind === 'seal' ? sealEvent(d.event.eventId) : settleEvent(d.event.eventId)),
            )
          }}
          onCancel={() => { setDanger(null) }}
        />
      )}
    </main>
  )
}

/* ── 회차 생성 폼 ────────────────────────────────────────
   종목은 줄바꿈으로 나눈 6자리 코드다. 입력 위젯을 열 개 만들지 않는 이유 —
   운영자는 코드 목록을 다른 곳에서 복사해 온다. 붙여넣기 한 번이 제일 빠르다. */
function CreateForm({ onCreated, onError }: {
  onCreated: (eventId: number) => void
  onError: (message: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [sending, setSending] = useState(false)
  const [form, setForm] = useState({
    title: '', slug: '', opensAt: '', sealsAt: '',
    baseDate: '', settleDate: '', minStake: '100', maxStake: '5000',
    minStocks: '5', codes: '',
  })

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    setForm((f) => ({ ...f, [k]: e.target.value }))
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSending(true)
    try {
      const body: EventCreateBody = {
        title: form.title,
        // 빈 slug 를 보내면 패턴 검사에 걸린다. 점검용 회차는 주소가 없어도 된다
        slug: form.slug.trim() || undefined,
        // datetime-local 은 오프셋이 없다. 명세 §1 이 KST 오프셋을 요구하므로 붙여 준다
        opensAt: `${form.opensAt}:00+09:00`,
        sealsAt: `${form.sealsAt}:00+09:00`,
        baseDate: form.baseDate,
        settleDate: form.settleDate,
        minStake: form.minStake,
        maxStake: form.maxStake,
        minStocks: Number(form.minStocks),
        // 순서가 곧 idx 다. 정렬하지 않는다
        stockCodes: form.codes.split(/[\s,]+/).map((c) => c.trim()).filter(Boolean),
      }
      const res = await createEvent(body)
      onCreated(res.eventId)
      setOpen(false)
    } catch (err) {
      onError((err as ApiError).message)
    } finally {
      setSending(false)
    }
  }

  if (!open) {
    return (
      <button type="button" className="se-btn solid" onClick={() => { setOpen(true) }}>
        회차 만들기
      </button>
    )
  }

  return (
    <form className="admin-form" onSubmit={(e) => { void submit(e) }}>
      <label>제목<input value={form.title} onChange={set('title')} required maxLength={80} /></label>
      <label>slug<input value={form.slug} onChange={set('slug')} placeholder="scalping (비우면 주소 없음)" /></label>
      <label>접수 시작<input type="datetime-local" value={form.opensAt} onChange={set('opensAt')} required /></label>
      <label>접수 마감<input type="datetime-local" value={form.sealsAt} onChange={set('sealsAt')} required /></label>
      <label>기준일<input type="date" value={form.baseDate} onChange={set('baseDate')} required /></label>
      <label>판정일<input type="date" value={form.settleDate} onChange={set('settleDate')} required /></label>
      <label>최소 배팅<input type="number" min={1} value={form.minStake} onChange={set('minStake')} required /></label>
      <label>최대 배팅<input type="number" min={1} value={form.maxStake} onChange={set('maxStake')} required /></label>
      <label>최소 참가 종목 수<input type="number" min={1} value={form.minStocks} onChange={set('minStocks')} required /></label>
      <label className="wide">
        종목코드 — 줄바꿈이나 쉼표로 구분. <b>적은 순서가 컨트랙트 종목 번호</b>다
        <textarea rows={5} value={form.codes} onChange={set('codes')} required placeholder={'005930\n000660\n…'} />
      </label>
      <div className="admin-actions">
        <button type="submit" className="se-btn solid" disabled={sending}>
          {sending ? '보내는 중…' : '만들기'}
        </button>
        <button type="button" className="se-btn" onClick={() => { setOpen(false) }}>취소</button>
      </div>
    </form>
  )
}

/* ── 기준가 수동 입력 ────────────────────────────────────
   13:00 배치가 늦거나 실패하면 기준가가 비어 접수를 못 연다. 그때 손으로 넣는 길이다.
   idx 는 종목 코드가 아니라 회차 안의 칸 번호(0부터)다. */
function BasePriceRow({ eventId, onDone, onError }: {
  eventId: number
  onDone: () => void
  onError: (message: string) => void
}) {
  const [idx, setIdx] = useState('0')
  const [price, setPrice] = useState('')
  const [sending, setSending] = useState(false)

  return (
    <div className="admin-inline">
      <span className="admin-dim">기준가 수동 입력</span>
      <input type="number" min={0} value={idx} onChange={(e) => { setIdx(e.target.value) }}
        aria-label="종목 칸 번호(idx)" style={{ width: 72 }} />
      <input type="number" min={0} step="0.01" value={price} placeholder="기준가"
        onChange={(e) => { setPrice(e.target.value) }} aria-label="기준가" />
      <button type="button" className="se-btn" disabled={sending || !price}
        onClick={() => {
          setSending(true)
          setBasePrice(eventId, Number(idx), price)
            .then(() => { setPrice(''); onDone() })
            .catch((err: unknown) => { onError((err as ApiError).message) })
            .finally(() => { setSending(false) })
        }}
      >넣기</button>
    </div>
  )
}

function when(iso: string) {
  return new Date(iso).toLocaleString('ko-KR', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}
