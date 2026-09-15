/* M-01 지갑 연동 완료 화면 — 지갑에 ANT 를 **대신 넣어 준다**.

   왜 필요한가. 연동과 보너스 지급이 다 끝나도 지갑에는 아무 일도 안 일어난 것처럼
   보인다. ERC-20 은 지갑이 알고 있는 토큰만 목록에 그리는데, ANT 는 누군가 넣기
   전까지 그 목록에 없다. 잔액이 0 인 게 아니라 볼 창이 없는 것인데 사용자에게는
   구분이 안 된다 — "토큰 준다더니 안 왔다" 가 여기서 나온다.

   왜 안내가 아니라 자동인가. 손으로 넣는 길은 네 걸음이고 그중 하나가 42자 주소
   붙여넣기다. 읽고 따라 하게 만들 일이 아니라 우리가 부르면 되는 일이다(EIP-747).

   ⚠️ **지갑 확인 창까지 없앨 수는 없다.** wallet_watchAsset 은 지갑이 사용자에게
   묻고 끝내는 호출이라, 우리가 하는 일은 "그 창을 대신 띄워 주는 것" 까지다.
   사용자가 그 창에서 취소하면 넣을 방법이 없으므로 그때 절차를 펼친다.

   설계 제약
   - 한 번만 부른다. 다시 그려질 때마다 부르면 지갑 창이 겹쳐 뜬다.
   - 체인이 어긋나면 **부르지 않는다.** wallet_watchAsset 은 지갑이 지금 보고 있는
     네트워크에 넣으므로, 다른 네트워크에서 부르면 영영 잔액 0 인 토큰이 하나 생긴다.
     그건 안 넣느니만 못하다 — 사용자는 넣었다고 믿는다.
   - 실패해도 완료 화면의 "확인" 을 막지 않는다. 연동은 이미 끝났다. */
import { useEffect, useRef, useState } from 'react'
import { ANT_DECIMALS, ANT_SYMBOL, chainInfo, hexChainId } from '../../wallet/chain'
import { currentChainId, ensureNetwork, watchToken, type WatchResult } from '../../wallet/provider'

/* checking  지갑이 어느 체인인지 읽는 중 — 아직 아무것도 안 띄운다
   asking    지갑 확인 창이 떠 있다
   wrong-network  체인이 달라 부르지 않았다
   그 밖(WatchResult)은 지갑이 준 답 그대로다. */
type Phase = 'checking' | 'asking' | 'wrong-network' | WatchResult

export default function TokenAutoAdd({ chainId }: {
  /** 서비스가 쓰는 체인. POST /wallet/nonce 가 준 값이다 — 지갑에 묻지 않는다 */
  chainId: number
}) {
  const chain = chainInfo(chainId)
  const [phase, setPhase] = useState<Phase>('checking')
  /* 이미 한 번 불렀는가. 상태로 두면 다시 그려지는 사이에 두 번째 호출이 끼어든다 —
     지갑 창이 둘 겹쳐 뜨고 뒤엣것은 -32002 로 튕긴다. */
  const fired = useRef(false)

  useEffect(() => {
    if (!chain || fired.current) return
    let alive = true

    void (async () => {
      /* 지갑 창을 띄우지 않는 조회라 물어도 사용자를 방해하지 않는다.
         null 은 "못 읽었다" 다 — 그때는 막지 않고 부른다. 체인을 모르면서 막으면
         멀쩡한 사람까지 손으로 넣게 된다. */
      const walletChain = await currentChainId()
      if (!alive) return
      if (walletChain !== null && walletChain !== chainId) {
        setPhase('wrong-network')
        return
      }

      fired.current = true
      setPhase('asking')
      const result = await watchToken({
        address: chain.tokenAddress,
        symbol: ANT_SYMBOL,
        decimals: ANT_DECIMALS,
      })
      if (alive) setPhase(result)
    })()

    return () => { alive = false }
  }, [chain, chainId])

  /* 주소를 모르는 체인이면 아무것도 하지 않는다. 빈 주소로 부르면 지갑에 쓰레기가
     들어가고, 빈 주소를 보여 주면 사용자가 아무 값이나 붙여 넣는다(wallet/chain.ts). */
  if (!chain) return null

  // 다시 부를 수 있는 실패. 취소했거나 앞선 창이 아직 떠 있던 경우다
  const retryable = phase === 'rejected' || phase === 'busy'
  // 자동으로 안 된 갈래. 그때만 손으로 넣는 절차를 펼친다
  const manual = retryable || phase === 'unsupported' || phase === 'wrong-network'

  // 아래 둘을 화살표 상수로 둔 이유 — 함수 선언은 끌어올려져 위 가드의 좁힌 타입을 못 물려받는다
  const options = { address: chain.tokenAddress, symbol: ANT_SYMBOL, decimals: ANT_DECIMALS }

  const retry = async () => {
    setPhase('asking')
    setPhase(await watchToken(options))
  }

  /* 네트워크를 바꾼 뒤 토큰까지 이어서 넣는다. 바꾸기만 하고 멈추면 사용자가 이 창에서
     할 일이 또 하나 남는데, 그게 원래 자동으로 됐어야 하는 일이다. */
  const switchThenAdd = async () => {
    setPhase('asking')
    const switched = await ensureNetwork({
      chainId: hexChainId(chain.chainId),
      chainName: chain.name,
      rpcUrls: [chain.rpcUrl],
      nativeCurrency: { name: chain.currencySymbol, symbol: chain.currencySymbol, decimals: 18 },
    })
    if (switched !== 'added') { setPhase(switched); return }
    setPhase(await watchToken(options))
  }

  return (
    <div className="wl-chain">
      {phase === 'checking' && (
        <p className="wl-chain-lead">지갑에 {ANT_SYMBOL} 를 추가하는 중…</p>
      )}

      {phase === 'asking' && (
        <p className="wl-chain-lead">
          <b>지갑 창에서 {ANT_SYMBOL} 추가를 승인해 주세요.</b> 추가해야 받은 토큰이
          지갑 목록에 보입니다.
        </p>
      )}

      {phase === 'added' && (
        <p className="wl-chain-done">지갑에 {ANT_SYMBOL} 를 추가했습니다.</p>
      )}

      {phase === 'wrong-network' && (
        <>
          <p className="wl-chain-warn" role="alert">
            지갑이 다른 네트워크를 보고 있어 {ANT_SYMBOL} 를 추가하지 못했습니다.
            <b>{chain.name}</b> 로 바꾸면 넣을 수 있습니다.
          </p>
          {/* 바꾸는 것도 우리가 부를 수 있는 일이다 — 경고만 남기면 막다른 길이 된다.
              바꾸고 나면 그 자리에서 토큰까지 이어 넣는다. */}
          <button type="button" className="wl-btn ghost wl-chain-add" onClick={switchThenAdd}>
            {chain.name} 네트워크로 바꾸고 추가
          </button>
        </>
      )}

      {phase === 'rejected' && (
        <p className="wl-chain-lead">지갑 창에서 취소했습니다. {ANT_SYMBOL} 는 아직 지갑에 없습니다.</p>
      )}
      {phase === 'busy' && (
        <p className="wl-chain-lead">지갑이 다른 창을 처리하는 중이라 추가하지 못했습니다.</p>
      )}
      {phase === 'unsupported' && (
        <p className="wl-chain-lead">이 지갑은 자동 추가를 지원하지 않습니다.</p>
      )}

      {retryable && (
        <button type="button" className="wl-btn ghost wl-chain-add" onClick={retry}>
          지갑에 {ANT_SYMBOL} 추가 다시 시도
        </button>
      )}

      {manual && (
        <div className="wl-chain-manual">
          {/* 접지 않는다. 여기까지 왔다는 건 자동이 안 됐다는 뜻이고,
              그러면 이 절차가 유일한 길이라 한 번 더 누르게 할 이유가 없다 */}
          <p className="wl-chain-manual-head">직접 추가하는 방법</p>
          <ol>
            <li>지갑 토큰 목록 오른쪽 위 점 세 개(⋮) → <b>토큰 관리</b></li>
            <li>맨 아래 <b>맞춤형 토큰 추가</b></li>
            <li><b>토큰 계약 주소</b>에 아래 주소를 붙여넣기</li>
            <li><b>토큰 추가</b></li>
          </ol>
          <CopyAddress address={chain.tokenAddress} />
          <p className="wl-note">기호 {ANT_SYMBOL} 와 소수점 {ANT_DECIMALS} 은 자동으로 채워집니다.</p>
        </div>
      )}
    </div>
  )
}

/** 주소 한 줄 + 복사. 42자를 줄이지 않는다 — 지갑에 붙여 넣은 값과 눈으로 대조할 수 있어야 한다. */
function CopyAddress({ address }: { address: string }) {
  const [copied, setCopied] = useState(false)

  // 복사됨 표시를 잠깐 뒤 되돌린다. 언마운트되면 타이머를 거둔다(CopyHash 와 같은 방식)
  useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => setCopied(false), 1400)
    return () => clearTimeout(timer)
  }, [copied])

  async function copy() {
    try {
      // clipboard 는 보안 컨텍스트(https·localhost)에서만 있다. 없으면 조용히 넘어간다 —
      // 주소는 화면에 그대로 있어 손으로 골라 복사할 수 있다
      await navigator.clipboard?.writeText(address)
      setCopied(true)
    } catch { /* 권한을 막은 경우. 화면을 깨뜨리지 않는다 */ }
  }

  return (
    <div className="wl-chain-copy">
      <code className="wl-address">{address}</code>
      <button type="button" className="wl-btn ghost" onClick={copy}>
        {copied ? '복사했습니다' : '주소 복사'}
      </button>
    </div>
  )
}
