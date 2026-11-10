# store-info-agent

가게 정보가 바뀌었는지 조사해 수정안을 돌려주는 AI 서버. 사업자등록증 읽기도 맡는다.
백엔드가 호출하며, 국세청 대조와 대상 선별은 백엔드가 한다.

## 개발 환경
1. `uv sync` 로 의존성 설치
2. `.env.example` 참고해 `.env` 생성 후 값 채우기
3. 서버 실행 (`ai/` 에서)

```bash
uv run uvicorn src.server.main:app --reload --port 8000
```

## 테스트

```bash
./scripts/test.sh             # ai/ 에서 실행. uv run pytest tests/ 와 같고, CI 도 이 스크립트를 쓴다
```

`eval/`은 CI에서 돌리지 않는다 — Vertex나 네이버 같은 외부 API를 실제로 불러 비용이 들거나 로컬에 백엔드를 띄워 둬야 하는 스크립트라서다.

## 폴더 구조
- `src/` — 서비스 코드. 아직 운영에 못 올린 기능도 여기 둔다 (측정·실험 스크립트는 `eval/`)
- `tests/` — pytest 단위 테스트. "코드가 의도대로 동작하는가"를 pass/fail로 검증
- `eval/` — 벤치마크·실험·로컬 검증용 스크립트. pytest 대상 아님, 사람이 직접 실행해서
  모델별 정확도를 재거나 연동 흐름을 돌려 보는 용도 (`uv run python -m eval.<script>`)
  - `eval/fixtures/` — 벤치마크용 케이스 데이터. 실제 가게 정보라 커밋하지 않는다(각자 받아 둔다)

새 기능 추가 시에도 이 구분을 따른다: 서비스 코드는 `src/`, 정답 검증은 `tests/`,
측정·실험·로컬 검증은 `eval/`.

`src/` 아래는 기능 단위로 묶는다.

- `src/biz_number/` — 비어 있는 사업자등록번호를 웹검색으로 찾는다
- `src/backend_client/` — 백엔드를 **호출하는** 쪽 (httpx)
- `src/investigation/` — 2차 조사. 요청·응답 형식, 지도 대조·웹검색·에이전트, 규칙 판정
- `src/biz_cert/` — 사업자등록증을 읽어 점주 가입 칸에 채울 값을 돌려준다
- `src/server/` — 백엔드가 AI를 **호출하는** 쪽 (FastAPI)

## 배경과 수치

- [`docs/웹검색_폴백.md`](docs/웹검색_폴백.md) — 문제 정의, 설계, 측정 수치, 남은 결정 사항.
- [`docs/백엔드_연동.md`](docs/백엔드_연동.md) — AI ↔ 백엔드 HTTP 구간. 방향을 나눈 이유, 계약이 어긋나는 걸 막는 테스트.
- [`docs/2차_조사.md`](docs/2차_조사.md) — 2차 조사 흐름(지도 대조 → 웹검색·에이전트 → 규칙 판정), 기능 스위치, 측정, 외부 출처 약관.
