-- 가게 변경 기록에 사업자등록번호(BIZ_NO) 항목을 더한다. PROMPT-161
--
-- Hibernate 가 enum 칸에 "허용 값 목록" CHECK 를 만든다. 코드의 enum 에 값을 늘려도 이 제약을
-- 같이 고치지 않으면 새 값을 넣는 순간 DB 가 거절한다. 전에는 ddl-auto: update 가 이미 있는
-- 제약을 고치지 않아 이 자리가 막혀 있었다 — 그래서 이 작업이 Flyway(PROMPT-159) 를 기다렸다.
--
-- 값을 더하기만 하므로 이미 쌓인 행은 어길 수 없다. 그래서 V2 와 달리 NOT VALID 없이 바로 건다.

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
    ])::text[])));
