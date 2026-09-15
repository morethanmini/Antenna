/* 브라우저 지갑(EIP-1193) 접점. 설계서 §4 M-01 · API 명세서 §1.2.

   라이브러리를 쓰지 않는다 — 필요한 건 eth_requestAccounts 와 personal_sign
   두 개뿐이고, ethers/web3 는 이 둘을 쓰자고 들이기엔 번들이 너무 크다.

   서명 방식은 personal_sign 이다. EIP-712 가 아니다 —
   컨트랙트가 사용자 서명을 검증하지 않는 구조(릴레이어가 서버 키로 전송)라
   구조화 서명의 이점이 없다. 명세 §1.2 에서 2026-08-31 확정. */
import { ApiError, CLIENT_ERROR_CODE } from '../api/errors'

/** EIP-1193 요청 인터페이스. 지갑이 주입하는 객체의 우리가 쓰는 부분만 적는다. */
type Eip1193 = {
  /* params 가 배열만은 아니다 — wallet_watchAsset 은 객체 하나를 받는다(EIP-747).
     배열로 좁혀 두면 그 호출이 타입에서 막힌다. */
  request: (args: { method: string; params?: unknown[] | Record<string, unknown> }) => Promise<unknown>
}

declare global {
  interface Window {
    ethereum?: Eip1193
  }
}

function provider(): Eip1193 | null {
  return typeof window !== 'undefined' && window.ethereum ? window.ethereum : null
}

/** 지갑 확장이 주입돼 있는지. 없으면 버튼을 눌러도 할 수 있는 게 없다. */
export function hasWallet() {
  return provider() !== null
}

/**
 * 지갑이 없는 사용자를 보낼 곳.
 *
 * 이 자리에 두는 이유 — 지갑 감지(hasWallet)와 같은 경계다. 화면마다 주소를
 * 적어 두면 지갑을 바꿀 때 한 곳만 고쳐지고 갈라진다.
 *
 * 특정 지갑을 지목한다. 우리가 쓰는 것은 EIP-1193 표준이라 다른 지갑도
 * 동작하지만, "지갑 확장을 설치하세요" 만 알려 주면 무엇을 설치할지 모른다.
 */
export const WALLET_INSTALL_URL = 'https://metamask.io/download/'

/**
 * 지갑이 주입되면 알려 준다. 구독을 끊는 함수를 돌려준다.
 *
 * 왜 한 번 확인으로 끝내면 안 되는가 — 확장은 페이지 로드 시점에 주입되는데
 * **그게 렌더보다 늦을 수 있다.** MetaMask 는 준비되면 ethereum#initialized 를
 * 쏜다. 화면이 렌더 한 번으로 판단하고 끝내면, 주입이 그 뒤에 끝났을 때
 * "지갑을 찾을 수 없습니다" 에 굳고 다시 볼 계기가 없다.
 *
 * **이미 로드된 페이지에 나중에 설치한 경우는 이걸로 살아나지 않는다.**
 * 확장은 로드 때만 주입되므로 새로고침 말고는 방법이 없다 — 그 갈래는 화면이
 * 새로고침 버튼으로 안내한다.
 *
 * 폴링을 영원히 돌리지 않는다. 주입 경쟁은 로드 직후 몇 초 안에 끝나고,
 * 그 뒤로도 도는 타이머는 아무것도 못 잡으면서 배터리만 먹는다.
 */
export function onWalletReady(onReady: () => void): () => void {
  if (hasWallet()) {
    onReady()
    return () => {}
  }

  let stopped = false
  const check = () => {
    if (stopped || !hasWallet()) return
    stop()
    onReady()
  }

  const timer = setInterval(check, 250)
  const deadline = setTimeout(() => { clearInterval(timer) }, 4000)
  window.addEventListener('ethereum#initialized', check)

  function stop() {
    stopped = true
    clearInterval(timer)
    clearTimeout(deadline)
    window.removeEventListener('ethereum#initialized', check)
  }
  return stop
}

/* EIP-1193 표준 오류 코드. 지갑마다 문구는 달라도 code 는 같다.
   문구로 분기하면 지갑을 바꾸는 순간 죽는 분기가 된다. */
const REJECTED = 4001
const PENDING = -32002

/** 지갑이 던진 것을 우리 오류 계약으로 옮긴다. status 는 서버가 준 게 아니라 0 이다. */
function fromProviderError(e: unknown): ApiError {
  const code = (e as { code?: unknown } | null)?.code
  if (code === REJECTED) {
    return new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_SIGN_REJECTED, message: 'user rejected' }, 0)
  }
  if (code === PENDING) {
    return new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_WALLET_BUSY, message: 'request pending' }, 0)
  }
  return new ApiError(
    { code: CLIENT_ERROR_CODE.UNKNOWN, message: e instanceof Error ? e.message : String(e) },
    0,
  )
}

function requireProvider(): Eip1193 {
  const p = provider()
  if (!p) {
    throw new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_WALLET_MISSING, message: 'no provider' }, 0)
  }
  return p
}

/**
 * 지갑 연결을 요청하고 주소 하나를 받는다.
 *
 * 서버가 소문자로 정규화해 저장하므로 여기서도 소문자로 맞춘다 —
 * payload 의 address 줄과 본문 address 가 서버 조립본과 한 글자라도
 * 다르면 복원 주소가 달라져 SIGNER_MISMATCH 가 난다.
 */
export async function connectAddress(): Promise<string> {
  const p = requireProvider()
  let accounts: unknown
  try {
    accounts = await p.request({ method: 'eth_requestAccounts' })
  } catch (e) {
    throw fromProviderError(e)
  }

  const first = Array.isArray(accounts) ? accounts[0] : undefined
  if (typeof first !== 'string' || first.length === 0) {
    // 지갑이 잠겨 있거나 사용자가 계정을 하나도 고르지 않았다.
    throw new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_NO_ACCOUNT, message: 'no account' }, 0)
  }
  return first.toLowerCase()
}

/**
 * personal_sign. 파라미터 순서는 [message, address] 다 — eth_sign 과 반대라
 * 뒤집으면 지갑이 조용히 거부한다.
 *
 * 메시지를 hex 로 감싸지 않고 평문 그대로 넘긴다. 그래야 지갑 창에
 * 사용자가 읽을 수 있는 payload 가 뜬다 — 무엇에 서명하는지 보여 주는 것이
 * 이 화면의 설계 제약이다.
 */
export async function personalSign(message: string, address: string): Promise<string> {
  const p = requireProvider()
  try {
    const signature = await p.request({ method: 'personal_sign', params: [message, address] })
    if (typeof signature !== 'string') {
      throw new ApiError({ code: CLIENT_ERROR_CODE.UNKNOWN, message: 'signature not a string' }, 0)
    }
    return signature
  } catch (e) {
    if (e instanceof ApiError) throw e
    throw fromProviderError(e)
  }
}

/* ── 지갑에 토큰 추가 (EIP-747) ───────────────────────────────
   서명·연결과 달리 이쪽은 **표시**의 문제다. 연동만 해서는 지갑 목록에 ANT 가
   없어 잔액이 0 처럼 보인다 — 토큰을 받아도 지갑에서는 아무 일도 안 일어난 것 같다. */

/**
 * 지갑이 지금 보고 있는 체인. 토큰이 **어느 네트워크에 추가될지**가 이 값으로 정해진다.
 *
 * 서명 payload 에는 절대 쓰지 않는다 — 그쪽은 서버가 준 chainId 다(api/wallet.ts).
 * 여기서만 지갑에 직접 묻는 이유는, 알고 싶은 것이 "서비스가 어느 체인인가" 가 아니라
 * "사용자가 지금 어디를 열어 두었나" 이기 때문이다.
 *
 * 실패를 던지지 않고 null 로 눕힌다. 체인을 못 읽었다고 완료 화면이 깨지면 안 된다 —
 * 연동은 이미 끝났고 토큰 추가는 곁들이다.
 */
export async function currentChainId(): Promise<number | null> {
  const p = provider()
  if (!p) return null
  try {
    const hex = await p.request({ method: 'eth_chainId' })
    if (typeof hex !== 'string') return null
    const id = Number.parseInt(hex, 16)
    return Number.isNaN(id) ? null : id
  } catch {
    return null
  }
}

/**
 * 토큰 추가 결과. 넷을 오류 하나로 합치지 않는다 — 사용자가 할 일이 다르다.
 *
 * added       지갑 목록에 들어갔다
 * rejected    지갑 창에서 사용자가 취소했다 — 다시 부르면 된다
 * busy        앞선 지갑 창이 아직 떠 있다 — 잠시 뒤 다시 부르면 된다
 * unsupported 이 지갑은 wallet_watchAsset 을 모른다 — 손으로 넣는 수밖에 없다
 */
export type WatchResult = 'added' | 'rejected' | 'busy' | 'unsupported'

/* EIP-1193 4200 = 지갑이 이 메서드를 구현하지 않음. JSON-RPC -32601 도 같은 뜻으로 온다. */
const UNSUPPORTED = [4200, -32601]

/**
 * wallet_watchAsset. 목록에 이미 있으면 지갑이 창을 띄우지 않고 true 만 돌려주기도 한다.
 *
 * params 가 배열이 아니라 객체다(EIP-747). 다른 메서드처럼 배열로 감싸면 지갑이 거절한다.
 *
 * ApiError 로 바꾸지 않는다. 이 호출의 실패는 전부 "그럼 손으로 넣으세요" 한 곳으로
 * 모이므로, errorText 를 태워 문구를 나눠 봐야 화면이 할 일이 달라지지 않는다.
 */
export async function watchToken(token: {
  address: string
  symbol: string
  decimals: number
}): Promise<WatchResult> {
  const p = provider()
  if (!p) return 'unsupported'
  try {
    const ok = await p.request({
      method: 'wallet_watchAsset',
      params: { type: 'ERC20', options: token },
    })
    /* 표준은 boolean 을 약속하지만 지갑마다 undefined 를 주기도 한다.
       거부는 보통 throw 로 오므로, 여기까지 왔으면 false 아닌 한 넣은 것으로 본다. */
    return ok === false ? 'rejected' : 'added'
  } catch (e) {
    const code = (e as { code?: unknown } | null)?.code
    if (code === REJECTED) return 'rejected'
    /* 연동 서명 창이 닫히기 전에 자동으로 부르면 여기로 온다. 실패가 아니라 순서 문제라
       '이 지갑은 못 한다' 로 뭉뚱그리면 다시 부르면 될 사람을 손으로 넣게 만든다. */
    if (code === PENDING) return 'busy'
    if (typeof code === 'number' && UNSUPPORTED.includes(code)) return 'unsupported'
    return 'unsupported'
  }
}

/* ── 지갑에 네트워크 넣기 (EIP-3085 · EIP-3326) ─────────────────
   토큰을 넣기 전에 네트워크가 먼저다. 지갑이 다른 체인을 보고 있으면 ANT 를 넣어도
   그 체인의 ANT 가 아니라 잔액이 영영 0 이다(TokenAutoAdd 의 wrong-network). */

/** 지갑이 체인을 모른다. switch 요청에만 온다 — 그때는 add 로 넘어간다. */
const UNKNOWN_CHAIN = 4902

export type NetworkParams = {
  /** 16진수. chain.ts 의 hexChainId 로 만든다 */
  chainId: string
  chainName: string
  rpcUrls: string[]
  nativeCurrency: { name: string; symbol: string; decimals: number }
}

/**
 * 그 체인으로 맞춘다. **이미 넣어 둔 사람에게 추가 창을 띄우지 않는다.**
 *
 * 먼저 wallet_switchEthereumChain 을 부른다. 지갑이 모르는 체인이면 4902 로 답하고,
 * 그때만 wallet_addEthereumChain 으로 넘어간다 — 추가는 지갑이 바꾸기까지 함께 묻는다.
 *
 * 순서를 뒤집으면(항상 add) 이미 등록한 사람도 "네트워크를 추가하시겠습니까" 를 다시 본다.
 */
export async function ensureNetwork(params: NetworkParams): Promise<WatchResult> {
  const p = provider()
  if (!p) return 'unsupported'

  try {
    await p.request({ method: 'wallet_switchEthereumChain', params: [{ chainId: params.chainId }] })
    return 'added'
  } catch (e) {
    const code = (e as { code?: unknown } | null)?.code
    if (code === REJECTED) return 'rejected'
    if (code === PENDING) return 'busy'
    // 아는 체인인데 바꾸기가 안 된 것이면 추가해 봐야 소용없다
    if (code !== UNKNOWN_CHAIN) return 'unsupported'
  }

  try {
    /* blockExplorerUrls 를 넣지 않는다. SSAFY 체인에는 탐색기가 없고,
       빈 배열을 주면 지갑이 형식 오류로 거절한다 — 아예 빼는 것이 맞다. */
    await p.request({ method: 'wallet_addEthereumChain', params: [params] })
    return 'added'
  } catch (e) {
    const code = (e as { code?: unknown } | null)?.code
    if (code === REJECTED) return 'rejected'
    if (code === PENDING) return 'busy'
    return 'unsupported'
  }
}
