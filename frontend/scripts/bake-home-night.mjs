/* 홈(B-01) 밤 시안 에셋을 웹용으로 굽는다 → public/assets/page_home/*.webp
 *
 *   node scripts/bake-home-night.mjs
 *
 * 왜 Chrome 인가 — 이 저장소의 다른 굽는 스크립트는 Python+Pillow 를 쓰는데
 * (build-city-night.py 등) 없는 PC 가 있다. Chrome 은 프론트 개발에 이미 깔려
 * 있고 canvas.toDataURL('image/webp') 로 같은 일을 한다. ImageMagick 의 convert 는
 * 윈도우에서 파일시스템 변환기와 이름이 겹치니 쓰지 않는다.
 *
 * 원본(ChatGPT … .png)은 시안 그대로 두고 여기서 만든 webp 만 화면이 쓴다.
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 5610
const CDP = 9350
const DIR = path.resolve('public/assets/page_home')
const UI = path.join(DIR, 'ui')
const DOCS = path.resolve('../docs/dashboard-components-assets')

/* [원본 절대경로, 결과 절대경로, 목표 가로(px), 품질]

   ui/ 아래 것들은 docs 시안에서 온다. 처음에는 SVG 였는데 그림이 마음에 들지 않아
   PNG 로 다시 그려 왔다(2026-09-22) — 일러스트가 장당 1MB 라 그대로는 못 쓴다.
   아이콘은 화면에서 쓰는 크기의 두 배로 굽는다(고해상도 화면 대비). */
const JOBS = [
  [path.join(DIR, 'ChatGPT Image 2026년 9월 22일 오전 09_04_36 (1).png'), path.join(DIR, 'city-night.webp'), 1920, 0.82],
  [path.join(DIR, 'ChatGPT Image 2026년 9월 22일 오전 09_04_36 (2).png'), path.join(DIR, 'hero-scout.webp'), 900, 0.9],

  /* 대회 배너 완성본 한 장. lilac 이 조립한 9:1 띠(hm-ev-*)는 넓은 자리 전용이라
     시안의 좁은 가운데 칸에서는 조각이 서로 파고든다 — 그 칸에서는 이 한 장을 쓴다. */
  [path.join(DIR, 'ChatGPT Image 2026년 9월 22일 오전 09_04_40 (7).png'), path.join(DIR, 'contest-banner.webp'), 1000, 0.9],

  /* 히어로 윗줄(손글씨 네온)과 버튼. 글자가 그림 안에 있는 조각이라 CSS 로는
     못 만든다 — 시안의 네온 번짐이 폰트로 흉내 내지지 않는다. */
  /* 다섯째 값 trim — 투명한 가장자리를 잘라낸다. 받은 PNG 는 글자 둘레에 빈칸이
     넓어(손글씨는 높이의 69%가 빈칸이었다) 그대로 쓰면 CSS 여백을 0 으로 해도
     글자 사이가 벌어진다. */
  [path.join(DIR, 'src-hero-eyebrow.png'), path.join(DIR, 'hero-eyebrow.webp'), 760, 0.92, true],
  [path.join(DIR, 'src-hero-cta.png'), path.join(DIR, 'hero-cta.webp'), 520, 0.92, true],

  [path.join(DOCS, 'illustration-coins-v2.png'), path.join(UI, 'coins.webp'), 520, 0.9],
  [path.join(DOCS, 'illustration-watchlist-v2.png'), path.join(UI, 'empty-watchlist.webp'), 380, 0.9],
  [path.join(DOCS, 'illustration-predictors-v2.png'), path.join(UI, 'empty-predictors.webp'), 380, 0.9],

  [path.join(DOCS, 'icon-wallet.png'), path.join(UI, 'icon-wallet.webp'), 64, 0.92],
  [path.join(DOCS, 'icon-star.png'), path.join(UI, 'icon-star.webp'), 64, 0.92],
  [path.join(DOCS, 'icon-trophy.png'), path.join(UI, 'icon-trophy.webp'), 64, 0.92],
  [path.join(DOCS, 'icon-bars-earned.png'), path.join(UI, 'icon-bars-earned.webp'), 80, 0.92],
  [path.join(DOCS, 'icon-bars-spent.png'), path.join(UI, 'icon-bars-spent.webp'), 80, 0.92],
  [path.join(DOCS, 'icon-clock.png'), path.join(UI, 'icon-clock.webp'), 40, 0.92],
]

/* 파일 이름에 한글과 공백이 섞여 있어 주소로 실어 보내면 인코딩이 어긋난다.
   번호로만 고르게 한다 — 서버가 JOBS 를 그대로 보고 연다. */
const server = http.createServer((q, s) => {
  const job = JOBS[Number(q.url.slice(1))]
  if (!job) { s.writeHead(404); return s.end() }
  const file = job[0]
  if (!fs.existsSync(file)) { s.writeHead(404); return s.end() }
  /* 캔버스가 더럽혀지지 않게 CORS 를 연다 — crossOrigin='anonymous' 로 받아야
     toDataURL 이 SecurityError 없이 돈다. 로컬 임시 서버라 * 로 둔다. */
  s.writeHead(200, { 'content-type': 'image/png', 'access-control-allow-origin': '*' })
  s.end(fs.readFileSync(file))
}).listen(PORT)

const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'bake-'))
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${CDP}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', 'about:blank',
], { stdio: 'ignore' })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let ws, id = 0
const waiting = new Map()

for (let i = 0; i < 40 && !ws; i++) {
  await sleep(300)
  try {
    const list = await fetch(`http://127.0.0.1:${CDP}/json/list`).then((r) => r.json())
    const page = list.find((t) => t.type === 'page')
    if (page) {
      ws = new WebSocket(page.webSocketDebuggerUrl)
      await new Promise((res) => { ws.onopen = res })
      ws.onmessage = (e) => {
        const m = JSON.parse(e.data)
        if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id) }
      }
    }
  } catch { /* 아직 안 떴다 */ }
}
if (!ws) { console.error('Chrome 에 못 붙었다'); process.exit(1) }

const send = (method, params = {}) => new Promise((res, rej) => {
  const n = ++id
  waiting.set(n, (m) => (m.error ? rej(new Error(`${method}: ${m.error.message}`)) : res(m.result)))
  ws.send(JSON.stringify({ id: n, method, params }))
})
const evalJs = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text)
  return r.result.value
}

await send('Page.enable')
await send('Runtime.enable')

for (const [i, [src, out, width, quality, trim, rect]] of JOBS.entries()) {
  /* 원본이 없으면 건너뛴다. 큰 PNG 는 굽고 지우는 것이 방침이라(레포에 4MB 를
     남길 이유가 없다) 이 목록에는 이미 없는 원본이 섞인다 — 죽는 대신 지나간다.
     다시 구워야 하면 그 원본만 제자리에 두고 이 스크립트를 돌리면 된다. */
  if (!fs.existsSync(src)) {
    console.log(`건너뜀  ${path.basename(out).padEnd(22)} 원본이 없다 (${path.basename(src)})`)
    continue
  }
  const url = `http://127.0.0.1:${PORT}/${i}`
  const data = await evalJs(`(async () => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.src = ${JSON.stringify(url)}
    await img.decode()

    /* 투명한 가장자리 잘라내기. 원본 크기에서 알파 경계를 찾은 뒤 그 사각형만
       옮겨 그린다. 문턱을 8 로 둔 것은 네온 번짐의 아주 옅은 끝까지 살리기
       위해서다 — 0 으로 두면 눈에 안 보이는 한 점 때문에 안 잘리고, 크게 두면
       빛이 잘려 글자에 테두리가 생긴다. */
    /* 여섯째 값 rect — 낱개 모음 한 장에서 한 조각만 오려낸다. 조각끼리 빛이
       이어져 있어 자동 탐지로는 안 갈라진다(연결 요소가 한 덩어리가 된다). */
    const rect = ${rect ? JSON.stringify(rect) : 'null'}
    let sx = rect ? rect[0] : 0, sy = rect ? rect[1] : 0
    let sw = rect ? rect[2] : img.naturalWidth, sh = rect ? rect[3] : img.naturalHeight
    if (${trim ? 'true' : 'false'}) {
      const m = document.createElement('canvas')
      m.width = sw; m.height = sh
      const mg = m.getContext('2d')
      mg.drawImage(img, sx, sy, sw, sh, 0, 0, sw, sh)
      const d = mg.getImageData(0, 0, sw, sh).data
      let top = -1, bottom = -1, left = sw, right = -1
      for (let y = 0; y < sh; y++) for (let x = 0; x < sw; x++) {
        if (d[(y * sw + x) * 4 + 3] > 8) {
          if (top < 0) top = y
          bottom = y; if (x < left) left = x; if (x > right) right = x
        }
      }
      if (top >= 0) { sx += left; sy += top; sw = right - left + 1; sh = bottom - top + 1 }
    }

    const w = Math.min(${width}, sw)
    const h = Math.round(sh * (w / sw))
    const c = document.createElement('canvas')
    c.width = w; c.height = h
    /* 알파를 지운 채로 그리지 않는다 — 누끼가 검은 판이 된다 */
    const g = c.getContext('2d')
    g.imageSmoothingQuality = 'high'
    g.drawImage(img, sx, sy, sw, sh, 0, 0, w, h)

    /* 오려낸 네모의 가장자리를 부드럽게 지운다. 조각끼리 빛이 닿아 있어 어디서
       잘라도 이웃의 옅은 빛이 조금 들어오는데, 그대로 두면 하늘 위에 **직사각형
       자국**이 보인다. 가장자리 몇 px 만 투명으로 녹이면 티가 나지 않는다.
       rect 로 오려낸 것에만 건다 — 온전한 누끼는 가장자리가 이미 투명하다. */
    if (rect) {
      const f = Math.max(4, Math.round(Math.min(w, h) * 0.05))
      g.globalCompositeOperation = 'destination-out'
      const fade = (x0, y0, x1, y1) => {
        const lg = g.createLinearGradient(x0, y0, x1, y1)
        lg.addColorStop(0, 'rgba(0,0,0,1)')
        lg.addColorStop(1, 'rgba(0,0,0,0)')
        g.fillStyle = lg
      }
      fade(0, 0, f, 0); g.fillRect(0, 0, f, h)
      fade(w, 0, w - f, 0); g.fillRect(w - f, 0, f, h)
      fade(0, 0, 0, f); g.fillRect(0, 0, w, f)
      fade(0, h, 0, h - f); g.fillRect(0, h - f, w, f)
      g.globalCompositeOperation = 'source-over'
    }
    return JSON.stringify({ w, h, ow: img.naturalWidth, oh: img.naturalHeight,
      url: c.toDataURL('image/webp', ${quality}) })
  })()`)
  const o = JSON.parse(data)
  const bytes = Buffer.from(o.url.split(',')[1], 'base64')
  fs.mkdirSync(path.dirname(out), { recursive: true })
  fs.writeFileSync(out, bytes)
  const before = fs.statSync(src).size
  console.log(`${out.padEnd(18)} ${o.ow}x${o.oh} -> ${o.w}x${o.h}  `
    + `${(before / 1024 / 1024).toFixed(2)}MB -> ${(bytes.length / 1024).toFixed(0)}KB`)
}

ws.close(); chrome.kill(); server.close(); await sleep(300)
