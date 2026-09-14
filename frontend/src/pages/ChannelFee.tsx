/* E-04 내 채널 설정 · /me/channel
   담당 스토리 [ANT-FE-CHANNEL-FEE]

   이 화면은 H-03 환경 설정(/settings) 안의 "내 채널" 섹션으로 합쳤다.
   둘 다 내 계정을 손보는 곳인데 마이페이지에서 진입점이 둘로 갈려 있었다.

   라우트는 남겨 두고 그리로 넘긴다 — 설계서 §3 E 의 경로표와 외부에 공유된
   링크가 죽지 않게 하려는 것이다. replace 를 쓰는 이유는 뒤로 가기를 눌렀을 때
   여기로 되돌아와 다시 튕기는 고리를 만들지 않기 위해서다.

   구독료 설정을 다시 독립 화면으로 떼어낸다면 이 파일을 되살리면 된다. */
import { Navigate } from 'react-router-dom'

export default function ChannelFee() {
  // 탭이 생겨 정확히 그 자리로 보낼 수 있다 — 설정 첫 탭에 떨구면 한 번 더 찾아야 한다
  return <Navigate to="/settings?tab=channel" replace />
}
