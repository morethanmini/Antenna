"""시즌 종목 과거 공시 (season_news 의 DISCLOSURE·IR 행). 파이썬 표준 라이브러리만 쓴다.

과거 뉴스 데이터셋에는 공시가 없다. DART 공시 목록(list.json)을 시즌 종목·시즌 구간으로 받는다.
키는 백엔드와 같은 DART_API_KEY(환경변수 → 없으면 backend/.env). 종목코드→고유번호는 corpCode.xml.

    python3 fetch_disclosures.py        → out/season-news-disclosure.jsonl

- 게임일: 목록에는 접수 시각이 없고 날짜만 있다. 장 마감 뒤 공시를 그날에 붙이면 미래가 새므로
  <b>다음 거래일</b>에 붙인다(늦게 보일 뿐 새지는 않는다). 마지막 날 공시는 시즌 밖이라 버린다.
- 빼는 공시: 임원·주요주주 소유 보고, 5% 대량보유, 정정·첨부, 증권 발행 서류, 기업집단 현황 같은
  잦고 판단 재료가 약한 것. 종목·게임일마다 최대 MAX_PER_DAY 건.
- 제목은 보고서명("사업보고서 (2019.12)" 의 연도는 가린다), 본문은 없음, 링크는 DART 뷰어. 기업설명회는 IR 로 적는다.
"""
import collections, csv, io, json, os, time, urllib.parse, urllib.request, zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, time as dtime

import extract_candidates as ex
import select_news

API = "https://opendart.fss.or.kr/api"
MAX_PER_DAY = 3
SKIP = ("임원ㆍ주요주주", "특정증권등소유상황", "대량보유상황", "기재정정", "첨부정정", "첨부추가", "발행조건확정",
        "증권발행실적", "투자설명서", "일괄신고", "증권신고서", "기업집단", "동일인", "결과보고서", "명의개서정지",
        "주주명부폐쇄", "자율준수프로그램", "지속가능경영", "최대주주등소유주식변동", "특수관계인", "소규모",
        "자기주식처분결과", "신탁계약", "공시대리인", "주식등의대량보유", "의결권대리행사", "사외이사", "독립이사",
        "감사보고서제출", "소집공고", "집중일개최사유", "기업지배구조보고서", "약관에의한금융거래", "주식매수선택권",
        "결산실적공시예고", "기부", "사회공헌")   # 기부·사회공헌은 뉴스 판정과 같은 기준(무관)


def key():
    env = dict(os.environ)
    dotenv = ex.HERE.parent.parent / "backend/.env"
    for line in dotenv.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            env.setdefault(k.strip(), v.strip())
    return env["DART_API_KEY"]


def get(path, **params):
    url = f"{API}/{path}?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def corp_codes(k):
    """종목코드 → DART 고유번호(상장사만)."""
    with zipfile.ZipFile(io.BytesIO(get("corpCode.xml", crtfc_key=k))) as z:
        root = ET.fromstring(z.read(z.namelist()[0]))
    return {e.findtext("stock_code").strip(): e.findtext("corp_code")
            for e in root.iter("list") if (e.findtext("stock_code") or "").strip()}


def disclosures(k, corp, start, end):
    """한 회사·한 구간의 공시 전부. status 013 은 0건, 020 은 한도 소진이라 멈춘다."""
    items, page = [], 1
    while True:
        body = json.loads(get("list.json", crtfc_key=k, corp_code=corp, bgn_de=start.strftime("%Y%m%d"),
                              end_de=end.strftime("%Y%m%d"), page_no=page, page_count=100))
        if body["status"] == "013":
            return items
        if body["status"] != "000":
            raise RuntimeError(f"DART status={body['status']} {body.get('message')}")
        items += body["list"]
        if page >= int(body["total_page"]):
            return items
        page += 1


def main():
    k = key()
    codes = corp_codes(k)
    seasons = ex.load_seasons()
    tickers = collections.defaultdict(list)
    for r in csv.DictReader(open(ex.HERE / "ref/season_tickers.csv", encoding="utf-8")):
        tickers[r["season_seed"]].append(r["code"])
    out, n, calls, missing = open(ex.HERE / "out/season-news-disclosure.jsonl", "w", encoding="utf-8"), 0, 0, []
    for s in seasons:
        start, end = s["cutoffs"][0].date(), s["cutoffs"][-1].date()
        for code in tickers[s["seed"]]:
            if code not in codes:
                missing.append(code)
                continue
            items = disclosures(k, codes[code], start, end)
            calls += 1
            per_day = collections.Counter()
            for d in sorted(items, key=lambda x: x["rcept_no"]):
                name = " ".join(d["report_nm"].split())
                if any(w in name for w in SKIP):
                    continue
                received = datetime.strptime(d["rcept_dt"], "%Y%m%d")
                day = ex.game_day(s["cutoffs"], datetime.combine(received.date(), dtime(23, 59)))   # 다음 거래일
                if day is None or per_day[day] >= MAX_PER_DAY:
                    continue
                per_day[day] += 1
                out.write(json.dumps(dict(
                    seasonSeed=int(s["seed"]), gameDay=day, kind="IR" if "기업설명회" in name else "DISCLOSURE",
                    code=code, title=select_news.mask_dates(name), body=None, link=f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={d['rcept_no']}",
                    promptVersion=None), ensure_ascii=False) + "\n")
                n += 1
            time.sleep(0.1)
    out.close()
    print(f"DART 호출 {calls}회 · 공시 {n:,}행 · 고유번호 없는 종목 {len(missing)}개 {sorted(set(missing))[:10]}")


if __name__ == "__main__":
    main()
