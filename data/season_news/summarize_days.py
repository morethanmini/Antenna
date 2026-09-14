"""게임일별 시장 요약 생성 (AI 한줄 요약 · season_news 의 AI_HINT 행). 파이썬 표준 라이브러리만 쓴다.

extract_day_issues.py 가 고른 그날의 이슈 제목만 주고 "한 줄 + 주요 사건 세 줄" 을 쓰게 한다.
OpenAI 호환 Chat Completions 라 백엔드 app.ai 와 같은 설정(AI_BASE_URL·AI_API_KEY·AI_MODEL)을 쓴다 —
환경변수가 없으면 backend/.env 에서 읽는다. 게임일 144개 = 144콜, 한 번 만들고 끝나는 배치다.

    python3 summarize_days.py      → out/season-news-hint.jsonl · out/hint-review.md (사람 검수용)

가장 큰 위험은 모델이 원래 아는 "그 뒤의 일" 이다(2020년 3월 뒤 반등을 안다). 지시문이 제목 밖의 사실·
이후 전개·전망을 금지하고, 결과는 hint-review.md 로 사람이 전부 읽는다. 날짜 숫자는 한 번 더 가린다.
끊겨도 이어 돈다 — 출력에 있는 게임일은 건너뛴다.
"""
import json, os, re, sys, time, urllib.error, urllib.request
from pathlib import Path

import select_news

HERE = Path(__file__).resolve().parent
PROMPT_VERSION = "hint-v1"
OUT = HERE / "out/season-news-hint.jsonl"

INSTRUCTION = """너는 증권사 시황 담당자다. 아래는 어느 거래일 하루(전 거래일 장 마감 뒤부터 그날 장 마감까지)에 가장 많이 보도된 기사 제목들이다.
이 제목들만 근거로, 그날 시장을 움직였을 만한 일을 투자자에게 짧게 알려 준다.

- 첫 줄: 그날을 한 문장으로 요약한다. 40자 안팎.
- 다음 세 줄: 주요 사건 세 개를 "· " 로 시작해 한 줄씩 쓴다. 증시에 영향이 큰 것부터. 정치·외교·사고·해외
  소식도 시장에 영향이 있으면 넣는다. 한 줄에 45자 안팎.
- 제목에 없는 사실, 그 뒤에 벌어진 일, 전망, 결과를 쓰지 않는다. 네가 아는 이후의 전개를 절대 쓰지 않는다.
- 날짜·요일·연도와 언론사 이름을 쓰지 않는다. 종목을 사라·팔라고 하지 않는다.
- 다른 말 없이 네 줄만 출력한다."""


def settings():
    env = dict(os.environ)
    dotenv = HERE.parent.parent / "backend/.env"
    if dotenv.exists():
        for line in dotenv.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                env.setdefault(k.strip(), v.strip())
    return env["AI_BASE_URL"].rstrip("/"), env["AI_API_KEY"], env["AI_MODEL"]


def complete(base, key, model, headlines):
    body = {"model": model, "temperature": 0,
            "messages": [{"role": "system", "content": INSTRUCTION},
                         {"role": "user", "content": "기사 제목:\n" + "\n".join(f"- {h}" for h in headlines)}]}
    req = urllib.request.Request(f"{base}/chat/completions", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)["choices"][0]["message"]["content"]
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 503) or attempt == 3:
                raise
            time.sleep(10 * (attempt + 1))


def parse(text):
    """첫 줄 = 한 줄 요약, "·" 로 시작하는 줄 셋 = 사건. 모양이 틀리면 None — 그 게임일은 다시 돈다."""
    lines = [l.strip().lstrip("-*").strip() for l in text.strip().splitlines() if l.strip()]
    if len(lines) < 4:
        return None
    head = lines[0].lstrip("#").strip()
    events = [("· " + l.lstrip("·•").strip()) for l in lines[1:4]]
    return select_news.mask_dates(head), "\n".join(select_news.mask_dates(e) for e in events)


def main():
    base, key, model = settings()
    days = [json.loads(l) for l in open(HERE / "out/day-issues.jsonl", encoding="utf-8")]
    done = set()
    if OUT.exists():
        done = {(r["seasonSeed"], r["gameDay"]) for r in map(json.loads, open(OUT, encoding="utf-8"))}
    with open(OUT, "a", encoding="utf-8") as out:
        for d in days:
            if (d["seasonSeed"], d["gameDay"]) in done:
                continue
            parsed = parse(complete(base, key, model, d["headlines"]))
            if parsed is None:
                print(f"모양이 틀림 — {d['seasonSeed']} D{d['gameDay']} (다시 돌리면 재시도)")
                continue
            title, body = parsed
            out.write(json.dumps(dict(seasonSeed=d["seasonSeed"], gameDay=d["gameDay"], kind="AI_HINT", code=None,
                                      title=title, body=body, link=None, promptVersion=PROMPT_VERSION),
                                 ensure_ascii=False) + "\n")
            out.flush()
            print(f"{d['seasonSeed']} D{d['gameDay']:>2} {title}")
            time.sleep(1)   # 무료 한도의 분당 호출 수를 넘지 않게
    review()


PREDICATE = re.compile(r"(다|며|고|해|서|자|면|나|요|한|된|던|는|은|인|진|른|쳐|게|지|어|아|여|로|적)$")
SUMMARY_WORDS = set("""하루 증시 코스피 코스닥 주요 소식 여파 영향 발생 사건 사고 이슈 화두 중심 지속 기록 보도 논란 우려 관심
주목 가운데 속에서 동시 이어 연이어 잇달아 대부분 다수 전반 국내외 해외 정부 정치권 기업 기업들 시장 투자자 상승 하락 급락
급등 반등 상승세 하락세 약세 강세 부진 호조 확산 여야 여당 야당 미국 중국 일본 한국 국내 세계 전국 지역 관련 대한 대해 따른""".split())


def ungrounded(hint, headlines):
    """요약에 쓰였는데 재료 제목 어디에도 없는 명사. 서술어·흔한 시황 낱말은 빼고, 활용형 차이는 앞 두 글자로 본다.
    걸리는 것이 곧 틀린 말은 아니다(SVB → 실리콘밸리은행 처럼 풀어 쓴 것도 걸린다) — 사람이 볼 곳을 좁히는 표시다."""
    import extract_day_issues as di
    source = " ".join(headlines)
    text = hint["title"] + " " + hint["body"]
    nouns = [w for w in di.words(text) if not PREDICATE.search(w) and w not in SUMMARY_WORDS and (len(w) >= 3 or re.search(r"[A-Za-z0-9]", w))]
    return sorted(w for w in nouns if w[:2] not in source and not re.match(r"^[○\d]", w))


def review():
    """게임일마다 요약과 재료 제목을 나란히 — 사람이 '제목에 없는 말' 을 찾는 표."""
    hints = {(r["seasonSeed"], r["gameDay"]): r for r in map(json.loads, open(OUT, encoding="utf-8"))}
    days = [json.loads(l) for l in open(HERE / "out/day-issues.jsonl", encoding="utf-8")]
    with open(HERE / "out/hint-review.md", "w", encoding="utf-8") as f:
        f.write(f"# AI 한줄 요약 검수 ({PROMPT_VERSION}) — 요약에 재료 제목에 없는 말·그 뒤의 일이 있으면 표시\n\n")
        for d in days:
            h = hints.get((d["seasonSeed"], d["gameDay"]))
            f.write(f"## 시즌 {d['seasonSeed']} · {d['gameDay']}일차 (기사 {d['articles']:,}건)\n\n")
            f.write(f"**{h['title']}**\n\n{h['body']}\n\n" if h else "_(요약 없음)_\n\n")
            missing = ungrounded(h, d["headlines"]) if h else []
            if missing:
                f.write("⚠ 재료 제목에 없는 말: " + ", ".join(missing) + "\n\n")
            f.write("<details><summary>재료 제목</summary>\n\n" + "\n".join(f"- {x}" for x in d["headlines"]) + "\n\n</details>\n\n")


if __name__ == "__main__":
    main()
