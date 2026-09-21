/* B-03 종목 상세 · /stocks/:code
   담당 스토리 [ANT-FE-STOCK-DETAIL]
   설계서 docs/화면설계서.md §3 B · §4 B-03 · §9.2

   화면 구조
   요약 헤더는 스크롤에 붙어 남고, 그 아래가 안쪽 페이지 둘로 갈린다.
     · 종목 정보 — 차트 · 심리 · 브리핑 · 포인트 · 공시/뉴스 · 개요 · 재무 · 밸류 · 경쟁사
     · 예측하기 — C-01 예측 등록
   종목을 보다가 바로 예측하는 흐름이라 페이지를 갈아 끼우지 않는다. 방금 읽던
   근거와 차트가 그대로 남아 있어야 목표가를 정할 수 있다.

   탭은 ?tab= 로 주소에 남긴다. 뒤로가기가 탭 사이를 오가고, 링크로 특정 탭을
   바로 열 수 있다. 상태로만 들고 있으면 새로고침에 첫 탭으로 돌아간다.

   투자 포인트 선택은 두 탭이 함께 쓰므로 여기(부모)에 둔다. 이 인계에는 API 가
   없다 — 클라이언트 상태로 넘기는 게 설계다(§4 B-03). */
import { useCallback, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import InfoTab from '../components/stockDetail/InfoTab'
import PredictTab from '../components/stockDetail/PredictTab'
import SettledToday from '../components/stockDetail/SettledToday'
import {
  emptyPredictForm, type PredictForm, type PredictResult,
} from '../components/stockDetail/predictForm'
import { useBlock } from '../api/useBlock'
import ErrorState from '../components/state/ErrorState'
import { addWatch, removeWatch, MARKET_LABEL } from '../api/stocks'
import { getSummary } from '../api/stockDetail'
import { CONTEST, CONTEST_PATH } from '../event/contest'
import StockLogo from '../components/stocks/StockLogo'
import '../styles/screens/stock-detail.css'

const TABS = [
  { key: 'info', label: '종목 정보' },
  { key: 'predict', label: '예측하기' },
] as const
type TabKey = (typeof TABS)[number]['key']

export default function StockDetail() {
  const { code = '' } = useParams<{ code: string }>()
  const [params, setParams] = useSearchParams()

  const tab: TabKey = params.get('tab') === 'predict' ? 'predict' : 'info'

  /* 대회(J-01)로 들어왔는가. 대회 종목 카드가 `?tab=predict&event=scalping` 으로
     데려온다(EventStocks). 탭과 같이 **주소에** 둔다 — 상태로만 들고 있으면
     새로고침에 평소 화면으로 돌아가고, 적어 둔 배팅이 말없이 사라진다.

     **여기서는 "대회로 들어왔다" 는 의사만 본다**(2026-09-18).
     회차가 열렸는지·이 종목이 회차 종목인지·이미 걸었는지는 서버만 아는 값이라
     PredictTab 이 GET /events/{id} 로 확인한다. 예전에는 contest.ts 의 CONTEST_CODES
     열 개와 대조했는데, 회차 종목이 바뀌면 카드에서 눌러 들어와도 배팅 칸이 조용히
     사라진다 — 화면 상수와 서버 목록이 갈리는 순간 생기는 사고다. */
  const eventMode = params.get('event') === CONTEST.id
  const goTab = useCallback((next: TabKey) => {
    const p = new URLSearchParams(params)
    if (next === 'info') p.delete('tab')
    else p.set('tab', next)
    /* 탭 전환은 히스토리를 쌓지 않는다. 탭을 다섯 번 오간 뒤 뒤로가기를
       다섯 번 눌러야 목록으로 돌아가면 답답하다. */
    setParams(p, { replace: true })
  }, [params, setParams])

  const summary = useBlock(() => getSummary(code), [code])
  const s = summary.data

  /* 근거 포인트 선택 — 두 탭이 공유한다 */
  const [picked, setPicked] = useState<number[]>([])

  /* 예측 입력값도 여기서 든다. 탭을 바꾸면 PredictTab 이 언마운트되므로 그
     안에 두면 근거를 고르러 갔다 온 사이에 적어 둔 것이 전부 사라진다.
     picked 를 여기 둔 것과 같은 이유다. */
  /* 함수를 그대로 넘긴다(호출하지 않는다) — useState 의 지연 초기화라 noteSalt 를
     한 번만 뽑는다. emptyPredictForm() 로 부르면 다시 그릴 때마다 새 난수가 나온다. */
  const [form, setForm] = useState<PredictForm>(emptyPredictForm)
  /* 등록 완료 결과도 여기다(2026-09-21, QA-0918 #330). PredictTab 안에 두었을 때는
     완료 화면에서 종목 정보 탭을 다녀오면 결과가 사라지고 값이 남은 폼이 되살아나
     같은 예측을 두 번 등록할 수 있었다.

     어느 종목의 결과인지를 함께 든다. 이 페이지는 /stocks/:code 의 code 만 바뀔 때
     다시 마운트되지 않아(App.tsx 의 key 는 경로 패턴이다) 그냥 두면 다른 종목에
     "등록했습니다" 가 서 있게 된다 — 코드가 다르면 렌더에서 없는 것으로 읽는다.
     effect 로 지우면 한 렌더 동안 옛 결과가 보이고 린트도 그걸 짚는다. */
  const [done, setDone] = useState<{ code: string; result: PredictResult } | null>(null)
  const result = done?.code === code ? done.result : null
  const onResult = useCallback((r: PredictResult) => setDone({ code, result: r }), [code])
  const onPick = useCallback((id: number) => {
    setPicked((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }, [])

  /* 관심 토글은 낙관적으로 먼저 바꾸고 실패하면 되돌린다(§4 B-02 와 같은 규칙) */
  const [watchOverride, setWatchOverride] = useState<boolean | null>(null)
  const watched = watchOverride ?? s?.watching ?? false

  const toggleWatch = useCallback(() => {
    const next = !watched
    setWatchOverride(next)
    const call = next ? addWatch(code) : removeWatch(code)
    call.catch(() => setWatchOverride(!next))
  }, [watched, code])

  const change = useMemo(() => {
    if (!s || s.changeRate === null) return null
    return { up: s.changeRate >= 0, text: `${s.changeRate > 0 ? '+' : ''}${s.changeRate.toFixed(2)}%` }
  }, [s])

  return (
    <main className="main">
      <div className="main-inner">
        {summary.error ? (
          <ErrorState error={summary.error} onRetry={summary.retry} />
        ) : (
          <>
            {/* ── 요약 헤더 ───────────────────────────────────
                스크롤을 내려도 어느 종목을 보는지, 종가가 얼마인지 남아 있어야
                아래쪽 재무·경쟁사를 읽을 때 기준을 잃지 않는다. */}
            {/* 뒤로가기는 sticky 밖이다. 붙여 두면 스크롤 내내 따라다니는데,
                한 번 쓰고 마는 길이라 자리만 차지한다. 위로 올리면 다시 보인다. */}
            <div className="sd-head-top">
              {/* 온 곳으로 돌려보낸다. 대회 종목 카드를 눌러 들어왔으면 돌아갈
                  곳은 대회이지 종목 탐색이 아니다 — 남은 종목을 이어서 걸려면
                  카드 목록으로 돌아가야 하는데, 목록으로 보내면 길이 끊긴다. */}
              <Link className="sd-back" to={eventMode ? CONTEST_PATH : '/stocks'}>
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <path d="M15 5 8 12l7 7" />
                </svg>
                {eventMode ? CONTEST.name : '종목 탐색'}
              </Link>
            </div>

            <header className="sd-head">
              <div className="sd-ident">
                {/* 로고는 **불러오기와 상관없이** 먼저 세운다 — 종목코드는 주소에서
                    바로 알 수 있어 이름을 기다릴 이유가 없고, 뒤늦게 끼어들면
                    제목 줄이 그만큼 옆으로 밀린다. 이름이 오기 전에는 글자 배지가
                    빈 채로 자리만 지킨다(목록·홈과 같은 컴포넌트다). */}
                <StockLogo code={code} name={s?.name ?? ''} className="sd-logo" />
                {summary.loading ? (
                  <span className="sd-skel sd-skel-title" aria-hidden="true" />
                ) : (
                  <>
                    <h1>{s?.name}</h1>
                    <span className="sd-code num">{code}</span>
                    {s?.market && <span className="sd-chip">{MARKET_LABEL[s.market]}</span>}
                    {s?.sector && <span className="sd-chip is-soft">{s.sector}</span>}

                    {/* 오른쪽 끝 묶음. 오늘 판정 카드와 관심 종목을 한 상자에 넣어
                        오른쪽으로 민다 — 둘에 각각 margin-left:auto 를 주면 남는
                        폭을 반씩 나눠 가져 카드가 가운데로 떠 버린다. */}
                    <div className="sd-ident-right">
                      {/* 오늘 판정된 예측. 없으면 스스로 아무것도 그리지 않는다.
                          **대회로 들어왔으면 접는다** — 이 문으로 온 사람은 대회 종목에
                          예측을 걸러 온 것이라, 오늘 끝난 남의 예측이 할 말이 없다. */}
                      {!eventMode && <SettledToday code={code} />}

                      <button
                        type="button"
                        className={watched ? 'sd-watch is-on' : 'sd-watch'}
                        aria-pressed={watched}
                        onClick={toggleWatch}
                      >
                        <svg width="16" height="16" viewBox="0 0 24 24"
                             fill={watched ? 'currentColor' : 'none'} stroke="currentColor"
                             strokeWidth="2" strokeLinejoin="round" aria-hidden="true">
                          <path d="m12 3.6 2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8L3.5 9.8l5.9-.9z" />
                        </svg>
                        {watched ? '관심 종목' : '관심 담기'}
                      </button>
                    </div>
                  </>
                )}
              </div>

              {/* 전일 종가만 쓴다. "현재가" 라는 말을 만들지 않는다(§7 legal) */}
              <div className="sd-price">
                {summary.loading ? (
                  <span className="sd-skel sd-skel-price" aria-hidden="true" />
                ) : s?.prevClose === null ? (
                  <p className="sd-halted">이 종목은 해당 영업일에 거래가 없었습니다</p>
                ) : (
                  <>
                    <b className="num">{s?.prevClose?.toLocaleString('ko-KR')}<span>원</span></b>
                    {change && (
                      <span className={`sd-change num ${change.up ? 'up' : 'down'}`}>{change.text}</span>
                    )}
                    <span className="sd-asof num">{s?.asOf?.replace(/-/g, '.')} 종가</span>
                  </>
                )}
              </div>

              {/* ── 안쪽 페이지 전환 ────────────────────────── */}
              <div className="sd-tabs" role="tablist" aria-label="종목 상세 보기">
                {TABS.map((t) => (
                  <button
                    key={t.key}
                    type="button"
                    role="tab"
                    id={`sd-tab-${t.key}`}
                    aria-selected={tab === t.key}
                    aria-controls={`sd-panel-${t.key}`}
                    className={tab === t.key ? 'is-on' : undefined}
                    onClick={() => goTab(t.key)}
                  >
                    {t.label}
                    {t.key === 'predict' && s && s.predictionCount > 0 && (
                      <span className="sd-tab-count num">{s.predictionCount}</span>
                    )}
                  </button>
                ))}
              </div>
            </header>

            <div
              role="tabpanel"
              id={`sd-panel-${tab}`}
              aria-labelledby={`sd-tab-${tab}`}
              tabIndex={-1}
            >
              {tab === 'info' ? (
                <InfoTab
                  code={code}
                  summary={s}
                  picked={picked}
                  onPick={onPick}
                  onGoPredict={() => goTab('predict')}
                />
              ) : (
                <PredictTab
                  code={code}
                  summary={s}
                  picked={picked}
                  onPick={onPick}
                  onGoInfo={() => goTab('info')}
                  form={form}
                  onForm={setForm}
                  result={result}
                  onResult={onResult}
                  eventMode={eventMode}
                />
              )}
            </div>
          </>
        )}
      </div>
    </main>
  )
}
