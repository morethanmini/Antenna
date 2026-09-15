/* M-01 지갑 연동 — 연결하기 **전에** 지갑에 SSAFY 네트워크를 넣는다.

   왜 연결보다 먼저인가. 연동 자체는 체인과 무관하다(서명 + DB). 하지만 연동을 마치는
   순간 ANT 토큰을 넣고, 그건 **지갑이 보고 있는 네트워크에** 들어간다. 네트워크가
   없거나 다른 것을 보고 있으면 잔액이 영영 0 인 토큰이 하나 생긴다 — 사용자는 넣었다고
   믿는다. 그래서 순서가 네트워크 → 연동 → 토큰이다.

   여기서는 자동으로 부르지 않는다. 토큰 추가와 다른 점이다 —
   그쪽은 사용자가 방금 연동을 끝낸 직후라 지갑 창이 이어 뜨는 게 자연스럽지만,
   이 화면은 아직 아무것도 누르지 않은 상태다. 열자마자 지갑 창이 튀어나오면
   무엇에 답하는지 모른 채 승인하거나 닫는다.

   설계 제약
   - 이미 그 네트워크면 아무것도 띄우지 않는다. 할 일이 없는 칸은 지면만 먹는다.
   - 손으로 넣는 값을 **표 그대로** 함께 둔다. 지갑이 EIP-3085 를 모를 수 있고,
     한 번 눌러 실패한 사람에게 남는 선택지가 없으면 안 된다. */
import { useEffect, useState } from 'react'
import { chainInfo, hexChainId } from '../../wallet/chain'
import { currentChainId, ensureNetwork, type WatchResult } from '../../wallet/provider'

type Phase = 'checking' | 'need' | 'asking' | 'ok' | WatchResult

export default function NetworkAdd({ chainId }: {
  /** 서비스가 쓰는 체인. POST /wallet/nonce 가 준 값이다 */
  chainId: number
}) {
  const info = chainInfo(chainId)
  const [phase, setPhase] = useState<Phase>('checking')
  const [manualOpen, setManualOpen] = useState(false)

  /* 지갑 창을 띄우지 않는 조회다. null 은 못 읽었다는 뜻이고, 그때는 안내를 띄운다 —
     맞는지 모르면서 숨기면 정작 네트워크가 없는 사람이 길을 잃는다. */
  useEffect(() => {
    let alive = true
    currentChainId().then((id) => {
      if (alive) setPhase(id === chainId ? 'ok' : 'need')
    })
    return () => { alive = false }
  }, [chainId])

  if (!info || phase === 'checking' || phase === 'ok' || phase === 'added') return null

  async function add() {
    if (!info) return
    setPhase('asking')
    setPhase(await ensureNetwork({
      chainId: hexChainId(info.chainId),
      chainName: info.name,
      rpcUrls: [info.rpcUrl],
      /* 가스가 0 이라 표시용이다. 그래도 지갑이 필수로 받으므로 채운다 */
      nativeCurrency: { name: info.currencySymbol, symbol: info.currencySymbol, decimals: 18 },
    }))
  }

  const failed = phase === 'rejected' || phase === 'busy' || phase === 'unsupported'

  return (
    <div className="wl-token">
      <p className="wl-token-lead">
        <b>이 서비스는 {info.name} 네트워크를 씁니다.</b> 지갑에 먼저 넣어 두어야
        연동 뒤 받은 ANT 가 지갑에 보입니다.
      </p>

      {phase === 'rejected' && (
        <p className="wl-token-hint">지갑 창에서 취소했습니다. 다시 누르거나 아래 값으로 넣어 주세요.</p>
      )}
      {phase === 'busy' && (
        <p className="wl-token-hint">지갑이 다른 창을 처리하는 중입니다. 잠시 뒤 다시 눌러 주세요.</p>
      )}
      {phase === 'unsupported' && (
        <p className="wl-token-hint">이 지갑은 자동 추가를 지원하지 않습니다. 아래 값으로 넣어 주세요.</p>
      )}

      <button
        type="button" className="wl-btn ghost wl-token-add"
        onClick={add} disabled={phase === 'asking'}
      >
        {phase === 'asking' ? '지갑 확인 중…' : `지갑에 ${info.name} 네트워크 추가`}
      </button>

      {/* 실패했으면 그게 유일한 길이라 우리가 펼친다. 아니면 접어 둔다 */}
      {failed || manualOpen ? (
        <div className="wl-token-manual">
          <p className="wl-token-manual-head">직접 추가하는 방법</p>
          <ol>
            <li>지갑에서 <b>네트워크 선택</b> → <b>네트워크 추가</b> → <b>수동으로 추가</b></li>
            <li>아래 값을 채우고 저장</li>
          </ol>
          <dl className="wl-net-table">
            <div><dt>네트워크 이름</dt><dd>{info.name} <em>(자유)</em></dd></div>
            <div><dt>RPC URL</dt><dd><code>{info.rpcUrl}</code></dd></div>
            <div><dt>체인 ID</dt><dd><code>{info.chainId}</code></dd></div>
            <div><dt>통화 기호</dt><dd>{info.currencySymbol} <em>(가스비가 0 이라 표시용)</em></dd></div>
            <div><dt>블록 탐색기</dt><dd><em>비워 두기</em></dd></div>
          </dl>
        </div>
      ) : (
        <button
          type="button" className="wl-token-toggle"
          onClick={() => setManualOpen(true)} aria-expanded={false}
        >
          직접 추가하는 방법
          <span aria-hidden="true">⌄</span>
        </button>
      )}
    </div>
  )
}
