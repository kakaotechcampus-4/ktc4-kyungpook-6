"""동일한 테스트셋을 여러 AI 모델(provider)로 돌려 정확도를 비교하는 벤치마크 스크립트.

사용법:
    uv run python -m eval.benchmark

테스트 케이스는 eval/fixtures/testcases_name_mismatch_answers.csv 에 추가한다
(팀원이 분류한 실제 케이스 — 상호명/등록상호명 불일치 유형별로 정리돼 있음).

한 번에 mock + 모델 하나만 돈다. .env의 BENCHMARK_MODEL 값을 바꿔가며 재실행해서
모델을 하나씩 갈아끼운다 (예: BENCHMARK_MODEL=gemini-3.8-flash).
비워두면 mock만 돈다. 등록된 모델 목록은 MODEL_FACTORIES 참고.
"""

import csv
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import openai
from dotenv import load_dotenv

from src.google_web_search_provider import GoogleWebSearchProvider
from src.name_search import MockNameSearchProvider, NameSearchProvider

AI_ROOT = Path(__file__).resolve().parent.parent
# 실행 시 작업 디렉터리가 어디든(ai/, 저장소 루트 등) ai/.env를 찾도록 경로를 명시한다.
load_dotenv(AI_ROOT / ".env")

FIXTURES_PATH = Path(__file__).resolve().parent / "fixtures" / "testcases_name_mismatch_answers.csv"


@dataclass
class TestCase:
    store_name: str
    address: str
    expected_official_name: str | None


@dataclass
class ModelResult:
    model_name: str
    accuracy: float
    avg_confidence: float
    failures: list[str]


def load_test_cases(path: Path = FIXTURES_PATH) -> list[TestCase]:
    # 엑셀에서 내보낸 CSV라 BOM이 붙어있음 — utf-8-sig로 열어야 첫 컬럼명이 깨지지 않는다.
    with path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))

    return [
        TestCase(
            store_name=row["상호명"],
            address=row["정제도로명주소"] or row["정제지번주소"],
            expected_official_name=row["국세청_등록상호명"] or None,
        )
        for row in rows
    ]


def run_benchmark(model_name: str, provider: NameSearchProvider, cases: list[TestCase]) -> ModelResult:
    correct = 0
    total_confidence = 0.0
    failures: list[str] = []

    for case in cases:
        try:
            result = provider.search_official_name(case.store_name, case.address)
        except openai.APIError as e:
            # 한 케이스에서 API 에러(네트워크/레이트리밋/인증 등)가 나도
            # 나머지 모델·케이스 비교는 계속 진행한다.
            print(f"[{model_name}] {case.store_name} 조회 실패: {e}")
            failures.append(case.store_name)
            continue

        total_confidence += result.confidence
        if result.official_name == case.expected_official_name:
            correct += 1
        else:
            failures.append(case.store_name)

    n = len(cases)
    return ModelResult(
        model_name=model_name,
        accuracy=correct / n if n else 0.0,
        avg_confidence=total_confidence / n if n else 0.0,
        failures=failures,
    )


def print_report(results: list[ModelResult]) -> None:
    header = f"{'model':<24}{'accuracy':<10}{'avg_conf':<10}failures"
    print(header)
    print("-" * len(header))
    for r in results:
        print(f"{r.model_name:<24}{r.accuracy:<10.1%}{r.avg_confidence:<10.2f}{r.failures}")


# 테스트해볼 모델 후보 목록. 실제로 인스턴스를 만들지 않고 "만드는 방법"만 등록해둬서,
# 목록에 있다고 전부 실행되는 게 아니라 BENCHMARK_MODEL로 고른 것 하나만 만들어진다.
MODEL_FACTORIES: dict[str, Callable[[], NameSearchProvider]] = {
    # gemini-3.5-flash-lite 등 다른 모델은 카테캠 프록시 키가 401로 거부함 — 확인된 것만 등록.
    "gemini-3.8-flash": lambda: GoogleWebSearchProvider(model="gemini-3.8-flash"),
}

MODEL_PROVIDERS: dict[str, NameSearchProvider] = {
    "mock": MockNameSearchProvider(),
}

selected_model = os.environ.get("BENCHMARK_MODEL")
if selected_model:
    if selected_model not in MODEL_FACTORIES:
        raise ValueError(
            f"알 수 없는 BENCHMARK_MODEL: {selected_model!r}. "
            f"선택 가능: {sorted(MODEL_FACTORIES)}"
        )
    MODEL_PROVIDERS[selected_model] = MODEL_FACTORIES[selected_model]()


if __name__ == "__main__":
    cases = load_test_cases()
    results = [run_benchmark(name, provider, cases) for name, provider in MODEL_PROVIDERS.items()]
    print_report(results)
