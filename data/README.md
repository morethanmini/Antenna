# data/ — 특화 데이터셋 전처리 (ANT-DATA-09)

SSAFY 특화 데이터셋(뉴스 2020~2025 · 주가 · PER/PBR)을 운영 DB 에 넣기 전에 정제·선별하는 파이썬 작업대다.
DuckDB 로 CSV 를 SQL 로 다루고, marimo 노트북으로 탐색한다. 백엔드(Java)와 무관하며 Gradle 빌드에 포함되지 않는다.

지라: [ANT-DATA-09 뉴스 전처리](https://ssafy.atlassian.net/browse/S15P21A507-218) · [ANT-DATA-08 적재](https://ssafy.atlassian.net/browse/S15P21A507-212) · [ANT-DATA-10 군집화+LLM 채점](https://ssafy.atlassian.net/browse/S15P21A507-219)

## 준비

1. 원본 데이터셋을 받아 레포 **옆**에 둔다: `../특화데이터셋/extracted/` (다른 곳이면 환경변수 `ANT_RAW_DIR` 로 지정)
   - `ssafy_dataset_news_2020.csv` … `_2025.csv`
   - `ssafy_dataset_stock_price/*.csv`, `ssafy_dataset_stock_per_pbr/*.csv`
2. 가상환경 (Windows PowerShell 기준, 이 폴더에서)
   ```
   python -m venv .venv
   .\.venv\Scripts\pip install -r requirements.txt
   ```
3. 노트북 열기: `.\.venv\Scripts\marimo.exe edit <파일>.py` → 브라우저가 열린다. `python <파일>.py` 로 돌리면 노트북 전체가 스크립트로 실행된다.

## 파일

| 파일 | 역할 | 첫 실행 |
|---|---|---|
| `explore_news.py` | 뉴스 원본을 눈으로 보는 탐색 노트북. 연도별 CSV 를 `cache/news_<연도>.parquet` 으로 한 번 변환 | 연도당 20~30초 |
| `eda_news.py` | 골든 데이터셋 선별 EDA — 종목명·매크로 주제·시의성 태깅 → `cache/tagged.parquet` → 깔때기·커버리지·별칭 후보 | 태깅 5분, 이후 셀은 초 단위 |
| `clean_stock.py` | 주가·PER/PBR 정제 (ANT-DATA-07, 닫힘). 종목코드 ISIN→6자리 규칙 포함. `out/stock_price.parquet` 등 산출 | 1분 |
| `stage0_news.py` | **0단계 필터**: 카테고리 제외표 + 본문 200자 + 템플릿 + 광고 + 시의성 −1 이하 + 중복 제거, 날짜 4형식 → timestamp. `cache/stage0.parquet` 산출. 이후 임베딩·군집·LLM(ANT-DATA-10)의 입력 | 5~10분 |
| `embed_prep.py` | stage0 에서 제목+리드(본문 앞 300자)만 뽑아 연도별 `cache/embed_input_<연도>.parquet` (300~480MB) + 검증용 2만 건 표본. 본문은 옮기지 않는다 — GPU 서버 업로드용 | 2분 |
| `embed_cluster.py` | 임베딩(sentence-transformers, e5-base) + k-means 군집 → `clusters.parquet`(link→군집)·`cluster_summary.parquet`(군집별 대표 제목). GPU 서버용, `--device cpu` 로 표본 검증 가능 | 793만 건 GPU 1시간 |
| `category_exclude.csv` | 카테고리 제외표 281개 (스포츠·연예·날씨·사진영상·부고·생활취미·종교). 2,310개 중 유저 검토로 확정(2026-09-08). `stage0_news.py` 가 읽는다 | |
| `aliases.csv` | 종목 수동 별칭 (삼전·하닉·현기차…). `eda_news.py` 가 읽는다 | |
| `requirements.txt` | 고정 버전 | |

`cache/`·`out/`·`.venv/` 는 gitignore 대상이다. 지우면 노트북이 다시 만든다.

## 문서

설계·결정 로그·EDA 결과는 담당자(KSR) 개인 문서에 있다. 팀 공유가 필요한 결론은 지라 이슈 설명과 MR 본문으로 옮긴다.
