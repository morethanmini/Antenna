"""임베딩 입력 추출 — stage0 에서 제목 + 리드만 뽑아 연도별 Parquet 으로 (ANT-DATA-10 1단계 준비).

  python embed_prep.py                 → cache/embed_input_2020.parquet … _2025.parquet
  python embed_prep.py --sample 20000  → cache/embed_input_sample.parquet (로컬 검증용, 연도 균등 표본)

본문(6GB)은 옮기지 않는다. 임베딩은 제목 + 본문 앞 300자만 쓰고, 결과(군집 번호·점수)는 link 로 다시 붙인다.
연도별로 나누는 이유: Jupyter 브라우저 업로드가 파일 하나 수 GB 를 견디지 못한다. 연도당 150~200MB.
"""
import argparse
import os
import sys
from pathlib import Path

import duckdb

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).resolve().parent
CACHE = (HERE / "cache").as_posix()

ap = argparse.ArgumentParser()
ap.add_argument("--sample", type=int, default=0, help="연도 균등 무작위 표본 N건만 뽑아 embed_input_sample.parquet 으로")
args = ap.parse_args()

con = duckdb.connect()
con.sql(f"SET threads TO {max(2, (os.cpu_count() or 4) - 2)}")
con.sql(f"""
CREATE VIEW s0 AS
SELECT link, source, published_at, published_date, year(published_at) AS yr, title,
       -- 임베딩 텍스트: 제목 + 리드. 줄바꿈·연속 공백을 접는다. e5 계열은 'query: ' 접두를 권장하지만 그건 embed 단계에서 붙인다.
       regexp_replace(title || '. ' || substr(article, 1, 300), '\\s+', ' ', 'g') AS text
FROM read_parquet('{CACHE}/stage0.parquet')
""")

if args.sample:
    per_year = args.sample // 6
    out = f"{CACHE}/embed_input_sample.parquet"
    con.sql(f"""
    COPY (
        SELECT link, source, published_at, published_date, yr, title, text
        FROM (SELECT *, row_number() OVER (PARTITION BY yr ORDER BY random()) AS rn FROM s0)
        WHERE rn <= {per_year}
    ) TO '{out}' (FORMAT parquet, COMPRESSION zstd)
    """)
    print(out, con.sql(f"SELECT count(*), round(avg(length(text))) FROM '{out}'").fetchall(), f"{os.path.getsize(out)/1e6:.1f} MB")
else:
    for yr in range(2020, 2026):
        out = f"{CACHE}/embed_input_{yr}.parquet"
        con.sql(f"""
        COPY (SELECT link, source, published_at, published_date, yr, title, text FROM s0 WHERE yr = {yr})
        TO '{out}' (FORMAT parquet, COMPRESSION zstd)
        """)
        n = con.sql(f"SELECT count(*) FROM '{out}'").fetchone()[0]
        print(f"{out}  {n:,} rows  {os.path.getsize(out)/1e6:.0f} MB")
