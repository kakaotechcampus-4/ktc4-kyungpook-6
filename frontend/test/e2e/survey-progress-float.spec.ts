import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

/**
 * 우하단 조사 진행 표시.
 *
 * 담당자가 조사를 걸어 두고 가게 목록으로 돌아가면 진행 상황을 볼 길이 없었다 —
 * 분석 결과 화면에만 "조사 중 · N곳 중 M곳 끝남" 이 있어서, 다른 화면에서는 끝났는지
 * 알 수 없어 새로고침을 반복해야 했다. 여기서는 그 표시가 **모든 화면에** 뜨는지,
 * 진행→완료·실패로 바뀌는지, 조사가 없을 때 기존 로봇 버튼이 그대로인지를 본다.
 */

type JobStatus = "PENDING" | "IN_PROGRESS" | "DONE" | "FAILED";

const job = (status: JobStatus, completedCount: number, extra = {}) => ({
  jobId: 7,
  status,
  targetCount: 6,
  completedCount,
  createdAt: "2026-11-10T10:00:00",
  finishedAt: status === "DONE" ? "2026-11-10T10:10:00" : null,
  errorMessage: null,
  tasks: [],
  ...extra,
});

/** 토큰이 없으면 조사 상태를 아예 조회하지 않는다(로그인 화면에서 401 을 맞지 않으려고). */
const logIn = (page: Page) =>
  page.addInitScript(() =>
    sessionStorage.setItem("accessToken", "e2e-token")
  );

/**
 * 가게 목록에서 첫 행을 고른다. 로봇 버튼(조사 시작)은 선택이 하나라도 있어야 뜬다.
 *
 * 백엔드 없이 도는 테스트라 목록은 목업으로 떨어진다 — 그 행이 그려진 뒤에 눌러야 한다
 * (조회 중에는 이름 없는 Skeleton 행이라 선택해도 셀 가게가 없다).
 * 체크박스 입력은 sr-only 라 보이는 칸이 클릭을 가리므로 force 로 입력을 직접 체크한다.
 */
async function selectFirstStore(page: Page) {
  const checkbox = page.getByRole("checkbox", { name: "정든 국밥집 선택" });
  await expect(checkbox).toBeAttached();
  await checkbox.check({ force: true });
}

test.describe("조사 진행 표시", () => {
  test.beforeEach(async ({ page }) => {
    await logIn(page);
  });

  /*
    도는 중 원의 색과 보더는 Figma 115:5633 그대로여야 한다.

    계산된 값으로 본다 — 보더 두께가 공유 토큰(FLOAT_CIRCLE 의 border-4)을
    덮어쓰는 구조라, Tailwind 가 내보내는 CSS 순서가 바뀌면 조용히 4px 로 돌아간다.
    클래스 문자열만 보는 검사로는 그걸 못 잡는다.
  */
  test("도는 중에는 어두운 원에 6px 보더다", async ({ page }) => {
    await page.route("**/api/jobs/latest", (route) =>
      route.fulfill({ json: job("IN_PROGRESS", 2) })
    );

    await page.goto("/");

    const float = page.getByRole("status").filter({ hasText: "작업 중" });
    await expect(float).toBeVisible();

    const circle = float.locator("span.size-14 > span").first();
    await expect(circle).toHaveCSS("background-color", "rgb(66, 32, 6)");
    await expect(circle).toHaveCSS("border-top-width", "6px");
    await expect(circle).toHaveCSS("border-top-color", "rgb(241, 245, 249)");
  });

  test("조사가 도는 동안 가게 목록에도 진행률이 뜬다", async ({ page }) => {
    await page.route("**/api/jobs/latest", (route) =>
      route.fulfill({ json: job("IN_PROGRESS", 2) })
    );

    await page.goto("/");

    const float = page.getByRole("status").filter({ hasText: "작업 중" });
    await expect(float).toBeVisible();
    /* 6곳 중 2곳 = 33%. 내림이라 34% 가 아니다. */
    await expect(float).toContainText("33%");
  });

  test("분석 결과 화면이 아닌 다른 화면에서도 뜬다", async ({ page }) => {
    await page.route("**/api/jobs/latest", (route) =>
      route.fulfill({ json: job("IN_PROGRESS", 0) })
    );

    await page.goto("/owners");

    const float = page.getByRole("status").filter({ hasText: "작업 중" });
    await expect(float).toBeVisible();
    await expect(float).toContainText("0%");
  });

  test("조사가 끝나면 '작업 완료' 로 바뀌고, 눌러 결과 화면으로 간다", async ({
    page,
  }) => {
    let finished = false;
    await page.route("**/api/jobs/latest", (route) =>
      route.fulfill({
        json: finished ? job("DONE", 6) : job("IN_PROGRESS", 5),
      })
    );

    await page.goto("/");
    await expect(
      page.getByRole("status").filter({ hasText: "작업 중" })
    ).toBeVisible();

    /* 다음 폴링(3초)에 끝난 응답을 준다. */
    finished = true;
    const done = page.getByRole("status").filter({ hasText: "작업 완료" });
    await expect(done).toBeVisible({ timeout: 10_000 });

    await done.getByRole("button", { name: /조사 결과 보기/ }).click();
    await expect(page).toHaveURL(/\/analysis\/7$/);
  });

  test("조사가 멈추면 '작업 실패' 로 바뀐다", async ({ page }) => {
    let failed = false;
    await page.route("**/api/jobs/latest", (route) =>
      route.fulfill({
        json: failed
          ? job("FAILED", 1, { errorMessage: "AI 서버가 응답하지 않습니다" })
          : job("IN_PROGRESS", 1),
      })
    );

    await page.goto("/");
    await expect(
      page.getByRole("status").filter({ hasText: "작업 중" })
    ).toBeVisible();

    failed = true;
    const failedFloat = page.getByRole("status").filter({ hasText: "작업 실패" });
    await expect(failedFloat).toBeVisible({ timeout: 10_000 });
    await expect(
      failedFloat.getByRole("button", {
        name: /AI 서버가 응답하지 않습니다/,
      })
    ).toBeVisible();

    /* 확인했으면 닫을 수 있다 — 끝난 알림이 화면에 영영 떠 있지 않는다. */
    await failedFloat.getByRole("button", { name: "작업 실패 알림 닫기" }).click();
    await expect(failedFloat).toBeHidden();
  });

  test("조사한 적이 없으면 기존 로봇 버튼이 그대로다", async ({ page }) => {
    /* 조사 이력이 없으면 백엔드가 404 를 준다. */
    await page.route("**/api/jobs/latest", (route) =>
      route.fulfill({ status: 404, json: { title: "조사 기록이 없습니다" } })
    );

    await page.goto("/");

    /* 가게를 하나 고르면 조사 시작 버튼이 뜬다(기존 동작). */
    await selectFirstStore(page);

    await expect(
      page.getByRole("button", { name: "선택한 가게 조사 시작" })
    ).toBeVisible();
    await expect(page.getByRole("status").filter({ hasText: "작업" })).toHaveCount(
      0
    );
  });

  /*
    이 기능의 주 흐름이다. 조사가 없는 동안에는 폴링을 멈춰 두기 때문에, 조사를 시작할 때
    '가장 최근 조사' 캐시를 갱신하지 않으면 표시가 다음 새로고침까지 뜨지 않는다.
  */
  test("조사를 시작하면 바로 진행 표시가 뜬다", async ({ page }) => {
    let started = false;

    await page.route("**/api/stores*", (route) =>
      route.fulfill({
        json: {
          content: [
            {
              storeId: 42,
              name: "정든 국밥집",
              addressRoad: "대구 북구 대학로 80",
              lat: null,
              lng: null,
              status: "OPEN",
              category: null,
              phone: "053-123-4567",
              bizNo: null,
              lastCheckedAt: "2026-09-01T10:00:00",
            },
          ],
          page: 0,
          limit: 20,
          totalElements: 1,
          totalPages: 1,
          hasNext: false,
        },
      })
    );
    await page.route("**/api/jobs/latest", (route) =>
      started
        ? route.fulfill({ json: job("PENDING", 0, { targetCount: 1 }) })
        : route.fulfill({ status: 404, json: { title: "조사 기록이 없습니다" } })
    );
    await page.route("**/api/jobs", (route) => {
      started = true;
      return route.fulfill({ json: { jobId: 7, targetCount: 1, excluded: [] } });
    });

    await page.goto("/");
    await page
      .getByRole("checkbox", { name: "정든 국밥집 선택" })
      .check({ force: true });
    await page.getByRole("button", { name: "선택한 가게 조사 시작" }).click();
    await page.getByRole("button", { name: "조사 시작하기" }).click();

    await expect(page).toHaveURL(/\/analysis\/7$/);
    await expect(
      page.getByRole("status").filter({ hasText: "작업 중" })
    ).toBeVisible();
  });

  test("끝난 지난 조사는 다시 알리지 않는다", async ({ page }) => {
    /*
      /api/jobs/latest 는 조사가 끝난 뒤에도 영영 그 조사를 준다.
      이걸 그대로 띄우면 화면을 열 때마다 지난주 조사의 "작업 완료" 가 다시 뜬다.
    */
    await page.route("**/api/jobs/latest", (route) =>
      route.fulfill({ json: job("DONE", 6) })
    );

    await page.goto("/");
    await selectFirstStore(page);

    await expect(
      page.getByRole("button", { name: "선택한 가게 조사 시작" })
    ).toBeVisible();
    await expect(
      page.getByRole("status").filter({ hasText: "작업 완료" })
    ).toHaveCount(0);
  });
});
