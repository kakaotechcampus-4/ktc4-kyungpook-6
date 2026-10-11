-- 예약 메시지. "누가 언제 무엇을 어디로" 와 그 결과를 한 행에 둔다.
CREATE TABLE IF NOT EXISTS reservations (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id  TEXT    NOT NULL,  -- 발송할 채널 (명령을 입력한 채널)
    user_id     TEXT    NOT NULL,  -- 예약을 건 사람. 목록·취소를 본인 것으로 제한하는 데 쓴다
    content     TEXT    NOT NULL,
    due_at      INTEGER NOT NULL,  -- 보낼 시각 (epoch milliseconds, UTC)
    created_at  INTEGER NOT NULL,
    sent_at     INTEGER,           -- 발송 성공 시각. NULL 이면 아직 안 보냈다
    canceled_at INTEGER,           -- 취소 시각. NULL 이면 살아 있다
    -- 발송이 실패한 이유. 남겨 두면 다음 분에 다시 시도하고, 사람이 원인을 볼 수 있다
    last_error  TEXT
);

-- cron 이 1분마다 "보낼 때가 됐고 아직 안 보냈고 취소되지 않은 것"만 훑는다.
CREATE INDEX IF NOT EXISTS idx_reservations_pending
    ON reservations (due_at)
    WHERE sent_at IS NULL AND canceled_at IS NULL;

-- /예약목록 은 본인 것만 보여준다.
CREATE INDEX IF NOT EXISTS idx_reservations_user ON reservations (user_id, due_at);
