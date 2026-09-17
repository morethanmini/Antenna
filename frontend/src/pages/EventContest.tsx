/* J-01 천하제일 단타대회 · /events/scalping
   담당 스토리 [ANT-FE-EVENT-CONTEST]

   이벤트용 예측 대회. 들어오는 길은 **링크와 인사이트 홈 배너 둘뿐**이라
   사이드바에 메뉴를 만들지 않는다. 그래서 이 화면은 처음 온 사람이 아무 맥락
   없이 착지한다고 보고 짰다 — 무슨 대회인지, 언제까지 뭘 해야 하는지,
   어디를 눌러야 하는지가 한 화면에 다 있어야 한다.

   차례는 "무엇을 해야 하나" 가 먼저다. 규칙을 맨 위에 길게 깔면 종목 카드가
   접히는데, 정작 눌러야 하는 것은 카드다. 그래서 머리에 지금 단계와 마감만
   크게 적고, 자세한 규칙은 카드 아래로 내렸다.

   대회 정의(날짜·종목·글귀)는 event/contest.ts 한 곳에 있다. 이 파일에는
   날짜를 적지 않는다 — 일정이 밀릴 때 고칠 곳이 둘이 되면 하나를 빠뜨린다. */
import { Navigate } from 'react-router-dom'
import { fetchContestEvent, type EventHome } from '../api/events'
import { useAsync } from '../api/useAsync'
import EventMyEntries from '../components/event/EventMyEntries'
import EventParticipants from '../components/event/EventParticipants'
import EventStocks from '../components/event/EventStocks'
import { BANNER_BASE, CONTEST, HERO, RULES, phaseAt, stamp } from '../event/contest'
import '../styles/screens/event-contest.css'

/* 회차를 slug 로 찾고 홈을 읽는다. 쿼리 둘이다.

   전에는 contest.ts 의 CONTEST_CODES 를 돌며 종목 상세를 **열 번** 불렀다. 그 목록이
   화면 상수였는데, idx 가 온체인 커밋의 재료라 서버 목록과 하나만 어긋나도 배팅이
   엉뚱한 종목에 걸린다. 이제 서버가 순서까지 정해 준다(2026-09-16).

   **모듈 스코프 함수다.** useAsync 는 load 가 바뀌면 다시 읽으므로 컴포넌트 안에서
   만들면 매 렌더 새로 읽는다(useAsync 머리말). */
async function loadContest(): Promise<EventHome | null> {
  /* slug 가 우선이고, 없으면 지금 등록받는 회차 → 가장 최근 회차 순으로 잡는다.
     운영 점검용 리허설 회차는 slug 가 비어 있어 slug 로만 찾으면 못 띄운다
     (api/events.ts fetchContestEvent 머리말). */
  return fetchContestEvent(CONTEST.id)
}

/* 머리 — 대회 이름 · 한 줄 소개 · 다음 마감 · 규칙을 한 카드에 담는다.

   **규칙을 따로 카드로 두지 않는다**(2026-09-16). 머리가 왼쪽에 글만 두고
   오른쪽을 통째로 비워 두는 모양이었는데, 그 빈자리에 규칙이 들어가면 머리
   하나로 "무슨 대회인지 · 언제 뭘 해야 하는지" 가 끝난다. 카드 하나가 줄고
   종목 카드가 그만큼 위로 올라온다.

   글자는 대부분 그림이다. 읽어 주는 기기가 그림 속 글자를 못 읽으므로,
   제목·소개는 alt 로, 규칙 네 줄은 화면 밖 목록으로 함께 깐다. */
function Hero({ phase }: { phase: ReturnType<typeof phaseAt> }) {
  /* 단계마다 "다음에 올 일" 이 다르다. 셋 다 보여 주면 무엇을 봐야 할지
     고르게 만드는 셈이라, 지금 기다리는 것 하나만 적는다. */
  const next =
    phase === 'before' ? { label: '등록 시작', at: stamp(CONTEST.opensAt) }
    : phase === 'open' ? { label: '등록 마감', at: stamp(CONTEST.closesAt) }
    : phase === 'judging' ? { label: '결과 공개', at: stamp(CONTEST.revealAt) }
    : null

  return (
    <header className="ev-hero">
      {/* 바탕 — 배너와 같은 조각을 쓴다. 두 화면이 같은 이벤트라는 게 보인다 */}
      <span className="ev-hero-bg" aria-hidden="true">
        <img className="ev-hero-bgimg" src={BANNER_BASE.bg} alt="" />
        <img className="ev-hero-chart" src={BANNER_BASE.chart} alt="" />
        <img className="ev-hero-confetti" src={BANNER_BASE.confetti} alt="" />
      </span>

      {/* **상자 폭이 곧 제목 폭이다.** 그래야 소개 줄과 알약이 제목에 가로
          가운데로 맞는다 — 폭이 다른 것을 옆에 끼우면 아래 줄들이 제목이 아니라
          칸 왼쪽 끝을 기준으로 서 버린다. */}
      <div className="ev-hero-lead">
        <img className="ev-hero-event" src={HERO.event} alt="이벤트" />

        <h1 id="ev-h" className="ev-hero-h1">
          <img className="ev-hero-title" src={HERO.title} alt={CONTEST.name} />
        </h1>
        <img className="ev-hero-lede" src={HERO.lede} alt={CONTEST.lede} />

        {/* **시작 전에도 글자로 그린다.** 원래는 그림(HERO.opensAt)을 썼는데 거기
            "13:10" 이 구워져 있고, 담당자 확정값이 13:30 으로 바뀌었다(2026-09-16).
            날짜가 박힌 그림은 일정이 흔들릴 때마다 조용히 거짓말을 한다 — 여기만
            글자로 돌리면 contest.ts 한 곳을 고치는 것으로 끝난다.
            새 그림을 받으면 아래 한 줄을 되살리면 된다:
              {phase === 'before' ? <img className="ev-hero-next" src={HERO.opensAt}
                 alt={`${next?.label} ${next?.at}`} /> : …} */}
        {next ? (
          <p className="ev-next">
            <b>{next.label}</b>
            <span className="num">{next.at}</span>
          </p>
        ) : null}
      </div>

      <img className="ev-hero-mascot" src={BANNER_BASE.mascot} alt="" aria-hidden="true" />

      <div className="ev-hero-rules">
        <img className="ev-hero-rules-title" src={HERO.rulesTitle} alt="대회 규칙" />
        {/* **임시로 글자다.** 원래는 그림 한 장(HERO.rules)이었는데 네 줄이 통째로
            구워져 있어 13:10 · 9월 28일이 박혀 있다. 담당자 확정값이 13:30 · 9월 22일로
            바뀌어(2026-09-16) 그림이 거짓말을 한다 — 사람들이 금요일 13:30 에 보는 것이
            바로 이 패널이라, 모양이 덜 예쁜 것보다 시각이 틀린 것이 위험하다(이상민님 합의).

            덤으로 읽어 주는 기기용 글을 따로 둘 필요가 없어졌다. 전에는 같은 내용을
            그림과 sr-only 로 두 벌 갖고 있었고, 그래서 날짜가 어긋날 수 있었다.

            **새 그림을 받으면** 이 dl 을 지우고 아래 두 줄을 되살린다:
              <img className="ev-hero-rules-panel" src={HERO.rules} alt="" aria-hidden="true" />
              <dl className="sr-only">…같은 map…</dl> */}
        <dl className="ev-rules">
          {RULES.map((r) => (
            <div className="ev-rule" key={r.k}>
              <dt>{r.k}</dt>
              <dd>{r.v}</dd>
            </div>
          ))}
        </dl>
      </div>
    </header>
  )
}

export default function EventContest() {
  /* 머리의 단계는 contest.ts 의 날짜로 센다(그림·문구가 그 값을 쓴다). 종목·등록·순위는
     **서버의 phase** 를 따른다 — 접수는 서버 크론이 세 조건(시각·기준가·온체인)을 보고
     열기 때문에, 시각만 지났다고 열린 것이 아니다. 두 값을 섞지 않는다. */
  const phase = phaseAt()
  const { data, loading, error, reload } = useAsync(loadContest)
  /* 등록 결과를 **부모가 들고 있다.** 폼이 들고 있으면 등록 직후 reload 가 도는 동안
     data 가 잠깐 null 이 되어 폼이 언마운트되고, 성공 화면이 그대로 사라진다 —
     사용자 눈에는 폼이 비워진 것으로만 보여 등록이 됐는지 알 수 없다(2026-09-16 확인).
     그래서 data 가드 밖에 둔다. */

  /* 판정이 끝난 회차는 이 화면이 아니라 랭킹 탭이 답이다(2026-09-17 결정, ANT-FE-EVENT-LIFECYCLE).
     여기 남겨 두면 끝난 대회의 종목 카드가 계속 보인다 — 링크로 바로 들어온 사람에게 순위를 보여 준다.
     훅 뒤에 둔다. 위 useAsync 보다 앞에서 return 하면 훅 순서가 렌더마다 달라진다. */
  if (data?.phase === 'SETTLED') return <Navigate to="/rankings?tab=event" replace />

  /* 본 대회 회차가 없다 — 아직 안 만들어졌거나 점검용 회차만 있다(그건 사용자 화면에 안 잡힌다,
     api/events.ts fetchContestSummary). 머리(그림)까지 내린다: 회차가 없는데 하드코딩 일정으로
     "등록 시작 9/18 13:10" 이 떠 있으면 거짓이다. */
  const noEvent = !loading && !error && !data

  return (
    <main className="main">
      <div className="main-inner ev" aria-labelledby="ev-h">
        {!noEvent && <Hero phase={phase} />}

        {loading && (
          /* 자리를 미리 잡는다. 카드가 없다가 한 번에 나타나면 아래가 통째로 밀린다 */
          <section className="card ev-block" aria-hidden="true">
            <ul className="ev-cards">
              {Array.from({ length: 10 }, (_, i) => (
                <li key={i}><span className="ev-card is-skel" /></li>
              ))}
            </ul>
          </section>
        )}

        {error && (
          <section className="card ev-block">
            <p className="ev-state">
              대회 정보를 불러오지 못했어요.{' '}
              <button type="button" className="ev-retry" onClick={reload}>다시 시도</button>
            </p>
          </section>
        )}

        {noEvent && (
          <section className="card ev-block" aria-labelledby="ev-h">
            <h1 id="ev-h" className="ev-state">지금 진행 중인 대회가 없어요</h1>
            <p className="ev-state is-quiet">대회가 열리면 인사이트 홈 배너로 알려 드릴게요.</p>
          </section>
        )}

        {/* "등록했습니다" 배너를 뺐다(2026-09-18) — 등록이 이 화면에서 일어나지 않는다.
            종목마다 C-01 에서 걸고 돌아오므로, 결과는 위 진행 줄(N/10)과 아래
            내 참가 내역이 말한다. 돌아온 사람에게 지난 등록을 다시 알릴 이유가 없다. */}

        {data && (
          <>
            <EventStocks event={data} />
            {/* 등록 폼이 여기 없다(2026-09-18). 열 종목을 한 화면에서 적던 묶음 폼을
                걷고, **종목 카드가 곧 입구**가 됐다 — 카드를 누르면 그 종목의 C-01 로
                가고 거기서 방향·목표가·근거·배팅을 적는다.

                예측을 쓰는 화면을 한 벌로 둔 것이 핵심이다. 묶음 폼은 목표가와 방향만
                받을 수 있어서, 대회 예측에는 근거 포인트·내 판단이 빠진 채 남았다.
                C-01 을 그대로 쓰면 대회 예측도 평소 예측과 한 글자도 다르지 않다. */}
            {/* 순위판을 뺐다(2026-09-17). 이 화면이 답하는 것은 "무엇에 걸까" 이고,
                순위를 보러 온 사람은 랭킹의 이벤트 탭(/rankings?tab=event)으로 간다 —
                거기가 전체를 다 보여 주는 자리다. 그 자리를 참가자 타일이 받는다.
                순위와 달리 접수 중에도 채워진다.

                컴포넌트(EventLeaderboard)도 같이 지웠다 — 여기서 떼고 나니 부르는
                곳이 하나도 없었다. 조회 함수와 타입은 그대로다(api/events.ts 의
                fetchLeaderboard · EventLeaderboard). 이벤트 탭이 그걸 쓴다.

                두 칸으로 나눈 것은 둘 다 짧아서다. 세로로 쌓으면 종목 카드 열 장 아래에
                빈 칸 두 개가 길게 늘어진다. 좁아지면 아래에서 한 줄로 돌아간다. */}
            <div className="ev-duo">
              <EventMyEntries eventId={data.eventId} />
              <EventParticipants eventId={data.eventId} />
            </div>
          </>
        )}
      </div>
    </main>
  )
}
