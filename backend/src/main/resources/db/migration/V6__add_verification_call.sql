-- 확인 전화 기록 (PROMPT-165)
--
-- 가게에 건 확인 전화 한 통. 전화 API(POST /v1/verification-call)에 요청을 넣고 받은
-- verificationId 와, 그 뒤로 받아 온 진행 상태·답을 담는다.
--
-- ⚠️ 담당자 확인 기록(verification)과 다른 표다. 이름이 비슷한 것은 전화 API 가 자기 리소스를
-- verification-call 로 부르기 때문이고, 뜻은 이렇게 나뉜다.
--   verification_call  전화를 걸어 점주에게 물은 기록. 자동으로 생긴다
--   verification       담당자가 가게를 확인했다고 사람이 남긴 기록
-- 전화 한 통이 담당자 확인을 대신하지 않는다 — store.last_checked_at 은 전화로 올라가지 않는다.

CREATE TABLE IF NOT EXISTS verification_call (
    call_id        BIGSERIAL    PRIMARY KEY,
    store_id       BIGINT       NOT NULL REFERENCES store (store_id),
    -- 조사 결과에서 걸었으면 그 결과. 가게 목록에서 바로 거는 길이 생기면 비어 있을 수 있다
    task_id        BIGINT       REFERENCES task (task_id),
    reason         VARCHAR(30)  NOT NULL,
    -- 전화 API 쪽 기록을 가리키는 유일한 열쇠. 같은 통화를 두 행으로 들고 있으면 폴러가 두 번 읽는다
    external_id    VARCHAR(64)  NOT NULL UNIQUE,
    status         VARCHAR(20)  NOT NULL,
    -- 통화에서 들은 답. [{"questionId","field","value"}] 모양이다. 아직 없으면 NULL
    answers        JSONB,
    failure_reason VARCHAR(200),
    requested_by   VARCHAR(100) NOT NULL,
    requested_at   TIMESTAMP    NOT NULL,
    -- 아래 셋은 전화 API 가 알려 준 시각을 우리 시계로 옮겨 적은 값이다
    started_at     TIMESTAMP,
    connected_at   TIMESTAMP,
    ended_at       TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,

    -- enum 값을 DB 에서도 묶는다. 상수를 추가하면 이 제약도 마이그레이션으로 함께 고친다
    -- (ddl-auto=validate 는 제약을 갱신하지 않는다 — V2·V5 가 그래서 생겼다)
    CONSTRAINT verification_call_reason_check
        CHECK (reason IN ('CHANGE_DETECTED', 'PERIODIC_RECHECK')),
    CONSTRAINT verification_call_status_check
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_verification_call_store_id ON verification_call (store_id);
CREATE INDEX IF NOT EXISTS idx_verification_call_task_id  ON verification_call (task_id);
-- 폴러가 "아직 안 끝난 전화" 를 오래된 것부터 찾을 때 쓴다. 끝난 전화가 쌓여도 훑는 범위가 늘지 않는다
CREATE INDEX IF NOT EXISTS idx_verification_call_status   ON verification_call (status, requested_at);
