# Test Pipeline

- 목적: 최대한 사람이 코드를 덜 보고, 테스트를 덜 하는것이 목적!
- 방법: 작성하는 코드에 대한 모든 테스트를 미리 작성하고, 검증하며 개발한다. → TDD (Test-Driven-Development)

# TADD

- 위의 TDD에 AI를 더한 기법이다.
1. AI에게 구현할 내용을 이야기하며, Test Code를 작성해달라고 한다.
2. 위의 테스트 코드를 통과할 코드를 작성한다.
3. 2번에 작성한 코드가 이전에 작성했던 모든 테스트 코드를 통과하는지 확인한다 → 회귀 테스트

# CI/CD

- Pull Request 과정에서 Github Action을 활용하여 크게 3가지의 테스트를 거칠 예정
    - Static Test: 코드 실행 없이 정적으로 검사 가능한 영역을 검사한다. 코드 내에 오타나 사용하지 않는 라이브러리 등을 정리할 수 있다.
    - Unit Test: TDD에서 작성한 테스트 코드를 다시 확인한다.
    - E2E Test: 실제로 동작하는 환경에서 테스트를 자동으로 진행한다.

# Frontend

- ESLint + Vitest + Playwright

# Backend

- Gradle + JUnit + Mockito
  <!-- 최초 작성 시 Maven으로 적혀 있었으나, 백엔드는 최초 세팅 커밋부터 Gradle(build.gradle/gradlew)만 써왔고 pom.xml이 존재한 적이 없어 실제 상태에 맞게 수정함 -->

# AI

- Ruff + pytest + LangSmith
