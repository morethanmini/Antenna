-- 로컬에서 대회 화면(J-01)을 눈으로 확인할 때 쓰는 시드. **운영에 올라가는 파일이 아니다** —
-- append-only.sql 과 달리 기동할 때 자동으로 실행되지 않고, 손으로 부를 때만 돈다.
--
--   docker exec -i antenna-postgres psql -U antenna -d antenna \
--     < backend/src/main/resources/db/seed-contest.local.sql
--
-- 왜 필요한가 — 대회는 관리자 API(POST /admin/events)로 열리고 배팅은 지갑 서명이 있어야
-- 들어간다. 화면 한 줄 고칠 때마다 그 두 관문을 다 지날 수는 없어서, 화면이 읽는 표만
-- 직접 채운다. 체인에는 아무것도 안 올라가므로 tx 해시 자리에는 '0xseed' 를 넣는다.
--
-- 지울 때는 맨 아래 주석을 본다. **predictions 는 지워지지 않는다** — append-only 트리거가
-- 막는다(db/append-only.sql, ANT-DB-02). 그래서 이 파일을 두 번 돌리면 예측 행이 쌓인다.
-- 남은 예측은 전부 OPEN 이라 적중률·랭킹 집계에는 안 잡히고, "내 예측" 목록에만 보인다.

BEGIN;

-- ── 회차 ──────────────────────────────────────────────
-- opens_at 을 한 시간 전으로 두어 접수 중(OPEN) 상태로 연다. min_stocks 5 가 순위 자격선이다.
INSERT INTO prediction_events (slug, title, status, opens_at, seals_at, base_date, settle_date,
                               min_stake, max_stake, min_stocks, open_tx_hash, created_at)
VALUES ('scalping', '천하제일 단타대회', 'OPEN', now() - interval '1 hour', now() + interval '2 day',
        current_date, current_date + 4, 500, 2000, 5, '0xseed', now());

-- ── 종목 열 개 ────────────────────────────────────────
-- idx 가 온체인 커밋 keccak(eventId, idx, side, noteHash) 의 재료다. 순서를 바꾸면 안 된다.
INSERT INTO prediction_event_stocks (event_id, idx, stock_code, base_price)
SELECT e.id, t.idx, t.code,
       -- 기준가는 그 종목의 최신 종가를 그대로 쓴다. 손으로 정하면(예: 전부 10000)
       -- 등락률이 +2435% 처럼 터무니없이 찍힌다.
       (select q.close from daily_quotes q where q.stock_code = t.code
         order by q.trade_date desc limit 1)
  FROM prediction_events e,
       (VALUES (0::smallint,'005930'), (1,'000660'), (2,'373220'), (3,'005380'), (4,'012450'),
               (5,'034020'), (6,'068270'), (7,'035420'), (8,'298040'), (9,'035720')) AS t(idx, code)
 WHERE e.slug = 'scalping';

-- ── 참가자 셋 ─────────────────────────────────────────
-- 4 는 3종목(자격 미달) · 5 는 6종목(자격 O) · 6 은 2종목(자격 미달).
-- 자격선을 넘은 사람과 못 넘은 사람이 같이 있어야 진행 줄·타일의 두 상태를 다 볼 수 있다.
WITH plan(uid, idx) AS (
  VALUES (4,0),(4,1),(4,2),
         (5,0),(5,1),(5,2),(5,3),(5,4),(5,5),
         (6,3),(6,7)
), ins AS (
  INSERT INTO predictions (user_id, stock_code, direction, horizon, target_price, track, status,
                           base_date, base_price, settle_date, created_at, updated_at)
  SELECT p.uid, s.stock_code,
         CASE WHEN p.idx % 2 = 0 THEN 'UP' ELSE 'DOWN' END,
         3, s.base_price * 1.03, 'REAL', 'OPEN',
         current_date, s.base_price, current_date + 4,
         now() + (p.idx || ' minutes')::interval, now()
    FROM plan p
    JOIN prediction_event_stocks s ON s.idx = p.idx
   WHERE s.event_id = (select id from prediction_events where slug = 'scalping')
  RETURNING id, user_id, stock_code
)
INSERT INTO prediction_event_bets (prediction_id, event_id, event_stock_id, user_id,
                                   attempts, bet_status, bet_index, event_commit, stake, created_at)
-- bet_status 는 ACTIVE 다. 풀 집계(LIVE_FOR_POOL)와 참가자 목록이 PENDING·FAILED 를 빼므로,
-- PENDING 으로 넣으면 배팅은 있는데 화면에는 아무것도 안 보인다.
SELECT i.id, s.event_id, s.id, i.user_id, 1, 'ACTIVE', row_number() over (), '0xseed', 800, now()
  FROM ins i
  JOIN prediction_event_stocks s ON s.stock_code = i.stock_code
 WHERE s.event_id = (select id from prediction_events where slug = 'scalping');

COMMIT;

-- ── 지울 때 (외래키 때문에 순서가 있다) ───────────────
-- DELETE FROM prediction_event_bets WHERE event_commit = '0xseed';
-- DELETE FROM prediction_event_stocks WHERE event_id IN (SELECT id FROM prediction_events WHERE slug = 'scalping');
-- DELETE FROM prediction_events WHERE slug = 'scalping';
--
-- predictions 는 위 셋과 달리 트리거에 막힌다. 정말 지워야 하면 잠깐 끄고 돌리는데,
-- **팀이 일부러 걸어 둔 장치다.** 지우는 범위(id)를 눈으로 확인하고 쓸 것.
--   ALTER TABLE predictions DISABLE TRIGGER trg_predictions_no_delete;
--   DELETE FROM predictions WHERE id IN (...);
--   ALTER TABLE predictions ENABLE TRIGGER trg_predictions_no_delete;
