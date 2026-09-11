"""시즌 종목 뉴스 관련도 판정 (2단계) — GPU 박스(JupyterLab 터미널)에서 돌린다. 표준 라이브러리만 쓴다.

운영 NewsRelevanceService v2(MR !168)와 같은 지시문 · temperature 0 · 같은 파서다. 재료만 다르다 —
운영은 '제목 · 발췌 100자', 여기는 '제목 · 본문 앞 500자'. 채점표(사람 정답 600건)는 이 재료로 쟀다(95%).

끊겨도 이어 돈다 — 출력 파일에 이미 있는 id 는 건너뛰고, 한 건 끝날 때마다 한 줄씩 붙인다.
몇만 건이라 tmux 안에서 돌린다:

    tmux new -s judge 'python3 gpu_judge.py candidates.jsonl.gz judged.jsonl; bash'
"""
import gzip, json, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

URL = "http://localhost:8100/v1/chat/completions"
KEY = "antenna-gpu"
MODEL = "antenna"
WORKERS = 16   # vLLM 은 동시 요청을 한데 묶어 처리한다

INSTRUCTION = """너는 기업 뉴스 선별기다. 아래 기사가 그 회사의 사업·실적·공시·주가에 관한 것인지 판정한다.

- 회사와 이름만 같은 스포츠 구단·지역·인물·상표의 소식은 "무관" 이다.
  예: "두산 선발 최승용 3이닝 4실점"(KBO 두산 베어스), "한화 9회말 끝내기"(한화 이글스).
- 회사가 주체이거나 대상인 사업 소식이면 "관련" 이다. 스포츠가 소재여도 회사의 사업
  결정이면 "관련" 이다. 예: 구단 매각·인수, 중계권 계약, 스폰서십 체결.
- 기부·봉사·성금 같은 사회공헌 소식은 "무관" 이다. 투자 판단의 재료가 아니다.
  직원 복지·수상·판촉 행사는 사회공헌이 아니다.
- 판단이 서지 않으면 "관련" 으로 둔다. 재료에서 빼는 쪽이 더 위험하다.
- 다른 말을 쓰지 않는다. "관련" 또는 "무관" 한 낱말만 출력한다.
"""


def parse(text):
    """운영 NewsRelevanceService.parse 와 같다 — '무관' 을 먼저 본다('관련 없음' 을 반대로 읽지 않게)."""
    if text is None:
        return None
    a = text.strip()
    if "무관" in a or "관련 없" in a or "관련없" in a:
        return False
    if "관련" in a:
        return True
    return None


def judge(item):
    body = {"model": MODEL, "temperature": 0,
            "messages": [{"role": "system", "content": INSTRUCTION},
                         {"role": "user", "content": f"회사: {item['company']}\n기사: {item['title']} · {item['lead']}"}]}
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            raw = json.load(r)["choices"][0]["message"]["content"]
        return {"id": item["id"], "gpu": parse(raw), "raw": raw.strip()[:40]}
    except Exception as e:
        return {"id": item["id"], "gpu": None, "raw": f"ERROR {e}"[:120]}


def read_lines(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    try:
        done = {r["id"] for r in read_lines(dst) if r["gpu"] is not None}   # 못 읽은 답·오류는 다시 판정
    except FileNotFoundError:
        done = set()
    items = [i for i in read_lines(src) if i["id"] not in done]
    print(f"남은 {len(items):,}건 (이미 {len(done):,}건)", flush=True)
    t0, n, rel = time.time(), 0, 0
    with open(dst, "a", encoding="utf-8") as out, ThreadPoolExecutor(WORKERS) as pool:
        for fut in as_completed(pool.submit(judge, i) for i in items):
            r = fut.result()
            out.write(json.dumps(r, ensure_ascii=False) + "\n")
            out.flush()
            n += 1
            rel += r["gpu"] is True
            if n % 1000 == 0 or n == len(items):
                rate = n / (time.time() - t0)
                print(f"{n:,}/{len(items):,} · 관련 {rel:,} · 초당 {rate:.1f}건 · 남은 {(len(items) - n) / rate / 60:.0f}분", flush=True)
