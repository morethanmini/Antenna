"""시즌 종목 뉴스 선별·가림 (3단계). 파이썬 표준 라이브러리만 쓴다.

GPU 가 관련으로 본 기사·종목 쌍에서 종목·게임일마다 최대 MAX_PER_DAY 건을 고르고, 제목·발췌에서
날짜·연도·언론사를 가려 시드 파일 한 줄씩으로 쓴다(SeasonNewsSeeder 가 읽는 형식).

    python3 select_news.py out/candidates.jsonl out/judged.jsonl   → out/season-news-ticker.jsonl

- 같은 종목·같은 게임일에 여러 언론사가 같은 일을 쓴 기사는 한 묶음이다(제목 글자 두 개씩의 겹침).
  많이 보도된 묶음부터, 묶음마다 먼저 나온 기사 하나.
- 가림: 연도·날짜 숫자는 ○ 로 바꾸고("3월 15일" → "○월 ○일"), 언론사·기자 표기와 이메일은 지운다.
  종목·사건 이름은 그대로 둔다(ERD v0.8 · 연습 모드만 남음). 원문 링크는 그대로 싣는다.
"""
import json, re, sys, collections

MAX_PER_DAY = 5
EXCERPT = 150
SIMILAR = 0.5          # 제목 두 글자 조각 겹침(자카드) 이 이상이면 같은 일로 본다

BYLINE = [
    re.compile(r"^\s*[\[(【<][^\])】>]{0,40}(기자|특파원|=|뉴스|TV|포토|통신|닷컴|일보|경제|데일리|더팩트|SEN)[^\])】>]{0,40}[\])】>]\s*"),
    re.compile(r"^\s*\S{1,15}\s*=\s*\S{2,4}\s*(기자|특파원)\s*="),              # "서울=연합뉴스 홍길동 기자 ="
    re.compile(r"^\s*[가-힣]{2,4}\s*(기자|특파원)\s*[=\]]"),
    re.compile(r"^\s*\[?앵커\]?\s*|^\s*【\s*앵커멘트\s*】\s*"),
]
EMAIL = re.compile(r"\S+@\S+\.\S+")
INLINE_BYLINE = re.compile(r"[\[(【]\s*[^\])】]{0,30}(기자|특파원)[^\])】]{0,10}[\])】]|\(\S{1,15}=\S{2,10}\)\s*([가-힣]{2,4}\s*(기자|특파원)\s*=)?")
JUNK = re.compile(r"재무분석차트영역상세보기|\ufeff")
PHOTO = re.compile(r"/?\(?사진(제공)?\s*=\s*[^)\s]+\)?|사진 ?= ?\S+")
TAIL_BYLINE = re.compile(r"[가-힣]{2,4}\s*(기자|특파원)\s*$")
YEAR = re.compile(r"(?<!\d)(19|20)\d{2}(?=\s*년|\s|[.·,)\]'’”-]|$)")
YEAR_SHORT = re.compile(r"(?<![\d.])['’]\d{2}(?!\d)")                          # '20, ’23
MONTH_DAY = re.compile(r"(?<!\d)\d{1,2}\s*월\s*\d{1,2}\s*일")
MONTH = re.compile(r"(?<!\d)\d{1,2}\s*월(?!\s*\d)")
DAY = re.compile(r"(?<![\d.])\d{1,2}\s*일(?=\s|[,.(·)]|$|(오전|오후|새벽|밤|께|부터|까지|현지))")
DOTTED = re.compile(r"(?<!\d)(19|20)?\d{2}\.\d{1,2}\.\d{1,2}\.?")


def mask_dates(text):
    text = DOTTED.sub("○○.○.○", text)
    text = MONTH_DAY.sub("○월 ○일", text)
    text = YEAR.sub("○○○○", text)
    text = YEAR_SHORT.sub("'○○", text)
    text = MONTH.sub("○월", text)
    return DAY.sub("○일", text)


def clean_title(title, outlet):
    t = JUNK.sub("", title)
    # 언론사 이름이 든 머리표만 뗀다 — [속보]·[특징주]·[컨콜] 은 정보라 남긴다
    t = re.sub(r"\[[^\]]*" + re.escape(outlet) + r"[^\]]*\]\s*", "", t) if outlet else t
    t = re.sub(r"^\s*(\[(SEN|TF[^\]]*|머니S[^\]]*|시그널|biz-플러스|현장\+|마켓딥다이브|HK영상|이기업 왜이래[^\]]*)\]\s*)+", "", t)
    return mask_dates(" ".join(t.split()))


def clean_excerpt(lead, outlet):
    t = lead
    for _ in range(3):
        for b in BYLINE:
            t = b.sub("", t)
    if outlet:
        t = t.replace(outlet, "")
    t = EMAIL.sub("", PHOTO.sub("", INLINE_BYLINE.sub("", JUNK.sub("", t))))
    t = mask_dates(" ".join(t.split()))
    if len(t) > EXCERPT:                          # 문장 끝에서 자른다 — 없으면 글자 수로
        cut = max(t.rfind(". ", 0, EXCERPT), t.rfind("다.", 0, EXCERPT) + 1)
        t = t[: cut + 1] if cut > EXCERPT // 2 else t[:EXCERPT].rstrip() + "…"
    return TAIL_BYLINE.sub("", t).strip()


def grams(title):
    s = re.sub(r"[^0-9A-Za-z가-힣]", "", re.sub(r"\[[^\]]*\]", "", title))
    return {s[i:i + 2] for i in range(len(s) - 1)}


def pick(items):
    """같은 일 묶음 → 많이 보도된 묶음부터 대표 하나씩."""
    groups = []                                        # [(grams, [items])]
    for it in sorted(items, key=lambda x: x["published_at"]):
        g = grams(it["title"])
        for gg, members in groups:
            if g and gg and len(g & gg) / len(g | gg) >= SIMILAR:
                members.append(it)
                break
        else:
            groups.append((g, [it]))
    groups.sort(key=lambda x: (-len(x[1]), x[1][0]["published_at"]))
    return [members[0] for _, members in groups[:MAX_PER_DAY]]


def main():
    cand = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8")]
    judged = {}
    for l in open(sys.argv[2], encoding="utf-8"):
        r = json.loads(l)
        if r["id"].startswith("c"):                    # 같은 파일에 섞인 채점표 결과(g…)는 버린다
            judged[r["id"]] = r["gpu"]
    kept = [c for c in cand if judged.get(c["id"]) is True]
    by = collections.defaultdict(list)
    for c in kept:
        by[(c["season_seed"], c["code"], c["game_day"])].append(c)

    out, n = open("out/season-news-ticker.jsonl", "w", encoding="utf-8"), 0
    for (seed, code, day), items in sorted(by.items()):
        for it in pick(items):
            out.write(json.dumps(dict(
                seasonSeed=int(seed), gameDay=day, kind="NEWS", code=code,
                title=clean_title(it["title"], it["outlet"]), body=clean_excerpt(it["lead"], it["outlet"]),
                link=it["link"], promptVersion=None), ensure_ascii=False) + "\n")
            n += 1
    out.close()
    unjudged = sum(1 for c in cand if judged.get(c["id"]) is None)
    print(f"후보 {len(cand):,} · 판정 없음 {unjudged:,} · 관련 {len(kept):,} · 종목·게임일 {len(by):,} → 시드 {n:,}행")


if __name__ == "__main__":
    main()
