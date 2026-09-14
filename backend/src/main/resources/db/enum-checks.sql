-- ANT-TOKEN-07: Hibernate 가 enum 칼럼에 만든 옛 CHECK 제약을 지운다.
-- Hibernate 는 @Enumerated(STRING) 칼럼에 "CHECK (kind IN (…))" 를 테이블을 만들 때 박는데, ddl-auto: update 는
-- 이미 있는 제약을 고치지 않는다. 그래서 enum 에 값을 더하면 테이블이 이미 있는 DB(로컬·팀·운영)에서만 INSERT 가
-- 깨진다 — 매번 새로 만드는 H2 테스트는 이걸 못 잡는다. 어휘는 JPA enum 이 이미 막으므로 DB 쪽 목록은 지운다.
-- 새 목록으로 다시 ADD 하지 않는 이유: SQL 에 목록을 한 벌 더 들고 있으면 다음 값 추가 때 같은 사고가 난다.
-- 매 부팅마다 실행된다(spring.sql.init) — IF EXISTS 라 멱등이다. 새 DB 에서는 Hibernate 가 막 만든 제약을 곧바로 지운다.
--
-- 대상을 늘릴 때: enum 에 값을 더했는데 그 칼럼에 옛 목록 제약이 있으면 여기에 한 줄 더한다.

-- Operation.Kind 에 SIGNUP_BONUS 추가(ANT-TOKEN-07). 이게 없으면 지갑 연동이 보너스 작업 INSERT 에서 500 으로 깨진다.
ALTER TABLE operations DROP CONSTRAINT IF EXISTS operations_kind_check;
