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

for (const [i, [src, out, width, quality]] of JOBS.entries()) {
  const url = `http://127.0.0.1:${PORT}/${i}`
  const data = await evalJs(`(async () => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.src = ${JSON.stringify(url)}
    await img.decode()
    const w = Math.min(${width}, img.naturalWidth)
    const h = Math.round(img.naturalHeight * (w / img.naturalWidth))
    const c = document.createElement('canvas')
    c.width = w; c.height = h
    /* 알파를 지운 채로 그리지 않는다 — 누끼가 검은 판이 된다 */
    const g = c.getContext('2d')
    g.imageSmoothingQuality = 'high'
    g.drawImage(img, 0, 0, w, h)
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
