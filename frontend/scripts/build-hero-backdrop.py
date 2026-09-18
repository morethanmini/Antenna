"""모의투자 홈(G-01) 히어로 배경 요소를 public/assets/pageset/ 으로 굽는다.

    python scripts/build-hero-backdrop.py

받은 원본은 public/assets/page_train/ 에 "ChatGPT Image 2026년 ..." 이라는 이름으로
들어 있다. 파일명에 날짜·시각·괄호번호가 섞여 있어 코드에서 직접 부를 수 없고,
같은 폴더에 연습 시나리오 카드(전쟁·코로나·트럼프 …)도 함께 있어 이름만으로는
어느 것이 배경 요소인지 가려지지 않는다. 그래서 **이름을 정렬한 순서의 번호**로
집는다 — 아래 SOURCES 의 키가 그 번호다.

파일을 더 넣거나 지우면 번호가 밀린다. 그럴 때는 build-hero-backdrop 을 다시 돌리기
전에 번호부터 확인해야 한다:

    python -c "import glob;print(*enumerate(sorted(glob.glob('public/assets/page_train/ChatGPT*.png'))),sep=chr(10))"

원본은 2000px 안팎인데 화면에는 길어야 250px 로 뜬다. 긴 변을 잘라 두지 않으면
쓰지도 않을 픽셀을 내려받게 된다.
"""
import glob
import os

from PIL import Image

SRC = 'public/assets/page_train'
OUT = 'public/assets/pageset'
QUALITY = 86

# 정렬 순서 번호 -> (내보낼 이름, 긴 변 상한 px)
SOURCES = {
    11: ('panel-line', 620),   # 선차트가 그려진 유리판
    12: ('panel-pie', 620),    # 파이·막대가 그려진 유리판
    14: ('candles', 760),      # 캔들 뭉치
    15: ('sparks', 620),       # 반짝임
    16: ('wave-thin', 1100),   # 가는 빛줄기. 굵은 것(17·18)은 칩 글자를 덮어 안 쓴다
}


def main() -> None:
    files = sorted(glob.glob(f'{SRC}/ChatGPT*.png'))
    for idx, (name, cap) in SOURCES.items():
        im = Image.open(files[idx]).convert('RGBA')
        im.thumbnail((cap, cap), Image.LANCZOS)
        path = f'{OUT}/{name}.webp'
        im.save(path, 'WEBP', quality=QUALITY, method=6)
        print(f'{name:11s} {str(im.size):12s} {os.path.getsize(path) / 1024:5.0f} KB  <- {os.path.basename(files[idx])}')


if __name__ == '__main__':
    main()
