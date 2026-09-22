/* 사전 연동 띠 [ANT-FE-WALLET-PRELINK]

   서명이 필요한 화면(예측 등록 · 광고 등록 · 리포트 작성)에 **지갑 없이** 들어온 사람에게,
   입력을 시작하기 전에 연동부터 권한다. 연동된 사람에게는 아무것도 그리지 않는다.

   왜 "막상 누를 때" 가 아니라 "들어왔을 때" 인가 (QA 09-18 · 09-20 조사)
   전에는 다 적고 등록을 누른 뒤에야 연동 창이 떴다. 그 순서가 세 가지를 낳았다.
   ① 지갑 확장이 없으면 연동 창이 새로고침을 시키는데, 폼은 메모리에만 있어 적은 것이 전부 사라졌다.
   ② 연동이 끝나면 하려던 등록을 자동으로 이어 가는데, 가입 보너스는 연동 **뒤에** 체인으로 들어온다
      (보통 10초, 전송이 실패하면 10분). 그 사이 잔액은 0 이라 토큰이 드는 등록(대회 배팅 · 광고)이
      서명까지 받고 서버에서 "잔액 부족" 으로 끝났다.
   ③ 대회 화면은 잔액이 0 이면 발판이 잠겨서, 미연동인 사람은 연동 창으로 가는 길 자체가 없었다.
   먼저 연동하면 적는 동안 보너스가 도착하고, 잃을 입력도 없다.

   강제하지 않는다 — 띠는 안내다. 그냥 적고 등록을 눌러도 전처럼 연동 창이 뜬다(ANT-FE-WALLET-GATE).
   구독에는 두지 않았다: 채널 화면은 읽으러 오는 곳이고, 구독은 연동 뒤 모달을 **새로** 열어 잔액을
   다시 읽으므로 위 ② 가 생기지 않는다. */
import { useState } from 'react'
import { useAuth } from '../../auth/context'
import WalletLinkModal from './WalletLinkModal'
import '../../styles/screens/wallet-link.css'

export default function WalletPrelink({ purpose }: {
  /** 무엇을 하려면 연동이 필요한가 — "예측을 등록하려면" 처럼 문장 앞머리로 읽히게 */
  purpose: string
}) {
  const { user } = useAuth()
  const [linking, setLinking] = useState(false)

  /* 연동 창이 떠 있는 동안에는 띠를 남겨 둔다. 창이 서명 성공 시점에 walletLinked 를 세우는데,
     그때 띠(와 그 안의 창)가 같이 사라지면 완료 화면 — 가입 보너스 안내 — 을 못 본다. */
  if (!user || (user.walletLinked && !linking)) return null

  return (
    <aside className="wl-prelink" aria-label="지갑 연동 안내">
      <p>
        <b>{purpose} 지갑 서명이 필요해요.</b>
        <span>먼저 연동해 두면 적은 내용을 잃지 않고, 가입 보너스 ANT 도 그사이 들어옵니다.</span>
      </p>
      <button type="button" className="wl-btn solid" onClick={() => setLinking(true)}>
        지갑 연동하기
      </button>
      {linking && <WalletLinkModal onClose={() => setLinking(false)} />}
    </aside>
  )
}
