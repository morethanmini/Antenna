-- 알림 소급(2026-09-16). 알림 발송(ANT-NOTIFY-01)이 배포되기 전에 일어난 일을 한 번 알림으로 만든다.
--
-- ** 기동 때 자동으로 돌지 않는다 ** — application.yaml 의 schema-locations 에 넣지 않았다.
-- 사람이 운영 DB 에 한 번 돌린다:
--   docker exec -i antenna-postgres psql -U "$DB_USER" -d "$DB_NAME" < backend/src/main/resources/db/backfill-notifications.sql
--
-- 규칙
-- - cutoff 앞의 사건만 본다. 그 뒤 것은 코드가 이미 실시간으로 쌓았다 — 겹치면 두 번 알린다.
--   cutoff = 발송 코드가 운영에 올라간 시각(master 파이프라인 #199821 deploy-backend, 2026-09-16 17:31 KST).
-- - 두 번 돌아도 안전하다. (받는 사람, 종류, 링크[, 행위자]) 가 같은 알림이 있으면 건너뛴다.
-- - created_at 은 사건 시각이다 — 목록이 사건 순서로 서고 "N일 전" 이 거짓말을 안 한다. read_at 은 비운다.
-- - 본문·링크는 코드가 만드는 것과 같은 모양이다(ReportService · ReportSocialService · PredictionRegisterService ·
--   TokenIndexService · PredictionSettlementService). 닉네임이 없으면 "누군가" / "구독 중인 채널".
-- - FEE_CHANGED 는 소급하지 않는다 — 언제 얼마로 바뀌었는지 기록이 없다.
-- - 구독 채널의 리포트·예측은 "구독이 시작된 뒤에 올라온 것" 만 — 그 전 것은 구독자가 알림 받을 자리가 없었다.

BEGIN;

CREATE TEMP TABLE backfill_params AS
SELECT TIMESTAMPTZ '2026-09-16 17:31:00+09' AS cutoff;

-- ① SUBSCRIBE — 지금 ACTIVE 인 구독. 받는 사람 = 발행자, 행위자 = 구독자.
INSERT INTO notifications (user_id, actor_id, type, title, body, link_path, created_at)
SELECT s.publisher_id, s.subscriber_id, 'SUBSCRIBE', '새 구독자가 생겼습니다',
       COALESCE(u.nickname, '누군가') || ' 님이 내 채널을 구독했습니다',
       '/channels/' || s.publisher_id,
       COALESCE(s.started_at, s.created_at)
FROM subscriptions s
JOIN users u ON u.id = s.subscriber_id
CROSS JOIN backfill_params p
WHERE s.status = 'ACTIVE'
  AND COALESCE(s.started_at, s.created_at) < p.cutoff
  AND NOT EXISTS (
        SELECT 1 FROM notifications n
        WHERE n.user_id = s.publisher_id AND n.actor_id = s.subscriber_id
          AND n.type = 'SUBSCRIBE' AND n.link_path = '/channels/' || s.publisher_id);

-- ② REPORT_LIKE — (리포트, 사람)당 한 번. 내 글에 내가 누른 것은 제외.
INSERT INTO notifications (user_id, actor_id, type, title, body, link_path, created_at)
SELECT r.user_id, l.user_id, 'REPORT_LIKE', '리포트에 좋아요가 달렸습니다',
       COALESCE(u.nickname, '누군가') || ' 님이 "' || r.title || '" 을 좋아합니다',
       '/reports/' || r.id, l.created_at
FROM report_likes l
JOIN reports r ON r.id = l.report_id
JOIN users u ON u.id = l.user_id
CROSS JOIN backfill_params p
WHERE l.user_id <> r.user_id
  AND l.created_at < p.cutoff
  AND NOT EXISTS (
        SELECT 1 FROM notifications n
        WHERE n.user_id = r.user_id AND n.actor_id = l.user_id
          AND n.type = 'REPORT_LIKE' AND n.link_path = '/reports/' || r.id);

-- ③ REPORT_COMMENT — 보이는 댓글마다. 내가 단 댓글은 제외. 같은 시각의 같은 알림이 있으면 건너뛴다.
INSERT INTO notifications (user_id, actor_id, type, title, body, link_path, created_at)
SELECT r.user_id, c.user_id, 'REPORT_COMMENT', '리포트에 댓글이 달렸습니다',
       LEFT(COALESCE(u.nickname, '누군가') || ' · ' || c.body, 300),
       '/reports/' || r.id, c.created_at
FROM report_comments c
JOIN reports r ON r.id = c.report_id
JOIN users u ON u.id = c.user_id
CROSS JOIN backfill_params p
WHERE c.user_id <> r.user_id
  AND c.status = 'VISIBLE'
  AND c.created_at < p.cutoff
  AND NOT EXISTS (
        SELECT 1 FROM notifications n
        WHERE n.user_id = r.user_id AND n.actor_id = c.user_id
          AND n.type = 'REPORT_COMMENT' AND n.link_path = '/reports/' || r.id
          AND n.created_at = c.created_at);

-- ④ VERDICT — 판정 끝난 실전(REAL) 예측. 받는 사람 = 예측 주인.
INSERT INTO notifications (user_id, actor_id, type, title, body, link_path, created_at)
SELECT pr.user_id, NULL, 'VERDICT', '예측이 판정되었습니다',
       st.name || ' ' || CASE pr.direction WHEN 'UP' THEN '상승' ELSE '하락' END || ' 예측 · '
         || CASE pr.status WHEN 'HIT' THEN '적중' ELSE '미적중' END
         || ' · 오차 ' || COALESCE(pr.error_rate::text, '-') || '%',
       '/predictions/' || pr.id, pr.updated_at
FROM predictions pr
JOIN stocks st ON st.code = pr.stock_code
CROSS JOIN backfill_params p
WHERE pr.status IN ('HIT', 'MISS')
  AND pr.track = 'REAL'
  AND pr.updated_at < p.cutoff
  AND NOT EXISTS (
        SELECT 1 FROM notifications n
        WHERE n.user_id = pr.user_id AND n.type = 'VERDICT' AND n.link_path = '/predictions/' || pr.id);

-- ⑤ REPORT_PUBLISHED — 구독 시작 뒤에 발행된 구독 채널 리포트. 받는 사람 = ACTIVE 구독자.
INSERT INTO notifications (user_id, actor_id, type, title, body, link_path, created_at)
SELECT s.subscriber_id, NULL, 'REPORT_PUBLISHED', '새 리포트가 발행되었습니다',
       LEFT(COALESCE(a.nickname || ' · ', '') || r.title, 280)
         || CASE WHEN r.is_public THEN ' · 공개' ELSE ' · 구독자 전용' END,
       '/reports/' || r.id, r.created_at
FROM reports r
JOIN users a ON a.id = r.user_id
JOIN subscriptions s ON s.publisher_id = r.user_id AND s.status = 'ACTIVE'
CROSS JOIN backfill_params p
WHERE r.created_at >= COALESCE(s.started_at, s.created_at)
  AND r.created_at < p.cutoff
  AND NOT EXISTS (
        SELECT 1 FROM notifications n
        WHERE n.user_id = s.subscriber_id AND n.type = 'REPORT_PUBLISHED' AND n.link_path = '/reports/' || r.id);

-- ⑥ PREDICTION_REGISTERED — 구독 시작 뒤에 등록된 구독 채널의 실전 예측.
INSERT INTO notifications (user_id, actor_id, type, title, body, link_path, created_at)
SELECT s.subscriber_id, NULL, 'PREDICTION_REGISTERED', '구독 채널에 새 예측이 등록되었습니다',
       COALESCE(a.nickname, '구독 중인 채널') || ' · ' || st.name || ' '
         || CASE pr.direction WHEN 'UP' THEN '상승' ELSE '하락' END
         || ' · 목표가 ' || pr.target_price::text || '원',
       '/predictions/' || pr.id, pr.created_at
FROM predictions pr
JOIN users a ON a.id = pr.user_id
JOIN stocks st ON st.code = pr.stock_code
JOIN subscriptions s ON s.publisher_id = pr.user_id AND s.status = 'ACTIVE'
CROSS JOIN backfill_params p
WHERE pr.track = 'REAL'
  AND pr.created_at >= COALESCE(s.started_at, s.created_at)
  AND pr.created_at < p.cutoff
  AND NOT EXISTS (
        SELECT 1 FROM notifications n
        WHERE n.user_id = s.subscriber_id AND n.type = 'PREDICTION_REGISTERED'
          AND n.link_path = '/predictions/' || pr.id);

SELECT type, COUNT(*) AS rows FROM notifications GROUP BY type ORDER BY type;

COMMIT;
