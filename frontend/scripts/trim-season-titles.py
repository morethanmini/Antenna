# -*- coding: utf-8 -*-
"""시즌 제목 그림(lockup)의 위·아래 빈 공간을 잘라낸다.

왜 필요했나
  page_train 의 *-title.webp 는 ChatGPT 가 뽑아 준 그대로라 글자 위아래에 투명한
  여백이 잔뜩 붙어 있었다. 재보면 장마다 다르다(아래 여백만 코로나 24px · 계엄 58 ·
  전쟁 97 · 엔캐리 121 · 트럼프 166, 원본 픽셀 기준). 화면(G-03 시즌 상세)은 그림을
  폭으로 맞춰 넣으므로 이 여백이 그대로 세로 높이를 먹고, 그 높이만큼 아래 조건
  카드가 화면 밖으로 밀렸다. 게다가 여백이 장마다 다르니 "카드를 몇 px 겹칠까" 를
  한 값으로 정할 수가 없었다 — 트럼프에 맞추면 코로나 글자가 잘리는 식이다.

무엇을 하나
  알파가 32 이상인 픽셀의 위아래 끝을 찾아 8px 만 남기고 자른다.
  **가로는 건드리지 않는다.** 화면이 그림을 폭 기준으로 늘리기 때문에, 가로를
  자르면 글자가 그만큼 커져 다섯 장의 크기 균형이 깨진다. 세로만 자르면 보이는
  글자 크기는 한 픽셀도 달라지지 않고 빈 공간만 사라진다.

  이미 잘린 그림은 그대로 둔다(여백이 8px 이하면 건너뛴다) — 여러 번 돌려도 안전하다.

실행
  python scripts/trim-season-titles.py
"""
from pathlib import Path
from PIL import Image

SRC = Path(__file__).resolve().parent.parent / 'public' / 'assets' / 'page_train'
ALPHA_MIN = 32   # 이보다 옅은 픽셀은 글자가 아니라 번짐으로 본다
PAD = 8          # 자른 뒤 남길 숨통(원본 픽셀)
QUALITY = 80     # 다시 저장할 때의 webp 품질. 원본(q92 추정)보다 낮춰 용량을 지킨다 —
                 # 글자에 두꺼운 외곽선이 그려져 있어 이 아래로도 티가 안 난다

for path in sorted(SRC.glob('*-title.webp')):
    im = Image.open(path).convert('RGBA')
    w, h = im.size
    mask = im.split()[3].point(lambda v: 255 if v >= ALPHA_MIN else 0)
    box = mask.getbbox()
    if box is None:
        print(f'{path.name}: 비어 있다 — 건너뛴다')
        continue
    top, bottom = box[1], h - box[3]
    if top <= PAD and bottom <= PAD:
        print(f'{path.name}: 이미 잘려 있다 (위 {top} · 아래 {bottom})')
        continue
    y0 = max(0, box[1] - PAD)
    y1 = min(h, box[3] + PAD)
    im.crop((0, y0, w, y1)).save(path, 'WEBP', quality=QUALITY, method=6)
    print(f'{path.name}: {w}x{h} -> {w}x{y1 - y0}  (위 {top} · 아래 {bottom} 걷음)')
