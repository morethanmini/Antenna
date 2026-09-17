/* 이 종목의 예측 분포 — B-03 예측 탭 왼쪽, 그리고 정보 탭의 "예측 현황" 카드.
   설계 변경 2026-09-10.

   전에는 개별 예측을 목록으로 보여줬다. 이제는 **누가 걸었는지를 보여주지 않고**
   목표가 구간별 인원만 낸다. 개인은 작성자 채널(E-02)에서만 본다.

   주식 호가창을 본떴지만 다른 점이 하나 있다 — 우리에겐 상·하한가가 없다.
   그래서 절대가가 아니라 **전일 종가 대비 %** 로 자른다. 그래야 1,000원 종목과
   100만원 종목이 같은 개수의 구간으로 나뉜다.

   기준선은 전일 종가다. 실전 시세는 그것뿐이라 "현재가" 라고 적지 않는다(§7 legal).

   막대 길이는 최대 구간을 100 으로 본 상대값이다. 전체 합으로 나누면 구간이
   많을수록 모든 막대가 짧아져 분포 모양이 안 보인다. */
import { useBlock } from '../../api/useBlock'
import { getPredictionDistribution } from '../../api/predictions'
import type { PredictionBucket, PredictionDistribution } from '../../api/predictions'
import ErrorState from '../state/ErrorState'
import { Panel } from './Block'

const won = (n: number) => n.toLocaleString('ko-KR')

/** 2026-09-09 → 2026.09.09. 화면 머리의 "2026.09.09 종가" 와 같은 표기다
    (StockDetail·InfoTab 이 쓰는 것과 같은 변환) */
const dot = (iso: string) => iso.replace(/-/g, '.')

/**
 * 구간 이름. **경계 한쪽만 적는다** — 기준가에서 먼 쪽이다.
 *
 * 양쪽을 다 적으면(`+5 ~ +10%`) 열 줄이 모두 물결표로 뒤덮여, 정작 눈이 좇아야 할
 * 숫자가 묻힌다. 위 구간은 상한, 아래 구간은 하한을 적으면 "여기까지 움직인다고
 * 본 사람들" 로 위아래가 같은 뜻이 되고 기준선을 사이에 두고 대칭이 된다.
 *
 * 아래쪽에 상한을 적으면 기준선 바로 밑 줄이 `0%` 가 되어, 바로 위 기준선과
 * 같은 값을 가리키는 줄이 생긴다.
 */
function bucketLabel(b: PredictionBucket) {
  if (b.fromPct === null) return `${b.toPct}% 미만`
  if (b.toPct === null) return `+${b.fromPct}% 초과`
  const edge = b.fromPct >= 0 ? b.toPct : b.fromPct
  return edge > 0 ? `+${edge}%` : `${edge}%`
}

/** 구간의 실제 가격대. 열린 구간은 한쪽만 적는다 */
function priceLabel(b: PredictionBucket) {
  if (b.fromPrice === null) return `${won(b.toPrice as number)}원 미만`
  if (b.toPrice === null) return `${won(b.fromPrice)}원 이상`
  return `${won(b.fromPrice)} ~ ${won(b.toPrice)}`
}

/* 전일 종가 자리에 굵은 기준선을 그었다가 뺐다(2026-09-10). 한 줄만 두꺼우면
   그 줄이 더 중요한 값처럼 읽힌다. 종가가 어디인지는 등락률 부호가 +5% 에서
   -5% 로 넘어가는 자리와 표 위 말풍선으로 알 수 있다. */

function Rows({ d }: { d: PredictionDistribution }) {
  /* 비싼 쪽이 위로 오게 뒤집는다 — 호가창과 같은 방향이다 */
  const rows = [...d.buckets].reverse()
  const peak = Math.max(1, ...rows.map((b) => b.count))

  return (
    <div className="pd-book">
      {/* 기준가 안내를 표 아래에서 제목 바로 밑으로 올렸다(2026-09-10). 0% 가
          무엇인지 모르고 표를 읽으면 등락률이 무엇 대비인지 알 수 없어, 표보다
          먼저 와야 하는 문장이다. 개미가 말해 주는 모양으로 둔다. */}
      <div className="pd-say">
        {/* 표정 있는 얼굴로 바꿨다(2026-09-17). 기준가를 짚어 주는 말이라
            웃는 얼굴이 맞다 — 값이 좋다 나쁘다를 말하는 자리가 아니다 */}
        <img className="pd-face" src="/assets/character/face_ant/face-smile.webp"
             alt="" aria-hidden="true" width="42" height="42" />
        <p className="pd-bubble">
          등락률은 전일 종가 <b className="num">{won(d.basePrice)}원</b>({dot(d.asOf)}) 기준입니다.
          개별 예측은 작성자 채널에서 볼 수 있습니다.
        </p>
      </div>

      <p className="pd-total num">
        판정 대기 <b>{won(d.total)}</b>건
        {/* 한 건도 없으면 표가 점으로만 채워진다. 그게 고장이 아니라 "아직 아무도
            안 걸었다" 라는 뜻임을 여기서 말해 준다 — 빈 표만 두면 불러오다 만
            화면처럼 보인다. */}
        {d.total === 0 && (
          <span className="pd-first">아직 아무도 걸지 않았습니다. 첫 예측을 등록해 보세요.</span>
        )}
      </p>

      {/* 가운데 등락률을 축으로 상승은 왼쪽, 하락은 오른쪽으로 뻗는다.
          한 줄은 상승이거나 하락이라 반대쪽 칸은 늘 비고, 그 덕에 위아래로
          두 덩어리가 갈려 "어느 쪽이 몰렸나" 가 한눈에 들어온다. */}
      <table className="pd-table">
        <caption className="hm-sr">
          목표가 구간별 예측 수. 전일 종가 {won(d.basePrice)}원({dot(d.asOf)}) 기준.
          상승 예측은 왼쪽, 하락 예측은 오른쪽 막대입니다.
        </caption>
        <colgroup>
          <col className="pd-c-price" />
          <col className="pd-c-side" />
          <col className="pd-c-pct" />
          <col className="pd-c-side" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col" className="pd-h-price">목표가</th>
            <th scope="col" className="pd-h-up">상승</th>
            <th scope="col" className="pd-h-pct">등락률</th>
            <th scope="col" className="pd-h-down">하락</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((b, i) => {
            const up = b.fromPct === null ? false : b.fromPct >= 0
            const width = `${(b.count / peak) * 100}%`
            /* 막대는 장식이라 읽어 주지 않는다 — 숫자를 늘 함께 적는다. 건수가 0 이면
               막대를 그리지 않는다: 폭 0 짜리 조각이 남으면 한 점으로 보여 "한 명 있음"
               처럼 읽힌다.

               둘을 **셀 안쪽 상자**에 넣는 이유 — td 에 직접 display:flex 를 주면 그 칸이
               표 레이아웃에서 빠져, 아래 테두리가 행 높이가 아니라 flex 상자 높이에
               걸린다(옆 칸과 줄이 어긋난다). */
            const bar = (
              <div className="pd-side-in">
                {b.count > 0 && (
                  <span
                    className={`pd-bar is-${up ? 'up' : 'down'}`}
                    /* --pd-i 는 자라나는 순서다. 위에서 아래로 차례로 뻗게 해
                       한꺼번에 튀어나오지 않는다(CSS 의 animation-delay). */
                    style={{ width, ['--pd-i' as string]: String(i) }}
                    aria-hidden="true"
                  />
                )}
                <b className="num">{b.count > 0 ? won(b.count) : '·'}</b>
              </div>
            )
            return (
              <tr
                key={`${b.fromPct ?? 'min'}:${b.toPct ?? 'max'}`}
                className={`pd-row${b.count === 0 ? ' is-empty' : ''}`}
              >
                <td className="pd-price num">{priceLabel(b)}</td>
                <td className="pd-side is-up">{up && bar}</td>
                <th scope="row" className={`pd-pct num is-${up ? 'up' : 'down'}`}>
                  {bucketLabel(b)}
                </th>
                <td className="pd-side is-down">{!up && bar}</td>
              </tr>
            )
          })}
        </tbody>
      </table>

    </div>
  )
}

type Props = {
  code: string
  span?: 4 | 5 | 6 | 7 | 8 | 12
  /** 카드 제목. 정보 탭에서는 "예측 현황" 이라는 이름으로 같은 표를 쓴다 */
  title?: string
  /* 접기 — 부모가 상태를 들고 있다(Block.tsx 의 규칙). 둘 다 주지 않으면
     접히지 않는 카드다. 접혀 있는 동안에는 부르지도 않는다. */
  open?: boolean
  onToggle?: () => void
}

export default function PredictionDepth({
  code, span = 6, title = '이 종목의 예측 분포', open, onToggle,
}: Props) {
  /* 기준가는 응답의 basePrice 다. 목업 때는 화면 머리의 전일 종가를 인자로 건네고
     그 값이 들어올 때 다시 불렀는데, 서버가 기준가를 정하므로 종목만 보면 된다. */
  const q = useBlock(() => getPredictionDistribution(code), [code], open ?? true)

  return (
    <Panel title={title} span={span} open={open} onToggle={onToggle}>
      {q.error && <ErrorState error={q.error} onRetry={q.retry} inline />}
      {!q.error && q.loading && <p className="pf-none">불러오는 중입니다…</p>}
      {/* 한 건도 없어도 **표는 그린다**(2026-09-16). 전에는 문단 한 줄로 갈음했는데,
          그러면 이 카드가 옆 칸 높이만큼 늘어난 채 속이 비어 무엇을 보는 자리인지
          알 수 없었다. 구간은 예측이 없어도 정해져 있다 — 서버가 기준가만 있으면
          count 0 인 칸들을 그대로 내려 준다. 막대만 없는 같은 표다.

          buckets 가 빈 것은 다른 사정이다. 서버가 기준가를 못 구했을 때(거래정지 등)
          "구간을 지어내지 않는다" 며 빈 배열을 준다. 그때는 그릴 눈금이 없다. */}
      {!q.error && !q.loading && q.data && (
        q.data.buckets.length === 0
          ? <p className="pf-none">전일 종가가 없어 예측 구간을 나눌 수 없습니다.</p>
          : <Rows d={q.data} />
      )}
    </Panel>
  )
}
