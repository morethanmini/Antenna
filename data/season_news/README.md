# data/season_news — 시즌 뉴스 만들기 (ANT-DATA-08~10 · ANT-SEASON-07)

과거 뉴스 데이터셋(`ssafy_dataset_news_<연도>.csv`, 30GB)에서 연습 시즌의 종목 뉴스를 골라 `season_news` 에 넣을
재료를 만든다. 파이썬 표준 라이브러리만 쓴다. 원본 CSV 는 레포 밖에 둔다.

| 단계 | 어디서 | 파일 | 결과 |
|---|---|---|---|
| 1 후보 추출 | 맥 | `extract_candidates.py <원본 폴더>` | `out/candidates.jsonl` (시즌 구간 × 시즌 종목 × 제목 매칭) |
| 2 관련도 판정 | GPU 박스 | `gpu_judge.py candidates.jsonl.gz judged.jsonl` | 기사·종목 쌍마다 관련/무관 |
| 3 선별·가림 | 맥 | `select_news.py out/candidates.jsonl out/judged.jsonl` | `out/season-news-ticker.jsonl` (종목·게임일당 5건, 날짜·연도·언론사 가림) |
| 4 시장 이슈 | 맥 | `extract_day_issues.py <원본 폴더>` | `out/day-issues.jsonl` (게임일마다 이슈 제목 30개) |
| 5 AI 요약 | 맥 | `summarize_days.py` (backend/.env 의 AI_* 사용, 144콜) | `out/season-news-hint.jsonl` · 검수표 `out/hint-review.md` |
| 6 공시 | 맥 | `fetch_disclosures.py` (backend/.env 의 DART_API_KEY, 약 600콜) | `out/season-news-disclosure.jsonl` (다음 거래일에 붙임) |
| 7 시드 | 맥 | 아래 한 줄 | `backend/src/main/resources/seed/season-news.jsonl.gz` → 기동 때 `SeasonNewsSeeder` 가 적재 |

```bash
cat out/season-news-ticker.jsonl out/season-news-hint.jsonl out/season-news-disclosure.jsonl | gzip -9n > ../../backend/src/main/resources/seed/season-news.jsonl.gz
```

- `ref/` — 로컬 DB 에서 뽑은 연습 시즌 종목(`season_tickers.csv`)과 게임일 달력(`season_calendar.csv`, 0 은 워밍업 마지막 날).
- `aliases.csv` — 옛 사명·통칭(기아차·현대상선·SKT·LG엔솔…). 시즌 종목 이름 기준.
- `category_exclude.csv` — 김성령 0단계의 카테고리 제외표(281개, `origin/KSR_ANT-DATA-09`). 잡음 규칙도 거기서 가져왔다.
- `check_extract.py` — 게임일 경계·이름 매칭 규칙 확인.
- `out/` 는 커밋하지 않는다.
