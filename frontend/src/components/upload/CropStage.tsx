/* 자르기 UI — 어디를 남길지 고른다.

   왜 만드는가 — 그전에는 비율이 정해진 화면(광고 8:5 · 프로필 1:1)에서 가운데를 잘랐다.
   배너는 가운데에 글자를 두는 것이 보통이라 가장 덜 틀리는 선택이었을 뿐, 사람 얼굴이
   왼쪽에 있는 사진은 얼굴이 잘려 나갔다.

   왜 라이브러리를 쓰지 않는가 — 필요한 것이 "네모를 옮기고 모서리로 키우기"뿐이다.
   pointer 이벤트로 끝나는 일에 의존성을 더할 이유가 없다.

   **그림은 고정, 네모가 움직인다.** 네모 밖은 어둡게 덮어 무엇을 버리는지 보이게 한다.

   비율 유무로 두 가지로 돈다.
     비율 있음(광고·프로필)  모서리를 끌면 반대쪽 모서리를 못으로 박고 비율을 지킨 채 커진다
     비율 없음(리포트 본문)  가로·세로가 따로 논다. **자르지 않고 원본 그대로 보낼 수도 있다** —
                             자르면 캔버스로 다시 뽑는 셈이라 PNG 는 오히려 용량이 늘 수 있다

   좌표계가 둘이라 헷갈리기 쉬워 적어 둔다.
     화면 좌표  미리보기 안의 px. 모달 너비에 따라 달라진다
     원본 좌표  파일이 실제로 가진 px. 잘라 낼 자리(CropRect)는 이쪽으로만 넘긴다
   배율 ds = 화면 / 원본. 그림 전체가 보이도록 맞춘 값이라 1 보다 작다. */
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { formatAspect, type AspectRule, type CropRect } from '../../api/uploads'

type Props = {
  file: File
  /** 원본 크기. measure() 가 잰 값 */
  size: { width: number; height: number }
  /** 없으면 자유 크롭이다. 그 화면은 자르지 않고 넘어갈 수도 있다 */
  aspect?: AspectRule
  onCancel: () => void
  onDone: (rect: CropRect) => void
  /** 비율이 없는 화면에서 "원본 그대로". 없으면 버튼을 그리지 않는다 */
  onSkip?: () => void
}

/** 미리보기 높이 상한(px). 세로로 긴 그림이 모달을 밀어내지 않게 */
const VIEW_MAX_H = 320

/** 네모의 최소 크기(화면 px). 이보다 작으면 모서리를 잡을 수가 없다 */
const MIN_SIDE = 48

type Box = { x: number; y: number; w: number; h: number }
type Corner = 'nw' | 'ne' | 'sw' | 'se'
type Drag =
  | { kind: 'move'; id: number; dx: number; dy: number }
  | { kind: 'resize'; id: number; corner: Corner; anchorX: number; anchorY: number }

export default function CropStage({ file, size, aspect, onCancel, onDone, onSkip }: Props) {
  const viewRef = useRef<HTMLDivElement>(null)
  const [viewW, setViewW] = useState(0)
  const [box, setBox] = useState<Box | null>(null)

  const [url] = useState(() => URL.createObjectURL(file))
  useEffect(() => () => URL.revokeObjectURL(url), [url])

  /* 미리보기 너비는 모달을 따른다. 창을 줄이면 같이 줄어야 하므로 관찰한다 */
  useLayoutEffect(() => {
    const el = viewRef.current
    if (!el) return
    const read = () => setViewW(el.clientWidth)
    read()
    const observer = new ResizeObserver(read)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  /* 그림 전체가 보이게 맞춘다 — 잘라 낼 자리를 고르는 화면에서 그림이 잘려 보이면 안 된다 */
  const ds = viewW > 0 ? Math.min(viewW / size.width, VIEW_MAX_H / size.height) : 0
  const shownW = size.width * ds
  const shownH = size.height * ds

  /* 손대기 전 자리. 비율이 있으면 가장 큰 네모를 가운데에(예전 자동 크롭과 같은 자리),
     없으면 그림 전체다 — 자르지 않겠다는 선택이 기본이어야 한다 */
  const initial = useCallback((): Box => {
    if (!aspect) return { x: 0, y: 0, w: shownW, h: shownH }
    const w = Math.min(shownW, shownH * aspect.ratio)
    const h = w / aspect.ratio
    return { x: (shownW - w) / 2, y: (shownH - h) / 2, w, h }
  }, [shownW, shownH, aspect])

  const view = box ?? initial()

  /** 그림 밖으로 나가지 않게 민다. 반올림이 섞여도 가장자리가 비지 않는다 */
  const clamp = useCallback(
    (next: Box): Box => {
      let w = Math.min(Math.max(MIN_SIDE, next.w), shownW)
      let h = Math.min(Math.max(MIN_SIDE, next.h), shownH)
      if (aspect) {
        // 비율이 잠겨 있으면 둘 중 작은 쪽에 맞춘다 — 한쪽만 자르면 비율이 깨진다
        w = Math.min(w, h * aspect.ratio)
        h = w / aspect.ratio
      }
      return {
        w, h,
        x: Math.min(Math.max(0, next.x), shownW - w),
        y: Math.min(Math.max(0, next.y), shownH - h),
      }
    },
    [shownW, shownH, aspect],
  )

  const drag = useRef<Drag | null>(null)

  /* 포인터를 이 요소에 묶어 둔다 — 손이 네모 밖으로 나가도 끌기가 이어진다.
     잡을 포인터가 없으면(합성 이벤트 등) 묶지 않고 그냥 진행한다 */
  function capture(e: React.PointerEvent) {
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // 묶지 못해도 끌기 자체는 view 의 pointermove 로 이어진다
    }
  }

  function localPoint(e: React.PointerEvent) {
    const rect = viewRef.current!.getBoundingClientRect()
    /* 그림이 미리보기 칸보다 좁을 수 있다(세로로 긴 그림). 그만큼 왼쪽 여백을 빼야
       손가락 위치와 네모가 어긋나지 않는다 */
    return {
      x: e.clientX - rect.left - (rect.width - shownW) / 2,
      y: e.clientY - rect.top,
    }
  }

  function startMove(e: React.PointerEvent) {
    e.stopPropagation()
    const p = localPoint(e)
    drag.current = { kind: 'move', id: e.pointerId, dx: p.x - view.x, dy: p.y - view.y }
    capture(e)
  }

  /* 모서리를 잡으면 **반대쪽 모서리가 못**이다. 그 점에서 얼마나 멀어졌는지가 새 크기가 된다 */
  function startResize(e: React.PointerEvent, corner: Corner) {
    /* 모서리를 잡았으면 네모 전체를 옮기는 부모 핸들러는 돌면 안 된다. **가장 먼저** 막는다 —
       뒤로 미루면 그 사이에서 예외가 나는 순간 부모가 덮어써 리사이즈가 이동으로 바뀐다 */
    e.stopPropagation()
    const anchorX = corner === 'nw' || corner === 'sw' ? view.x + view.w : view.x
    const anchorY = corner === 'nw' || corner === 'ne' ? view.y + view.h : view.y
    drag.current = { kind: 'resize', id: e.pointerId, corner, anchorX, anchorY }
    capture(e)
  }

  function onPointerMove(e: React.PointerEvent) {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    const p = localPoint(e)

    if (d.kind === 'move') {
      setBox(clamp({ ...view, x: p.x - d.dx, y: p.y - d.dy }))
      return
    }

    const dx = Math.abs(p.x - d.anchorX)
    const dy = Math.abs(p.y - d.anchorY)
    /* 비율이 잠겨 있으면 가로·세로 움직임을 **평균**낸다. 큰 쪽만 따르면 한 축만 안으로
       끌었을 때 네모가 꿈쩍도 하지 않고(다른 축이 그대로라 그 값이 이긴다), 작은 쪽만
       따르면 반대로 한 축만 밖으로 끌 때 안 커진다. 자유 크롭은 둘을 그대로 쓴다 */
    const w = aspect ? (dx + dy * aspect.ratio) / 2 : dx
    const h = aspect ? w / aspect.ratio : dy
    const left = d.corner === 'nw' || d.corner === 'sw'
    const top = d.corner === 'nw' || d.corner === 'ne'
    setBox(clamp({
      w, h,
      x: left ? d.anchorX - w : d.anchorX,
      y: top ? d.anchorY - h : d.anchorY,
    }))
  }

  function onPointerUp(e: React.PointerEvent) {
    if (drag.current?.id === e.pointerId) drag.current = null
  }

  /** 화면 좌표를 원본 좌표로 되돌린다. 네모의 왼쪽 위가 원본의 어디였는지가 sx·sy 다 */
  function done() {
    onDone({ sx: view.x / ds, sy: view.y / ds, sw: view.w / ds, sh: view.h / ds })
  }

  /** 원본 px 로 본 선택 크기. 얼마나 남는지 숫자로 보여 준다 */
  const outW = Math.round(view.w / (ds || 1))
  const outH = Math.round(view.h / (ds || 1))

  return (
    <div className="iu-crop">
      <div className="iu-crop-view" ref={viewRef} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
        {/* 드래그 중 브라우저 기본 이미지 끌기가 끼어들지 않게 막는다 */}
        <img src={url} alt="" draggable={false} style={{ width: shownW, height: shownH }} />

        {ds > 0 && (
          <div
            className="iu-crop-box"
            style={{ left: (shownW < viewW ? (viewW - shownW) / 2 : 0) + view.x, top: view.y, width: view.w, height: view.h }}
            onPointerDown={startMove}
          >
            {(['nw', 'ne', 'sw', 'se'] as const).map((corner) => (
              <span
                key={corner}
                className={`iu-crop-handle is-${corner}`}
                onPointerDown={(e) => startResize(e, corner)}
              />
            ))}
          </div>
        )}
      </div>

      <p className="iu-crop-hint">
        {aspect
          ? `네모를 끌어 옮기고, 모서리로 크기를 맞추세요 · ${formatAspect(aspect)} 로 잘립니다`
          : '네모를 끌어 옮기고, 모서리로 크기를 맞추세요 · 자르지 않으려면 원본 그대로를 누르세요'}
        <span className="num">{` · ${outW} × ${outH}`}</span>
      </p>

      <div className="iu-crop-actions">
        <button type="button" className="iu-btn" onClick={onCancel}>다시 고르기</button>
        {onSkip && (
          <button type="button" className="iu-btn" onClick={onSkip}>원본 그대로</button>
        )}
        <button type="button" className="iu-btn solid" onClick={done}>이 자리로 자르기</button>
      </div>
    </div>
  )
}
