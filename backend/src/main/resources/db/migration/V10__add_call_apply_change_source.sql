-- 확인 전화 결과 자동 반영 (PROMPT-165)
--
-- 1) 가게 변경 출처에 CALL_APPLY 를 더한다.
--    사람이 누른 것이 아니라 통화가 끝나면서 저절로 바뀐 자리라 다른 출처와 나눠야 한다 —
--    전화로 바뀐 값만 추려 되돌릴 수 있어야 하고, 담당자가 "내가 안 고쳤는데 값이 바뀌었다" 를
--    변경 기록에서 설명할 수 있어야 한다.
--
-- 2) 전화 기록에 applied_changes 를 더한다. 그 통화가 실제로 무엇을 바꿨는지다.
--    answers(들은 것)와 다르다 — 들었지만 안 바꾼 것이 있다(영업 여부, 이미 같은 값).

ALTER TABLE store_change_history DROP CONSTRAINT IF EXISTS store_change_history_source_check;
ALTER TABLE store_change_history ADD CONSTRAINT store_change_history_source_check
    CHECK (((source)::text = ANY ((ARRAY[
        'STORE_EDIT'::character varying,
        'TASK_APPLY'::character varying,
        'SIGNUP_FILL'::character varying,
        'OPERATING_INFO_SAVE'::character varying,
        'CALL_APPLY'::character varying
    ])::text[])));

-- 반영한 값. 예: {"name": "신나는 분식"}. 아무것도 안 바꿨으면 NULL
ALTER TABLE verification_call ADD COLUMN IF NOT EXISTS applied_changes JSONB;
