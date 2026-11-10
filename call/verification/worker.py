import fcntl
import errno
import logging
import threading
import time
from .runtime import CallFailure, ResultSaveFailure

log = logging.getLogger(__name__)

class Worker:
    def __init__(self, store, runner, lock_file):
        self.store, self.runner, self.lock_file = store, runner, lock_file
        self.stop_event = threading.Event()
        self.wakeup = threading.Event()
        self.save_blocked = threading.Event()
        self.thread = None
        self.lock = None
        self.db_lock = None

    def start(self):
        lock_conflict = False
        try:
            self.lock_file.parent.mkdir(parents=True,exist_ok=True)
            self.lock = self.lock_file.open('a')
            self.db_lock = self.store.path.with_suffix('.worker.lock').open('a')
            try:
                fcntl.flock(self.lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(self.db_lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                lock_conflict = exc.errno in (errno.EACCES, errno.EAGAIN)
                raise
            self.store.recover()
            self.thread = threading.Thread(target=self.run,name='verification-call-worker',daemon=False)
            self.thread.start()
        except Exception as exc:
            for lock in (self.db_lock, self.lock):
                if lock is not None:
                    lock.close()
            self.lock = self.db_lock = None
            if lock_conflict:
                raise RuntimeError('모뎀 또는 DB를 사용하는 worker가 이미 실행 중입니다.') from exc
            raise RuntimeError('통화 worker 시작에 실패했습니다.') from exc

    def run(self):
        pending_finish = None
        shutdown_deadline = None
        while not self.stop_event.is_set() or pending_finish is not None:
            if pending_finish is not None:
                call_id, reason = pending_finish
                try:
                    self.store.record_outcome(call_id, reason)
                    self.store.finish(call_id, reason)
                    pending_finish = None
                    self.save_blocked.clear()
                    continue
                except Exception as exc:
                    self.save_blocked.set()
                    log.error('Call %s result save failed (%s)', call_id, type(exc).__name__)
                    if self.stop_event.is_set():
                        shutdown_deadline = shutdown_deadline or time.monotonic() + 5
                        if time.monotonic() >= shutdown_deadline:
                            return  # recover() marks a claimed call failed on restart.
                    time.sleep(0.5)
                    continue
            self.wakeup.clear()
            try:
                job = self.store.claim()
            except Exception as exc:
                log.error('Call claim failed (%s)', type(exc).__name__)
                self.stop_event.wait(0.5)
                continue
            if job is None:
                self.wakeup.wait(0.5)
                continue
            call_id, request = job
            try:
                self.runner(call_id,request,self.stop_event)
            except ResultSaveFailure as exc:
                pending_finish = (call_id, exc.reason)
                self.save_blocked.set()
            except CallFailure as exc:
                pending_finish = (call_id, str(exc))
            except InterruptedError as exc:
                pending_finish = (call_id, 'SERVER_SHUTDOWN' if str(exc) == 'SERVER_SHUTDOWN' else 'PROCESSING_ERROR')
            except Exception as exc:
                # Do not expose upstream error bodies/credentials in public API or logs.
                log.error('Call %s failed (%s)',call_id,type(exc).__name__)
                pending_finish = (call_id, 'PROCESSING_ERROR')

    def stop(self):
        self.stop_event.set()
        self.wakeup.set()
        if self.thread:
            self.thread.join(timeout=150)
            if self.thread.is_alive():
                raise RuntimeError('통화 worker가 종료 제한 시간 안에 정리되지 않았습니다.')
        for lock in (self.lock,self.db_lock):
            if lock:
                lock.close()
        self.lock = self.db_lock = None
