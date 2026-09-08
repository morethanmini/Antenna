"""0단계 — 확실한 잡음만 규칙으로 걷어내고 날짜를 통일한다 (ANT-DATA-09).

  python stage0_news.py          → cache/stage0.parquet + 깔때기 표 출력

판단이 필요한 선별(종목·시장 연관)은 여기서 하지 않는다 — 그건 임베딩·군집·LLM(ANT-DATA-10) 의 일이다.
여기 규칙은 전부 "이 기사는 어떤 문맥에서도 시장 뉴스가 아니다"가 확실한 것만이다:

  1. 카테고리 제외표 category_exclude.csv (스포츠·연예·날씨·사진영상·부고·생활취미·종교, 281개 — 유저 검토 2026-09-08)
  2. 본문 200자 미만 (방송 스크립트 조각·속보 스텁)
  3. 방송 구획 템플릿 제목 (헤드라인·클로징·오프닝·교통 상황·저작권 고지)
  4. 리딩방 광고 기사 (종목추천·매수가 매도가·주식카톡방)
  5. 시의성 −1 이하 (리드에 상록수 표현이 시간 표현보다 많음 — 건강 꿀팁·운세류)
  6. 중복: 같은 (제목, 날짜, 언론사) 는 1건, 같은 link 는 1건

정제: published 4형식 → published_at TIMESTAMP · 제목 첫 줄만 · \\r·NUL 제거 · 빈 문자열 → NULL
원본 위치는 explore_news.py 가 만든 cache/news_2020..2025.parquet.
"""
import os
import sys
import time
from pathlib import Path

import duckdb

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
CACHE = (HERE / "cache").as_posix()
OUT = f"{CACHE}/stage0.parquet"

TEMPLATE = r"^\[?(뉴스\d*|뉴스광장|스포츠\d*|930뉴스|뉴스투데이)[^\]]*헤드라인|^클로징$|^오프닝$|출근길 교통 상황|저작권 고지|이웃 돕기 성금|^\[?날씨\]?|^날씨"
AD = r"종목추천|매수가 매도가|무료로 알려|주식카톡방|리딩방|급등주 (추천|공개)|평생 공짜"
TL_TIME = r"이날|오늘|어제|전날|금일|지난 ?\d{1,2}일|\d{1,2}일 (오전|오후|새벽|밤)|이번 ?주|현지 ?시간|\d{1,2}월 \d{1,2}일"
TL_VERB = r"밝혔다|발표했다|전했다|발생했다|체결했다|공시했다|결정했다|확정했다|출시했다|인수했다|합병|상장했다|기록했다|집계됐다"
TL_EVERGREEN = r"하는 ?법|방법|꿀팁|효능|증상|레시피|추천|알아보기|Q&A|총정리|란\?|이란\?|정리해|알아야|주의해야|건강 ?관리|다이어트|운세"

t0 = time.time()
con = duckdb.connect()
con.sql(f"SET threads TO {max(2, (os.cpu_count() or 4) - 2)}")

con.sql(f"""
CREATE VIEW excl AS SELECT category FROM read_csv('{HERE.as_posix()}/category_exclude.csv', header=true, all_varchar=true)
""")

# ── 정제 + 플래그 ──────────────────────────────────────────────────────────────
con.sql(f"""
CREATE VIEW base AS
WITH raw AS (
    SELECT company, link, published, category, category_str, reporter,
           replace(replace(article, chr(13), ''), chr(0), '') AS article,
           trim(split_part(title, chr(10), 1))                 AS title1
    FROM read_parquet('{CACHE}/news_20*.parquet')
),
ts AS (
    SELECT *,
        -- 4형식: 'YYYY-MM-DD HH:MM' · 'YYYY-MM-DD HH:MM:SS' · 'YYYY.MM.DD HH:MM' · 'YYYY-MM-DD 오전|오후 h:mm:ss'
        coalesce(
            try_strptime(published, '%Y-%m-%d %H:%M:%S'),
            try_strptime(published, '%Y-%m-%d %H:%M'),
            try_strptime(published, '%Y.%m.%d %H:%M'),
            CASE WHEN regexp_matches(published, '오전|오후') THEN
                make_timestamp(
                    regexp_extract(published, '^(\\d{{4}})', 1)::INT,
                    regexp_extract(published, '^\\d{{4}}-(\\d{{2}})', 1)::INT,
                    regexp_extract(published, '^\\d{{4}}-\\d{{2}}-(\\d{{2}})', 1)::INT,
                    (regexp_extract(published, '(\\d{{1,2}}):\\d{{2}}:\\d{{2}}$', 1)::INT % 12)
                        + CASE WHEN published LIKE '%오후%' THEN 12 ELSE 0 END,
                    regexp_extract(published, '\\d{{1,2}}:(\\d{{2}}):\\d{{2}}$', 1)::INT,
                    regexp_extract(published, '\\d{{1,2}}:\\d{{2}}:(\\d{{2}})$', 1)::DOUBLE)
            END
        ) AS published_at
    FROM raw
)
SELECT
    company                                            AS source,
    nullif(title1, '')                                 AS title,
    link,
    published                                          AS published_raw,
    published_at,
    published_at::DATE                                 AS published_date,
    nullif(category, '')                               AS category,
    nullif(category_str, '')                           AS category_str,
    nullif(reporter, '')                               AS reporter,
    article,
    length(article)                                    AS body_chars,
    -- 제외 플래그
    coalesce(category IN (SELECT category FROM excl), false) AS x_category,   -- 분류 없는 21% 는 제외 대상 아님
    length(article) < 200                              AS x_short,
    regexp_matches(title1, '{TEMPLATE}')               AS x_template,
    regexp_matches(title1, '{AD}')                     AS x_ad,
    (length(regexp_extract_all(title1 || ' ' || substr(article, 1, 300), '{TL_TIME}'))
     + length(regexp_extract_all(title1 || ' ' || substr(article, 1, 300), '{TL_VERB}'))
     - length(regexp_extract_all(title1 || ' ' || substr(article, 1, 300), '{TL_EVERGREEN}'))) AS timeliness_rule
FROM ts
""")
print(f"base built ({time.time()-t0:.0f}s)")

# 본문을 뺀 플래그 표만 메모리에 둔다 (1,000만 행 × 작은 열). 본문은 아래 COPY 에서 한 번만 흘린다.
con.sql("""
CREATE TABLE flags AS
SELECT source, title, link, published_raw, published_at, published_date, body_chars,
       x_category, x_short, x_template, x_ad, timeliness_rule
FROM base
""")
print(f"flags built ({time.time()-t0:.0f}s)")
con.sql("""
CREATE TABLE kept AS
SELECT link, published_at
FROM flags
WHERE NOT x_category AND NOT x_short AND NOT x_template AND NOT x_ad
  AND coalesce(timeliness_rule, 0) > -1
  AND title IS NOT NULL AND published_at IS NOT NULL
QUALIFY row_number() OVER (PARTITION BY title, published_date, source ORDER BY link) = 1
""")
con.sql("""
CREATE TABLE stage0_keys AS
SELECT link FROM kept
QUALIFY row_number() OVER (PARTITION BY link ORDER BY published_at) = 1
""")

# ── 깔때기 ───────────────────────────────────────────────────────────────────
print("\n### 깔때기")
con.sql("""
SELECT step, n_rows, n_rows - lag(n_rows) OVER (ORDER BY ord) AS delta FROM (
  SELECT 0 ord, 'total' step, count(*) n_rows FROM flags
  UNION ALL SELECT 1, '1 카테고리 제외표', count(*) FROM flags WHERE NOT x_category
  UNION ALL SELECT 2, '2 본문 200자', count(*) FROM flags WHERE NOT x_category AND NOT x_short
  UNION ALL SELECT 3, '3 템플릿 제목', count(*) FROM flags WHERE NOT x_category AND NOT x_short AND NOT x_template
  UNION ALL SELECT 4, '4 광고', count(*) FROM flags WHERE NOT x_category AND NOT x_short AND NOT x_template AND NOT x_ad
  UNION ALL SELECT 5, '5 시의성 > -1', count(*) FROM flags WHERE NOT x_category AND NOT x_short AND NOT x_template AND NOT x_ad AND coalesce(timeliness_rule,0) > -1
  UNION ALL SELECT 6, '6 제목·날짜 파싱 실패 제외', count(*) FROM flags WHERE NOT x_category AND NOT x_short AND NOT x_template AND NOT x_ad AND coalesce(timeliness_rule,0) > -1 AND title IS NOT NULL AND published_at IS NOT NULL
  UNION ALL SELECT 7, '7 (제목,날짜,언론사) 중복', count(*) FROM kept
  UNION ALL SELECT 8, '8 link 중복 → stage0', count(*) FROM stage0_keys
) ORDER BY ord
""").show()

print("\n### 날짜 파싱 검증 — 형식별 실패 수 (0 이어야)")
con.sql("""
SELECT CASE WHEN published_raw ~ '^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$' THEN 'YYYY-MM-DD HH:MM'
            WHEN published_raw ~ '^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}$' THEN 'YYYY-MM-DD HH:MM:SS'
            WHEN published_raw ~ '^\\d{4}\\.\\d{2}\\.\\d{2} \\d{2}:\\d{2}$' THEN 'YYYY.MM.DD HH:MM'
            WHEN regexp_matches(published_raw, '오전|오후') THEN '오전/오후' ELSE 'OTHER' END AS fmt,
       count(*) AS n_rows, sum((published_at IS NULL)::int) AS parse_fail
FROM flags GROUP BY 1 ORDER BY 2 DESC
""").show()
print("\n### 오전/오후 변환 표본")
con.sql("SELECT published_raw, published_at FROM flags WHERE regexp_matches(published_raw, '오전|오후') ORDER BY random() LIMIT 6").show()

print("\n### stage0 연도별")
con.sql("SELECT year(published_at) AS yr, count(*) AS n_rows, count(DISTINCT source) AS sources FROM flags f JOIN stage0_keys k USING (link) GROUP BY 1 ORDER BY 1").show()

# 본문은 여기서 딱 한 번 읽어 바로 쓴다 (정렬하지 않는다 — 정렬은 본문 전체를 메모리에 올린다)
con.sql(f"""
COPY (
    SELECT b.source, b.title, b.link, b.published_raw, b.published_at, b.published_date,
           b.category, b.category_str, b.reporter, b.article, b.body_chars, b.timeliness_rule
    FROM base b JOIN stage0_keys k USING (link)
) TO '{OUT}' (FORMAT parquet, COMPRESSION zstd)
""")
print(f"\n→ {OUT} ({os.path.getsize(OUT)/1e9:.2f} GB, {time.time()-t0:.0f}s)")
