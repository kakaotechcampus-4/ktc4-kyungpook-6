import fcntl
import logging
import threading
from .runtime import CallFailure

log = logging.getLogger(__name__)

class Worker:
    def __init__(self, store, runner, lock_file):
        self.store, self.runner, self.lock_file = store, runner, lock_file
        self.stop_event = threading.Event()
        self.wakeup = threading.Event()
        self.thread = None
        self.lock = None
        self.db_lock = None

    def start(self):
        self.lock_file.parent.mkdir(parents=True,exist_ok=True)
        self.lock = self.lock_file.open('a')
        self.db_lock = self.store.path.with_suffix('.worker.lock').open('a')
        try:
            fcntl.flock(self.lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.flock(self.db_lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.store.recover()
            self.thread = threading.Thread(target=self.run,name='verification-call-worker',daemon=False)
            self.thread.start()
        except BaseException:
            self.lock.close()
            self.db_lock.close()
            raise RuntimeError('모뎀 또는 DB를 사용하는 worker가 이미 실행 중이거나 시작에 실패했습니다.')

    def run(self):
        while not self.stop_event.is_set():
            self.wakeup.clear()
            job = self.store.claim()
            if job is None:
                self.wakeup.wait(0.5)
                continue
            call_id, request = job
            try:
                self.runner(call_id,request,self.stop_event)
            except CallFailure as exc:
                self.store.finish(call_id,str(exc))
            except Exception as exc:
                # Do not expose upstream error bodies/credentials in public API or logs.
                log.error('Call %s failed (%s)',call_id,type(exc).__name__)
                self.store.finish(call_id,'PROCESSING_ERROR')

    def stop(self):
        self.stop_event.set()
        self.wakeup.set()
        if self.thread:
            # IO has bounded timeouts. Keep both locks until hardware cleanup has completed.
            self.thread.join()
        for lock in (self.lock,self.db_lock):
            if lock:
                lock.close()
