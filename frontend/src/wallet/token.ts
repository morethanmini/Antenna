/* ANT 토큰의 지갑 쪽 메타데이터. "지갑에 토큰 추가" 가 유일한 용도다.

   왜 주소가 코드에 있나 — 서버가 이 값을 내려 주는 응답이 없다. 백엔드는
   CONTRACT_PREDICT_TOKEN 으로 알고 있지만 API 로 열지 않는다(명세 §지갑·토큰에
   그런 필드가 없다). **서버가 열어 주면 이 표를 지우고 응답값을 쓰는 것이 맞다** —
   재배포하면 바뀌는 값이라 원래 화면 코드에 박힐 자리가 아니다.

   ⚠️ 체인마다 주소가 다르다. 운영(SSAFY)의 ANT 와 로컬 Hardhat 의 ANT 는 서로
   다른 컨트랙트다. 하나만 박아 두면 다른 쪽 사람에게 늘 잔액 0 인 토큰을 쥐여 준다.
   단일 진실은 contracts/deployments/README.md 이고 이 표는 그 사본이다 —
   주소를 바꾸면 양쪽을 같이 고친다. */

/** 지갑 목록에 뜨는 이름. 서버 잔액 응답의 symbol 과 같은 값이다. */
export const ANT_SYMBOL = 'ANT'

/** ANT 는 decimals 0 이다(ANT-CHAIN-03). api/wallet.ts 의 formatToken 과 같은 전제다. */
export const ANT_DECIMALS = 0

export type AntToken = {
  address: string
  /** 지갑 네트워크 고르개에 뜨는 이름. 체인이 어긋났을 때 어디로 바꾸라고 말하려면 필요하다. */
  network: string
}

const ANT_BY_CHAIN: Record<number, AntToken> = {
  // 운영 — SSAFY 체인. 서비스 ANT 이고 재배포하지 않는다
  31221: { address: '0xEee56721cd2c0383139756c88B6DB06024a412Cb', network: 'SSAFY' },
  // 로컬 — docker compose 의 Hardhat. 새 노드에 배포하면 늘 이 주소다(ANT-CHAIN-14)
  31337: { address: '0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512', network: '로컬 체인' },
}

/**
 * 이 체인의 ANT. 모르는 체인이면 null 이다.
 *
 * null 을 "빈 주소" 로 얼버무리지 않는다 — 주소를 모르는데 안내를 띄우면
 * 사용자가 아무 값이나 붙여 넣게 된다.
 */
export function antToken(chainId: number | null): AntToken | null {
  return chainId == null ? null : ANT_BY_CHAIN[chainId] ?? null
}
