"""주가·PER/PBR 특화 데이터셋 정제 노트북 (ANT-DATA-07).

marimo 노트북이자 그냥 파이썬 스크립트다.
  대화형:   marimo edit clean_stock.py
  일괄 실행: python clean_stock.py      → out/stock_price.parquet, out/stock_valuation.parquet

원본은 읽기만 한다. 원본 위치는 환경변수 ANT_RAW_DIR 로 바꿀 수 있다
(기본값: 레포 옆의 특화데이터셋/extracted).
"""

import marimo

__generated_with = "0.24.0"
app = marimo.App(width="medium")


@app.cell
def _():
    import os
    from pathlib import Path

    import duckdb
    import marimo as mo

    # 원본 CSV 폴더. 레포 밖(../../특화데이터셋/extracted)이 기본이고 환경변수로 덮는다.
    RAW = Path(
        os.environ.get("ANT_RAW_DIR", Path(__file__).resolve().parent.parent.parent / "특화데이터셋" / "extracted")
    ).as_posix()
    OUT = Path(__file__).resolve().parent / "out"
    OUT.mkdir(exist_ok=True)
    OUT = OUT.as_posix()
    return OUT, RAW, mo


@app.cell
def _(RAW, mo):
    mo.md(f"""
    # 주가 · PER/PBR 정제

    원본: `{RAW}`

    두 데이터 모두 **달력일 전부에 행이 있고 휴장일은 값이 0인 자리채움 행**이다 (2026 시세만 거래일만 있음).
    종목코드는 ISIN(`KR7005930003`) 이라 6자리(`005930`)로 자른다.

    | 산출물 | 대상 테이블 | 키 |
    |---|---|---|
    | `out/stock_price.parquet` | `daily_quotes` (+ `stocks` 마스터 재료) | (stock_code, trade_date) |
    | `out/stock_valuation.parquet` | `stock_valuations` (신설 예정) | (stock_code, trade_date) |
    """)
    return


@app.cell
def _(mo):
    mo.md(r"""
    ## 1. 원본 읽기

    `all_varchar=true` 로 전부 문자열로 받는다 — 종목코드 `005930` 을 숫자로 추론하면 앞자리 0이 사라진다.
    `union_by_name=true` 로 파일마다 다른 컬럼(2026 의 `기준일자`·`저장시간`, 2024~ PER 의 `종목숏코드`)을 이름으로 맞춘다.
    """)
    return


@app.cell
def _(RAW, mo):
    price_raw = mo.sql(
        f"""
        CREATE OR REPLACE VIEW price_raw AS
        SELECT *, regexp_extract(filename, '[^/]+$') AS src_file
        FROM read_csv('{RAW}/ssafy_dataset_stock_price/*.csv',
                      delim='|', header=true, all_varchar=true, union_by_name=true, filename=true)
        """
    )
    return


@app.cell
def _(RAW, mo):
    per_raw = mo.sql(
        f"""
        CREATE OR REPLACE VIEW per_raw AS
        SELECT *, regexp_extract(filename, '[^/]+$') AS src_file
        FROM read_csv('{RAW}/ssafy_dataset_stock_per_pbr/*.csv',
                      delim='|', header=true, all_varchar=true, union_by_name=true, filename=true)
        """
    )
    return


@app.cell
def _(mo, price_raw):
    # 파일별 행 수 · 날짜 범위. 2026 만 '기준일자' 컬럼을 쓴다.
    _df = mo.sql(
        f"""
        SELECT src_file, count(*) AS n_rows,
               min(coalesce(날짜, 기준일자)) AS min_date, max(coalesce(날짜, 기준일자)) AS max_date,
               count(DISTINCT coalesce(날짜, 기준일자)) AS n_days
        FROM price_raw GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(mo, per_raw):
    _df = mo.sql(
        f"""
        SELECT src_file, count(*) AS n_rows, min(날짜) AS min_date, max(날짜) AS max_date, count(DISTINCT 날짜) AS n_days
        FROM per_raw GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(mo):
    mo.md(r"""
    ## 2. 종목코드 — ISIN → 6자리 단축코드

    원본 종목코드는 ISIN(`KR7005930003`) 이고 `stocks.code` 는 6자리 단축코드다. **앞 6자리를 자르면 안 된다** —
    우선주(`KR7005931001` → 실제 코드 `005935`)와 1990년대 신주(`동신제약1신` = `KR7006600019`)가 보통주와 같은 6자리를 갖는다.

    ISIN 구조 `KR7` + 6자리 + 증권종류 2자리(`sec_type`) + 체크 1자리. PER 2024~2025 파일에 (단축코드, ISIN) 쌍이
    있어 규칙을 뽑았고 2,863쌍 전부 재현된다:

    | ISIN 6자리 끝글자 | sec_type | 단축코드 끝글자 | 예 |
    |---|---|---|---|
    | `1` / `2` / `3` | `00` | `5` / `7` / `9` | 삼성전자우 `005931`→`005935`, 현대차2우B→`005387` |
    | `K` | `02` | `L` | 두산퓨얼셀2우B `33626K`→`33626L` |
    | 그 외 | | 그대로 | 두산퓨얼셀1우(`01`) `33626K` 유지 |

    규칙이 안 통하는 것: **외국 ISIN**(`HK…`, `KYG…`, `KR8…` — 단축코드가 `900xxx`/`950xxx`)과 **1990년대 신주**(sec_type `01`~`04`, `11`).
    - 외국 종목은 2025-12-30(ISIN) ↔ 2026-01-02(단축코드) 를 종목명으로 이어 매핑표를 만든다. 매핑 없는 옛 외국 종목(2007~2023, 연 20종 안팎)은 버린다.
    - 신주는 `stocks` 에 자리가 없다(6자리 코드가 없음). sec_type ≠ `00` 이고 매핑표에도 없으면 버린다.
    - 그래도 같은 (코드, 날짜)가 남으면 보통주(6자리 끝 `0`)를 남기고 나머지를 버린다. 2024-02-13 외국 종목처럼 **행 자체가 중복**인 것도 여기서 정리된다.
    """)
    return


@app.cell
def _(RAW, mo, price_raw):
    # 매핑표: PER 2024~25 의 (단축코드, ISIN) + 2025-12-30 ↔ 2026-01-02 종목명 조인(외국 종목용)
    isin_map = mo.sql(
        f"""
        CREATE OR REPLACE TABLE isin_map AS
        WITH per AS (
            SELECT DISTINCT 종목코드 AS isin, 종목숏코드 AS short_code
            FROM read_csv('{RAW}/ssafy_dataset_stock_per_pbr/ssafy_dataset_stock_per_pbr_202[45].csv',
                          delim='|', header=true, all_varchar=true, union_by_name=true)
        ),
        nj AS (
            SELECT DISTINCT a.종목코드 AS isin, b.종목코드 AS short_code
            FROM (SELECT 종목코드, 종목명 FROM price_raw WHERE 날짜 = '20251230') a
            JOIN (SELECT 종목코드, 종목명 FROM price_raw WHERE 기준일자 = '20260102') b USING (종목명)
            WHERE length(a.종목코드) = 12
        )
        SELECT isin, short_code, 'per_2024_2025' AS src FROM per
        UNION ALL
        SELECT isin, short_code, 'name_join_2026' FROM nj WHERE isin NOT IN (SELECT isin FROM per)
        """
    )
    return (isin_map,)


@app.cell
def _(isin_map, mo):
    # 매핑표 검증: ISIN 하나에 단축코드 하나여야 한다
    _df = mo.sql(
        f"""
        SELECT src, count(*) AS n_pairs, count(DISTINCT isin) AS n_isin, count(DISTINCT short_code) AS n_short
        FROM isin_map GROUP BY 1
        """
    )
    return


@app.cell
def _(mo):
    mo.md(r"""
    ## 3. 시세 정제 → `price_clean`

    - **휴장일 자리채움 제거**: `종가 = 0 AND 거래량 = 0`. 상장주식수만 살아 있는 행이다.
    - **종가 = 0 인데 거래량 > 0**: 있으면 안 되는 행. 검증 셀에서 세고, `close NOT NULL` 이라 버린다.
    - **시가·고가·저가만 0** (거래정지 등): 0 을 null 로 두고 close 만 남긴다. `daily_quotes` 가 그렇게 설계돼 있다.
    - **종목코드**: 매핑표 우선, 없으면 위 규칙. 2026 파일은 이미 6자리.
    - **시장구분**: `KOSDAQGLOBAL` → `KOSDAQ` (`Stock.Market` enum 에 없다).
    - 거래대금·대비·등락률은 `daily_quotes` 에 없어 버린다. 시가총액·상장주식수는 `stocks` 마스터 재료로 남긴다.
    """)
    return


@app.cell
def _(isin_map, mo, price_raw):
    # 1단계: 코드 변환만. 버리는 판단은 다음 셀에서 사유별로 센다.
    price_coded = mo.sql(
        f"""
        CREATE OR REPLACE TABLE price_coded AS
        SELECT
            strptime(coalesce(날짜, 기준일자), '%Y%m%d')::DATE AS trade_date,
            종목코드 AS isin,
            CASE WHEN length(종목코드) = 12 THEN substr(종목코드, 10, 2) ELSE '00' END AS sec_type,
            coalesce(
                m.short_code,
                CASE WHEN length(종목코드) <> 12 THEN 종목코드
                     WHEN substr(종목코드, 1, 3) <> 'KR7' THEN NULL           -- 외국 ISIN, 매핑 없음
                     WHEN substr(종목코드, 10, 2) <> '00' THEN NULL            -- 신주 등, 매핑 없음
                     ELSE substr(종목코드, 4, 5) ||
                          CASE substr(종목코드, 9, 1) WHEN '1' THEN '5' WHEN '2' THEN '7' WHEN '3' THEN '9'
                                                     ELSE substr(종목코드, 9, 1) END
                END
            ) AS stock_code,
            종목명 AS name,
            CASE 시장구분 WHEN 'KOSDAQGLOBAL' THEN 'KOSDAQ' ELSE 시장구분 END AS market,
            nullif(시가::DOUBLE, 0)::DECIMAL(14,2) AS open,
            nullif(고가::DOUBLE, 0)::DECIMAL(14,2) AS high,
            nullif(저가::DOUBLE, 0)::DECIMAL(14,2) AS low,
            (종가::DOUBLE)::DECIMAL(14,2)          AS close,
            거래량::BIGINT                         AS volume,
            시가총액::DECIMAL(24,0)                AS market_cap,
            상장주식수::BIGINT                     AS listed_shares,
            src_file
        FROM price_raw p
        LEFT JOIN isin_map m ON m.isin = p.종목코드
        WHERE NOT (종가::DOUBLE = 0 AND 거래량::BIGINT = 0)   -- 휴장일 자리채움
          AND 종가::DOUBLE > 0                                -- close NOT NULL 대상만
        """
    )
    return (price_coded,)


@app.cell
def _(mo, price_coded):
    # 2단계: 코드 못 만든 행 제거 → (코드, 날짜) 충돌은 보통주(ISIN 6자리 끝 '0') 우선, 그다음 ISIN 사전순 → 1행만
    price_clean = mo.sql(
        f"""
        CREATE OR REPLACE TABLE price_clean AS
        SELECT * EXCLUDE (isin, sec_type)
        FROM price_coded
        WHERE stock_code IS NOT NULL
        QUALIFY row_number() OVER (
            PARTITION BY stock_code, trade_date
            ORDER BY (substr(isin, 9, 1) = '0') DESC, sec_type, isin
        ) = 1
        """
    )
    return


@app.cell
def _(mo, price_clean, price_coded):
    # 버린 행 집계 — 코드 없음(외국·신주) vs 충돌로 밀림. 진행상황.md 에 옮긴다.
    _df = mo.sql(
        f"""
        SELECT
            (SELECT count(*) FROM price_coded)                                  AS coded_rows,
            (SELECT count(*) FROM price_coded WHERE stock_code IS NULL
                AND substr(isin, 1, 3) <> 'KR7')                                AS dropped_foreign_unmapped,
            (SELECT count(*) FROM price_coded WHERE stock_code IS NULL
                AND substr(isin, 1, 3) = 'KR7')                                 AS dropped_new_share_types,
            (SELECT count(*) FROM price_coded WHERE stock_code IS NOT NULL)
              - (SELECT count(*) FROM price_clean)                              AS dropped_by_collision,
            (SELECT count(*) FROM price_clean)                                  AS clean_rows
        """
    )
    return


@app.cell
def _(mo, price_coded):
    # 충돌로 밀린 행이 누구인지 — 대부분 1990년대 신주, 일부 우선주(쌍용양회5우B 등)
    _df = mo.sql(
        f"""
        SELECT year(trade_date) AS yr, string_agg(DISTINCT sec_type, ',' ORDER BY sec_type) AS sec_types,
               count(*) AS n_rows, any_value(name) AS example
        FROM (
            SELECT * FROM price_coded
            WHERE stock_code IS NOT NULL
            QUALIFY row_number() OVER (PARTITION BY stock_code, trade_date
                                       ORDER BY (substr(isin, 9, 1) = '0') DESC, sec_type, isin) > 1
        )
        GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(mo, price_clean):
    # 검증 1: (종목, 날짜) 중복. 0 이어야 daily_quotes 의 UNIQUE 를 통과한다.
    _df = mo.sql(
        f"""
        SELECT count(*) AS dup_keys
        FROM (SELECT stock_code, trade_date FROM price_clean GROUP BY 1, 2 HAVING count(*) > 1)
        """
    )
    return


@app.cell
def _(mo, price_raw):
    # 검증 2: 버린 행의 내역. 자리채움 vs "종가 0 인데 거래량 있음"(있으면 원인 봐야 함)
    _df = mo.sql(
        f"""
        SELECT src_file,
               sum(CASE WHEN 종가::DOUBLE = 0 AND 거래량::BIGINT = 0 THEN 1 ELSE 0 END) AS placeholder_rows,
               sum(CASE WHEN 종가::DOUBLE = 0 AND 거래량::BIGINT > 0 THEN 1 ELSE 0 END) AS zero_close_with_volume
        FROM price_raw GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(mo, price_clean):
    # 검증 3: 연도별 거래일 수. 한국 증시는 연 240~250일. 2023 = 8월까지, 2024 = 7/5 하루 결손, 2026 = 상반기.
    _df = mo.sql(
        f"""
        SELECT year(trade_date) AS yr, count(DISTINCT trade_date) AS trading_days,
               count(DISTINCT stock_code) AS stocks, count(*) AS n_rows
        FROM price_clean GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(mo, price_clean):
    # 눈으로 확인: 삼성전자 2025-06 첫 주. 6/1(일)·6/3(대선) 이 빠져 있어야 한다.
    _df = mo.sql(
        f"""
        SELECT * FROM price_clean
        WHERE stock_code = '005930' AND trade_date BETWEEN '2025-05-30' AND '2025-06-06'
        ORDER BY trade_date
        """
    )
    return


@app.cell
def _(mo):
    mo.md(r"""
    ## 4. PER/PBR 정제 → `per_clean`

    - **자리채움 제거**: `종가 = 0`.
    - **0 → null**: EPS·PER·BPS·PBR·배당은 원본에서 적자·미산출이 `0.0` 으로 들어온다. 0 을 그대로 두면 "PER 0" 이 실값으로 읽힌다.
    - **시장구분**: `STK` → `KOSPI`, `KSQ` → `KOSDAQ`. PER 파일에는 KONEX 가 없다.
    - `종목숏코드`(2024~만 있음)·`종목명HTML`·`대비코드`·`대비`·`등락률` 은 버린다. 코드는 ISIN 에서 자르는 쪽으로 통일.
    - 종가는 시세 파일과 겹치므로 여기서는 검증용으로만 두고 Parquet 에는 넣지 않는다.
    """)
    return


@app.cell
def _(isin_map, mo, per_raw):
    per_clean = mo.sql(
        f"""
        CREATE OR REPLACE TABLE per_clean AS
        SELECT
            strptime(날짜, '%Y%m%d')::DATE                        AS trade_date,
            coalesce(m.short_code,
                     substr(종목코드, 4, 5) ||
                     CASE WHEN substr(종목코드, 10, 2) = '00'
                          THEN CASE substr(종목코드, 9, 1) WHEN '1' THEN '5' WHEN '2' THEN '7' WHEN '3' THEN '9' ELSE substr(종목코드, 9, 1) END
                          ELSE substr(종목코드, 9, 1) END)       AS stock_code,
            CASE 시장구분 WHEN 'STK' THEN 'KOSPI' WHEN 'KSQ' THEN 'KOSDAQ' ELSE 시장구분 END AS market,
            nullif(EPS::DOUBLE, 0)::DECIMAL(14,2)                  AS eps,
            nullif(PER::DOUBLE, 0)::DECIMAL(12,2)                  AS per,
            nullif(선행EPS::DOUBLE, 0)::DECIMAL(14,2)              AS forward_eps,
            nullif(선행PER::DOUBLE, 0)::DECIMAL(12,2)              AS forward_per,
            nullif(BPS::DOUBLE, 0)::DECIMAL(14,2)                  AS bps,
            nullif(PBR::DOUBLE, 0)::DECIMAL(12,2)                  AS pbr,
            nullif(주당배당금::DOUBLE, 0)::DECIMAL(14,2)           AS dps,
            nullif(배당수익률::DOUBLE, 0)::DECIMAL(8,2)            AS dividend_yield,
            src_file
        FROM per_raw p
        LEFT JOIN isin_map m ON m.isin = p.종목코드
        WHERE 종가::DOUBLE > 0
        QUALIFY row_number() OVER (PARTITION BY stock_code, trade_date ORDER BY p.종목코드) = 1
        """
    )
    return (per_clean,)


@app.cell
def _(mo, per_clean):
    _df = mo.sql(
        f"""
        SELECT count(*) AS dup_keys
        FROM (SELECT stock_code, trade_date FROM per_clean GROUP BY 1, 2 HAVING count(*) > 1)
        """
    )
    return


@app.cell
def _(mo, per_clean):
    # 검증: 연도별 거래일·종목 수, 그리고 PER 이 실제로 있는 비율 (적자 기업은 null 이 정상)
    _df = mo.sql(
        f"""
        SELECT year(trade_date) AS yr, count(DISTINCT trade_date) AS trading_days,
               count(DISTINCT stock_code) AS stocks, count(*) AS n_rows,
               round(100.0 * count(per) / count(*), 1) AS per_present_pct
        FROM per_clean GROUP BY 1 ORDER BY 1
        """
    )
    return


@app.cell
def _(mo, per_clean, price_clean):
    # 시세와 PER 의 (종목, 날짜) 짝 맞춤. 한쪽에만 있는 키가 얼마나 되는지.
    _df = mo.sql(
        f"""
        SELECT
            (SELECT count(*) FROM price_clean p JOIN per_clean v USING (stock_code, trade_date)) AS both_sides,
            (SELECT count(*) FROM price_clean p ANTI JOIN per_clean v USING (stock_code, trade_date)) AS price_only,
            (SELECT count(*) FROM per_clean v ANTI JOIN price_clean p USING (stock_code, trade_date)) AS per_only
        """
    )
    return


@app.cell
def _(mo):
    mo.md(r"""
    ## 5. 종목 마스터 재료 → `stock_master`

    `stocks` 테이블 채울 때 쓴다. **가장 최근 거래일의 이름·시장·상장주식수**를 취한다 — 사명이 바뀐 종목이 많아 첫 행을 쓰면 옛 이름이 들어간다.
    `listed` 는 마지막 거래일(전체 max) 에 행이 있으면 true.
    """)
    return


@app.cell
def _(mo, price_clean):
    stock_master = mo.sql(
        f"""
        CREATE OR REPLACE TABLE stock_master AS
        SELECT stock_code, name, market, listed_shares,
               trade_date = (SELECT max(trade_date) FROM price_clean) AS listed,
               trade_date AS last_trade_date,
               (SELECT min(trade_date) FROM price_clean p2 WHERE p2.stock_code = p.stock_code) AS first_trade_date
        FROM price_clean p
        QUALIFY row_number() OVER (PARTITION BY stock_code ORDER BY trade_date DESC) = 1
        """
    )
    return


@app.cell
def _(mo, stock_master):
    _df = mo.sql(
        f"""
        SELECT market, listed, count(*) AS stocks FROM stock_master GROUP BY 1, 2 ORDER BY 1, 2
        """
    )
    return


@app.cell
def _(mo):
    mo.md(r"""
    ## 6. Parquet 내보내기

    `out/` 은 gitignore 대상이다. 적재(ANT-DATA-08)는 이 세 파일만 읽는다.
    """)
    return


@app.cell
def _(OUT, mo, per_clean, price_clean, stock_master):
    _df = mo.sql(
        f"""
        COPY (SELECT * EXCLUDE (src_file) FROM price_clean  ORDER BY trade_date, stock_code) TO '{OUT}/stock_price.parquet'     (FORMAT parquet, COMPRESSION zstd);
        COPY (SELECT * EXCLUDE (src_file) FROM per_clean    ORDER BY trade_date, stock_code) TO '{OUT}/stock_valuation.parquet' (FORMAT parquet, COMPRESSION zstd);
        COPY (SELECT * FROM stock_master ORDER BY stock_code)                                 TO '{OUT}/stock_master.parquet'    (FORMAT parquet, COMPRESSION zstd);
        """
    )
    return


@app.cell
def _(OUT, mo):
    # 내보낸 파일을 다시 읽어 행 수·스키마 확인
    _df = mo.sql(
        f"""
        SELECT 'stock_price' AS f, count(*) AS n_rows FROM '{OUT}/stock_price.parquet'
        UNION ALL SELECT 'stock_valuation', count(*) FROM '{OUT}/stock_valuation.parquet'
        UNION ALL SELECT 'stock_master', count(*) FROM '{OUT}/stock_master.parquet'
        """
    )
    return


if __name__ == "__main__":
    app.run()
