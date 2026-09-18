/* 스프라이트 시트 한 장을 canvas 에 프레임 단위로 그려 애니메이션으로 돌린다.

   <img> 를 여러 장 갈아 끼우지 않고 canvas 를 쓰는 이유는 시트가 한 장이라서다 —
   그림 파일이 하나뿐이라 요청도 하나고, 프레임을 바꾸는 일이 drawImage 한 번이라
   중간에 깜빡이지 않는다.

   **배경은 시트의 알파를 그대로 쓴다.** 매 프레임 clearRect 로 지우고 그리므로
   canvas 는 투명한 채로 남는다 — 뒤에 깔린 카드 배경이 그대로 비친다.

   canvas 는 width·height **속성**이 곧 고유 비율이다. 그래서 CSS 에서 width 만
   주고 height:auto 로 두면 <img> 와 똑같이 비율이 유지된다. 속성에 프레임 크기를
   그대로 넣는 건 그래서다 — 화면에 뜨는 크기는 CSS 가 정한다. */
import { useEffect, useRef } from 'react'

export type SpriteAnimationProps = {
  /** 스프라이트 시트 경로 */
  src: string
  /** 총 프레임 수. 마지막 행이 덜 찬 경우가 있어 행×열로 구하지 않는다 */
  frameCount: number
  /** 한 행에 몇 프레임인지 */
  columns: number
  frameWidth: number
  frameHeight: number
  /** 초당 프레임. 기본 12 — 데모 HTML 의 기본값과 같다 */
  fps?: number
  /** 끝에서 되감아 왕복시킨다. 기본 true.
      false 면 마지막 프레임 다음에 첫 프레임으로 바로 튄다 */
  pingpong?: boolean
  className?: string
  /** 읽어 주는 기기용 설명. 비우면 장식으로 보고 접근성 트리에서 감춘다 */
  label?: string
}

export default function SpriteAnimation({
  src, frameCount, columns, frameWidth, frameHeight,
  fps = 12, pingpong = true, className, label,
}: SpriteAnimationProps) {
  const ref = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const cv = ref.current
    if (!cv) return
    const ctx = cv.getContext('2d')
    if (!ctx) return

    /* 움직임을 줄여 달라고 한 사람에게는 첫 프레임만 세워 둔다. 계속 도는 그림은
       전정기관이 예민한 사람에게 멀미를 일으킨다. 미디어 쿼리는 설정이 바뀔 수
       있으므로 구독해 두고, 켜지면 루프를 멈추고 꺼지면 다시 돌린다. */
    const calm = window.matchMedia('(prefers-reduced-motion: reduce)')

    const im = new Image()
    let raf = 0
    let i = 0
    /** 왕복일 때 진행 방향. +1 이 정방향(frame_01 → frame_37) */
    let dir = 1
    let last = 0
    let ready = false

    const draw = () => {
      const col = i % columns
      const row = Math.floor(i / columns)
      ctx.clearRect(0, 0, cv.width, cv.height)
      ctx.drawImage(
        im,
        col * frameWidth, row * frameHeight, frameWidth, frameHeight,
        0, 0, cv.width, cv.height,
      )
    }

    /* 왕복은 양 끝 프레임을 **한 번만** 보여 준다. 끝에 닿자마자 방향을 뒤집으면
       마지막 프레임이 두 박자 머물러 거기서 잠깐 걸리는 것처럼 보인다 — 그래서
       뒤집은 방향으로 한 칸 더 가서 돌려준다.

       왕복이면 한 바퀴가 37 이 아니라 72 프레임(37 + 35)이다. fps 를 그대로 두면
       한 바퀴가 두 배로 길어지므로, 느리다고 느껴지면 fps 를 올린다. */
    const step = () => {
      if (!pingpong) { i = (i + 1) % frameCount; return }
      if (i + dir < 0 || i + dir >= frameCount) dir = -dir
      i += dir
    }

    /* rAF 는 탭이 숨으면 알아서 멈춘다. t - last 로 재는 대신 프레임을 세지 않는
       이유는 화면 주사율이 60Hz 가 아닐 수 있어서다 — 시간으로 재야 120Hz 에서도
       같은 속도로 돈다. */
    const loop = (t: number) => {
      raf = requestAnimationFrame(loop)
      if (!last) last = t
      if (t - last < 1000 / fps) return
      last = t
      step()
      draw()
    }

    const sync = () => {
      cancelAnimationFrame(raf)
      raf = 0
      if (!ready) return
      if (calm.matches) { i = 0; dir = 1; draw(); return }
      last = 0
      raf = requestAnimationFrame(loop)
    }

    im.onload = () => { ready = true; draw(); sync() }
    im.src = src
    calm.addEventListener('change', sync)

    return () => {
      cancelAnimationFrame(raf)
      calm.removeEventListener('change', sync)
      im.onload = null
    }
  }, [src, frameCount, columns, frameWidth, frameHeight, fps, pingpong])

  return (
    <canvas
      ref={ref}
      className={className}
      width={frameWidth}
      height={frameHeight}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    />
  )
}
