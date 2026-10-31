-- 이름이 바뀐 enum 의 옛 값을 지금 값으로 옮긴다.
--
-- V2 는 CHECK 제약을 지금 값으로 다시 걸기만 하고 이미 들어 있는 행은 건드리지 않았다(NOT VALID).
-- 그래서 옛 이름이 남은 행은 DB 에서는 살아 있다가 JPA 가 읽을 때 valueOf 에서 터진다 —
-- 배포는 멈추지 않고 그 행을 읽는 화면만 500 이 된다. 제약을 다시 거는 것과 이미 쌓인 값을
-- 옮기는 것은 다른 일이고, 값을 바꾼 쪽이 옮기는 것까지 해야 한다.
--
-- 이름을 바꾼 enum 은 TaskClassification 하나다(e0f6a4e, 2026-09-18). 나머지 enum 은 값이
-- 늘기만 했으므로 옮길 것이 없다 — 늘어난 값은 옛 행이 어길 수 없다.
--
--   TASK_HIGH → PRIORITY_CHECK · TASK_LOW → ADDITIONAL_CHECK · TASK_NONE → NO_CHANGE
--
-- 여러 번 돌려도 같은 결과다. 옮길 행이 없으면 0건을 고치고 지나간다.


UPDATE task
   SET classification = CASE classification
           WHEN 'TASK_HIGH' THEN 'PRIORITY_CHECK'
           WHEN 'TASK_LOW'  THEN 'ADDITIONAL_CHECK'
           WHEN 'TASK_NONE' THEN 'NO_CHANGE'
       END
 WHERE classification IN ('TASK_HIGH', 'TASK_LOW', 'TASK_NONE');


-- ── 옮기고 나면 검증까지 올라가는 제약이 생긴다 ──────────────────────
--
-- V2 와 같은 방식이다. 올리지 못한 제약은 아직 모르는 값이 남아 있다는 뜻이고, 그것은
-- 여기서 고칠 수 없다 — 어떤 값으로 옮길지 사람이 정해야 한다.
--
-- 기동할 때 StaleEnumValueReporter 가 아직 NOT VALID 인 제약을 찾아 log.error 로 알린다.
-- 여기의 RAISE WARNING 은 마이그레이션 로그에만 남아 배포 로그에 묻히기 때문이다.
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT rel.relname AS table_name, con.conname AS constraint_name
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace ns ON ns.oid = rel.relnamespace
        WHERE con.contype = 'c' AND ns.nspname = 'public' AND NOT con.convalidated
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', c.table_name, c.constraint_name);
        EXCEPTION WHEN check_violation THEN
            RAISE WARNING '옛 값이 남아 있어 아직 검증하지 못했습니다: %.% — 자료를 정리한 뒤 ALTER TABLE % VALIDATE CONSTRAINT % 를 돌리세요',
                c.table_name, c.constraint_name, c.table_name, c.constraint_name;
        END;
    END LOOP;
END $$;
