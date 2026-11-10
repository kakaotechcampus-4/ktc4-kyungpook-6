import asyncio
import hmac
from contextlib import asynccontextmanager
from uuid import UUID
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response
from .call_logs import read_call_logs
from .config import Settings
from .models import CallLogs, CallRequest, CallResult, Created
from .providers import Providers
from .runtime import CallRunner
from .store import Store, QueueFull
from .worker import Worker

def create_app(settings=None, runner_factory=None):
    @asynccontextmanager
    async def lifespan(app):
        s = settings or Settings.from_env()
        s.data_dir.mkdir(parents=True,exist_ok=True)
        store = Store(s.data_dir/'calls.sqlite3',s.queue_capacity,s.pending_max_age_seconds)
        providers = Providers(s)
        runner = runner_factory(s,store,providers) if runner_factory else CallRunner(s,store,providers)
        worker = Worker(store,runner,s.modem_lock_file)
        app.state.store, app.state.worker, app.state.settings = store,worker,s
        try:
            worker.start()
            yield
        finally:
            await asyncio.to_thread(worker.stop)
            providers.close()

    app = FastAPI(title='선한레이더 전화 정보 확인',version='1.0.0',lifespan=lifespan)

    def authorize(authorization: str | None = Header(default=None)):
        expected = 'Bearer ' + app.state.settings.api_key.get_secret_value()
        if not authorization or not hmac.compare_digest(authorization.encode(),expected.encode()):
            raise HTTPException(401,'유효한 Bearer API 키가 필요합니다.',headers={'WWW-Authenticate':'Bearer'})

    @app.get('/healthz')
    def health(response: Response):
        alive = app.state.worker.thread.is_alive()
        blocked = app.state.worker.save_blocked.is_set()
        if not alive or blocked:
            response.status_code = 503
        return {'status':'worker_unavailable' if not alive else
                'result_storage_unavailable' if blocked else 'ok'}

    @app.post('/v1/verification-call',response_model=Created,status_code=202,dependencies=[Depends(authorize)])
    def create(request: CallRequest):
        if not app.state.worker.thread.is_alive() or app.state.worker.save_blocked.is_set():
            raise HTTPException(503,'통화 worker를 사용할 수 없습니다.')
        try:
            call_id = app.state.store.enqueue(request)
        except QueueFull:
            raise HTTPException(429,'통화 대기열이 가득 찼습니다.',headers={'Retry-After':'60'})
        app.state.worker.wakeup.set()
        return {'verificationId':call_id}

    @app.get('/v1/verification-call/{verification_id}',response_model=CallResult,dependencies=[Depends(authorize)])
    def get(verification_id: UUID):
        result = app.state.store.get(str(verification_id))
        if result is None:
            raise HTTPException(404,'해당 통화가 없습니다.')
        return result

    @app.get('/v1/verification-call/{verification_id}/logs',response_model=CallLogs,
             dependencies=[Depends(authorize)])
    def get_logs(verification_id: UUID, after: int = Query(default=0, ge=0)):
        call_id = str(verification_id)
        result = app.state.store.get(call_id)
        if result is None:
            raise HTTPException(404,'해당 통화가 없습니다.')
        logs, cursor = read_call_logs(app.state.settings.data_dir/'calls'/call_id,after)
        return {key:result[key] for key in (
            'verificationId','status','startedAt','connectedAt','endedAt','failureReason')} | {
                'logs':logs,'nextCursor':cursor}

    return app

app = create_app()
