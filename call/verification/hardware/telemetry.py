"""스레드 안전한 통화별 JSONL 실행 로그. 시간 측정은 monotonic clock 사용."""

import json
import threading
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path


class EventLog:
    def __init__(self, directory: str | Path | None = None):
        self.session_id = uuid.uuid4().hex[:12]
        self._started = time.monotonic()
        self._lock = threading.Lock()
        self._file = None
        self.path = None
        if directory is not None:
            folder = Path(directory)
            folder.mkdir(parents=True, exist_ok=True)
            stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
            self.path = folder / f'call-{stamp}-{self.session_id}.jsonl'
            self._file = self.path.open('x', encoding='utf-8')

    def emit(self, stage: str, **fields) -> None:
        entry = {
            'timestamp': datetime.now(timezone.utc).isoformat(),
            'session_id': self.session_id,
            'elapsed_ms': round((time.monotonic() - self._started) * 1000, 2),
            'stage': stage,
            **fields,
        }
        with self._lock:
            if self._file is not None:
                self._file.write(json.dumps(entry, ensure_ascii=False) + '\n')
                self._file.flush()

    @contextmanager
    def span(self, stage: str, **fields):
        started = time.monotonic()
        operation_id = uuid.uuid4().hex[:12]
        self.emit(stage, status='started', operation_id=operation_id, **fields)
        result = {}
        try:
            yield result
        except BaseException as exc:
            self.emit(stage, status='error', operation_id=operation_id,
                      duration_ms=round((time.monotonic() - started) * 1000, 2),
                      error_type=type(exc).__name__, error=str(exc), **fields, **result)
            raise
        else:
            self.emit(stage, status='ok', operation_id=operation_id,
                      duration_ms=round((time.monotonic() - started) * 1000, 2),
                      **fields, **result)

    def close(self) -> None:
        with self._lock:
            if self._file is not None:
                self._file.close()
                self._file = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
