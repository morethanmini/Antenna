-- 이미 있는 DB 의 낡은 CHECK 제약을 부팅 때 맞춘다(ANT-TOKEN-07 · ANT-PRED-08).
-- 맨 아래 ③ 은 같은 이유로 유니크 제약을 부분 유니크 인덱스로 바꾼다(ANT-FE-ABUSE-REPORT).
--
-- 뿌리는 하나다 — Hibernate 는 CHECK 를 **테이블을 만들 때** 박고, ddl-auto: update 는 이미 있는 제약을 고치지 않는다.
-- 그래서 규칙을 넓히면 테이블이 이미 있는 DB(로컬·팀·운영)에서만 INSERT 가 깨진다. 매번 새로 만드는 H2 테스트는
-- 이걸 못 잡는다. 매 부팅마다 실행된다(spring.sql.init) — 전부 멱등이라 여러 번 돌아도 같다.
--
-- ── 두 갈래가 있고, 다시 거느냐가 갈린다 ──────────────────────────────────────
--
-- ① enum 목록(@Enumerated(STRING) 칼럼의 "CHECK (kind IN (…))") — 지우고 **다시 걸지 않는다.**
--    어휘는 JPA enum 이 이미 막는다. SQL 에 목록을 한 벌 더 들고 있으면 다음 값 추가 때 같은 사고가 난다.
--
-- ② 손으로 적은 @Check — 지우고 **새 조건으로 다시 건다.**
--    이쪽은 앱이 대신 막아 주지 않는 불변식이고, 무엇보다 SchemaCheckConstraintTest 가 실제 PostgreSQL 로
--    이 제약을 검증한다. 운영에서만 떼면 테스트가 초록인 채로 운영이 다른 값을 받는다.
--    엔티티의 @Check 와 여기 조건은 **글자 그대로 같아야 한다** — 한쪽만 고치면 새 DB 와 옛 DB 가 갈린다.
--
-- 대상을 늘릴 때: 규칙을 넓혔는데 그 칼럼에 옛 제약이 남는가? 그러면 ①이나 ②로 여기에 더한다.

-- ① Operation.Kind 에 SIGNUP_BONUS 추가(ANT-TOKEN-07).
--    이게 없으면 지갑 연동이 보너스 작업 INSERT 에서 500 으로 깨진다.
ALTER TABLE operations DROP CONSTRAINT IF EXISTS operations_kind_check;

-- ② Prediction 의 REAL 기간을 7/14/30/90 집합에서 3~90 연속 구간으로 넓혔다(ANT-PRED-08 · Prediction @Check).
--    옛 집합 제약이 남은 DB 는 45일 예측을 거절한다. 조건은 엔티티의 @Check(ck_predictions_horizon)와 같다.
ALTER TABLE predictions DROP CONSTRAINT IF EXISTS ck_predictions_horizon;
ALTER TABLE predictions ADD CONSTRAINT ck_predictions_horizon
    CHECK ((track = 'REAL' AND horizon BETWEEN 3 AND 90) OR (track = 'REPLAY' AND horizon > 0));

-- ① Operation.ResourceType 에 EVENT 추가(ANT-EVENT-02). kind 의 EVENT_BET·EVENT_ADMIN 은 위 operations_kind_check 삭제로 이미 열려 있다.
--    이게 없으면 관리자의 이벤트 열기(openEvent 작업 INSERT)가 500 으로 깨진다.
ALTER TABLE operations DROP CONSTRAINT IF EXISTS operations_resource_type_check;

-- ① UploadFile.Purpose 에 PROFILE 추가(ANT-AUTH-05).
--    이게 없으면 프로필 사진 업로드가 INSERT 에서 500 으로 깨진다 — AD·REPORT·POST 만 든
--    옛 목록이 남아 있기 때문이다. 어휘는 JPA enum 이 막으므로 다시 걸지 않는다.
ALTER TABLE upload_files DROP CONSTRAINT IF EXISTS upload_files_purpose_check;

-- ① Subscription.Status 에 FAILED 추가(ANT-TOKEN-01 · 2026-09-19).
--    이게 없으면 결제 실패를 FAILED 로 닫는 UPDATE 가 500 으로 깨진다 — PENDING·ACTIVE·EXPIRED 만 든
--    옛 목록이 남아 있기 때문이다. 어휘는 JPA enum 이 막으므로 다시 걸지 않는다.
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_check;

-- ② AbuseReport 신고 대상에 리포트·리포트 댓글 추가(ANT-FE-ABUSE-REPORT · AbuseReport @Check).
--    옛 "셋 중 정확히 하나" 제약이 남은 DB 는 리포트 신고 INSERT 를 거절한다 — 새 칼럼 둘이 합산에 없어
--    리포트만 채운 행의 합이 0 이 되기 때문이다. 조건은 엔티티의 @Check(ck_abuse_reports_exactly_one_target)와 같다.
ALTER TABLE abuse_reports DROP CONSTRAINT IF EXISTS ck_abuse_reports_exactly_one_target;
ALTER TABLE abuse_reports ADD CONSTRAINT ck_abuse_reports_exactly_one_target
    CHECK ((case when target_post_id is null then 0 else 1 end
          + case when target_comment_id is null then 0 else 1 end
          + case when target_user_id is null then 0 else 1 end
          + case when target_report_id is null then 0 else 1 end
          + case when target_report_comment_id is null then 0 else 1 end) = 1);

-- ③ 신고 중복 방지를 "상태별 한 건" 유니크 제약에서 "처리 대기(PENDING) 중 한 건" 부분 인덱스로 바꾼다(ANT-FE-ABUSE-REPORT).
--    옛 제약은 status 를 키에 넣어, 같은 사람이 같은 대상을 두 번 신고해 두 번 다 기각되면 두 번째 처리 UPDATE 가
--    (신고자, 대상, REJECTED) 중복으로 500 이 났다. 부분 인덱스는 JPA 로 표현할 수 없어 여기서만 건다 —
--    엔티티에는 @UniqueConstraint 가 없다(AbuseReport 머리말). 옛 제약이 PENDING 중복을 이미 막고 있었으므로
--    기존 행이 새 인덱스를 어기는 일은 없다. IF [NOT] EXISTS 라 매 부팅 돌아도 같다.
ALTER TABLE abuse_reports DROP CONSTRAINT IF EXISTS uk_abuse_reports_reporter_post_status;
ALTER TABLE abuse_reports DROP CONSTRAINT IF EXISTS uk_abuse_reports_reporter_comment_status;
ALTER TABLE abuse_reports DROP CONSTRAINT IF EXISTS uk_abuse_reports_reporter_user_status;
ALTER TABLE abuse_reports DROP CONSTRAINT IF EXISTS uk_abuse_reports_reporter_report_status;
ALTER TABLE abuse_reports DROP CONSTRAINT IF EXISTS uk_abuse_reports_reporter_report_comment_status;
CREATE UNIQUE INDEX IF NOT EXISTS uq_abuse_reports_pending_post
    ON abuse_reports (reporter_id, target_post_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS uq_abuse_reports_pending_comment
    ON abuse_reports (reporter_id, target_comment_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS uq_abuse_reports_pending_user
    ON abuse_reports (reporter_id, target_user_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS uq_abuse_reports_pending_report
    ON abuse_reports (reporter_id, target_report_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS uq_abuse_reports_pending_report_comment
    ON abuse_reports (reporter_id, target_report_comment_id) WHERE status = 'PENDING';
