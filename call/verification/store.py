"""SQLite is both the durable FIFO queue and result store."""
import json
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from .models import CallRequest

def now():
    return datetime.now(timezone.utc).isoformat()

class QueueFull(Exception):
    pass

class Store:
    def __init__(self, path: Path, capacity=1000, max_pending_age_seconds=900):
        self.path, self.capacity = path, capacity
        self.max_pending_age_seconds = max_pending_age_seconds
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('''CREATE TABLE IF NOT EXISTS calls (
                seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL,
                request TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING',
                claimed INTEGER NOT NULL DEFAULT 0, answers TEXT NOT NULL DEFAULT '[]',
                created TEXT NOT NULL, started TEXT, connected TEXT, ended TEXT, reason TEXT,
                outcome_recorded INTEGER NOT NULL DEFAULT 0, outcome_reason TEXT)''')
            columns = {row['name'] for row in db.execute('PRAGMA table_info(calls)')}
            if 'outcome_recorded' not in columns:
                db.execute('ALTER TABLE calls ADD COLUMN outcome_recorded INTEGER NOT NULL DEFAULT 0')
            if 'outcome_reason' not in columns:
                db.execute('ALTER TABLE calls ADD COLUMN outcome_reason TEXT')
            db.execute('CREATE INDEX IF NOT EXISTS pending ON calls(status,claimed,seq)')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def enqueue(self, request):
        call_id = str(uuid.uuid4())
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            count = db.execute("SELECT count(*) FROM calls WHERE status IN ('PENDING','IN_PROGRESS')").fetchone()[0]
            if count >= self.capacity:
                raise QueueFull()
            db.execute('INSERT INTO calls(id,request,created) VALUES(?,?,?)',
                       (call_id,request.model_dump_json(),now()))
        return call_id

    def recover(self):
        # Called only after exclusive worker lock. Never redial a possibly placed call.
        with self.connect() as db:
            db.execute('''UPDATE calls SET
                status=CASE WHEN outcome_recorded=1 AND outcome_reason IS NULL
                            THEN 'COMPLETED' ELSE 'FAILED' END,
                reason=CASE WHEN outcome_recorded=1 THEN outcome_reason ELSE 'SERVER_RESTARTED' END,
                ended=? WHERE claimed=1 AND status IN ('PENDING','IN_PROGRESS')''',(now(),))

    def claim(self):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            cutoff = (datetime.now(timezone.utc) -
                      timedelta(seconds=self.max_pending_age_seconds)).isoformat()
            db.execute("""UPDATE calls SET status='FAILED', reason='QUEUE_EXPIRED', ended=?
                       WHERE status='PENDING' AND claimed=0 AND created < ?""",(now(),cutoff))
            row = db.execute("SELECT * FROM calls WHERE status='PENDING' AND claimed=0 ORDER BY seq LIMIT 1").fetchone()
            if row is None:
                return None
            db.execute('UPDATE calls SET claimed=1 WHERE id=?',(row['id'],))
            return row['id'], CallRequest.model_validate_json(row['request'])

    def started(self, call_id):
        with self.connect() as db:
            db.execute("UPDATE calls SET status='IN_PROGRESS', started=? WHERE id=?",(now(),call_id))

    def connected(self, call_id):
        with self.connect() as db:
            db.execute('UPDATE calls SET connected=? WHERE id=?',(now(),call_id))

    def save_answers(self, call_id, answers):
        with self.connect() as db:
            db.execute('UPDATE calls SET answers=? WHERE id=?',(json.dumps(answers,ensure_ascii=False),call_id))

    def record_outcome(self, call_id, reason=None):
        # Commit the terminal intent before finish(); recovery can then preserve success.
        with self.connect() as db:
            db.execute("""UPDATE calls SET outcome_recorded=1, outcome_reason=?
                       WHERE id=? AND status IN ('PENDING','IN_PROGRESS')""",(reason,call_id))

    def finish(self, call_id, reason=None):
        with self.connect() as db:
            db.execute('UPDATE calls SET status=?, reason=?, ended=? WHERE id=?',
                       ('FAILED' if reason else 'COMPLETED',reason,now(),call_id))

    def get(self, call_id):
        with self.connect() as db:
            row = db.execute('SELECT * FROM calls WHERE id=?',(call_id,)).fetchone()
        if row is None:
            return None
        return dict(verificationId=row['id'],status=row['status'],answers=json.loads(row['answers']),
                    createdAt=row['created'],startedAt=row['started'],connectedAt=row['connected'],
                    endedAt=row['ended'],failureReason=row['reason'])
