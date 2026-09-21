"""어두운 상단바용 워드마크를 만든다 → public/assets/logo/antenna-wordmark-3d-on-dark.png

모의투자 홈(G-01)은 상단바까지 밤이라 기본 워드마크를 그대로 쓸 수 없다. 'ANTENNA'
글자가 짙은 남색(#00002f 언저리)이어서 어두운 바 위에서 거의 사라진다.

**글자만 밝힌다.** 마스코트와 물결(청록 스워시)은 그대로 둔다 — 원본을 훑어보면
어두운 픽셀(밝기 100 이하)은 전부 글자에 있고 마스코트에는 하나도 없다(가장 왼쪽
어두운 픽셀이 x=104, 'A' 의 꼭지점이다). 그래서 x 범위를 나누지 않고 밝기만으로
가른다. 경계는 부드럽게 섞어(100~150) 글자 테두리가 계단지지 않게 한다.

원본이 바뀌면 다시 돌린다.
  python scripts/build-logo-on-dark.py
"""
from PIL import Image

SRC = 'public/assets/logo/antenna-wordmark-3d.png'
OUT = 'public/assets/logo/antenna-wordmark-3d-on-dark.png'
LIGHT = (244, 243, 255)   # --night-ink 와 같은 값
DARK, KEEP = 100, 150     # 이 밝기 아래는 전부 글자, 위는 손대지 않는다

im = Image.open(SRC).convert('RGBA')
px = im.load()
w, h = im.size
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if a == 0:
            continue
        v = max(r, g, b)
        if v >= KEEP:
            continue
        t = 1.0 if v <= DARK else (KEEP - v) / (KEEP - DARK)
        px[x, y] = (
            round(r + (LIGHT[0] - r) * t),
            round(g + (LIGHT[1] - g) * t),
            round(b + (LIGHT[2] - b) * t),
            a,
        )
im.save(OUT, optimize=True)
print('wrote', OUT, im.size)
