"""시즌 종목 뉴스 후보 추출 (1단계). 파이썬 표준 라이브러리만 쓴다.

세 연습 시즌의 구간에 나온 기사 중 제목에 시즌 종목 이름(또는 별칭)이 든 것을 뽑아
GPU 관련도 판정에 넘길 파일을 만든다. 판정은 gpu_judge.py.

    python3 extract_candidates.py ~/Downloads            → out/candidates.jsonl

- 게임일: 전 거래일 15:30 초과 ~ 그 거래일 15:30 이하에 나온 기사가 그 게임일 기사다. 체결이 그날
  종가라, 장 마감 뒤·휴장일 기사를 같은 날에 붙이면 미래가 샌다. 달력은 ref/season_calendar.csv.
- 잡음 규칙은 김성령 0단계(origin/KSR_ANT-DATA-09 data/stage0_news.py)를 그대로 쓴다 — 카테고리
  제외표·본문 200자·템플릿·리딩방 광고·(제목·날짜·언론사)와 링크 중복. 시세 자동 기사도 뺀다.
- 이름 매칭은 운영 NewsIngestService.mentions() 보다 엄격하다. 띄어쓰기를 지우지 않고("얘기 아냐"→기아),
  이름 바로 앞이 한글·영숫자면 버린다("실효성"→효성, "현대상선"→대상). 영문으로 끝나는 이름은 바로
  뒤도 영문자면 버린다("GST"→GS, "KTX"→KT). 한 제목에서 종목은 앞에서부터 셋까지.
"""
import csv, json, re, sys, bisect, collections, html
from datetime import datetime, date, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
RAW = Path(sys.argv[1]).expanduser()
OUT = HERE / "out"
csv.field_size_limit(sys.maxsize)

LEAD = 500
MAX_TICKERS = 3
CLOSE = time(15, 30)
TEMPLATE = re.compile(r"^\[?(뉴스\d*|뉴스광장|스포츠\d*|930뉴스|뉴스투데이)[^\]]*헤드라인|^클로징$|^오프닝$|출근길 교통 상황|저작권 고지|이웃 돕기 성금|^\[?날씨\]?|^날씨")
AD = re.compile(r"종목추천|매수가 매도가|무료로 알려|주식카톡방|리딩방|급등주 (추천|공개)|평생 공짜")
TICKER_BOT = re.compile(r"^<(유|코)>|매도잔량|매수잔량|체결강도|신저가 기록|신고가 돌파|일일회전율")
PUBLISHED = re.compile(r"^(\d{4})[-.](\d{2})[-.](\d{2})\s+(?:(오전|오후)\s*)?(\d{1,2}):(\d{2})")
BEFORE_BAD = re.compile(r"[가-힣A-Za-z0-9]")
LATIN = re.compile(r"[A-Za-z0-9]")
BROKEN_ENTITY = re.compile(r"(?<!&)(#0?39|quot|amp|lt|gt);")   # 원본에 '&' 가 떨어져 나간 조각 — "#039;전 키움#039;"
DOUBLE_AMP = re.compile(r"(?<!&)amp;(?=(#0?39|quot|lt|gt);)")    # 두 번 이스케이프된 조각 — "amp;quot;"


def clean(text):
    return html.unescape(BROKEN_ENTITY.sub(r"&\1;", DOUBLE_AMP.sub("", text)))


def published_at(text):
    """4형식('YYYY-MM-DD HH:MM[:SS]' · 'YYYY.MM.DD HH:MM' · 'YYYY-MM-DD 오전|오후 h:mm:ss')."""
    m = PUBLISHED.match(text.strip())
    if not m:
        return None
    y, mo, d, ampm, h, mi = m.groups()
    h = int(h)
    if ampm == "오후" and h < 12:
        h += 12
    if ampm == "오전" and h == 12:
        h = 0
    try:
        return datetime(int(y), int(mo), int(d), h, int(mi))
    except ValueError:
        return None


def load_seasons():
    cal = collections.defaultdict(list)
    for r in csv.DictReader(open(HERE / "ref/season_calendar.csv", encoding="utf-8")):
        cal[r["season_seed"]].append(date.fromisoformat(r["trade_date"]))
    tickers = collections.defaultdict(dict)          # seed → {표기: (코드, 종목명)}
    for r in csv.DictReader(open(HERE / "ref/season_tickers.csv", encoding="utf-8")):
        tickers[r["season_seed"]][r["name"]] = (r["code"], r["name"])
    for r in csv.DictReader(open(HERE / "aliases.csv", encoding="utf-8")):
        for names in tickers.values():
            if r["name"] in names:
                names[r["alias"]] = names[r["name"]]
    seasons = []
    for seed, days in cal.items():
        cutoffs = [datetime.combine(d, CLOSE) for d in days]      # [0] 은 전 거래일(워밍업 마지막 날)
        names = tickers[seed]
        pattern = re.compile("|".join(re.escape(n) for n in sorted(names, key=len, reverse=True)))
        seasons.append(dict(seed=seed, cutoffs=cutoffs, names=names, pattern=pattern))
    return seasons


def game_day(cutoffs, t):
    """전 거래일 15:30 초과 ~ 그 거래일 15:30 이하 → 그 게임일. 구간 밖이면 None."""
    if t <= cutoffs[0] or t > cutoffs[-1]:
        return None
    return bisect.bisect_left(cutoffs, t)


def match_tickers(season, title):
    found = []
    for m in season["pattern"].finditer(title):
        name, s, e = m.group(0), m.start(), m.end()
        if s > 0 and BEFORE_BAD.match(title[s - 1]):
            continue
        if LATIN.match(name[-1]) and e < len(title) and LATIN.match(title[e]):
            continue
        code, ticker = season["names"][name]
        if code not in (f[0] for f in found):
            found.append((code, ticker, name))
        if len(found) == MAX_TICKERS:
            break
    return found


def main():
    seasons = load_seasons()
    excl = {r["category"] for r in csv.DictReader(open(HERE / "category_exclude.csv", encoding="utf-8"))}
    years = sorted({c.year for s in seasons for c in (s["cutoffs"][0], s["cutoffs"][-1])})
    OUT.mkdir(exist_ok=True)
    funnel = collections.Counter()
    seen_link, seen_key, n = set(), set(), 0
    with open(OUT / "candidates.jsonl", "w", encoding="utf-8") as out:
        for year in years:
            with open(RAW / f"ssafy_dataset_news_{year}.csv", newline="", encoding="utf-8") as f:
                r = csv.reader(f, delimiter="|")
                next(r)
                for row in r:
                    if len(row) < 8:
                        continue
                    t = published_at(row[3])
                    if t is None:
                        continue
                    hit = [(s, g) for s in seasons if (g := game_day(s["cutoffs"], t)) is not None]
                    if not hit:
                        continue
                    funnel["0 시즌 구간"] += 1
                    title = clean(row[1].split("\n", 1)[0].replace("\r", "").strip())
                    body = clean(" ".join(row[7].split()))
                    if row[4].strip() in excl: continue
                    funnel["1 카테고리 제외표"] += 1
                    if len(body) < 200: continue
                    funnel["2 본문 200자"] += 1
                    if TEMPLATE.search(title) or AD.search(title) or TICKER_BOT.search(title): continue
                    funnel["3 템플릿·광고·시세 자동 기사"] += 1
                    key = (title, t.date(), row[0].strip())
                    if row[2] in seen_link or key in seen_key: continue
                    seen_link.add(row[2]); seen_key.add(key)
                    funnel["4 중복"] += 1
                    for season, g in hit:
                        for code, ticker, surface in match_tickers(season, title):
                            n += 1
                            funnel["5 제목에 종목(쌍)"] += 1
                            out.write(json.dumps(dict(
                                id=f"c{n:06d}", season_seed=season["seed"], game_day=g, code=code, ticker=ticker,
                                company=surface, title=title, lead=body[:LEAD], link=row[2], outlet=row[0].strip(),
                                published_at=t.isoformat(timespec="minutes")), ensure_ascii=False) + "\n")
    for k in sorted(funnel):
        print(f"{k:<24} {funnel[k]:>9,}")


if __name__ == "__main__":
    main()
