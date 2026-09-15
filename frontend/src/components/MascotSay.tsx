/* 개미가 한마디 하는 말풍선.

   B-03 종목 상세의 예측 호가창(PredictionDepth)이 쓰던 모양을 공용으로 뺐다.
   거기서는 "등락률이 무엇 대비인가" 를, G-04 시즌 진행에서는 "보조지표가 무엇인가" 를
   말한다 — 둘 다 표나 그림을 읽기 전에 한 번 짚고 가야 하는 문장이고, 본문으로
   쓰면 무거워서 개미가 대신 말해 주는 자리다.

   **B-03 은 아직 자기 마크업을 쓴다.** 그쪽 스타일이 .stock-detail-page 로 스코프돼
   있어 옮기려면 그 화면을 함께 손봐야 한다 — 지금 고칠 이유가 없어 두었다.
   그쪽을 만질 일이 생기면 이걸로 바꾸고 pd-say·pd-face·pd-bubble 을 지운다.

   **모양이 B-03 과 다르다.** 저쪽은 얼굴이 말풍선 밖에 있고 꼬리가 달렸는데,
   여기서는 얼굴을 안에 넣었다 — 값이 빽빽한 카드에서는 그 방식이 말풍선 왼쪽 끝을
   안쪽으로 밀어 카드 안 다른 줄들과 어긋난다(mascot-say.css 머리말).

   얼굴은 검정 개미다. 흰 개미는 흰 말풍선 위에서 윤곽이 사라진다. */
import '../styles/mascot-say.css'

type Props = {
  children: React.ReactNode
  /** 말풍선 오른쪽 끝에 붙는 것. 더보기 토글 같은 것을 넣는다 */
  trailing?: React.ReactNode
}

export default function MascotSay({ children, trailing }: Props) {
  return (
    <div className="ms-say">
      <img
        className="ms-face"
        src="/assets/character/black_ant/antenna-profile.png"
        alt="" aria-hidden="true"
      />
      <div className="ms-text">{children}</div>
      {trailing}
    </div>
  )
}
