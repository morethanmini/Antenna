/* 모의투자 캔들 차트. 설계서 §7 이 G-04 · G-05 공용으로 지정한 CandleChart 다.

   CloseChart 와 역할이 다르다. 저쪽은 실전 시세라 법적 제약으로 종가만 받고 단선으로
   그린다. 이쪽은 시즌 가격이라 OHLCV 가 다 있고 봉으로 그린다 — 그래서 합치지 않는다.

   설계 의도
   - **x 축이 날짜가 아니라 gameDay 다.** 시즌 가격에는 실제 날짜가 아예 없다(ERD v0.9).
     타입에 날짜가 없으므로 실수로 그릴 수도 없다.
   - **0 이하는 워밍업 구간이다.** 시즌 시작 전 봉이라 배경을 깔아 플레이 구간과 구분한다.
     이게 없으면 첫날 캔들이 한 개라 이동평균도 MACD 도 값이 없다.
   - **진행일을 넘는 봉은 그리지 않는다.** 서버가 이미 잘라서 주지만(커닝 차단) 여기서도
     자른다 — 로컬에서 진행일을 앞당겨 보는 개발 중에도 화면 규칙이 같아야 한다.
   - 지표는 여기서 계산한다(sim/indicators.ts). 서버는 OHLCV 만 준다.
   - 값 확인은 십자선으로 한다. 키보드는 좌우 화살표 — 마우스가 없으면 못 읽는 정보를
     만들지 않는다. CloseChart 와 같은 규칙이다.
   - **보는 구간이 따로 있다**(2026-09-16). 가진 봉을 한 화면에 눌러 그리면 워밍업
     120봉 + 진행분이 실오라기처럼 얇아진다. 끌어서 옮기고 휠로 넓힌다. 지표는
     **자르기 전 전체 봉으로 계산한 뒤 보는 구간만 잘라 그린다** — 자른 뒤 계산하면
     왼쪽 가장자리에서 MA30·볼린저가 통째로 빈다. */
import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { Candle } from '../../api/seasonPlay'
import { bollinger, canShow, sma, type IndicatorKind } from '../../sim/indicators'
import { DEFAULT_BARS, MIN_BARS, resolveView, type BarView } from '../../sim/barView'

type Props = {
  /** 워밍업(0 이하)을 포함한 전체 봉. 오름차순 */
  candles: Candle[]
  /** 여기까지만 그린다. 플레이 구간의 현재 진행일 */
  currentDay: number
  /** 켤 지표. 값이 나올 만큼 봉이 없으면 알아서 빠진다 */
  indicators?: IndicatorKind[]
  height?: number
  /** 보는 구간. 주지 않으면 늘 끝에서 기본 폭만큼이다 */
  view?: BarView
  onViewChange?: (view: BarView) => void
}

/* viewBox 기준으로 그리고 CSS 로 늘린다. 폭을 재지 않아도 컨테이너에 맞춰
   늘어나므로 리사이즈 관찰자가 필요 없다. CloseChart 와 같은 방식이다.

   가로 좌표는 아래 지표 패널(IndicatorPane)과 <b>같아야 한다</b> — 봉 하나가 캔들과
   MACD 에서 다른 x 에 서면 두 그림을 위아래로 읽을 수 없다. 그래서 내보낸다. */
export const VW = 1000
export const PAD_L = 6
export const PAD_R = 68
/* 거래량 막대가 차지하는 비율. 캔들이 주인공이라 5분의 1만 준다 */
const VOL_RATIO = 0.2

const MA_COLOR: Partial<Record<IndicatorKind, string>> = {
  MA5: '#e8a13c',
  MA15: '#7b61ff',
  MA30: '#16a06a',
}
const MA_PERIOD: Partial<Record<IndicatorKind, number>> = { MA5: 5, MA15: 15, MA30: 30 }

const won = (n: number) => Math.round(n).toLocaleString('ko-KR')

export default function CandleChart({
  candles,
  currentDay,
  indicators = ['MA5', 'MA15', 'MA30'],
  height = 340,
  view = null,
  onViewChange,
}: Props) {
  const gid = useId()
  const svgRef = useRef<SVGSVGElement>(null)
  /** 십자선이 가리키는 봉. **전체 봉 기준 번호**다 — 구간이 움직여도 같은 봉을 가리킨다 */
  const [cursor, setCursor] = useState<number | null>(null)
  /** 끌고 있는 중에는 십자선을 따라가지 않는다 — 봉을 읽는 조작과 옮기는 조작이 겹친다 */
  const drag = useRef<{ id: number; x: number; from: number } | null>(null)

  const padT = 12
  const padB = 22

  /* 진행일까지만 남긴다. 워밍업(gameDay <= 0)은 전부 남는다 —
     시즌 시작 전 구간이라 커닝이 아니고, 이게 있어야 첫날부터 지표가 나온다. */
  const shown = useMemo(
    () => candles.filter((c) => c.gameDay <= currentDay),
    [candles, currentDay],
  )

  /* 보는 구간. **진행일이 바뀔 때 끝으로 되돌리는 것은 호출부 몫이다** — 진행일이
     바뀌면 받아 둔 봉이 통째로 버려져 이 컴포넌트가 잠깐 사라졌다 다시 선다. 그때
     여기서 비교하면 새 진행일로 다시 태어나므로 바뀐 줄을 모른다. */
  const { from, count } = resolveView(view, shown.length)

  const visible = useMemo(() => shown.slice(from, from + count), [shown, from, count])

  /* 휠로 구간 폭을 바꾼다. **passive 가 아닌 리스너로 직접 붙인다** — React 의 onWheel 은
     루트에 passive 로 달려 preventDefault 가 먹지 않고, 그러면 차트를 확대하면서 페이지가
     같이 굴러간다. 커서가 가리키는 봉을 제자리에 두고 양옆을 넓히거나 좁힌다. */
  useEffect(() => {
    const el = svgRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      if (e.deltaY === 0) return
      e.preventDefault()
      const total = shown.length
      const next = Math.max(MIN_BARS, Math.min(total, Math.round(count * (e.deltaY > 0 ? 1.2 : 1 / 1.2))))
      if (next === count) return
      const box = el.getBoundingClientRect()
      const at = box.width === 0 ? 0.5 : Math.min(1, Math.max(0, (e.clientX - box.left) / box.width))
      const anchor = from + at * count
      const nextFrom = Math.max(0, Math.min(total - next, Math.round(anchor - at * next)))
      onViewChange?.(nextFrom >= total - next && next === DEFAULT_BARS ? null : { from: nextFrom, count: next })
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [from, count, shown.length, onViewChange])

  const geom = useMemo(() => {
    if (visible.length < 2) return null

    /* 심지가 있으면 고·저까지 담아야 봉이 잘리지 않는다. 없으면 종가로 대신한다 */
    const highs = visible.map((c) => c.high ?? c.close)
    const lows = visible.map((c) => c.low ?? c.close)

    /* 지표는 **전체 봉으로** 계산하고 보는 구간만 잘라 쓴다. 자른 뒤 계산하면
       왼쪽 가장자리 MA30·볼린저가 통째로 비어, 구간을 옮길 때마다 선이 사라진다.
       켤지 말지도 전체 봉 수로 정한다 — 20봉만 보고 있어도 MA30 은 값이 있다. */
    const allCloses = shown.map((c) => c.close)
    const cut = <T,>(values: T[]) => values.slice(from, from + count)

    const on = indicators.filter((k) => canShow(k, shown.length))
    const lines = on
      .filter((k) => MA_PERIOD[k])
      .map((k) => ({ kind: k, values: cut(sma(allCloses, MA_PERIOD[k]!)), color: MA_COLOR[k]! }))
    const rawBand = on.includes('BOLL') ? bollinger(allCloses) : null
    const band = rawBand && { upper: cut(rawBand.upper), lower: cut(rawBand.lower) }

    /* 지표선도 축에 넣는다 — 볼린저 상단이 고가보다 위로 나가면 선이 잘린다 */
    const extra = [
      ...lines.flatMap((l) => l.values),
      ...(band ? [...band.upper, ...band.lower] : []),
    ].filter((v): v is number => v !== null)

    const lo = Math.min(...lows, ...extra)
    const hi = Math.max(...highs, ...extra)
    const margin = (hi - lo || hi * 0.02) * 0.06
    const min = lo - margin
    const max = hi + margin
    const span = max - min || 1

    const w = VW - PAD_L - PAD_R
    const volH = (height - padT - padB) * VOL_RATIO
    const priceH = height - padT - padB - volH - 8

    /* 봉 하나가 차지하는 폭.

       상한이 9 였는데 봉이 적을 때 이게 문제였다. DAY 1 은 워밍업 30 + 1 = 31봉인데
       칸은 30 넓고 몸통만 9 라 사이가 21씩 비어 성기게 보였다. HTS 는 봉이 적으면
       봉이 굵어진다 — 칸의 68% 를 쓰고 상한만 남겨 둔다. 봉이 많아지면 어차피
       칸이 좁아져 얇아지므로 상한은 적은 쪽에서만 걸린다. */
    const step = w / visible.length
    const bodyW = Math.max(1, Math.min(18, step * 0.68))

    const cx = (i: number) => PAD_L + step * (i + 0.5)
    const y = (v: number) => padT + (1 - (v - min) / span) * priceH

    const maxVol = Math.max(1, ...visible.map((c) => c.volume ?? 0))
    const volTop = padT + priceH + 8
    const vy = (v: number) => volTop + volH - (v / maxVol) * volH

    /* null 자리에서 선을 끊는다. 이어 버리면 값이 없는 구간에 가짜 직선이 그려진다 */
    const path = (values: (number | null)[]) => {
      let d = ''
      let open = false
      values.forEach((v, i) => {
        if (v === null) {
          open = false
          return
        }
        d += `${open ? 'L' : 'M'}${cx(i).toFixed(1)} ${y(v).toFixed(1)}`
        open = true
      })
      return d
    }

    /* 워밍업이 끝나는 자리. 플레이 구간 배경의 왼쪽 경계다. 보는 구간이 전부
       플레이 안이면 -1 이 되어 배경이 폭 전체를 덮는다 */
    const firstPlay = visible.findIndex((c) => c.gameDay > 0)
    const playX = firstPlay < 0
      ? (visible[0].gameDay > 0 ? PAD_L : VW - PAD_R)
      : PAD_L + step * firstPlay

    return {
      min, max, lo, hi, cx, y, vy, step, bodyW, volTop, volH, priceH, maxVol, playX,
      /* 범례에 쓸 마지막 값도 함께 낸다. 선만 그리면 어느 게 MA5 인지, 값이 얼마인지
         알 수 없다 — 53,500 과 53,900 은 선 두께 안에 들어가 눈으로 구분되지 않는다.
         세 선의 순서가 곧 추세 신호라 숫자가 있어야 비교가 된다. */
      lines: lines.map((l) => ({
        ...l,
        d: path(l.values),
        last: l.values[l.values.length - 1],
      })),
      band: band && { upper: path(band.upper), lower: path(band.lower) },
    }
  }, [visible, shown, from, count, indicators, height])

  if (!geom) {
    return <div className="ck-empty">{'봉이 모이지 않아 차트를 그릴 수 없습니다'}</div>
  }

  const pick = (clientX: number) => {
    const box = svgRef.current?.getBoundingClientRect()
    if (!box) return
    const vx = ((clientX - box.left) / box.width) * VW
    const i = Math.floor((vx - PAD_L) / geom.step)
    setCursor(from + Math.max(0, Math.min(visible.length - 1, i)))
  }

  /** 구간을 옮긴다. 양 끝을 넘지 않게 자르고, 끝에 딱 붙으면 null 로 돌려 "늘 끝" 으로 둔다 */
  const moveTo = (nextFrom: number, nextCount = count) => {
    const total = shown.length
    const c = Math.max(1, Math.min(total, nextCount))
    const f = Math.max(0, Math.min(total - c, Math.round(nextFrom)))
    onViewChange?.(f >= total - c && c === DEFAULT_BARS ? null : { from: f, count: c })
  }

  /* 화살표는 십자선을 옮긴다. 보는 구간 밖으로 나가면 구간이 따라간다 — 그렇지 않으면
     키보드 사용자는 끝에 닿은 뒤로 더 갈 수 없다(마우스만 과거를 볼 수 있게 된다). */
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    const at = cursor ?? from + visible.length - 1
    const next = Math.max(0, Math.min(shown.length - 1, at + (e.key === 'ArrowRight' ? 1 : -1)))
    setCursor(next)
    if (next < from) moveTo(next)
    if (next >= from + count) moveTo(next - count + 1)
  }

  const onPointerDown = (e: React.PointerEvent<SVGSVGElement>) => {
    if (e.button !== 0) return
    drag.current = { id: e.pointerId, x: e.clientX, from }
    /* 붙잡기가 실패해도 끌기는 이어간다 — 커서가 차트 밖으로 나갔을 때 따라오지
       않을 뿐이다. 여기서 던지면 그 예외가 끌기 시작 자체를 삼킨다. */
    try { e.currentTarget.setPointerCapture(e.pointerId) } catch { /* 이미 놓인 포인터 */ }
  }

  const onPointerMove = (e: React.PointerEvent<SVGSVGElement>) => {
    const d = drag.current
    if (!d) {
      pick(e.clientX)
      return
    }
    const box = svgRef.current?.getBoundingClientRect()
    if (!box) return
    /* 화면 폭을 보는 봉 수로 나눈 것이 봉 하나의 실제 픽셀 폭이다. viewBox 좌표가 아니라
       화면 좌표로 재야 손을 그대로 따라온다(preserveAspectRatio="none" 로 늘려 그린다). */
    const perBar = box.width / count
    moveTo(d.from - (e.clientX - d.x) / perBar)
  }

  const endDrag = (e: React.PointerEvent<SVGSVGElement>) => {
    if (!drag.current) return
    try { e.currentTarget.releasePointerCapture(drag.current.id) } catch { /* 이미 놓였다 */ }
    drag.current = null
  }

  const hit = cursor === null ? null : shown[cursor]
  const last = visible[visible.length - 1]
  const warmup = visible.length - visible.filter((c) => c.gameDay > 0).length

  return (
    <div className="ck">
      {/* 이평선 범례. 색만 있으면 어느 선이 MA5 인지 모르고, 값이 없으면 현재가가 그 선
          위인지 아래인지를 눈대중으로 재야 한다. 세 선의 순서가 추세 신호다 —
          현재가 < MA5 < MA15 < MA30 이면 하락, 거꾸로면 상승. */}
      {geom.lines.length > 0 && (
        <ul className="ck-legend num" aria-label="이동평균선">
          {geom.lines.map((l) => (
            <li key={l.kind}>
              <i style={{ background: l.color }} aria-hidden="true" />
              <span>{l.kind}</span>
              {/* 봉이 부족하면 값이 아직 없다. 그때는 왜 없는지 대신 적는다 */}
              <b>{l.last === null ? '—' : won(l.last)}</b>
            </li>
          ))}
        </ul>
      )}

      <svg
        ref={svgRef}
        className="ck-svg"
        viewBox={`0 0 ${VW} ${height}`}
        style={{ height }}
        preserveAspectRatio="none"
        role="img"
        tabIndex={0}
        aria-label={`캔들 차트. 보이는 ${visible.length}봉 중 시즌 시작 전 ${warmup}봉. 전체 ${shown.length}봉이며 좌우 화살표로 게임일별 값을 읽고 지나간 구간으로 넘어갑니다.`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onPointerLeave={() => setCursor(null)}
        onKeyDown={onKey}
        onBlur={() => setCursor(null)}
      >
        <defs>
          <clipPath id={`${gid}-plot`}>
            <rect x={PAD_L} y={padT} width={VW - PAD_L - PAD_R} height={geom.priceH} />
          </clipPath>
        </defs>

        {/* 플레이 구간 배경. 왼쪽이 워밍업이라는 걸 이 경계가 말해 준다 */}
        <rect
          className="ck-play"
          x={geom.playX}
          y={padT}
          width={Math.max(0, VW - PAD_R - geom.playX)}
          height={geom.priceH}
        />
        <line className="ck-play-edge" x1={geom.playX} x2={geom.playX} y1={padT} y2={height - padB} />

        {/* 거래량 — 캔들 아래 띠. 원천에 없으면 막대가 빠진다 */}
        <g className="ck-vols">
          {visible.map((c, i) =>
            c.volume == null ? null : (
              <rect
                key={c.gameDay}
                className={c.close >= (visible[i - 1]?.close ?? c.close) ? 'up' : 'down'}
                x={geom.cx(i) - geom.bodyW / 2}
                y={geom.vy(c.volume)}
                width={geom.bodyW}
                height={Math.max(0.5, geom.volTop + geom.volH - geom.vy(c.volume))}
              />
            ),
          )}
        </g>

        <g clipPath={`url(#${gid}-plot)`}>
          {/* 봉 — 심지 먼저, 몸통 나중. 몸통이 심지를 덮어야 깔끔하다 */}
          {visible.map((c, i) => {
            const o = c.open ?? c.close
            const h = c.high ?? Math.max(o, c.close)
            const l = c.low ?? Math.min(o, c.close)
            const rise = c.close >= o
            const top = geom.y(Math.max(o, c.close))
            const bottom = geom.y(Math.min(o, c.close))
            return (
              <g key={c.gameDay} className={`ck-candle ${rise ? 'up' : 'down'}`}>
                <line x1={geom.cx(i)} x2={geom.cx(i)} y1={geom.y(h)} y2={geom.y(l)} />
                <rect
                  x={geom.cx(i) - geom.bodyW / 2}
                  y={top}
                  width={geom.bodyW}
                  /* 시가와 종가가 같으면 높이가 0 이라 선 하나로 보이게 최소값을 준다 */
                  height={Math.max(1, bottom - top)}
                />
              </g>
            )
          })}

          {geom.band && (
            <g className="ck-band">
              <path d={geom.band.upper} />
              <path d={geom.band.lower} />
            </g>
          )}

          {geom.lines.map((l) => (
            <path key={l.kind} className="ck-ma" d={l.d} stroke={l.color} />
          ))}
        </g>

        {/* 십자선은 보는 구간 안에 있을 때만 그린다 — 구간을 옮겨 놓고 밖의 봉을
            가리키고 있으면 선이 왼쪽 벽에 붙어 엉뚱한 봉을 가리키는 것처럼 보인다 */}
        {cursor !== null && cursor >= from && cursor < from + count && (
          <line
            className="ck-cursor"
            x1={geom.cx(cursor - from)}
            x2={geom.cx(cursor - from)}
            y1={padT}
            y2={height - padB}
          />
        )}

        {/* 세로축은 오른쪽에 최고·최저만. 격자를 촘촘히 깔면 봉이 묻힌다 */}
        <g className="ck-axis">
          <text x={VW - PAD_R + 8} y={geom.y(geom.hi) + 4}>{won(geom.hi)}</text>
          <text x={VW - PAD_R + 8} y={geom.y(geom.lo) + 4}>{won(geom.lo)}</text>
          {/* x 축은 날짜가 아니라 게임일이다.
              진행일이 얼마 안 됐을 때는 두 라벨이 같은 자리에 붙는다 — DAY 1 시작 경계와
              마지막 봉이 겹쳐 "DADAY 1" 로 보였다. 떨어져 있을 때만 시작 라벨을 그린다. */}
          {warmup > 0 && VW - PAD_R - geom.playX > 90 && (
            <text x={geom.playX} y={height - 6} textAnchor="middle">DAY 1</text>
          )}
          {/* 오른쪽 끝 라벨. 끌어서 시작 전 구간을 보고 있으면 "DAY -34" 가 되는데,
              읽는 사람에게 음수 게임일이라는 것은 없다 — 시작 전이라고 말한다. */}
          <text x={VW - PAD_R} y={height - 6} textAnchor="end">
            {last.gameDay > 0 ? `DAY ${last.gameDay}` : `시작 ${1 - last.gameDay}일 전`}
          </text>
        </g>
      </svg>

      {/* 십자선 값. 차트 위에 겹치지 않고 아래 줄에 둔다 — 숫자가 봉을 가리지 않게.
          비어 있어도 자리를 유지해 화면이 튀지 않는다. CloseChart 와 같은 규칙이다. */}
      <p className="ck-readout num" aria-live="polite">
        {hit ? (
          <>
            <span className="ck-ro-day">
              {hit.gameDay > 0 ? `DAY ${hit.gameDay}` : '시작 전'}
            </span>
            <span>시 {hit.open == null ? '—' : won(hit.open)}</span>
            <span>고 {hit.high == null ? '—' : won(hit.high)}</span>
            <span>저 {hit.low == null ? '—' : won(hit.low)}</span>
            <b>종 {won(hit.close)}</b>
            {hit.volume != null && <span>거래량 {hit.volume.toLocaleString('ko-KR')}</span>}
          </>
        ) : (
          <span className="ck-ro-idle">{'차트 위에 올리면 게임일별 값을 봅니다'}</span>
        )}
      </p>
    </div>
  )
}
