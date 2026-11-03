-- 가게 변경 기록에 사업자등록번호(BIZ_NO) 항목을 더한다. PROMPT-161
--
-- Hibernate 가 enum 칸에 "허용 값 목록" CHECK 를 만든다. 코드의 enum 에 값을 늘려도 이 제약을
-- 같이 고치지 않으면 새 값을 넣는 순간 DB 가 거절한다. 전에는 ddl-auto: update 가 이미 있는
-- 제약을 고치지 않아 이 자리가 막혀 있었다 — 그래서 이 작업이 Flyway(PROMPT-159) 를 기다렸다.
--
-- 값을 더하기만 하므로 이미 쌓인 행은 어길 수 없다. 그래도 NOT VALID 로 거는 이유는, V2 가
-- 건 제약이 NOT VALID 라 "지금 들어 있는 행이 전부 깨끗하다" 를 DB 가 보장하지 않기 때문이다.
-- 바로 VALID 로 걸면 옛 값이 한 줄이라도 있을 때 마이그레이션이 실패하고 백엔드가 아예 뜨지
-- 않는다 — 기동할 때 자동으로 도는 것이라 그 실패는 곧 서비스 중단이다.

ALTER TABLE store_change_history DROP CONSTRAINT IF EXISTS store_change_history_field_check;
ALTER TABLE store_change_history ADD CONSTRAINT store_change_history_field_check
    CHECK (((field)::text = ANY ((ARRAY[
        'NAME'::character varying,
        'ADDRESS_ROAD'::character varying,
        'PHONE'::character varying,
        'STATUS'::character varying,
        'BUSINESS_HOURS'::character varying,
        'MENUS'::character varying,
        'BIZ_NO'::character varying
    ])::text[]))) NOT VALID;

-- 옛 값이 없으면 검증까지 올린다. 있으면 어느 표인지 로그에 남기고 지나간다 — V2 와 같은 방식이다.
DO $$
BEGIN
    ALTER TABLE store_change_history VALIDATE CONSTRAINT store_change_history_field_check;
EXCEPTION WHEN check_violation THEN
    RAISE WARNING '옛 값이 남아 있어 아직 검증하지 못했습니다: store_change_history.field — 자료를 정리한 뒤 ALTER TABLE store_change_history VALIDATE CONSTRAINT store_change_history_field_check 를 돌리세요';
END $$;
