"""게임일별 시장 이슈 제목 추출 (AI 한줄 요약의 재료). 파이썬 표준 라이브러리만 쓴다.

같은 사건은 여러 언론사가 다룬다 → 그 게임일 창(전 거래일 15:30 초과 ~ 그날 15:30 이하)의 기사 제목에
가장 많이 겹쳐 나온 낱말이 그날의 이슈다. 낱말마다 그 낱말을 가장 잘 대표하는 제목을 골라, 게임일마다
제목 HEADLINES 개를 남긴다. 요약은 summarize_days.py.

    python3 extract_day_issues.py ~/Downloads     → out/day-issues.jsonl

잡음 규칙·게임일 경계·정리는 extract_candidates.py 와 같다(그 파일의 함수를 쓴다).
"""
import csv, json, re, sys, collections
from pathlib import Path

import extract_candidates as ex

HERE = Path(__file__).resolve().parent
TOP_WORDS = 15
HEADLINES = 30
SIMILAR = 0.4
STOP = set("""속보 종합 종합2보 포토 사진 영상 오늘 내일 어제 이번 지난 올해 관련 위해 대한 통해 이후 기자 뉴스 단독 인터뷰
신규 확대 추가 발표 개최 진행 실시 강화 지원 사업 기업 시장 정부 한국 국내 서울 전국 최대 최고 게시판 인사 부고 출시
기념 행사 참석 선정 수상 협약 체결 운영 개발 공개 계획 예정 검토 가능 필요 전망 우려 논란 결정 현장 공략 성공 돌파
기록 대상 이유 모든 하루 우리 사람 가장 없는 있는 하는 한다 된다 했다 무슨 어떻게 누가 이제 다시 계속 결국 다음""".split())
JOSA = re.compile(r"(은|는|이|가|을|를|에|의|도|로|으로|와|과|서|에서|까지|부터|만|엔|께|측|들|에게|한테|이다|했다|한다)$")
DATEISH = re.compile(r"^\d+(월|일|년|시|분)$")


def words(title):
    ws = set()
    for w in re.findall(r"[0-9A-Za-z가-힣]+", re.sub(r"\[[^\]]*\]", " ", title)):
        w = JOSA.sub("", w)
        if len(w) >= 2 and w not in STOP and not w.isdigit() and not DATEISH.match(w):
            ws.add(w)
    return ws


def grams(title):
    s = re.sub(r"[^0-9A-Za-z가-힣]", "", title)
    return {s[i:i + 2] for i in range(len(s) - 1)}


def headlines(titles):
    """낱말 빈도 상위부터, 그 낱말이 든 제목 중 가장 대표적인(상위 낱말을 많이 품은) 것을 하나씩."""
    df = collections.Counter(w for t in titles for w in words(t))
    top = [w for w, _ in df.most_common(TOP_WORDS * 3)]
    weight = {w: df[w] for w in top}
    scored = sorted(titles, key=lambda t: -sum(weight.get(w, 0) for w in words(t)))
    picked, picked_grams = [], []
    for rounds in range(3):                      # 한 바퀴에 낱말마다 하나 — 큰 이슈가 제목을 독차지하지 않게
        for w in top[:TOP_WORDS]:
            for t in scored:
                if w in words(t) and t not in picked:
                    g = grams(t)
                    if all(len(g & pg) / max(1, len(g | pg)) < SIMILAR for pg in picked_grams):
                        picked.append(t)
                        picked_grams.append(g)
                        break
            if len(picked) >= HEADLINES:
                return picked, df.most_common(TOP_WORDS)
    return picked, df.most_common(TOP_WORDS)


def main():
    raw = Path(sys.argv[1]).expanduser()
    seasons = ex.load_seasons()
    excl = {r["category"] for r in csv.DictReader(open(HERE / "category_exclude.csv", encoding="utf-8"))}
    years = sorted({c.year for s in seasons for c in (s["cutoffs"][0], s["cutoffs"][-1])})
    by_day = collections.defaultdict(list)
    seen = set()
    for year in years:
        with open(raw / f"ssafy_dataset_news_{year}.csv", newline="", encoding="utf-8") as f:
            r = csv.reader(f, delimiter="|")
            next(r)
            for row in r:
                if len(row) < 8 or row[4].strip() in excl:
                    continue
                t = ex.published_at(row[3])
                if t is None:
                    continue
                hit = [(s["seed"], g) for s in seasons if (g := ex.game_day(s["cutoffs"], t)) is not None]
                if not hit:
                    continue
                title = ex.clean(row[1].split("\n", 1)[0].replace("\r", "").strip())
                if ex.TEMPLATE.search(title) or ex.AD.search(title) or ex.TICKER_BOT.search(title):
                    continue
                if row[2] in seen:
                    continue
                seen.add(row[2])
                for key in hit:
                    by_day[key].append(title)
    with open(HERE / "out/day-issues.jsonl", "w", encoding="utf-8") as out:
        for (seed, day), titles in sorted(by_day.items(), key=lambda x: (x[0][0], x[0][1])):
            picked, top = headlines(list(dict.fromkeys(titles)))
            out.write(json.dumps(dict(seasonSeed=int(seed), gameDay=day, articles=len(titles),
                                      topWords=top, headlines=picked), ensure_ascii=False) + "\n")
    print(f"게임일 {len(by_day)}개 · 하루 기사 {min(map(len, by_day.values())):,}~{max(map(len, by_day.values())):,}건")


if __name__ == "__main__":
    main()
