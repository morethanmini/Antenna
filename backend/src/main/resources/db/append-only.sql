-- ANT-DB-02: append-only 강제.
-- Hibernate(ddl-auto)가 만들지 못하는 트리거만 여기 둔다. 매 부팅마다 실행되므로 전부 멱등(CREATE OR REPLACE).
-- 권한 REVOKE 대신 트리거를 쓰는 이유: ddl-auto 가 돌려면 앱 계정이 테이블 소유자여야 하고,
-- 소유자에게서 권한을 회수할 수 없다. 트리거는 소유자에게도 걸린다.
-- 파일 전체가 한 문장으로 전송된다(spring.sql.init.separator). plpgsql 본문의 ';' 때문이다.

CREATE OR REPLACE FUNCTION antenna_reject_write() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END
$$;

-- 원장·체결 내역: 어떤 수정·삭제도 없다. 잔액은 SUM(delta), 손익은 체결 재계산으로 구한다.
CREATE OR REPLACE TRIGGER trg_token_ledger_append_only
    BEFORE UPDATE OR DELETE ON token_ledger FOR EACH ROW EXECUTE FUNCTION antenna_reject_write();
CREATE OR REPLACE TRIGGER trg_season_trades_append_only
    BEFORE UPDATE OR DELETE ON season_trades FOR EACH ROW EXECUTE FUNCTION antenna_reject_write();

-- 온체인 이벤트 원본: 삭제 불가. 후속 처리 완료 표시(processed_at)만 바뀔 수 있다.
CREATE OR REPLACE TRIGGER trg_chain_events_no_delete
    BEFORE DELETE ON chain_events FOR EACH ROW EXECUTE FUNCTION antenna_reject_write();

CREATE OR REPLACE FUNCTION antenna_chain_events_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.tx_hash, NEW.log_index, NEW.contract_address, NEW.event_name, NEW.block_number, NEW.payload::text)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.tx_hash, OLD.log_index, OLD.contract_address, OLD.event_name, OLD.block_number, OLD.payload::text) THEN
        RAISE EXCEPTION 'chain_events is append-only: only processed_at may change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE TRIGGER trg_chain_events_guard
    BEFORE UPDATE ON chain_events FOR EACH ROW EXECUTE FUNCTION antenna_chain_events_guard();

-- 예측: 등록 후 수정·삭제 불가. 배치가 채우는 판정 컬럼(status·base_*·settle_*·error_rate·updated_at)만 바뀐다.
-- 커밋 payload 를 이루는 내용(대상·방향·목표가·기간·등록 시각)은 한 글자도 바뀌면 커밋 해시와 어긋난다.
CREATE OR REPLACE TRIGGER trg_predictions_no_delete
    BEFORE DELETE ON predictions FOR EACH ROW EXECUTE FUNCTION antenna_reject_write();

CREATE OR REPLACE FUNCTION antenna_predictions_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.user_id, NEW.track, NEW.stock_code, NEW.season_ticker_id,
           NEW.direction, NEW.target_price, NEW.ref_close, NEW.horizon, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.user_id, OLD.track, OLD.stock_code, OLD.season_ticker_id,
           OLD.direction, OLD.target_price, OLD.ref_close, OLD.horizon, OLD.created_at) THEN
        RAISE EXCEPTION 'predictions is append-only: only settlement columns may change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE TRIGGER trg_predictions_guard
    BEFORE UPDATE ON predictions FOR EACH ROW EXECUTE FUNCTION antenna_predictions_guard();

-- ── ddl-auto: update 가 못 고치는 옛 스키마 바로잡기 ─────────────────────────────
-- update 는 컬럼을 더하기만 하고 NOT NULL 을 풀지 않는다. 엔티티가 바뀌기 전에 만들어진 배포 DB 는
-- 옛 제약이 그대로 남는다. 아래는 전부 멱등이라 매 부팅마다 돌아도 같다.

-- users.nickname: 08-31 에 NULL 허용(온보딩 전엔 닉네임이 없다)으로 바뀌었는데 그 전에 만들어진
-- 배포 DB 에 NOT NULL 이 남아 신규 가입 INSERT 가 500 이었다(2026-09-03 장애).
ALTER TABLE users ALTER COLUMN nickname DROP NOT NULL;

-- token_ledger: 이 테이블에는 인덱스가 하나도 없었다. Postgres 는 외래키에 인덱스를 자동으로 만들지
-- 않으므로 user_id 로 거르는 조회가 전부 순차 스캔이다 — 행이 적어 안 드러났을 뿐이다.
-- 여기를 거치는 것: 잔액 SUM(delta) · 원장 목록 · 30일 유입/유출 합. 잔액은 구독·광고가 결제 판단에
-- 쓰는 숫자라 느려지면 결제 경로까지 같이 느려진다.
-- created_at 을 뒤에 둔 것은 셋 다 user_id 로 먼저 좁히고, 그중 기간 집계만 범위를 더 자르기 때문이다.
-- 인덱스는 엔티티에 @Index 로 걸어 ddl-auto 에 맡길 수도 있지만, 이 프로젝트의 관례대로 스키마에
-- 손대는 것은 이 파일에 모은다. IF NOT EXISTS 라 매 부팅 멱등이다.
CREATE INDEX IF NOT EXISTS idx_token_ledger_user_created
    ON token_ledger (user_id, created_at);

-- 대회 회차: 아직 끝나지 않은 회차는 기준일마다 하나다 (ANT-EVENT · 2026-09-22 QA).
-- 관리자 화면에서 생성을 세 번 누르면 회차 세 개가 생기고 온체인 openEvent 도 그만큼 나갔다.
-- 화면 가드는 버튼이 잠기기 전에 새고, 생성에는 멱등 키가 없다 — 마지막 방어선을 DB 에 둔다.
-- 부분 인덱스인 이유: 지난 회차(SEALED 이후)는 같은 기준일로 다시 열릴 수 있어야 한다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_prediction_events_live_base_date
    ON prediction_events (base_date)
    WHERE status IN ('SCHEDULED', 'OPEN');
