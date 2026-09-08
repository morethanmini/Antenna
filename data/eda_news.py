"""뉴스 골든 데이터셋 선별 EDA (ANT-DATA-09 1단계).

  marimo edit eda_news.py        대화형
  python eda_news.py             일괄 실행 (cache/tagged.parquet 생성)

원본은 explore_news.py 가 만든 cache/news_2020..2025.parquet (무가공, 전부 문자열).
무거운 태깅(§3)은 한 번 돌려 cache/tagged.parquet 에 굳히고, 그 아래 깔때기·차트는 그 위에서 1초 안쪽으로 돈다.
기준(임계·사전)을 바꾸면 깔때기만 다시 돌고, 사전을 바꾸면 태깅부터 다시 돈다 — 그때는 tagged.parquet 을 지운다.

설계 근거: .claude/docs-personal/impl/ANT-DATA-09/plan.md §설계
"""

import marimo

__generated_with = "0.24.0"
app = marimo.App(width="full")


# ──────────────────────────────────────────────────────────────────────────────
# 0. 경로 · 라이브러리
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _():
    import csv
    import os
    import re
    from pathlib import Path

    import duckdb
    import marimo as mo

    HERE = Path(__file__).resolve().parent
    CACHE = (HERE / "cache").as_posix()
    OUT = (HERE / "out").as_posix()
    TAGGED = f"{CACHE}/tagged.parquet"
    return CACHE, OUT, TAGGED, csv, duckdb, mo, os, re


@app.cell
def _(mo):
    mo.md(
        r"""
    # 뉴스 골든 데이터셋 선별 — EDA

    **목표**: 2020~2025 뉴스 990만 건에서 "주가에 유효한" 기사만 남기는 기준을 숫자로 정한다.

    | 축 | 무엇을 보나 | 왜 |
    |---|---|---|
    | 종목 (A등급) | 제목·리드에 KOSPI 535 종목명 또는 별칭 | 시즌에서 `ticker_id` 가 붙는 뉴스 |
    | 시장 (B등급) | 제목·리드에 매크로 주제 사전 v2 | 트럼프 발언·전쟁처럼 종목 없이 시장을 움직이는 뉴스 |
    | **시의성** | 시간 고정어·보도 동사 vs 상록수 표현 | 건강정보처럼 어느 날에 놓아도 같은 글은 주가 신호가 아니다 |
    | 제외 | 스포츠·문화 · 짧은 본문 · 방송 템플릿 · 중복 | 잡음 |

    언론사는 거르지 않는다(유저 결정). 사람이 정한 가중치 컬럼도 두지 않는다 — LLM 채점은 별도 이슈.
    """
    )
    return


# ──────────────────────────────────────────────────────────────────────────────
# 1. 종목명 사전 — 공식(운영 stocks) + 이력(stock_price.parquet) + 수동 별칭(aliases.csv)
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _(mo):
    mo.md(
        r"""
    ## 1. 종목명 사전

    세 층을 합친다. **그룹·업종어**('국산차', '조선 3사')는 종목이 아니라 주제라서 여기 넣지 않는다.

    - `OFFICIAL` 운영 `stocks` 535종 (cache/kospi_stocks.csv)
    - `HISTORY` 2018년 이후 사명 변경 전 이름 (out/stock_price.parquet 의 코드별 종목명 변천)
    - `ALIAS` 수동 별칭 (aliases.csv). 현기차처럼 한 별칭이 두 코드를 가리킬 수 있다

    2자 이하 이름(LG·SK·CJ·기아·한화…)은 **제목에서 경계 조건으로만** 잡고 본문 확인에는 쓰지 않는다.
    언론사 이름과 겹치는 종목(`SBS`)은 그 언론사 기사에서 제외한다.
    """
    )
    return


@app.cell
def _(CACHE, OUT, mo):
    mo.sql(
        f"""
        CREATE OR REPLACE TABLE names AS
        WITH official AS (
            SELECT code AS stock_code, name AS nm, 'OFFICIAL' AS kind
            FROM read_csv('{CACHE}/kospi_stocks.csv', header=true, all_varchar=true)
        ),
        history AS (
            -- 2018 이후 쓰인 이름 중 현재 공식명과 다른 것. 2020 기사가 옛 사명으로 부르는 경우를 잡는다.
            SELECT DISTINCT p.stock_code, p.name AS nm, 'HISTORY' AS kind
            FROM read_parquet('{OUT}/stock_price.parquet') p
            JOIN official o USING (stock_code)
            WHERE p.trade_date >= '2018-01-01' AND p.name <> o.nm
        ),
        alias AS (
            SELECT stock_code, alias AS nm, 'ALIAS' AS kind
            FROM read_csv('{CACHE}/../aliases.csv', header=true, all_varchar=true)
        )
        SELECT stock_code, nm, kind, length(nm) AS nm_len
        FROM (SELECT * FROM official UNION ALL SELECT * FROM history UNION ALL SELECT * FROM alias)
        WHERE nm IS NOT NULL AND nm <> ''
        """,
        output=False,
    )
    names = mo.sql("SELECT * FROM names ORDER BY kind, nm")
    return (names,)


@app.cell
def _(mo, names):
    _df = mo.sql(
        f"""
        SELECT kind, count(*) AS n_names, sum((nm_len <= 2)::int) AS short_names,
               string_agg(CASE WHEN nm_len <= 2 THEN nm END, ' ') AS short_list
        FROM names GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(mo, names, re):
    # 정규식 두 벌. 긴 이름은 그대로, 2자 이름은 앞뒤가 한글·영문이 아닐 때만 (예: "LG전자" 의 LG 는 안 잡힘 — LG전자로 잡힌다).
    _rows = names.to_dicts() if hasattr(names, "to_dicts") else names.to_dict("records")
    _long = sorted({r["nm"] for r in _rows if r["nm_len"] >= 3}, key=len, reverse=True)
    _short = sorted({r["nm"] for r in _rows if r["nm_len"] <= 2})
    RX_LONG = "(" + "|".join(re.escape(n) for n in _long) + ")"
    RX_SHORT = "(?:^|[^A-Za-z가-힣])(" + "|".join(re.escape(n) for n in _short) + ")(?:[^A-Za-z가-힣]|$)"
    # 언론사명과 겹치는 종목 — 그 언론사의 기사에서는 종목 매칭으로 치지 않는다
    PUBLISHER_NAMES = {"SBS": "SBS뉴스"}
    mo.md(f"긴 이름 {len(_long)}개 · 2자 이름 {len(_short)}개 → 정규식 길이 {len(RX_LONG):,}자")
    return PUBLISHER_NAMES, RX_LONG, RX_SHORT


# ──────────────────────────────────────────────────────────────────────────────
# 2. 매크로 주제 사전 v2 · 시의성 표현 · 템플릿
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _(mo):
    mo.md(
        r"""
    ## 2. 사전

    **매크로 v2** — v1(1차 EDA)에서 범용 단어를 뺐다. 원칙: 주가 맥락 밖에서도 흔한 단어는 두 단어 조합으로.
    v1 대비 GEOPOLITICS(51만)·DOMESTIC_POL(55만)이 절반 이하로 줄어야 하고, 2022 지정학·2025 관세 스파이크는 남아야 한다.

    **시의성** — 점수 = 시간 고정어 수 + 보도 동사 수 − 상록수 표현 수. 리드(제목 + 본문 앞 300자)에서만 센다.
    규칙 근사라는 뜻으로 컬럼명을 `timeliness_rule` 로 둔다.
    """
    )
    return


@app.cell
def _():
    MACRO_V2 = {
        "RATE_FX":      r"기준금리|금리 ?인상|금리 ?인하|금리 ?동결|한국은행|한은 (총재|금통위)|금통위|연준|FOMC|파월|환율|원[·/]?달러|달러 (강세|약세)|통화정책|양적",
        "TRADE_TARIFF": r"관세|무역 ?협상|무역 ?전쟁|수출 ?규제|보복 ?관세|반도체법|IRA|트럼프 (관세|행정명령|발언|행정부)|USTR|상호관세",
        "GEOPOLITICS":  r"개전|공습|침공|휴전|정전 협정|확전|호르무즈|대만해협|北 ?미사일|북한 ?미사일|핵 ?실험|전쟁 (발발|우려|위기|장기화)|지정학",
        "COMMODITY":    r"국제유가|유가 (급등|급락|상승|하락)|WTI|브렌트유|OPEC|천연가스 가격|구리 가격|니켈 가격|리튬 가격|금값|원자재 가격",
        "SEMI_TECH":    r"반도체 (업황|수출|수요|가격|사이클)|D램|DRAM|HBM|낸드|엔비디아|TSMC|파운드리|AI 반도체|데이터센터 투자",
        "DOMESTIC_POL": r"탄핵|추경|추가경정|상법 ?개정|밸류업|금융위|금감원|공정위 (제재|과징금|조사)|세제 ?개편|법인세|대선 (결과|공약)|총선 (결과|공약)",
        "MARKET":       r"코스피|코스닥|외국인 (순매도|순매수)|기관 (순매도|순매수)|공매도|증시|주가지수|시총|서킷브레이커|사이드카|마감 ?시황|장중",
        "MACRO_DATA":   r"GDP|경제성장률|소비자물가|CPI|생산자물가|고용지표|실업률|수출액|무역수지|경상수지|PMI|산업생산|소비심리",
        "REALESTATE":   r"주택담보|LTV|DSR|부동산 (규제|대책|PF)|분양가 상한|재건축 (규제|허용)|집값 (급등|급락|하락|상승)",
        "CRYPTO":       r"비트코인|가상자산|이더리움|업비트|코인 (시세|급등|급락)",
    }
    # 시의성 (+) 시간 고정어 / (+) 보도 동사 / (−) 상록수 표현
    TL_TIME = r"이날|오늘|어제|전날|금일|지난 ?\d{1,2}일|\d{1,2}일 (오전|오후|새벽|밤)|이번 ?주|현지 ?시간|\d{1,2}월 \d{1,2}일"
    TL_VERB = r"밝혔다|발표했다|전했다|발생했다|체결했다|공시했다|결정했다|확정했다|출시했다|인수했다|합병|상장했다|기록했다|집계됐다"
    TL_EVERGREEN = r"하는 ?법|방법|꿀팁|효능|증상|레시피|추천|알아보기|Q&A|총정리|란\?|이란\?|정리해|알아야|주의해야|건강 ?관리|다이어트|운세"
    # 방송 구획·안내 템플릿 (유저 결정: 반복 횟수 아니라 명시 정규식으로)
    TEMPLATE = r"^\[?(뉴스\d*|뉴스광장|스포츠\d*|930뉴스|뉴스투데이)[^\]]*헤드라인|^클로징$|^오프닝$|출근길 교통 상황|저작권 고지|이웃 돕기 성금|^\[?날씨\]?|^날씨"
    return MACRO_V2, TEMPLATE, TL_EVERGREEN, TL_TIME, TL_VERB


# ──────────────────────────────────────────────────────────────────────────────
# 3. 태깅 테이블 (무거움 — 한 번만)
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _(mo):
    mo.md(
        r"""
    ## 3. 태깅 → `cache/tagged.parquet`

    본문은 여기서 버린다(태깅에만 쓰고 저장 안 함). 행 하나가 기사 하나, 원본 990만 행이 그대로 990만 행이어야 한다.
    A등급 후보(리드에 종목명)에는 그 이름이 본문에 몇 번 나오는지 세어 둔다 — 스쳐 지나가는 언급(1회)과 주제(2회+)를 나누는 근거.
    """
    )
    return


@app.cell
def _(CACHE, MACRO_V2, PUBLISHER_NAMES, RX_LONG, RX_SHORT, TAGGED, TEMPLATE, TL_EVERGREEN, TL_TIME, TL_VERB, mo, os):
    import duckdb as _duckdb

    if not os.path.exists(TAGGED):
        _pub_excl = " OR ".join(f"(company = '{p}' AND stock_title_nm = '{n}')" for n, p in PUBLISHER_NAMES.items()) or "false"
        _macro_cols = ",\n            ".join(f"regexp_matches(lead, '{rx}') AS m_{k.lower()}" for k, rx in MACRO_V2.items())
        with mo.status.spinner(title="태깅 중 — 990만 행, 4~6분"):
            _duckdb.sql(f"SET threads TO {max(2, os.cpu_count() - 2)}")
            _duckdb.sql(
                f"""
                COPY (
                WITH base AS (
                    SELECT company, link, published, article,
                           substr(published, 1, 4) AS yr, substr(published, 1, 7) AS ym, substr(published, 1, 10) AS d,
                           trim(split_part(title, chr(10), 1)) AS title1,
                           length(article) AS alen,
                           trim(split_part(title, chr(10), 1)) || ' ' || substr(article, 1, 300) AS lead,
                           CASE WHEN category = '' OR category IS NULL THEN 'NONE'
                                WHEN regexp_matches(category || '|' || category_str, '스포츠|야구|축구|골프|e스포츠') THEN 'SPORTS'
                                WHEN regexp_matches(category || '|' || category_str, '연예|문화|엔터|방송|영화|음악|스타|라이프|건강|여행|푸드|패션|게임|만화|종교|교육|날씨|오피니언|사설|칼럼|포토|사진|TV') THEN 'CULTURE'
                                WHEN regexp_matches(category || '|' || category_str, '증권|주식|금융|경제|산업|기업|재계|부동산|IT|과학|테크|마켓|투자') THEN 'ECON'
                                WHEN regexp_matches(category || '|' || category_str, '정치|국회|대선|청와대|외교') THEN 'POLITICS'
                                WHEN regexp_matches(category || '|' || category_str, '국제|세계|해외') THEN 'WORLD'
                                ELSE 'OTHER' END AS ccat
                    FROM read_parquet('{CACHE}/news_20*.parquet')
                ),
                tagged AS (
                    SELECT company, link, published, yr, ym, d, title1, alen, ccat,
                           regexp_matches(title1, '{TEMPLATE}')                                   AS is_template,
                           -- 종목: 제목(긴 이름 → 없으면 2자 이름) / 리드(긴 이름)
                           coalesce(nullif(regexp_extract(title1, '{RX_LONG}', 1), ''),
                                    nullif(regexp_extract(title1, '{RX_SHORT}', 1), ''))          AS stock_title_nm,
                           nullif(regexp_extract(lead, '{RX_LONG}', 1), '')                       AS stock_lead_nm,
                           -- 매크로 v2
                           {_macro_cols},
                           -- 시의성
                           length(regexp_extract_all(lead, '{TL_TIME}'))                          AS tl_time,
                           length(regexp_extract_all(lead, '{TL_VERB}'))                          AS tl_verb,
                           length(regexp_extract_all(lead, '{TL_EVERGREEN}'))                     AS tl_evergreen,
                           article
                    FROM base
                )
                SELECT * EXCLUDE (article),
                       -- 리드에 잡힌 종목명이 본문에 몇 번 나오나 (replace 트릭: 패턴이 컬럼이어도 된다)
                       CASE WHEN stock_lead_nm IS NOT NULL
                            THEN (length(article) - length(replace(article, stock_lead_nm, ''))) / length(stock_lead_nm)
                            ELSE 0 END                                                             AS stock_body_cnt,
                       tl_time + tl_verb - tl_evergreen                                            AS timeliness_rule,
                       CASE WHEN {_pub_excl} THEN NULL ELSE stock_title_nm END                     AS stock_title_nm_ok
                FROM tagged
                ) TO '{TAGGED}' (FORMAT parquet, COMPRESSION zstd)
                """
            )
    mo.md(f"태깅 파일: `{TAGGED}` ({os.path.getsize(TAGGED) / 1e6:,.0f} MB)")
    return


@app.cell
def _(TAGGED, mo):
    t = mo.sql(
        f"""
        CREATE OR REPLACE VIEW t AS SELECT * FROM read_parquet('{TAGGED}')
        """
    )
    return


@app.cell
def _(mo, t):
    # 검증: 원본 행 수와 같아야 한다 (990만). 연도별.
    _df = mo.sql(
        f"""
        SELECT yr, count(*) AS n_rows,
               sum((stock_title_nm_ok IS NOT NULL)::int) AS title_stock,
               sum((stock_lead_nm IS NOT NULL)::int) AS lead_stock,
               sum((stock_lead_nm IS NOT NULL AND stock_body_cnt >= 2)::int) AS lead_stock_body2,
               sum(is_template::int) AS templates
        FROM t GROUP BY 1 ORDER BY 1
        """
    )
    return


# ──────────────────────────────────────────────────────────────────────────────
# 4. 매크로 v2 검증 — v1 대비 줄었나, 스파이크는 남았나
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _(MACRO_V2, mo, t):
    _cols = ", ".join(f"sum(m_{k.lower()}::int) AS {k}" for k in MACRO_V2)
    macro_by_year = mo.sql(
        f"""
        SELECT yr, {_cols} FROM t GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(MACRO_V2, mo, t):
    # 주제별 표본 8건 — 오탐 눈검사용. 주제를 골라 본다.
    topic = mo.ui.dropdown(options=list(MACRO_V2.keys()), value="GEOPOLITICS", label="매크로 주제")
    topic
    return (topic,)


@app.cell
def _(mo, t, topic):
    _df = mo.sql(
        f"""
        SELECT yr, company, title1 FROM t WHERE m_{topic.value.lower()} USING SAMPLE 12 ROWS
        """
    )
    return


# ──────────────────────────────────────────────────────────────────────────────
# 5. 시의성 점수 — 분포와 표본
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _(mo, t):
    _df = mo.sql(
        f"""
        SELECT timeliness_rule, count(*) AS n_rows, round(100.0 * count(*) / sum(count(*)) OVER (), 2) AS pct
        FROM t GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(mo, t):
    # 상위·하위 표본. 하위에 건강·레시피, 상위에 공시·사건이 오면 방향이 맞다.
    _df = mo.sql(
        f"""
        (SELECT 'LOW' AS side, timeliness_rule, company, title1 FROM t WHERE timeliness_rule <= -1 USING SAMPLE 12 ROWS)
        UNION ALL
        (SELECT 'HIGH', timeliness_rule, company, title1 FROM t WHERE timeliness_rule >= 4 USING SAMPLE 12 ROWS)
        ORDER BY side, timeliness_rule
        """
    )
    return


# ──────────────────────────────────────────────────────────────────────────────
# 6. 깔때기 — 기준을 슬라이더로 바꾸며 잔존 수 확인
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _(mo):
    min_body = mo.ui.slider(start=0, stop=500, step=50, value=200, label="본문 최소 글자")
    min_tl = mo.ui.slider(start=-3, stop=5, step=1, value=0, label="시의성 점수 최소")
    body2 = mo.ui.checkbox(value=True, label="리드 종목은 본문 2회 이상만 A등급")
    mo.hstack([min_body, min_tl, body2], gap=2, wrap=True)
    return body2, min_body, min_tl


@app.cell
def _(MACRO_V2, body2, min_body, min_tl, mo, t):
    _macro_any = " OR ".join(f"m_{k.lower()}" for k in MACRO_V2)
    _a = f"(stock_title_nm_ok IS NOT NULL OR (stock_lead_nm IS NOT NULL{' AND stock_body_cnt >= 2' if body2.value else ''}))"
    funnel = mo.sql(
        f"""
        WITH s AS (
            SELECT *,
                   ccat NOT IN ('SPORTS','CULTURE')                    AS f1_not_sports_culture,
                   alen >= {min_body.value}                            AS f2_body,
                   NOT is_template                                     AS f3_not_template,
                   timeliness_rule >= {min_tl.value}                   AS f4_timely,
                   {_a}                                                AS grade_a,
                   ({_macro_any})                                      AS grade_b
            FROM t
        )
        SELECT 'total' AS step, count(*) AS n_rows FROM s
        UNION ALL SELECT '1 스포츠·문화 제외', count(*) FROM s WHERE f1_not_sports_culture
        UNION ALL SELECT '2 본문 길이', count(*) FROM s WHERE f1_not_sports_culture AND f2_body
        UNION ALL SELECT '3 템플릿 제외', count(*) FROM s WHERE f1_not_sports_culture AND f2_body AND f3_not_template
        UNION ALL SELECT '4 시의성', count(*) FROM s WHERE f1_not_sports_culture AND f2_body AND f3_not_template AND f4_timely
        UNION ALL SELECT '5a A등급(종목)', count(*) FROM s WHERE f1_not_sports_culture AND f2_body AND f3_not_template AND f4_timely AND grade_a
        UNION ALL SELECT '5b B등급(시장)', count(*) FROM s WHERE f1_not_sports_culture AND f2_body AND f3_not_template AND f4_timely AND grade_b AND NOT grade_a
        UNION ALL SELECT '5 A∪B', count(*) FROM s WHERE f1_not_sports_culture AND f2_body AND f3_not_template AND f4_timely AND (grade_a OR grade_b)
        UNION ALL SELECT '6 (제목,날짜,언론사) 중복 제거', count(*) FROM (
            SELECT DISTINCT title1, d, company FROM s WHERE f1_not_sports_culture AND f2_body AND f3_not_template AND f4_timely AND (grade_a OR grade_b))
        UNION ALL SELECT '7 링크 중복 제거', count(DISTINCT link) FROM s WHERE f1_not_sports_culture AND f2_body AND f3_not_template AND f4_timely AND (grade_a OR grade_b)
        """
    )
    return


# ──────────────────────────────────────────────────────────────────────────────
# 7. 종목 커버리지 — 535종목 중 기사가 있는 종목, 종목별 A등급 기사 수
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _(mo, names, t):
    # 제목 매칭 이름 → 종목코드로 접는다 (별칭·이력 포함). 현기차처럼 두 코드에 걸리면 둘 다 센다.
    mo.sql(
        f"""
        CREATE OR REPLACE TABLE stock_cov AS
        SELECT n.stock_code, o.nm AS official_nm, count(*) AS n_articles,
               count(DISTINCT t.d) AS n_days, min(t.d) AS first_d, max(t.d) AS last_d
        FROM t
        JOIN names n ON n.nm = t.stock_title_nm_ok
        JOIN (SELECT stock_code, nm FROM names WHERE kind = 'OFFICIAL') o USING (stock_code)
        WHERE t.ccat NOT IN ('SPORTS','CULTURE') AND NOT t.is_template
        GROUP BY 1, 2
        """,
        output=False,
    )
    stock_cov = mo.sql("SELECT * FROM stock_cov")
    return (stock_cov,)


@app.cell
def _(mo, stock_cov):
    _df = mo.sql(
        f"""
        SELECT * FROM stock_cov ORDER BY n_articles DESC LIMIT 30
        """
    )
    return


@app.cell
def _(mo, stock_cov):
    # 누적: 기사 N건 이상인 종목 수. 시즌에 쓸 수 있는 종목의 상한이 여기서 나온다.
    _df = mo.sql(
        f"""
        SELECT th AS min_articles, count(*) AS n_stocks
        FROM (VALUES (10),(50),(100),(300),(1000),(3000)) v(th), stock_cov
        WHERE n_articles >= th GROUP BY 1 ORDER BY 1
        """
    )
    return


# ──────────────────────────────────────────────────────────────────────────────
# 8. 별칭 후보 — 제목에 공식 종목명이 있는 기사에서 함께 나오는 단어
# ──────────────────────────────────────────────────────────────────────────────
@app.cell
def _(mo):
    mo.md(
        r"""
    ## 8. 별칭 후보

    공식 종목명이 제목에 있는 기사의 제목을 띄어쓰기·문장부호로 쪼개, 그 종목과 자주 같이 나오는 단어를 뽑는다.
    '정의선'·'갤럭시'처럼 별칭이 아닌 것도 섞이므로 **채택은 사람이** 한다 → `aliases.csv`.
    """
    )
    return


@app.cell
def _(mo, names):
    target = mo.ui.dropdown(
        options=sorted({r["nm"] for r in (names.to_dicts() if hasattr(names, "to_dicts") else names.to_dict("records")) if r["kind"] == "OFFICIAL"}),
        value="현대차", label="종목")
    target
    return (target,)


@app.cell
def _(mo, t, target):
    _df = mo.sql(
        rf"""
        WITH toks AS (
            SELECT unnest(regexp_split_to_array(title1, '[\s\[\]\(\)…·,\."“”‘’]+')) AS tok
            FROM t WHERE stock_title_nm_ok = '{target.value}'
        )
        SELECT tok, count(*) AS n FROM toks
        WHERE length(tok) BETWEEN 2 AND 8 AND tok <> '{target.value}'
          AND NOT regexp_matches(tok, '^[0-9%]+$|^(의|에|을|를|이|가|는|은|도|로|와|과)$')
        GROUP BY 1 ORDER BY 2 DESC LIMIT 40
        """
    )
    return


if __name__ == "__main__":
    app.run()
