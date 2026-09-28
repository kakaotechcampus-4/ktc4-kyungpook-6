-- 예약 메시지 저장소 (Cloudflare D1 = SQLite)
CREATE TABLE IF NOT EXISTS scheduled_messages (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  channel_id TEXT    NOT NULL,
  user_id    TEXT    NOT NULL,          -- 예약을 건 사람. 본인 것만 조회·취소할 수 있게 쓴다
  content    TEXT    NOT NULL,
  due_at     INTEGER NOT NULL,          -- UTC epoch 초
  created_at INTEGER NOT NULL,
  sent_at    INTEGER,                   -- NULL = 아직 안 보냄. 발송 직전에 찍어 중복을 막는다
  attempts   INTEGER NOT NULL DEFAULT 0 -- 발송 실패 횟수. 3회면 더 시도하지 않는다
);

-- cron 이 매분 던지는 질의(sent_at IS NULL AND due_at <= now)를 위한 인덱스
CREATE INDEX IF NOT EXISTS idx_pending ON scheduled_messages (sent_at, due_at);
