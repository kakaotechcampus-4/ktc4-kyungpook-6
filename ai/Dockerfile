# syntax=docker/dockerfile:1
#
# AI 서비스(FastAPI) 이미지. 백엔드가 `POST /investigations` 로 부른다.
#
# uv 로 의존성을 설치한다 — 저장소가 `uv.lock` 으로 버전을 고정하고 있어서, pip 로 바꾸면
# 잠금이 풀린다. `--frozen` 은 lock 과 pyproject 가 어긋나면 **조용히 맞추지 않고 실패**한다.
#
# 자격증명은 이미지에 넣지 않는다. compose 의 environment 로 받는다 —
# GOOGLE_CLOUD_PROJECT 가 없으면 조사기가 안 꽂히고 `/investigations` 가 503 으로 답한다
# (`GET /health` 의 investigator 칸으로 어느 쪽인지 확인할 수 있다).
FROM ghcr.io/astral-sh/uv:python3.12-bookworm-slim AS build
WORKDIR /app

ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

# 의존성만 먼저 올린다. 소스가 바뀌어도 이 레이어는 캐시가 산다.
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-install-project

COPY src src

FROM python:3.12-slim-bookworm
WORKDIR /app

# 루트로 돌리지 않는다. 백엔드 이미지와 같은 방식이다.
RUN addgroup --system app && adduser --system --ingroup app app

COPY --from=build --chown=app:app /app/.venv /app/.venv
COPY --from=build --chown=app:app /app/src /app/src

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

USER app
EXPOSE 8000

# uvicorn 을 직접 부른다. uv run 은 컨테이너 안에서 매번 환경을 확인해 기동이 느려진다.
CMD ["uvicorn", "src.server.main:app", "--host", "0.0.0.0", "--port", "8000"]
