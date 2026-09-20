/* 공유 잔액 저장소 [ANT-FE-WALLET-SYNC]

   ANT 잔액을 그리는 자리가 여섯 곳이다(상단 칩 · 홈 "내 자산" · 지갑 화면 · 마이페이지 ·
   구독 모달 · 대회 배팅 카드). 전에는 저마다 GET /wallet/balance 를 **자기 시점에** 읽어
   자기 state 에 들고 있었다. 그래서 구독하고 홈에 가면 카드는 5,000 인데 칩은 10,000 이었다
   (QA 09-18 "상단 보유잔액과 지갑 잔액의 싱크가 맞지 않음"). 특히 칩은 셸에 있어 라우트가
   바뀌어도 다시 마운트되지 않으니, 한 번 읽은 값이 새로고침 전까지 그대로였다.

   값을 여기 한 벌만 두고 여섯 곳이 전부 이것을 읽는다. 읽는 곳이 하나면 **같은 순간에 다른
   숫자가 나올 방법이 없다.** /wallet/balance 를 부르는 곳도 이 파일 하나다.

   ── 왜 컨텍스트가 아니라 모듈인가 ───────────────────────
   컴포넌트보다 오래 살아야 한다. 지갑 연동 창을 닫은 뒤에도 가입 보너스가 들어오는지
   1분간 지켜봐야 하는데(expectWalletChange), 그 타이머는 창이 사라져도 돌아야 한다.
   useSyncExternalStore 는 React 내장이라 의존성이 늘지 않는다.

   ── 언제 다시 읽나 ─────────────────────────────────────
   ⓐ 온체인 작업이 끝난 순간 — useOperation 이 부른다. 서버 인덱서는 원장 행과 작업
      SUCCEEDED 를 한 트랜잭션에 쓰므로, SUCCEEDED 를 봤다면 잔액은 이미 바뀌어 있다.
   ⓑ 30초마다 + 탭으로 돌아올 때 — 남이 일으킨 변화(구독 수익 · 광고 승인 소각 · 대회 배당)는
      내 탭에서 알 길이 없어 폴링뿐이다. 알림 종(NotificationBell)과 같은 주기 · 같은 패턴.
   ⓓ 읽는 자리가 마운트될 때 — "화면에 들어오면 최신" 이라는 전의 동작을 지킨다.
   ⓔ expectWalletChange — 곧 바뀔 것을 알 때(가입 보너스 · 일찍 닫은 작업 창) 잠깐 자주 본다.

   ponytail: 이 숫자는 원장(token_ledger) 합이라 체인보다 최대 5초(인덱서 주기) 늦다.
   메타마스크와 어긋나는 것은 그래서다 — 서버가 체인 잔액을 내리면 줄어든다(ANT-TOKEN-12). */
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react'
import { useAuth } from '../auth/context'
import type { ApiError } from './errors'
import { fetchWalletBalance, type WalletBalance } from './wallet'

type Snapshot = {
  /** 이 값이 누구 것인가. 로그인한 사람과 다르면 읽는 쪽이 버린다 */
  owner: number | null
  data: WalletBalance | null
  loading: boolean
  error: ApiError | null
  /** data 를 가져온 요청의 순번. fresh 가 "내가 열린 뒤에 읽은 값인가" 를 이것으로 가린다 */
  seq: number
}

const EMPTY: Snapshot = { owner: null, data: null, loading: false, error: null, seq: 0 }

/** 남이 일으킨 변화를 따라잡는 주기. 알림 종과 같다 — 짧게 잡으면 탭 수만큼 서버를 두드린다 */
const POLL_MS = 30_000
/* "곧 바뀐다" 를 알 때의 짧은 확인. 4초는 작업 폴링(useOperation)과 같은 간격이고,
   15회 = 1분은 연동 창의 안내 문구("최대 1분 소요됩니다")와 같은 길이다. */
const WATCH_MS = 4_000
const WATCH_MAX = 15

let snapshot: Snapshot = EMPTY
/** 시작한 요청의 수. 요청마다 하나씩 오른다 */
let started = 0
let inflight: Promise<void> | null = null
const listeners = new Set<() => void>()
let pollTimer: number | undefined
let watchTimer: number | undefined

function emit(next: Snapshot) {
  snapshot = next
  listeners.forEach((l) => l())
}

/**
 * 조용한 재읽기. loading 을 세우지 않고, **실패하면 가진 값을 그대로 둔다** — 안 그러면
 * 네트워크가 한 번 튈 때마다 상단 칩이 "—" 로 깜빡인다. 오류는 보여 줄 값이 하나도
 * 없을 때만 싣는다(첫 읽기 실패).
 *
 * 겹친 호출은 진행 중인 요청 하나를 같이 기다린다. 마운트 · 폴링 · 작업 종착이 한꺼번에
 * 와도 요청은 하나라 응답 순서가 뒤집힐 일이 없다.
 */
export function refreshWalletBalance(): Promise<void> {
  if (snapshot.owner === null) return Promise.resolve()
  if (inflight) return inflight

  const who = snapshot.owner
  const seq = ++started
  inflight = fetchWalletBalance()
    .then((data) => {
      /* 기다리는 사이 계정이 바뀌었으면 버린다. 앞 사람의 잔액을 뒷사람에게 보이면 안 된다 */
      if (snapshot.owner !== who) return
      emit({ owner: who, data, loading: false, error: null, seq })
    })
    .catch((e: unknown) => {
      if (snapshot.owner !== who) return
      if (snapshot.data === null) emit({ ...snapshot, loading: false, error: e as ApiError })
      else if (snapshot.loading) emit({ ...snapshot, loading: false })
    })
    .finally(() => { inflight = null })
  return inflight
}

/**
 * 잔액이 곧 바뀔 것을 알 때 부른다. 값이 달라지거나 1분이 지나면 멈춘다.
 *
 * 30초 폴링만으로는 가입 보너스가 들어오고도 한참 "0 ANT" 다 — QA 09-18 의 "가입했는데
 * ANT 가 안 들어옴" 이 그 모습이었다. 온체인 작업 창을 확정 전에 닫은 경우도 여기로 온다.
 */
export function expectWalletChange() {
  window.clearInterval(watchTimer)
  const before = snapshot.data?.balance ?? null
  let tries = 0
  const timer = window.setInterval(() => {
    tries += 1
    void refreshWalletBalance().then(() => {
      const changed = (snapshot.data?.balance ?? null) !== before
      if ((changed || tries >= WATCH_MAX) && watchTimer === timer) {
        window.clearInterval(timer)
        watchTimer = undefined
      }
    })
  }, WATCH_MS)
  watchTimer = timer
}

/** 이 값의 주인을 바꾼다. 달라지면 그 자리에서 비운다 — 로그아웃 · 세션 만료 · 다른 계정 */
function claim(userId: number | null) {
  if (snapshot.owner === userId) return
  window.clearInterval(watchTimer)
  watchTimer = undefined
  /* 진행 중이던 요청은 앞 사람 것이다. 응답은 owner 검사에서 버려지지만, 새 주인의 첫 읽기가
     그 요청에 올라타면 안 되므로 자리를 비운다 */
  inflight = null
  emit(userId === null ? EMPTY : { ...EMPTY, owner: userId, loading: true })
}

function tick() {
  if (document.visibilityState === 'visible') void refreshWalletBalance()
}

/* 읽는 자리가 하나라도 있을 때만 폴링한다. 상단 칩이 로그인 내내 떠 있으므로 사실상
   "로그인해 있는 동안" 이다. 안 보는 탭에서는 쉬고, 돌아오는 순간 한 번 읽는다. */
function subscribe(listener: () => void) {
  listeners.add(listener)
  if (listeners.size === 1) {
    pollTimer = window.setInterval(tick, POLL_MS)
    document.addEventListener('visibilitychange', tick)
  }
  return () => {
    listeners.delete(listener)
    if (listeners.size === 0) {
      window.clearInterval(pollTimer)
      document.removeEventListener('visibilitychange', tick)
      /* 읽는 자리가 하나도 없다 = 로그아웃했다(칩이 내려갔다). 지켜보던 것도 그만둔다 —
         토큰 없이 /wallet/balance 를 1분 동안 두드릴 이유가 없다 */
      window.clearInterval(watchTimer)
      watchTimer = undefined
    }
  }
}

const getSnapshot = () => snapshot

type Options = {
  /**
   * 이 컴포넌트가 열린 **뒤에** 읽은 값만 받는다. 그 전에는 data 가 null 이다.
   *
   * 잔액으로 결제 버튼을 잠그는 자리(구독 모달 · 대회 배팅)가 쓴다. 최대 30초 묵은 값으로
   * 서명 버튼을 잠갔다 풀었다 하면 안 되기 때문이다 — 그 자리들의 규칙은 전부터 "열 때 새로
   * 읽고, 못 읽었으면 막지 않는다" 였고 이 옵션이 그 규칙을 그대로 지킨다.
   */
  fresh?: boolean
  /** false 면 읽지 않고 빈 값을 돌려준다(대회 모드가 아닐 때의 배팅 카드) */
  enabled?: boolean
}

/**
 * 잔액 한 줄. 돌려주는 모양이 useApiQuery · useAsync 와 같아서 호출부는 훅 한 줄만 바꾸면 된다.
 */
export function useWalletBalance({ fresh = false, enabled = true }: Options = {}) {
  const { user } = useAuth()
  const userId = user?.id ?? null
  const active = enabled && userId !== null
  const snap = useSyncExternalStore(subscribe, getSnapshot)

  /* fresh 의 기준점 — 이 자리가 켜진 순간까지 시작된 요청의 수. 이보다 뒤에 시작한 요청의
     값만 "열린 뒤에 읽은 값" 이다. 시각이 아니라 순번인 것은 같은 밀리초에 끝난 옛 요청을
     새 값으로 착각하지 않으려는 것이다.
     꺼졌다 켜질 때(대회 모드에 들어설 때)마다 새로 잡는다. effect 가 아니라 렌더 중에 맞추는
     것은 effect 로 하면 묵은 기준점으로 한 번 그려지고 나서야 바뀌기 때문이다. useMemo 로도
     되지만 React 는 메모를 버릴 수 있고, 그러면 기준점이 뒤로 밀려 방금 읽은 값이 가려진다. */
  const [base, setBase] = useState({ active, since: started })
  if (base.active !== active) setBase({ active, since: started })
  const since = base.since

  useEffect(() => {
    if (!active) return
    claim(userId)
    void refreshWalletBalance().then(() => {
      /* 열리기 전에 이미 떠나 있던 요청에 올라탔다면 그 값은 옛 것일 수 있다. 한 번 더 읽는다 */
      if (fresh && snapshot.seq <= since) void refreshWalletBalance()
    })
  }, [active, userId, fresh, since])

  const reload = useCallback(() => {
    if (snapshot.owner === null) return
    emit({ ...snapshot, loading: true, error: null })
    void refreshWalletBalance()
  }, [])

  if (!active) return { data: null, loading: false, error: null, reload }
  /* 저장소의 값이 아직 앞 사람 것이다(effect 가 돌기 전의 한 렌더). 보이지 않게 막는다 */
  if (snap.owner !== userId) return { data: null, loading: true, error: null, reload }

  const data = !fresh || snap.seq > since ? snap.data : null
  return {
    data,
    loading: snap.loading || (data === null && snap.error === null),
    error: data === null ? snap.error : null,
    reload,
  }
}
