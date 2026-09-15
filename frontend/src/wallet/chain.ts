/* 지갑에 넣어 줘야 하는 것들 — 네트워크와 ANT 토큰. 체인 하나당 한 줄이다.

   왜 한 파일인가. 둘 다 "이 체인이 무엇인가" 를 말하는 값이고, 나눠 두면 네트워크
   이름이 양쪽에 생겨 한쪽만 고쳐진다. 실제로 토큰 표에 network 이름이 있었다.

   왜 값이 코드에 있나 — 서버가 내려 주는 응답이 없다. 백엔드는 CHAIN_ID ·
   CONTRACT_PREDICT_TOKEN 으로 알고 있지만 API 로 열지 않는다(명세 §지갑·토큰에
   그런 필드가 없다). **서버가 열어 주면 이 표를 지우고 응답값을 쓰는 것이 맞다** —
   재배포하면 바뀌는 값이라 원래 화면 코드에 박힐 자리가 아니다.

   ⚠️ 체인마다 토큰 주소가 다르다. 운영(SSAFY)의 ANT 와 로컬 Hardhat 의 ANT 는
   서로 다른 컨트랙트다. 하나만 박아 두면 다른 쪽 사람에게 늘 잔액 0 인 토큰을 쥐여 준다.
   단일 진실은 contracts/deployments/README.md 이고 이 표는 그 사본이다. */

/** 지갑 목록에 뜨는 이름. 서버 잔액 응답의 symbol 과 같은 값이다. */
export const ANT_SYMBOL = 'ANT'

/** ANT 는 decimals 0 이다(ANT-CHAIN-03). api/wallet.ts 의 formatToken 과 같은 전제다. */
export const ANT_DECIMALS = 0

export type ChainInfo = {
  chainId: number
  /** 지갑 네트워크 고르개에 뜰 이름 */
  name: string
  /**
   * 지갑이 붙을 RPC. **프론트의 VITE_CHAIN_RPC_URL 과 다른 주소다** —
   * 그쪽은 D-03 검산이 쓰는 웹소켓(wss)이고, 지갑은 http(s) 만 받는다.
   */
  rpcUrl: string
  /**
   * 가스 통화 기호. SSAFY 체인은 가스비가 0 이라 **표시용일 뿐**이고 아무 값이나 된다.
   * 그래도 비워 두지 않는 건 지갑이 필수로 받기 때문이다.
   */
  currencySymbol: string
  /** 이 체인의 ANT(PredictToken) 컨트랙트 */
  tokenAddress: string
}

const CHAINS: Record<number, ChainInfo> = {
  // 운영 — SSAFY 체인. 서비스 ANT 이고 재배포하지 않는다
  31221: {
    chainId: 31221,
    name: 'SSAFY',
    rpcUrl: 'https://rpc.ssafy-blockchain.com',
    currencySymbol: 'SSF',
    tokenAddress: '0xEee56721cd2c0383139756c88B6DB06024a412Cb',
  },
  // 로컬 — docker compose 의 Hardhat. 새 노드에 배포하면 늘 이 주소다(ANT-CHAIN-14)
  31337: {
    chainId: 31337,
    name: '로컬 체인',
    rpcUrl: 'http://127.0.0.1:8545',
    currencySymbol: 'ETH',
    tokenAddress: '0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512',
  },
}

/**
 * 이 체인의 정보. 모르는 체인이면 null 이다.
 *
 * null 을 빈 값으로 얼버무리지 않는다 — 주소도 RPC 도 모르는데 안내를 띄우면
 * 사용자가 아무 값이나 붙여 넣게 된다.
 */
export function chainInfo(chainId: number | null): ChainInfo | null {
  return chainId == null ? null : CHAINS[chainId] ?? null
}

/** 지갑이 받는 16진수 체인 ID. wallet_addEthereumChain · switchEthereumChain 이 요구한다. */
export function hexChainId(chainId: number) {
  return `0x${chainId.toString(16)}`
}
