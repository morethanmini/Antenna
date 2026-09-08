"""뉴스 데이터셋 탐색 노트북 (ANT-DATA-09 착수 전 눈으로 보기용).

  marimo edit explore_news.py

연도를 처음 고르면 그 해 CSV(3~4GB)를 cache/news_<연도>.parquet 으로 한 번 변환한다(20~30초).
그다음부터는 열 단위로 읽어 1초 안쪽이다. cache/ 는 gitignore(*.parquet) 대상.
정제는 하지 않는다 — 원본 그대로 본다.
"""

import marimo

__generated_with = "0.24.0"
app = marimo.App(width="full")


@app.cell
def _():
    import os
    from pathlib import Path

    import duckdb
    import marimo as mo

    RAW = Path(
        os.environ.get("ANT_RAW_DIR", Path(__file__).resolve().parent.parent.parent / "특화데이터셋" / "extracted")
    ).as_posix()
    CACHE = Path(__file__).resolve().parent / "cache"
    CACHE.mkdir(exist_ok=True)
    CACHE = CACHE.as_posix()
    return CACHE, RAW, duckdb, mo


@app.cell
def _(mo):
    year = mo.ui.dropdown(options=[str(y) for y in range(2020, 2026)], value="2025", label="연도")
    year
    return


@app.cell
def _(CACHE, RAW, duckdb, mo):
    # 고른 연도의 CSV 를 Parquet 으로 한 번만 변환. 이미 있으면 건너뛴다.
    import os as _os

    for i in range(2020, 2026):
        news_path = f"{CACHE}/news_{i}.parquet"
        if not _os.path.exists(news_path):
            with mo.status.spinner(title=f"{i} CSV → Parquet 변환 중 (첫 1회, 20~30초)"):
                duckdb.sql(
                    f"""
                    COPY (
                        SELECT * FROM read_csv('{RAW}/ssafy_dataset_news_{i}.csv',
                                               delim='|', quote='"', header=true, all_varchar=true,
                                               max_line_size=2000000)
                    ) TO '{news_path}' (FORMAT parquet, COMPRESSION zstd)
                    """
                )
        mo.md(f"읽는 파일: `{news_path}`")
    return (news_path,)


@app.cell
def _(mo, news_path):
    news = mo.sql(
        f"""
        CREATE OR REPLACE VIEW news AS
        SELECT * FROM read_parquet('{news_path}') 
        """
    )
    return


@app.cell
def _(mo, news):
    _df = mo.sql(
        f"""
        SELECT company, count(*) AS n_rows
        FROM news
        GROUP BY 1 ORDER BY 2 DESC


        """
    )
    return


@app.cell
def _(mo, news):
    _df = mo.sql(
        f"""
        select distinct category from news;
        """
    )
    return


if __name__ == "__main__":
    app.run()
