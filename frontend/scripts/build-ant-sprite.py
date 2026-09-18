"""모의투자 홈(G-01) 히어로 개미 스프라이트 시트를 굽는다.

    python scripts/build-ant-sprite.py

받은 원본(public/assets/kingsparaid_sprite_assets_v7/)은 영상에서 뽑아낸 것이라
그대로 쓸 수 없다. 이 스크립트가 손보는 것은 넷이다.

1. **생성기 워터마크** — 오른쪽 아래에 KlingAI 로고가 알파로 깎이다 말아 글자
   조각이 남아 있다. 37 프레임 전부를 훑어 그 사각형에 개미 본체가 한 픽셀도
   걸치지 않는 것을 확인하고 지운다.
2. **크로마키 초록** — 8·9·25·26·27 번 프레임에서 개미 턱과 판 사이 틈으로
   촬영 배경의 초록이 비친다. 배경이니 투명으로 돌리는 것이 맞다.

   **색이 아니라 자리로 가른다.** 처음엔 크로마가 R≈B 인 순초록이고 BUY 버튼은
   B 가 높은 민트색이라는 것으로 갈랐는데, 버튼의 그늘진 부분이 같은 조건에 걸려
   버튼이 뜯겨 나갔다. 37 프레임의 초록 덩어리를 전부 재보면 버튼은 언제나
   x 72~287 · y 492~745 에 있고 스필은 x 548~625 · y 474~589 에만 나온다.
   사이가 260px 이나 비어 있어 자리로 자르면 버튼이 걸릴 일이 없다.
3. **너덜너덜한 외곽** — 판 둘레에 검정·흰색 얼룩이 불투명하게 3~8px 붙어 있다.
   알파를 깎아(erode) 껍질째 걷어내고 가장자리만 다시 부드럽게 만든다.

   알파만 깎으면 **색이 남는다.** 깎고 난 새 가장자리의 RGB 가 여전히 얼룩에서
   온 검정이라, 판 밑변에 점선 같은 짙은 테가 남았다. 그래서 알파를 깎기 전에
   가장자리 띠의 색을 안쪽 성한 색으로 바꿔 놓는다(거리 변환으로 가장 가까운
   성한 픽셀을 찾아 그 색을 쓴다).
4. **줄일 때 생기는 검은 테** — RGBA 를 그냥 줄이면 투명한 검정 픽셀이 색에
   섞여 외곽이 어두워진다. 알파를 곱해 두고(premultiply) 줄인 뒤 되돌린다.
5. **무른 눈매** — 원본이 영상에서 뽑은 것이라 눈·입 경계가 무르다. 크게 구워도
   안 나아지는 것을 재서 확인했고(SCALE 주석), 대신 언샤프로 경계를 세운다.

**원본은 저장소에 없다.** public/assets/kingsparaid_sprite_assets_v7/ 은 시트 27MB 에
프레임 37 장 31MB 라 합이 57MB 고, public/ 아래라 넣으면 빌드 결과물까지 그만큼
무거워진다. 구운 결과(1.6MB)만 올린다 — 다시 구우려면 원본을 따로 받아 그 경로에
풀어야 한다.

결과는 public/assets/pageset/ant-play-sprite.webp 한 장이다. 원본 27MB PNG 를
그대로 쓰면 첫 화면이 한참 비어 있어 0.6 배 WebP 로 굽는다.
"""
import json
import os

import numpy as np
from PIL import Image, ImageFilter
from scipy.ndimage import binary_erosion, distance_transform_edt, gaussian_filter

SRC = 'public/assets/kingsparaid_sprite_assets_v7'
OUT = 'public/assets/pageset/ant-play-sprite.webp'

# 배율을 올려도 소용이 없다. 0.6(프레임 629px)·0.8(838px)·원본(1048px)을 화면에 뜨는
# 크기(DPR 2 기준 972px)로 맞춰 놓고 경계 또렷함을 재면 5.99 / 5.92 / 5.98 로 같다 —
# **원본 프레임 자체가 무르다.** 영상에서 뽑은 것이라 위에 더 담긴 정보가 없어서,
# 크게 구울수록 용량과 메모리만 먹는다(0.8 은 +0.6MB · 디코드 +43MB). 그래서 0.6 이다.
# 눈·입이 뭉개 보이는 것은 아래 SHARPEN 이 맡는다.
SCALE = 0.6
QUALITY = 82

# 언샤프 마스크 세기(%). 원본이 무른 것을 되살릴 수는 없지만 경계를 세워 주면
# 눈매가 또렷하게 읽힌다. 0·60·110·170 을 같은 자리에서 비교해 110 으로 정했다 —
# 170 부터는 눈 둘레에 흰 테(halo)가 돌기 시작한다. 0 이면 끈다.
# **알파에는 걸지 않는다.** 공들여 무르게 만든 가장자리가 다시 서 버린다.
SHARPEN = 110
WM_X, WM_Y = 868, 806   # 워터마크 사각형의 왼쪽·위 모서리. 오른쪽·아래는 프레임 끝까지
# 크로마 스필만 들어 있는 상자 (x0, x1, y0, y1). 실측 범위에 사방 여유를 뒀고,
# 초록 BUY 버튼(x 72~287)과는 200px 넘게 떨어져 있다
SPILL_BOX = (500, 700, 430, 640)
# 외곽에서 걷어낼 두께(px). 5·8·11·14 로 구워 보고 11 로 정했다 — 8 까지는 판 밑변에
# 검은 점선이 남고, 11 에서 판 밑변과 머리 오른쪽의 톱니가 같이 사라진다. 14 는 11 과
# 차이가 없으면서 더 깎는다. 더듬이 대가 11 을 견디는 것은 확인했다.
ERODE = 11
BLEED = 3               # 색을 끌어올 깊이. ERODE 보다 이만큼 더 안쪽이 "성한" 색이다
# 깎은 뒤 가장자리를 다시 무르게 하는 정도. 1.2 로 시작했는데 히어로 판이 어두워지자
# 판 밑변의 물결치는 윤곽이 그대로 드러났다 — 밝은 판에서는 안 보이던 것이다.
# 매트가 그은 선 자체가 구불거리는 것이라 더 깎아도 안 없어지고, 무르게 해야 그
# 구불거림이 "흐린 가장자리" 로 읽힌다. 어두운 배경에 올려 놓고 1.2·3.0·4.5 를
# 비교해 3.0 으로 정했다 — 4.5 는 판 모서리가 뭉개져 유리판 느낌이 사라진다.
FEATHER = 3.0


def disk(r: int) -> np.ndarray:
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r


def clean(a: np.ndarray) -> np.ndarray:
    """프레임 한 장(RGBA uint8)에서 워터마크·크로마·너덜거림을 걷어낸다."""
    a = a.astype(np.int16)
    r, g, b, al = a[..., 0], a[..., 1], a[..., 2], a[..., 3]

    a[WM_Y:, WM_X:, :] = 0

    # 크로마키 초록. 스필 상자 안에서만 지운다 — 밖에 있는 초록은 전부 버튼이다
    x0, x1, y0, y1 = SPILL_BOX
    chroma = np.zeros(al.shape, bool)
    box = (al[y0:y1, x0:x1] > 0) & (g[y0:y1, x0:x1] > r[y0:y1, x0:x1] + 30)         & (g[y0:y1, x0:x1] > b[y0:y1, x0:x1] + 30)
    chroma[y0:y1, x0:x1] = box
    a[chroma] = 0

    # 스필 — 초록에 닿아 있던 가장자리는 색이 초록 쪽으로 물들어 있다.
    # 지운 자리 둘레에서만 G 를 R·B 평균 위로 못 올라가게 누른다.
    ring = binary_erosion(~chroma, disk(6)) ^ ~chroma
    if ring.any():
        mid = (a[..., 0] + a[..., 2]) // 2
        spill = ring & (a[..., 1] > mid + 18) & (np.abs(a[..., 0] - a[..., 2]) < 30)
        a[..., 1] = np.where(spill, mid + 18, a[..., 1])

    # 가장자리 띠의 색을 안쪽 성한 색으로 갈아 끼운다. 이걸 안 하면 알파를 깎아도
    # 얼룩에서 온 검정이 새 가장자리에 그대로 남아 판 밑변에 점선 테가 진다
    inner = binary_erosion(a[..., 3] > 128, disk(ERODE + BLEED), border_value=0)
    if inner.any():
        iy, ix = distance_transform_edt(~inner, return_indices=True)[1]
        a[..., :3] = np.where(inner[..., None], a[..., :3], a[iy, ix, :3])

    # 외곽 껍질 걷어내기 — 알파를 깎고 가장자리만 다시 무르게 한다
    solid = binary_erosion(a[..., 3] > 128, disk(ERODE), border_value=0)
    soft = gaussian_filter(solid.astype(np.float32), FEATHER)
    a[..., 3] = np.minimum(a[..., 3], (soft * 255).astype(np.int16))
    return np.clip(a, 0, 255).astype(np.uint8)


def shrink(a: np.ndarray, w: int, h: int) -> Image.Image:
    """알파를 곱해 두고 줄인다. 그냥 줄이면 투명한 검정이 색에 섞여 테가 진다."""
    f = a.astype(np.float32)
    f[..., :3] *= f[..., 3:4] / 255.0
    small = np.asarray(
        Image.fromarray(f.astype(np.uint8), 'RGBA').resize((w, h), Image.LANCZOS),
        dtype=np.float32,
    )
    al = np.maximum(small[..., 3:4], 1.0)
    small[..., :3] = np.clip(small[..., :3] * 255.0 / al, 0, 255)
    out = Image.fromarray(small.astype(np.uint8), 'RGBA')
    if SHARPEN:
        r, g, b, a = out.split()
        mask = ImageFilter.UnsharpMask(radius=1.4, percent=SHARPEN, threshold=2)
        out = Image.merge('RGBA', (r.filter(mask), g.filter(mask), b.filter(mask), a))
    return out


def main() -> None:
    meta = json.load(open(f'{SRC}/kingsparaid_sprite_sheet_v7.json'))
    fw = round(meta['frameWidth'] * SCALE)
    fh = round(meta['frameHeight'] * SCALE)
    sheet = Image.new('RGBA', (fw * meta['columns'], fh * meta['rows']), (0, 0, 0, 0))

    for f in meta['frames']:
        a = np.asarray(Image.open(f"{SRC}/frames/{f['file']}").convert('RGBA'))
        sheet.paste(shrink(clean(a), fw, fh), (f['col'] * fw, f['row'] * fh))

    sheet.save(OUT, 'WEBP', quality=QUALITY, method=6)
    print(f'프레임 {fw}x{fh} · 시트 {sheet.size} · {os.path.getsize(OUT) / 1e6:.2f} MB → {OUT}')


if __name__ == '__main__':
    main()
