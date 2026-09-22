# store-info-agent

참여가게 정보가 최신 상태인지 공개정보 API와 대조하여
담당자가 확인할 대상을 선별해 주는 에이전트.

## 개발 환경
1. `uv sync` 로 의존성 설치
2. `.env.example` 참고해 `.env` 생성 후 값 채우기

## 테스트

```bash
uv run pytest tests/          # ai/ 에서 실행
```

`eval/`은 CI에서 돌리지 않는다 — 개인 GCP 계정에 과금된다.

## 폴더 구조
- `src/` — 에이전트 로직. 실제 런타임에 쓰이는 코드만 둔다 (검증/비교용 스크립트는 X)
- `tests/` — pytest 단위 테스트. "코드가 의도대로 동작하는가"를 pass/fail로 검증
- `eval/` — AI 모델 비교용 벤치마크 스크립트. pytest 대상 아님, 사람이 직접 실행해서
  모델별 정확도를 리포트로 확인하는 용도 (`uv run python -m eval.<script>`)
  - `eval/fixtures/` — 벤치마크용 케이스 데이터 (예: 상호명/주소/기대값)

새 기능 추가 시에도 이 구분을 따른다: 프로덕션 로직은 `src/`, 정답 검증은 `tests/`,
모델·구현 방식 비교 실험은 `eval/`.

**"프로덕션에 바로 못 올린다"는 이유로 `eval/`에 두지 않는다.** 그런 제약은 폴더가 아니라
**각 모듈 독스트링**에 적는다 — 개인 GCP 계정에 묶인다든가, 백엔드 구현으로 갈아 끼울
임시 코드라든가. 파일이 뭘 하는지도 마찬가지다. 여기에 옮겨 적지 않는다.

`src/` 아래는 기능 단위로 묶는다.

- `src/biz_number/` — 사업자등록번호 (PROMPT-49)
- `src/backend_client/` — 백엔드를 **호출하는** 쪽 (httpx)
- `src/server/` — 백엔드가 AI를 **호출하는** 쪽 (FastAPI). 실행: `uv run uvicorn src.server.main:app --reload --port 8000`

## 배경과 수치

- [`docs/웹검색_폴백.md`](docs/웹검색_폴백.md) — 문제 정의, 설계, 측정 수치, 남은 결정 사항.
- [`docs/백엔드_연동.md`](docs/백엔드_연동.md) — AI ↔ 백엔드 HTTP 구간. 방향을 나눈 이유, 계약이 어긋나는 걸 막는 테스트.
