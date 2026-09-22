/* M-07 이미지 업로드. API 명세서 §업로드 · 설계서 §3 M · §4 F-03 · H-04 · §6.

   POST /uploads   multipart(file, purpose) + Idempotency-Key → 201 UploadResponse
   GET  /uploads/{fileId}                                     → 이미지 바이트

   백엔드 ANT-COMMUNITY-06 으로 둘 다 열려 있다. 목업이 아니다.

   ── 티켓의 presigned URL 방식은 쓰지 않는다 ─────────────────
   지라 -191 은 "presigned URL 발급 → 스토리지 직접 PUT → 완료 통지" 3단계를 적고 있지만,
   구현된 백엔드는 **서버로 직접 multipart** 를 받는다. 발급 엔드포인트가 없다.
   UploadController 주석이 이유를 적어 두었다 — "저장 위치가 로컬 볼륨이라 서버가 직접
   내려보낸다. CDN 으로 옮기면 이 엔드포인트는 사라지고 url 이 외부 주소를 담는다."

   그래서 단계가 하나다. 티켓의 "발급 실패와 전송 실패를 구분" 은 그 형태로는 성립하지
   않으므로, 대신 **로컬 검증 실패 · 전송 실패 · 서버 거절** 셋을 구분한다(모달이 그린다).
   CDN 으로 옮겨 발급 단계가 생기면 이 파일에 한 단계를 더한다.

   ── 왜 fetch 가 아니라 XHR 인가 ─────────────────────────────
   설계 제약이 진행률을 요구하는데 fetch 에는 업로드 진행 이벤트가 없다(응답 스트림만 읽는다).
   XHR 의 upload.onprogress 가 유일한 길이다. 그래서 api/client.ts 의 request 를 쓰지 못하고,
   그 파일이 하는 일(토큰 헤더 · 401 재발급 후 1회 재시도 · 오류 계약 변환)을 여기서 되짚는다. */
import { API_BASE, getAccessToken, refreshAccessToken } from './client'
import { ApiError, CLIENT_ERROR_CODE, toApiError } from './errors'
import { idempotencyKey, releaseIdempotencyKey } from './idempotency'

/** 서버 UploadFile.Purpose 와 짝이다. 화면마다 무엇에 쓰는 그림인지 서버가 알아야 한다. */
export const UPLOAD_PURPOSES = ['AD', 'REPORT', 'POST', 'PROFILE'] as const
export type UploadPurpose = (typeof UPLOAD_PURPOSES)[number]

/** 서버 UploadResponse 와 짝이다. */
export type UploadedImage = {
  fileId: string
  /** 지금은 GET /uploads/{fileId}. CDN 으로 옮기면 외부 주소가 된다 */
  url: string
  width: number
  height: number
  bytes: number
  mime: string
}

/* ── 서버가 거절하는 조건. 같은 값을 여기서도 재 두어 헛업로드를 막는다 ──
   서버 검사를 대신하는 것이 아니다 — 10MB 를 다 올린 뒤 400 을 받으면 사용자는 기다린 만큼을
   잃는다. 서버 UploadProperties 의 기본값과 같은 수를 쓰고, 어긋나면 서버가 최종 판정한다. */

/** 10MB. 서버 app.uploads.max-bytes 와 같다 */
export const MAX_BYTES = 10 * 1024 * 1024

/** 8천만 화소. 서버 maxPixels 와 같다 — 용량이 작아도 픽셀이 크면 디코딩에서 터진다 */
export const MAX_PIXELS = 80_000_000

/**
 * 서버 ImageProbe 가 매직 바이트로 알아보는 세 가지.
 *
 * **이 목록으로 거절하지 않는다**(QA 09-21). GIF·BMP·AVIF 는 서버가 안 받지만 브라우저는 읽는다 —
 * 읽히면 캔버스로 다시 뽑아 이 셋 중 하나로 바꿔 보낸다. 목록의 쓸모는 "그대로 보내도 되나" 를
 * 가르는 것이다({@link needsRedraw}).
 */
export const ACCEPTED_MIME = ['image/png', 'image/jpeg', 'image/webp'] as const

/** 파일 선택 창에 넘길 값. 읽히는지는 골라 봐야 안다 — 여기서 좁히면 GIF 를 고를 길이 없다 */
export const ACCEPT_ATTR = 'image/*'

/** 서버가 그대로 받는 형식인가 */
export function isServerFormat(file: File) {
  return ACCEPTED_MIME.includes(file.type as (typeof ACCEPTED_MIME)[number])
}

/**
 * 배너 가로세로비 제약. 서버 adAspectRatio · adAspectTolerance 와 같다.
 *
 * AD 에만 걸린다 — 노출 자리가 고정이라 어긋난 그림은 화면에서 잘리거나 늘어나는데,
 * 그때는 이미 광고비를 받은 뒤라 되돌릴 수단이 없다(서버 UploadService 주석).
 */
export const AD_ASPECT = { ratio: 4, tolerance: 0.05 } as const

export type AspectRule = { ratio: number; tolerance: number }

/** 화면별 제약. 모달이 파라미터로 받는다(설계 제약) */
/** 프로필 사진은 정사각이다 — 동그랗게 잘라 그리므로 가로세로가 다르면 한쪽이 잘린다 */
export const PROFILE_ASPECT: AspectRule = { ratio: 1, tolerance: 0.05 }

export const ASPECT_BY_PURPOSE: Partial<Record<UploadPurpose, AspectRule>> = {
  AD: AD_ASPECT,
  PROFILE: PROFILE_ASPECT,
}

/**
 * 용도별 최대 변(px). 이보다 크면 줄여서 올린다.
 *
 * 프로필은 화면에서 **56px 언저리**로 그린다(.ch-avatar · .mp-avatar). 2배 화면을 셈해도
 * 112px 이면 충분한데, 나중에 더 크게 쓰는 자리가 생겨도 견디도록 256 으로 둔다. 휴대폰
 * 사진 한 장이 4000px·4MB 인데 그대로 두면 56px 자리에 쓰려고 그 용량을 계속 저장한다.
 *
 * 목록에 없는 용도는 줄이지 않는다 — 광고 배너는 크게 걸리고, 리포트 본문 그림은 사용자가
 * 확대해서 본다. 무엇을 줄여도 되는지는 그리는 자리가 정한다.
 */
export const MAX_EDGE_BY_PURPOSE: Partial<Record<UploadPurpose, number>> = {
  PROFILE: 256,
}

/* ── 로컬 검증 ─────────────────────────────────────────── */

/**
 * 디코딩 전에 알 수 있는 것. **이제 SVG 하나만 막는다**(QA 09-21).
 *
 * 전에는 형식 목록과 용량으로 여기서 거절했다. 지금은 읽히는 그림이면 어떻게든 넣는다 —
 * 형식이 다르면 바꾸고, 크면 줄인다({@link cropToAspect}). 그래서 관문이 "목록에 있나" 에서
 * "브라우저가 읽나"({@link measure})로 옮겨 갔다.
 *
 * SVG 만 남긴 이유 — `<img>` 는 SVG 도 읽지만 고유 크기가 없는 SVG 는 0×0 이나 300×150 으로
 * 나온다. 무엇이 올라갈지 사용자도 우리도 모른다.
 */
export function precheck(file: File): ApiError | null {
  if (file.type === 'image/svg+xml' || /\.svgz?$/i.test(file.name)) {
    return new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_IMAGE_UNREADABLE, message: 'svg' }, 0)
  }
  return null
}

/**
 * 디코딩해야 알 수 있는 것. 실패하면 이미지가 아니거나, 깨졌거나, 이 브라우저가 못 읽는 형식이다
 * (HEIC·TIFF). 셋을 구분할 방법이 없어 문구 하나로 모은다.
 */
export function measure(file: File): Promise<{ width: number; height: number; previewUrl: string }> {
  return new Promise((resolve, reject) => {
    const previewUrl = URL.createObjectURL(file)
    const img = new Image()
    const fail = () => {
      URL.revokeObjectURL(previewUrl)
      reject(new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_IMAGE_UNREADABLE, message: 'decode failed' }, 0))
    }
    img.onload = () => {
      if (img.naturalWidth > 0 && img.naturalHeight > 0) {
        resolve({ width: img.naturalWidth, height: img.naturalHeight, previewUrl })
      } else {
        fail()
      }
    }
    img.onerror = fail
    img.src = previewUrl
  })
}

/**
 * 원본을 그대로 보내면 안 되는 경우 — 서버가 거절할 것을 알고 있을 때다.
 *
 * 형식이 다르거나, 10MB·8천만 화소를 넘거나, 쓰는 자리보다 크다. 전에는 앞의 셋을 거절했다.
 * 사용자가 할 수 있는 일이 "다른 앱에서 바꿔 오기" 뿐인데 그건 캔버스가 대신 할 수 있다.
 */
export function needsRedraw(file: File, size: { width: number; height: number }, maxEdge?: number) {
  return !isServerFormat(file)
    || file.size > MAX_BYTES
    || size.width * size.height > MAX_PIXELS
    || (maxEdge != null && Math.max(size.width, size.height) > maxEdge)
}

/**
 * 자르기 UI 로 다룰 수 없을 만큼 가늘다. 50×100000 은 미리보기에서 0.16px 폭이 된다 —
 * 네모를 잡을 수가 없다. 이런 그림은 자르기 단계를 건너뛰고 가운데를 잡는다.
 *
 * 값의 근거: 미리보기는 너비 430 안팎 · 높이 320 이고 네모의 최소 변이 48px 이다(CropStage).
 * 짧은 변이 48px 아래로 그려지는 경계가 가로 9:1 · 세로 1:6.5 쯤이다.
 */
export function tooThinToCrop(size: { width: number; height: number }) {
  return size.width / size.height > 9 || size.height / size.width > 6.5
}

/** 화소 수. 다시 그린 **결과**를 잰다 — 원본이 넘는 것은 거절하지 않고 줄인다(needsRedraw). */
export function checkPixels(width: number, height: number): ApiError | null {
  if (width * height > MAX_PIXELS) {
    return new ApiError({ code: 'IMAGE_TOO_LARGE', message: 'local', field: 'file' }, 400)
  }
  return null
}

/** 디코딩 뒤 검사. 자르기가 제대로 됐는지 확인하는 마지막 관문으로도 쓴다. */
export function checkSize(width: number, height: number, aspect?: AspectRule): ApiError | null {
  const pixels = checkPixels(width, height)
  if (pixels) return pixels
  if (aspect && !fitsAspect(width, height, aspect)) {
    return new ApiError({ code: 'INVALID_IMAGE_RATIO', message: 'local', field: 'file' }, 400)
  }
  return null
}

export function fitsAspect(width: number, height: number, aspect: AspectRule) {
  return Math.abs(width / height - aspect.ratio) <= aspect.tolerance
}

/* ── 비율 맞추기 ───────────────────────────────────────
   비율이 어긋나면 거절하지 않고 **가운데를 기준으로 잘라서** 넣는다.

   왜 거절하지 않는가 — 사용자가 할 수 있는 일이 "밖에서 잘라 오기" 뿐인데, 그건 이 화면이
   대신 할 수 있는 일이다. 서버도 자르는 쪽을 전제하고 오차를 두었다
   (UploadProperties: "사용자가 자른 이미지는 정수 픽셀이라 정확히 나누어떨어지지 않는다").

   왜 가운데인가 — 어디를 남길지 물으려면 자르기 UI 가 필요하고, 그건 이 티켓 범위가 아니다.
   배너는 가운데에 글자를 두는 것이 보통이라 가장 덜 틀린다. **대신 잘랐다는 사실과 결과
   크기를 반드시 화면에 보여준다** — 모르는 사이에 그림이 바뀌면 그게 더 나쁘다.

   늘리지 않는다. 4:1 자리에 1:1 그림을 늘려 넣으면 얼굴도 글자도 뭉개진다. */

/** 다시 그린 결과. 자를 것도 줄일 것도 없으면 아예 부르지 않는다 */
export type CroppedImage = {
  file: File
  width: number
  height: number
  /** 손대기 전 크기. 화면이 "1600×900 → 1200×300" 을 보여줄 수 있게 */
  from: { width: number; height: number }
  /**
   * 무엇을 했는지. 화면 문구가 갈린다.
   * converted 는 형식을 바꿨다는 뜻(GIF → WebP), stretched 는 너무 작아 **늘려** 넣었다는 뜻이다.
   */
  did: { cropped: boolean; shrank: boolean; converted: boolean; stretched: boolean }
}

/**
 * 잘라 낼 원본 영역(원본 픽셀 기준). 자르기 UI 가 만들고 {@link cropToAspect} 가 받는다.
 *
 * 화면 좌표가 아니라 원본 좌표인 이유 — 미리보기는 모달 너비에 따라 크기가 달라지는데,
 * 그 배율을 여기까지 들고 오면 창 크기가 바뀔 때마다 자른 자리가 흔들린다.
 */
export type CropRect = { sx: number; sy: number; sw: number; sh: number }

/** 손대지 않았을 때의 자리. 넓으면 좌우를, 높으면 위아래를 덜어 낸다 — 남는 변은 그대로 둔다 */
export function centerRect(size: { width: number; height: number }, aspect?: AspectRule): CropRect {
  const { width, height } = size
  if (!aspect) return { sx: 0, sy: 0, sw: width, sh: height }
  const wide = width / height > aspect.ratio
  const sw = wide ? Math.round(height * aspect.ratio) : width
  const sh = wide ? height : Math.round(width / aspect.ratio)
  return { sx: Math.round((width - sw) / 2), sy: Math.round((height - sh) / 2), sw, sh }
}

/**
 * 원본 밖을 가리키지 않게 민다. 드래그·확대 계산에 반올림이 섞이면 1~2px 이 밖으로
 * 나가는데, 그대로 drawImage 에 넘기면 가장자리가 투명하게 비어 나온다.
 */
function clampRect(rect: CropRect, size: { width: number; height: number }): CropRect {
  const sw = Math.max(1, Math.min(Math.round(rect.sw), size.width))
  const sh = Math.max(1, Math.min(Math.round(rect.sh), size.height))
  return {
    sw,
    sh,
    sx: Math.min(Math.max(0, Math.round(rect.sx)), size.width - sw),
    sy: Math.min(Math.max(0, Math.round(rect.sy)), size.height - sh),
  }
}

/**
 * 캔버스가 다시 뽑아낼 수 있는 형식. 그 외(GIF·BMP·AVIF, 형식 없음)는 **webp** 로 바꾼다 —
 * 알파를 지키면서 png 보다 훨씬 작다(12MP 사진을 png 로 뽑으면 20MB 가 넘는다).
 * webp 인코더가 없는 브라우저(Safari)는 toBlob 이 조용히 png 를 준다. 그래서 요청한 형식이
 * 아니라 **나온 blob.type 을 믿는다.**
 */
const ENCODABLE = ['image/png', 'image/jpeg', 'image/webp']

/**
 * 용량이 넘칠 때 긴 변을 이 순서로 줄여 가며 다시 뽑는다. 첫 칸(Infinity)은 "줄이지 않고".
 * 품질을 떨어뜨리지 않고 크기를 줄이는 이유 — 흐려진 큰 그림보다 선명한 작은 그림이 낫고,
 * 본문·배너 어디에도 4096px 넘게 그리는 자리가 없다.
 */
const EDGE_STEPS = [Infinity, 4096, 2560, 1600]

/**
 * 올리기 좋게 다시 그린다 — 가운데를 기준으로 자르고, 너무 크면 줄인다.
 *
 * 둘을 한 번에 하는 이유는 캔버스를 한 번만 거치기 위해서다. 자른 뒤 다시 줄이면 두 번
 * 다시 뽑게 되고, 손실 형식(jpeg)에서는 그만큼 더 뭉갠다.
 *
 * 원본 형식을 지킨다 — png 를 jpeg 로 바꾸면 투명한 배경이 검게 칠해진다. 다만 다시 뽑는
 * 과정이라 **바이트 수는 원본과 다르다**(보통 줄지만 늘 그렇지는 않다). 그래서 여기서
 * 용량을 한 번 더 본다. 서버가 최종 판정자인 것은 그대로다.
 *
 * @param aspect 없으면 자르지 않는다
 * @param maxEdge 없으면 줄이지 않는다. **늘리지는 않는다** — 작은 그림을 키우면 뭉갠다
 * @param rect 사용자가 고른 자리. 없으면 가운데를 잡는다 — 자르기 UI 를 열지 않는 화면과
 *   손대지 않고 넘긴 경우가 예전과 똑같이 동작해야 한다
 */
export async function cropToAspect(
  file: File,
  size: { width: number; height: number },
  aspect?: AspectRule,
  maxEdge?: number,
  rect?: CropRect,
): Promise<CroppedImage> {
  const { width, height } = size
  const clamped = clampRect(rect ?? centerRect(size, aspect), size)

  /* 비율이 있으면 **원본에서 뜨는 자리부터 정확한 비율로** 다시 잡는다(QA 09-21).
     clampRect 는 가로·세로를 따로 반올림한다. 큰 그림에서는 티가 안 나지만 50px 폭이면
     50×13 = 3.85:1 이 되어 허용 오차(0.05)를 넘고, 우리가 자른 결과를 우리가 거절했다.
     drawImage 는 소수 좌표를 받으므로 자리는 소수로 두고, 정수여야 하는 것은 출력 크기뿐이다. */
  const w = aspect ? Math.min(clamped.sw, clamped.sh * aspect.ratio) : clamped.sw
  const h = aspect ? w / aspect.ratio : clamped.sh
  const sx = clamped.sx + (clamped.sw - w) / 2
  const sy = clamped.sy + (clamped.sh - h) / 2

  /** 긴 변 상한을 받아 출력 크기를 낸다. 비율이 있으면 세로를 먼저 정하고 가로는 그 배수다 —
      그래야 결과가 **언제나 정확히 r:1** 이라 서버의 오차 검사와 다툴 일이 없다. */
  const sizeFor = (edge: number) => {
    const byEdge = Math.min(1, edge / Math.max(w, h))
    const byPixels = Math.min(1, Math.sqrt(MAX_PIXELS / (w * h)))
    const scale = Math.min(byEdge, byPixels)
    if (!aspect) return { outW: Math.max(1, Math.round(w * scale)), outH: Math.max(1, Math.round(h * scale)), scale }
    /* 1e-6 은 부동소수 오차 몫이다. 6120 × (256/6120) 이 255.9999… 로 나와 내림하면 255 가 된다 */
    const outH = Math.max(1, Math.floor(h * scale + 1e-6))
    return { outW: Math.round(outH * aspect.ratio), outH, scale }
  }

  const wanted = ENCODABLE.includes(file.type) ? file.type : 'image/webp'

  /* from-image 를 명시한다. measure() 의 <img> 는 EXIF 회전을 적용해 크기를 재는데
     createImageBitmap 의 기본값은 브라우저마다 달라, 세워 찍은 휴대폰 사진에서 가로·세로가
     뒤바뀌어 엉뚱한 자리를 자를 수 있다. */
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  } catch {
    /* <img> 는 읽었는데 여기서 못 읽는 경우가 둘이다. 잘린 파일(앞부분만 그려진다)과,
       메모리에 다 펼칠 수 없을 만큼 큰 그림. DOMException 을 그대로 올리면 화면이
       "잠시 후 다시 시도" 를 띄우는데 같은 파일은 몇 번을 해도 같다. */
    const tooBig = width * height > MAX_PIXELS
    throw tooBig
      ? new ApiError({ code: 'IMAGE_TOO_LARGE', message: 'decode failed', field: 'file' }, 400)
      : new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_IMAGE_UNREADABLE, message: 'bitmap failed' }, 0)
  }

  try {
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_UPLOAD_FAILED, message: 'no 2d context' }, 0)

    const draw = async (outW: number, outH: number, mime: string) => {
      canvas.width = outW
      canvas.height = outH
      /* 크기를 바꾸면 컨텍스트 상태가 초기화된다. 매번 다시 켠다.
         크게 줄일 때 기본 보간은 계단이 진다. 브라우저의 고품질 축소를 켠다 */
      ctx.imageSmoothingEnabled = true
      ctx.imageSmoothingQuality = 'high'
      ctx.drawImage(bitmap, sx, sy, w, h, 0, 0, outW, outH)
      /* jpeg·webp 는 품질을 정해 주지 않으면 브라우저마다 다르다. 0.92 는 눈으로 구분이
         거의 안 되면서 용량이 크게 붇지 않는 선이다. png 는 무손실이라 이 값을 무시한다. */
      const blob = await new Promise<Blob | null>((res) => canvas.toBlob(res, mime, 0.92))
      if (!blob) {
        throw new ApiError({ code: CLIENT_ERROR_CODE.CLIENT_UPLOAD_FAILED, message: 'encode failed' }, 0)
      }
      return blob
    }

    /* 10MB 를 넘으면 거절하지 않고 줄여 본다(QA 09-21). 용도가 정한 maxEdge 가 있으면 거기서
       시작한다. 같은 크기가 또 나오는 단계는 건너뛴다 — 256px 프로필을 네 번 뽑을 이유가 없다. */
    let out = sizeFor(maxEdge ?? Infinity)
    let blob: Blob | null = null
    let tried = ''
    for (const step of EDGE_STEPS) {
      const next = sizeFor(Math.min(maxEdge ?? Infinity, step))
      const key = `${next.outW}x${next.outH}`
      if (key === tried) continue
      tried = key
      out = next
      blob = await draw(out.outW, out.outH, wanted)
      if (blob.size <= MAX_BYTES) break
    }
    /* 끝까지 줄여도 넘는 것은 잡음 많은 png 뿐이다. 마지막으로 webp 로 바꿔 본다 */
    if (blob && blob.size > MAX_BYTES && wanted !== 'image/webp') {
      blob = await draw(out.outW, out.outH, 'image/webp')
    }
    if (!blob || blob.size > MAX_BYTES) {
      throw new ApiError({ code: 'FILE_TOO_LARGE', message: 'after shrink', field: 'file' }, 400)
    }

    /* 이름과 lastModified 를 원본 그대로 둔다 — 사용자가 자기 파일로 알아보고,
       멱등 키 scope 가 이 둘을 쓰기 때문이다(같은 파일을 다시 골라도 같은 키가 나간다).
       형식을 바꿔도 이름은 그대로다(a.gif 안에 webp). 서버는 이름을 쓰지 않는다. */
    const cropped = new File([blob], file.name, { type: blob.type, lastModified: file.lastModified })
    return {
      file: cropped,
      width: out.outW,
      height: out.outH,
      from: { width, height },
      did: {
        cropped: Math.round(w) !== width || Math.round(h) !== height,
        /* 크기가 아니라 배율로 가른다. 50px 폭을 4:1 정수에 맞추느라 48 이 된 것은 줄인 게 아니다 */
        shrank: out.scale < 1,
        converted: blob.type !== file.type,
        stretched: out.outW > w,
      },
    }
  } finally {
    bitmap.close()
  }
}

/* ── 경고 ──────────────────────────────────────────────
   막지 않고 **말해 준다**(QA 09-21). 거절하면 사용자는 왜 안 올라가는지 모른 채 멈춘다.
   넣어 주되 어떻게 보일지를 올리기 전에 알려 주면, 그대로 갈지 다른 그림을 고를지는
   사용자가 정한다. 문구는 화면(모달)이 갖는다 — 여기는 조건만 안다. */

export type UploadWarning = 'CROP_LOSS' | 'AD_SMALL' | 'STRETCHED' | 'TALL' | 'FLAT' | 'GIF_STILL'

/** 광고 배너가 이보다 낮으면 자리(홈 배너 max-height 178px)에서 늘어나 흐려진다 */
const AD_MIN_HEIGHT = 120

export function warningsFor(
  purpose: UploadPurpose,
  original: File,
  result: { width: number; height: number },
  cut?: CroppedImage,
  /** 자르기 UI 를 거치지 않고 가운데를 잡았다. 사용자가 고른 자리가 아니므로 잃는 양을 알린다 */
  autoCropped = false,
): UploadWarning[] {
  const out: UploadWarning[] = []
  if (cut?.did.stretched) out.push('STRETCHED')
  if (autoCropped && cut?.did.cropped) {
    /* 줄인 것은 빼고 자르기로 잃은 면적만 본다 — 원본에서 뜬 자리의 넓이로 잰다 */
    const r = ASPECT_BY_PURPOSE[purpose]?.ratio
    if (r) {
      const keptW = Math.min(cut.from.width, cut.from.height * r)
      const kept = (keptW * (keptW / r)) / (cut.from.width * cut.from.height)
      if (kept < 0.5) out.push('CROP_LOSS')
    }
  }
  if (purpose === 'AD' && !cut?.did.stretched && result.height < AD_MIN_HEIGHT) out.push('AD_SMALL')
  if (purpose === 'REPORT' || purpose === 'POST') {
    /* 본문 그림은 max-width:100% · height:auto 로 그린다(markdown.css .md-img). 세로로 긴 그림은
       그 길이 그대로 본문을 밀어내고, 납작한 그림은 가는 줄이 된다. CSS 로 높이를 자르지 않는
       이유 — 세로로 긴 카드뉴스(800×5000)까지 손톱만 해진다. */
    if (result.height / result.width > 4 && result.height > 3000) out.push('TALL')
    if (result.width / result.height > 10) out.push('FLAT')
  }
  if (original.type === 'image/gif' && cut?.did.converted) out.push('GIF_STILL')
  return out
}

/* ── 업로드 ────────────────────────────────────────────── */

/**
 * 멱등 키 scope. 명세 §1 의 필수 대상 8개 중 하나다.
 *
 * 파일마다 따로 둔다 — 한 화면에서 그림 넉 장을 잇달아 올릴 때(F-04 는 최대 4장) 같은 키가
 * 나가면 두 번째부터 첫 파일의 fileId 를 돌려받는다. 이름·크기가 같아도 다른 파일일 수 있어
 * lastModified 까지 섞는다.
 */
export const uploadScope = (file: File) =>
  `upload:${file.name}:${file.size}:${file.lastModified}`

type UploadOptions = {
  /** 0~1. XHR 업로드 진행률 */
  onProgress?: (ratio: number) => void
  /** 사용자가 취소할 수 있게 한다. abort 하면 CLIENT_UPLOAD_ABORTED 로 끝난다 */
  signal?: AbortSignal
}

/**
 * 한 장 올린다. 성공하면 서버가 만든 fileId 와 실제 크기가 온다.
 *
 * 401 이면 재발급 후 **한 번만** 다시 보낸다 — api/client.ts 의 request 와 같은 규칙이다.
 * 같은 멱등 키로 재전송하므로 서버가 두 번 저장하지 않는다.
 */
export async function uploadImage(
  file: File,
  purpose: UploadPurpose,
  options: UploadOptions = {},
): Promise<UploadedImage> {
  const scope = uploadScope(file)
  /* 실패하면 키를 놓지 않는다 — 전송이 끊긴 것인지 서버가 저장까지 마치고 응답만 못 준
     것인지 클라이언트는 알 수 없다. 같은 키로 재시도해야 중복 저장이 안 생긴다.
     그래서 여기에는 catch 가 없다(오류는 그대로 호출부로 간다). */
  const result = await send(file, purpose, scope, options)
  /* 확정됐으니 키를 놓는다. 같은 파일을 나중에 다시 올리면 새 키로 새 fileId 가 나온다 —
     사용자가 지웠다가 다시 넣는 것은 다른 요청이다. */
  releaseIdempotencyKey(scope)
  return result
}

function send(
  file: File,
  purpose: UploadPurpose,
  scope: string,
  options: UploadOptions,
  retried = false,
): Promise<UploadedImage> {
  return new Promise((resolve, reject) => {
    const form = new FormData()
    /* 세 번째 인자가 전송되는 파일명이다. 줄여 보낸다(QA 09-21) — 톰캣은 파트 헤더가 512바이트를
       넘으면 끊는데, 스프링이 그걸 용량 초과로 올려 535바이트짜리 그림에 "파일이 너무 큽니다" 가
       떴다. 한글 140자쯤이면 닿는다. 서버는 이름을 쓰지 않으므로(UUID 로 저장) 잃는 것이 없고,
       화면의 파일명과 멱등 키 scope 는 원래 이름 그대로다. */
    form.append('file', file, wireName(file.name))
    form.append('purpose', purpose)

    const xhr = new XMLHttpRequest()
    xhr.open('POST', `${API_BASE}/uploads`)
    // refresh 쿠키를 함께 보낸다 — fetch 의 credentials:'include' 와 같은 뜻
    xhr.withCredentials = true

    const token = getAccessToken()
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.setRequestHeader('Idempotency-Key', idempotencyKey(scope))
    /* Content-Type 은 넣지 않는다. FormData 를 주면 브라우저가 multipart 경계값까지
       붙여 만들어 주는데, 직접 쓰면 그 경계값이 빠져 서버가 본문을 못 가른다. */

    if (options.onProgress) {
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) options.onProgress?.(e.loaded / e.total)
      }
    }

    const abort = () => xhr.abort()
    options.signal?.addEventListener('abort', abort, { once: true })
    const cleanup = () => options.signal?.removeEventListener('abort', abort)

    xhr.onload = () => {
      cleanup()
      const payload = parse(xhr.responseText)

      if (xhr.status === 201 || xhr.status === 200) {
        resolve(payload as UploadedImage)
        return
      }

      /* 401 은 access 가 만료됐을 뿐일 수 있다. 재발급 후 한 번만 다시 보낸다.
         두 번째도 401 이면 세션이 죽은 것이라 그대로 올린다. */
      if (xhr.status === 401 && !retried) {
        refreshAccessToken()
          .then((ok) => (ok
            ? send(file, purpose, scope, options, true)
            : Promise.reject(toApiError(401, payload))))
          .then(resolve, reject)
        return
      }

      reject(toApiError(xhr.status, payload))
    }

    xhr.onerror = () => {
      cleanup()
      // 서버에 닿지 못했다. status 0 은 "서버까지 가지 않은 실패" 라는 이 앱의 규칙이다.
      reject(new ApiError(
        { code: CLIENT_ERROR_CODE.CLIENT_UPLOAD_FAILED, message: 'network' }, 0,
      ))
    }

    xhr.onabort = () => {
      cleanup()
      reject(new ApiError(
        { code: CLIENT_ERROR_CODE.CLIENT_UPLOAD_ABORTED, message: 'aborted' }, 0,
      ))
    }

    xhr.send(form)
  })
}

/** 전송용 이름. 확장자는 지키고 몸통을 80자로 자른다 — 한글 80자가 240바이트라 512 안에 든다 */
export function wireName(name: string) {
  const dot = name.lastIndexOf('.')
  const ext = dot > 0 && name.length - dot <= 10 ? name.slice(dot) : ''
  // 코드 포인트로 자른다. length 로 자르면 이모지가 반으로 갈려 깨진 글자가 나간다
  const base = Array.from(ext ? name.slice(0, dot) : name)
  return base.length <= 80 ? name : base.slice(0, 80).join('') + ext
}

/** 오류 응답이 JSON 이 아닐 수 있다(서블릿이 먼저 끊은 413 등). 그때는 null 로 넘긴다. */
function parse(text: string): unknown {
  try {
    return JSON.parse(text) as unknown
  } catch {
    return null
  }
}

/* ── 표시 도우미 ─────────────────────────────────────── */

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)}KB`
  /* 10.0MB 처럼 딱 떨어지는 값에서 .0 은 읽는 데 방해만 된다 */
  return `${(bytes / 1024 / 1024).toFixed(1).replace(/\.0$/, '')}MB`
}

/** "4:1" 처럼 읽히게. 비율 제약을 숫자로만 적으면 무엇을 맞춰야 하는지 모른다 */
export function formatAspect(rule: AspectRule) {
  return `${rule.ratio}:1`
}
