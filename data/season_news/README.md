# data/season_news — 시즌 뉴스 만들기 (ANT-DATA-08~10 · ANT-SEASON-07)

과거 뉴스 데이터셋(`ssafy_dataset_news_<연도>.csv`, 30GB)에서 연습 시즌의 종목 뉴스를 골라 `season_news` 에 넣을
재료를 만든다. 파이썬 표준 라이브러리만 쓴다. 원본 CSV 는 레포 밖에 둔다.

| 단계 | 어디서 | 파일 | 결과 |
|---|---|---|---|
| 1 후보 추출 | 맥 | `extract_candidates.py <원본 폴더>` | `out/candidates.jsonl` (시즌 구간 × 시즌 종목 × 제목 매칭) |
| 2 관련도 판정 | GPU 박스 | `gpu_judge.py candidates.jsonl.gz judged.jsonl` | 기사·종목 쌍마다 관련/무관 |

- `ref/` — 로컬 DB 에서 뽑은 연습 시즌 종목(`season_tickers.csv`)과 게임일 달력(`season_calendar.csv`, 0 은 워밍업 마지막 날).
- `aliases.csv` — 옛 사명·통칭(기아차·현대상선·SKT·LG엔솔…). 시즌 종목 이름 기준.
- `category_exclude.csv` — 김성령 0단계의 카테고리 제외표(281개, `origin/KSR_ANT-DATA-09`). 잡음 규칙도 거기서 가져왔다.
- `check_extract.py` — 게임일 경계·이름 매칭 규칙 확인.
- `out/` 는 커밋하지 않는다.
