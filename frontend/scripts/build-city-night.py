"""모의투자 홈(G-01) 바탕의 도시 야경을 그린다 → public/assets/page_train/city-night.webp

사진을 쓰지 않는 이유 — 실제 도시 사진은 지역·시대 단서가 되고, 시안 원본(page_train 의
ChatGPT 이미지)은 글자·캐릭터가 함께 박혀 있어 바탕으로 못 쓴다. 그래서 사각형 건물과
창문 점만으로 실루엣을 만든다. 시드를 고정해 두어 다시 돌려도 같은 그림이 나온다.

  python scripts/build-city-night.py
"""
import random
from PIL import Image, ImageDraw, ImageFilter

random.seed(7)
W, H = 1920, 620
OUT = 'public/assets/page_train/city-night.webp'


def layer(minw, maxw, minh, maxh, col, winp, alpha, blur):
    """건물 한 겹. 멀리 있는 겹은 밝고 흐리게, 가까운 겹은 어둡고 또렷하게."""
    L = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(L)
    x = -random.randint(0, 60)
    while x < W + 80:
        w, h = random.randint(minw, maxw), random.randint(minh, maxh)
        top = H - h
        d.rectangle((x, top, x + w, H), fill=col + (alpha,))
        if random.random() < .35:  # 안테나·첨탑
            cx = x + w // 2
            d.rectangle((cx - 2, top - random.randint(20, 70), cx + 2, top), fill=col + (alpha,))
        for wy in range(top + 14, H - 10, 16):
            for wx in range(x + 8, x + w - 8, 14):
                if random.random() < winp:
                    c = random.choice([(255, 222, 150), (190, 180, 255), (140, 200, 255), (255, 240, 220)])
                    d.rectangle((wx, wy, wx + 5, wy + 7), fill=c + (random.randint(90, 220),))
        x += w + random.randint(6, 26)
    return L.filter(ImageFilter.GaussianBlur(blur)) if blur else L


img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
img.alpha_composite(layer(50, 120, 160, 380, (44, 40, 96), .10, 235, 1.6))
img.alpha_composite(layer(60, 150, 90, 260, (27, 24, 66), .16, 255, 0.6))
img.alpha_composite(layer(80, 200, 40, 160, (16, 14, 42), .12, 255, 0))

# 바닥의 보라 빛띠
g = Image.new('RGBA', (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(g)
for i in range(60):
    gd.line((0, H - 60 + i, W, H - 60 + i), fill=(120, 100, 255, int(90 * (1 - i / 60))))
img.alpha_composite(g.filter(ImageFilter.GaussianBlur(8)))

img.save(OUT, 'WEBP', quality=85)
print('wrote', OUT)
