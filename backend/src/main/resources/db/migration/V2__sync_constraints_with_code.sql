-- 이미 돌고 있는 DB 를 코드가 기대하는 모양으로 맞춘다.
--
-- 빈 DB 에서는 V1 이 방금 이대로 만들었으므로 이 파일은 아무것도 바꾸지 않는다.
-- 의미가 있는 쪽은 dev·prod 처럼 ddl-auto 로 자라 온 DB 다.
--
-- 왜 필요한가 — `ddl-auto: update` 는 표와 컬럼을 더하기만 한다. 이미 만들어진 CHECK 제약과
-- NOT NULL 은 따라오지 않는다. 그래서 2026-09-18 부터 한 달 가까이 배포본의 AI 조사가 죽어 있었다.
--
--   ERROR: new row for relation "task" violates check constraint "task_classification_check"
--     DB    CHECK (classification = ANY (ARRAY['TASK_HIGH','TASK_LOW','TASK_NONE']))
--     코드   PRIORITY_CHECK · ADDITIONAL_CHECK · NO_CHANGE   (e0f6a4e 에서 이름을 바꿨다)
--
-- 아래는 전부 여러 번 돌려도 같은 결과다.
--
-- CHECK 를 NOT VALID 로 거는 이유 — 그냥 걸면 Postgres 가 이미 들어 있는 행까지 검사한다.
-- 배포본에 옛 값이 한 줄이라도 남아 있으면 마이그레이션이 실패하고 백엔드가 아예 뜨지 않는다.
-- 이 마이그레이션은 기동할 때 자동으로 도는 것이라, 그 실패는 곧 서비스 중단이다.
-- NOT VALID 로 걸면 새로 들어오는 행부터 지켜지고 옛 행은 건드리지 않는다 — 고치려던 것이
-- "앞으로 저장되는 값이 거절당하는 일" 이므로 이것으로 충분하다.
--
-- 옛 행도 깨끗한 DB 에서는 맨 아래 블록이 제약을 VALIDATE 까지 올려 둔다. 올리지 못하면
-- 어느 표에 옛 값이 남았는지 로그에 남기고 넘어간다.


-- ── CHECK 제약: 지금 코드의 enum 값으로 다시 건다 ────────────────────


-- job
ALTER TABLE job DROP CONSTRAINT IF EXISTS job_status_check;
ALTER TABLE job ADD CONSTRAINT job_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'IN_PROGRESS'::character varying, 'DONE'::character varying, 'FAILED'::character varying])::text[]))) NOT VALID;

-- member
ALTER TABLE member DROP CONSTRAINT IF EXISTS member_role_check;
ALTER TABLE member ADD CONSTRAINT member_role_check CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'OWNER'::character varying])::text[]))) NOT VALID;
ALTER TABLE member DROP CONSTRAINT IF EXISTS member_status_check;
ALTER TABLE member ADD CONSTRAINT member_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) NOT VALID;

-- pending_upload
ALTER TABLE pending_upload DROP CONSTRAINT IF EXISTS pending_upload_file_type_check;
ALTER TABLE pending_upload ADD CONSTRAINT pending_upload_file_type_check CHECK (((file_type)::text = ANY ((ARRAY['JPEG'::character varying, 'PNG'::character varying, 'PDF'::character varying])::text[]))) NOT VALID;
ALTER TABLE pending_upload DROP CONSTRAINT IF EXISTS pending_upload_kind_check;
ALTER TABLE pending_upload ADD CONSTRAINT pending_upload_kind_check CHECK (((kind)::text = ANY ((ARRAY['STORE_IMAGE'::character varying, 'MENU_IMAGE'::character varying, 'BIZ_CERT'::character varying])::text[]))) NOT VALID;

-- signal
ALTER TABLE signal DROP CONSTRAINT IF EXISTS signal_field_check;
ALTER TABLE signal ADD CONSTRAINT signal_field_check CHECK (((field)::text = ANY ((ARRAY['STATUS'::character varying, 'PHONE'::character varying, 'ADDRESS_ROAD'::character varying, 'NAME'::character varying])::text[]))) NOT VALID;
ALTER TABLE signal DROP CONSTRAINT IF EXISTS signal_signal_type_check;
ALTER TABLE signal ADD CONSTRAINT signal_signal_type_check CHECK (((signal_type)::text = ANY ((ARRAY['SIGNAL_HIGH'::character varying, 'SIGNAL_LOW'::character varying, 'SIGNAL_NONE'::character varying])::text[]))) NOT VALID;
ALTER TABLE signal DROP CONSTRAINT IF EXISTS signal_source_check;
ALTER TABLE signal ADD CONSTRAINT signal_source_check CHECK (((source)::text = ANY ((ARRAY['NTS'::character varying, 'AI_WEB'::character varying])::text[]))) NOT VALID;

-- store
ALTER TABLE store DROP CONSTRAINT IF EXISTS store_status_check;
ALTER TABLE store ADD CONSTRAINT store_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'SUSPENDED'::character varying, 'CLOSED'::character varying, 'UNKNOWN'::character varying])::text[]))) NOT VALID;

-- store_business_hour
ALTER TABLE store_business_hour DROP CONSTRAINT IF EXISTS ck_store_business_hour_minutes;
ALTER TABLE store_business_hour ADD CONSTRAINT ck_store_business_hour_minutes CHECK ((((open_minute >= 0) AND (open_minute <= 1439)) AND ((close_minute >= 1) AND (close_minute <= 1440)) AND (open_minute <> close_minute))) NOT VALID;
ALTER TABLE store_business_hour DROP CONSTRAINT IF EXISTS store_business_hour_day_check;
ALTER TABLE store_business_hour ADD CONSTRAINT store_business_hour_day_check CHECK (((day)::text = ANY ((ARRAY['MON'::character varying, 'TUE'::character varying, 'WED'::character varying, 'THU'::character varying, 'FRI'::character varying, 'SAT'::character varying, 'SUN'::character varying])::text[]))) NOT VALID;

-- store_change_history
ALTER TABLE store_change_history DROP CONSTRAINT IF EXISTS store_change_history_field_check;
ALTER TABLE store_change_history ADD CONSTRAINT store_change_history_field_check CHECK (((field)::text = ANY ((ARRAY['NAME'::character varying, 'ADDRESS_ROAD'::character varying, 'PHONE'::character varying, 'STATUS'::character varying, 'BUSINESS_HOURS'::character varying, 'MENUS'::character varying])::text[]))) NOT VALID;
ALTER TABLE store_change_history DROP CONSTRAINT IF EXISTS store_change_history_source_check;
ALTER TABLE store_change_history ADD CONSTRAINT store_change_history_source_check CHECK (((source)::text = ANY ((ARRAY['STORE_EDIT'::character varying, 'TASK_APPLY'::character varying, 'SIGNUP_FILL'::character varying, 'OPERATING_INFO_SAVE'::character varying])::text[]))) NOT VALID;

-- store_nts_change
ALTER TABLE store_nts_change DROP CONSTRAINT IF EXISTS store_nts_change_from_state_check;
ALTER TABLE store_nts_change ADD CONSTRAINT store_nts_change_from_state_check CHECK (((from_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'CLOSED'::character varying, 'NOT_REGISTERED'::character varying])::text[]))) NOT VALID;
ALTER TABLE store_nts_change DROP CONSTRAINT IF EXISTS store_nts_change_to_state_check;
ALTER TABLE store_nts_change ADD CONSTRAINT store_nts_change_to_state_check CHECK (((to_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'CLOSED'::character varying, 'NOT_REGISTERED'::character varying])::text[]))) NOT VALID;

-- store_nts_check
ALTER TABLE store_nts_check DROP CONSTRAINT IF EXISTS store_nts_check_check_result_check;
ALTER TABLE store_nts_check ADD CONSTRAINT store_nts_check_check_result_check CHECK (((check_result)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'UNCONFIRMED'::character varying, 'NO_BIZ_NO'::character varying])::text[]))) NOT VALID;
ALTER TABLE store_nts_check DROP CONSTRAINT IF EXISTS store_nts_check_nts_state_check;
ALTER TABLE store_nts_check ADD CONSTRAINT store_nts_check_nts_state_check CHECK (((nts_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'SUSPENDED'::character varying, 'CLOSED'::character varying, 'NOT_REGISTERED'::character varying])::text[]))) NOT VALID;

-- task
ALTER TABLE task DROP CONSTRAINT IF EXISTS task_classification_check;
ALTER TABLE task ADD CONSTRAINT task_classification_check CHECK (((classification)::text = ANY ((ARRAY['PRIORITY_CHECK'::character varying, 'ADDITIONAL_CHECK'::character varying, 'NO_CHANGE'::character varying])::text[]))) NOT VALID;

-- upload_file
ALTER TABLE upload_file DROP CONSTRAINT IF EXISTS upload_file_file_type_check;
ALTER TABLE upload_file ADD CONSTRAINT upload_file_file_type_check CHECK (((file_type)::text = ANY ((ARRAY['JPEG'::character varying, 'PNG'::character varying, 'PDF'::character varying])::text[]))) NOT VALID;
ALTER TABLE upload_file DROP CONSTRAINT IF EXISTS upload_file_kind_check;
ALTER TABLE upload_file ADD CONSTRAINT upload_file_kind_check CHECK (((kind)::text = ANY ((ARRAY['STORE_IMAGE'::character varying, 'MENU_IMAGE'::character varying, 'BIZ_CERT'::character varying])::text[]))) NOT VALID;

-- verification
ALTER TABLE verification DROP CONSTRAINT IF EXISTS verification_action_check;
ALTER TABLE verification ADD CONSTRAINT verification_action_check CHECK (((action)::text = ANY ((ARRAY['UPDATE_INFO'::character varying, 'CHANGE_STATUS'::character varying, 'NO_ACTION'::character varying])::text[]))) NOT VALID;
ALTER TABLE verification DROP CONSTRAINT IF EXISTS verification_result_check;
ALTER TABLE verification ADD CONSTRAINT verification_result_check CHECK (((result)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'NOT_APPLICABLE'::character varying, 'PENDING'::character varying])::text[]))) NOT VALID;


-- ── NOT NULL: 코드가 nullable 이라고 보는 칸의 제약을 푼다 ──────────────
--
-- 반대 방향(코드는 NOT NULL 인데 DB 가 nullable)은 일부러 넣지 않았다. 이미 들어 있는
-- 행에 null 이 있으면 마이그레이션이 실패해 배포가 통째로 멈춘다. 그쪽은 값을 어떻게 채울지
-- 먼저 정해야 해서 자동으로 할 일이 아니다.


-- job
ALTER TABLE job ALTER COLUMN error_message DROP NOT NULL;
ALTER TABLE job ALTER COLUMN finished_at DROP NOT NULL;

-- member
ALTER TABLE member ALTER COLUMN biz_no DROP NOT NULL;
ALTER TABLE member ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE member ALTER COLUMN rejected_by DROP NOT NULL;
ALTER TABLE member ALTER COLUMN representative_name DROP NOT NULL;
ALTER TABLE member ALTER COLUMN reviewed_at DROP NOT NULL;
ALTER TABLE member ALTER COLUMN store_address DROP NOT NULL;
ALTER TABLE member ALTER COLUMN store_business_hours DROP NOT NULL;
ALTER TABLE member ALTER COLUMN store_menus DROP NOT NULL;
ALTER TABLE member ALTER COLUMN store_name DROP NOT NULL;
ALTER TABLE member ALTER COLUMN store_phone DROP NOT NULL;

-- signal
ALTER TABLE signal ALTER COLUMN confidence DROP NOT NULL;
ALTER TABLE signal ALTER COLUMN evidence_text DROP NOT NULL;
ALTER TABLE signal ALTER COLUMN evidence_url DROP NOT NULL;
ALTER TABLE signal ALTER COLUMN observed DROP NOT NULL;

-- store
ALTER TABLE store ALTER COLUMN biz_no DROP NOT NULL;
ALTER TABLE store ALTER COLUMN category DROP NOT NULL;
ALTER TABLE store ALTER COLUMN last_checked_at DROP NOT NULL;
ALTER TABLE store ALTER COLUMN lat DROP NOT NULL;
ALTER TABLE store ALTER COLUMN lng DROP NOT NULL;
ALTER TABLE store ALTER COLUMN owner_phone DROP NOT NULL;
ALTER TABLE store ALTER COLUMN phone DROP NOT NULL;

-- store_change_history
ALTER TABLE store_change_history ALTER COLUMN after_value DROP NOT NULL;
ALTER TABLE store_change_history ALTER COLUMN before_value DROP NOT NULL;
ALTER TABLE store_change_history ALTER COLUMN task_id DROP NOT NULL;

-- store_menu
ALTER TABLE store_menu ALTER COLUMN deleted_at DROP NOT NULL;
ALTER TABLE store_menu ALTER COLUMN photo_file_id DROP NOT NULL;

-- store_nts_change
ALTER TABLE store_nts_change ALTER COLUMN from_state DROP NOT NULL;

-- store_nts_check
ALTER TABLE store_nts_check ALTER COLUMN biz_no DROP NOT NULL;
ALTER TABLE store_nts_check ALTER COLUMN last_success_at DROP NOT NULL;
ALTER TABLE store_nts_check ALTER COLUMN nts_closed_at DROP NOT NULL;
ALTER TABLE store_nts_check ALTER COLUMN nts_state DROP NOT NULL;

-- store_owner
ALTER TABLE store_owner ALTER COLUMN unlinked_at DROP NOT NULL;
ALTER TABLE store_owner ALTER COLUMN unlinked_by DROP NOT NULL;

-- task
ALTER TABLE task ALTER COLUMN classification DROP NOT NULL;
ALTER TABLE task ALTER COLUMN failure_reason DROP NOT NULL;
ALTER TABLE task ALTER COLUMN proposed_changes DROP NOT NULL;

-- upload_file
ALTER TABLE upload_file ALTER COLUMN file_key DROP NOT NULL;

-- verification
ALTER TABLE verification ALTER COLUMN applied_changes DROP NOT NULL;
ALTER TABLE verification ALTER COLUMN note DROP NOT NULL;
ALTER TABLE verification ALTER COLUMN task_id DROP NOT NULL;


-- ── 옛 행이 없는 제약은 VALIDATE 까지 올린다 ──────────────────────────
--
-- 빈 DB 에서는 전부 올라간다(V1 이 방금 만든 행만 있으므로). 배포본에 옛 값이 남아 있는
-- 제약만 NOT VALID 로 남고, 어느 것인지 로그에 찍힌다. 그걸 보고 자료를 정리한 뒤
-- 손으로 VALIDATE 하면 된다 — 배포를 멈추지는 않는다.
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
