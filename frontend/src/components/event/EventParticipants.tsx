/* 대회 참가자 — J-01. GET /events/{id}/participants.

   ── 순위판이 아니다 ─────────────────────────────────────
   순위(GET /events/{id}/leaderboard)는 정산이 끝나야 나온다. 승패를 모르는데 줄을
   세우면 사용자가 현재 등수로 읽기 때문이다(09-16 결정). 그래서 대회가 도는 내내 이
   화면에는 "정산 뒤에 공개됩니다" 라는 안내만 떠 있었다. 그 순위를 크게 그리는 자리는
   랭킹의 이벤트 탭이다(components/ranking/EventRanking).

   이 블록은 그 빈자리를 메운다. **누가 참가했나는 승패와 무관하게 이미 정해진 사실**이라
   접수 중에도 그대로 보여줄 수 있다. 등수를 매기지 않으므로 잘못 읽힐 것도 없다.

   ── 열어 볼 수 없다 ─────────────────────────────────────
   타일은 **누를 수 없다**(2026-09-17 팀 결정). 참가자의 채널로 보내던 링크를 뗐다 —
   대회 참가자의 정보는 열어 보지 않는다. 얼굴과 이름이 올라온 것까지만 보인다.

   손을 얹으면 **건 종목 이름**만 말풍선으로 뜬다. 방향·목표가·배팅액은 응답에 아예
   없다 — "남의 방향·목표가는 나가지 않는다"(EventQueryService 머리말). 종목 이름이
   나가는 것은 종목 카드의 참가 인원이 이미 세고 있는 사실이라서다.

   ── 첫 화면은 비어 있는 것이 정상이다 ───────────────────
   접수가 열린 직후에는 참가자가 0명이다. 빈 목록도 그릴 줄 알아야 한다. */
import { useCallback } from 'react'
import { fetchParticipants } from '../../api/events'
import { DEFAULT_AVATAR } from '../../auth/context'
import { useAsync } from '../../api/useAsync'

export default function EventParticipants({ eventId }: { eventId: number }) {
  const load = useCallback(() => fetchParticipants(eventId), [eventId])
  const { data, loading } = useAsync(load)

  /* 불러오는 동안 자리를 비워 둔다. 옆 칸(내 참가 내역)이 이미 있어서 이 칸이 잠깐
     늦게 뜨는 것은 티가 안 나고, 뼈대를 깔면 오히려 깜빡인다 */
  if (loading || !data) return null

  return (
    <section className="card ev-block" aria-labelledby="ev-people-h">
      <header className="ev-block-head">
        <h2 id="ev-people-h">
          대회 참가자 {data.total > 0 && <span className="num">{data.total}</span>}
        </h2>
        <p>대회 종목에 예측을 등록하면 여기에 이름이 올라갑니다.</p>
      </header>

      {data.items.length === 0 ? (
        <p className="ev-state is-quiet">
          아직 참가자가 없어요. 첫 번째로 이름을 올려 보세요.
        </p>
      ) : (
        <ul className="ev-tiles">
          {data.items.map((p) => (
            <li key={p.userId}>
              {/* div 다 — 누를 데가 아니다. 링크로 두면 눌러 보고 아무 일도 안 일어난다 */}
              <div className="ev-tile">
                {/* 안 올렸으면 기본 개미다(DEFAULT_AVATAR) — 리포트·랭킹·구독 목록이
                    쓰는 그 상수 그대로다. 빈 원으로 두면 이 화면만 다른 규칙이 된다.

                    `?? DEFAULT_AVATAR` 로 받는 것이지 src="" 를 넘기지 않는다 — 빈
                    문자열은 리액트가 경고를 띄우고, 브라우저에 따라 지금 페이지를
                    통째로 다시 내려받는다. */}
                <img
                  className="ev-tile-face"
                  src={p.avatarUrl ?? DEFAULT_AVATAR}
                  alt=""
                  aria-hidden="true"
                />
                <span className="ev-tile-name">{p.nickname ?? '이름 없음'}</span>

                {/* 말풍선은 손을 얹어야 뜨므로 화면 낭독기에는 닿지 않는다. 같은 내용을
                    눈에 안 보이는 줄로 한 번 더 둔다 — 말풍선 쪽은 aria-hidden 이라
                    두 번 읽히지 않는다. */}
                <span className="ev-sr">{`건 종목 ${p.stocks.join(', ')}`}</span>

                {/* 건 종목 말풍선.
                    자격을 못 갖춘 사람에게는 몇 개 남았는지까지 말한다. 최소 종목 수는
                    등록을 막지 않고 순위 자격으로만 걸려서, 화면이 말하지 않으면 본인도
                    남도 정산 때 알게 된다. */}
                <span className="ev-tile-tip" aria-hidden="true">
                  <span className="ev-tile-tip-name">{p.nickname ?? '이름 없음'}</span>
                  <span className="ev-tile-tip-rows">
                    {p.stocks.map((name) => (
                      <span key={name}>{name}</span>
                    ))}
                  </span>
                  {!p.qualified && (
                    <span className="ev-tile-tip-note">아직 순위 자격에 못 미칩니다</span>
                  )}
                </span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
