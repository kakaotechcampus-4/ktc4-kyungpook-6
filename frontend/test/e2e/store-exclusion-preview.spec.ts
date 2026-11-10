import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

/**
 * 조사에서 빠질 가게를 **고르기 전에** 알 수 있게 하는 표시.
 *
 * 담당자가 가게 60곳을 골라 조사를 시작했는데 화면에 "56곳"이 떴다. 차이 4곳은 국세청 대조 기록이
 * 없어 빠진 가게였다. 제외는 설계된 정상 동작인데, 화면이 그걸 미리도 나중에도 설명하지 않았다.
 * 가게 명단을 새로 주입하면 다음 국세청 배치가 돌기 전까지 전부 그 상태라 같은 혼란이 반복된다.
 *
 * 여기서는 목록 행에 사유가 붙는지, 조사 시작 모달이 제외를 예고하는지, **빠지는 가게가 0곳이면
 * 그 줄을 그리지 않는지**를 본다. 백엔드가 가게마다 내려주는 `investigationExcluded` /
 * `investigationExclusionReason` 만 보고 그리므로, 응답을 가로채 그 값만 바꿔 확인한다.
 */

type ExclusionReason =
  | "NO_NTS_CHECK"
  | "DATA_PROBLEM"
  | "ALREADY_CLOSED"
  | "ALREADY_SUSPENDED";

const store = (
  storeId: number,
  name: string,
  investigationExclusionReason: ExclusionReason | null,
) => ({
  storeId,
  name,
  addressRoad: "대구 북구 대학로 80",
  lat: 35.8888,
  lng: 128.6107,
  status: "OPEN",
  category: "분식",
  phone: "053-123-4567",
  bizNo: "1234567890",
  lastCheckedAt: "2026-09-01T10:00:00",
  lastChangedAt: null,
  investigationExcluded: investigationExclusionReason !== null,
  investigationExclusionReason,
});

const storesPage = (content: ReturnType<typeof store>[]) => ({
  content,
  page: 0,
  limit: 20,
  totalElements: content.length,
  totalPages: 1,
  hasNext: false,
});

/** 토큰이 없으면 조사 상태를 아예 조회하지 않는다(로그인 화면에서 401 을 맞지 않으려고). */
const logIn = (page: Page) =>
  page.addInitScript(() => sessionStorage.setItem("accessToken", "e2e-token"));

/** 조사 이력이 없으면 백엔드가 404 를 준다 — 우하단 자리를 진행 표시가 쓰지 않게 둔다. */
const noRunningJob = (page: Page) =>
  page.route("**/api/jobs/latest", (route) =>
    route.fulfill({ status: 404, json: {} }),
  );

const serveStores = (page: Page, content: ReturnType<typeof store>[]) =>
  page.route("**/api/stores?*", (route) =>
    route.fulfill({ json: storesPage(content) }),
  );

/** 체크박스 입력은 sr-only 라 보이는 칸이 클릭을 가린다 — force 로 입력을 직접 체크한다. */
async function select(page: Page, name: string) {
  const checkbox = page.getByRole("checkbox", { name: `${name} 선택` });
  await expect(checkbox).toBeAttached();
  await checkbox.check({ force: true });
}

async function openSurveyModal(page: Page) {
  await page.getByRole("button", { name: "선택한 가게 조사 시작" }).click();
  const modal = page.getByRole("dialog");
  await expect(modal).toBeVisible();
  return modal;
}

test.describe("조사 제외 예고", () => {
  test.beforeEach(async ({ page }) => {
    await logIn(page);
    await noRunningJob(page);
  });

  test("빠질 가게의 행에 사유가 붙는다 — 숨기거나 못 고르게 하지는 않는다", async ({
    page,
  }) => {
    await serveStores(page, [
      store(1, "대조 안 된 가게", "NO_NTS_CHECK"),
      store(2, "조사되는 가게", null),
    ]);

    await page.goto("/");

    await expect(
      page.getByRole("img", {
        name: "국세청 대조 기록 없음 — 지금 조사를 시작하면 이 가게는 조사되지 않습니다",
      }),
    ).toBeVisible();
    /* 빠지는 가게도 보이고 고를 수 있어야 한다 — 번호를 고치거나 점주 연결을 봐야 할 수 있다. */
    await expect(
      page.getByRole("checkbox", { name: "대조 안 된 가게 선택" }),
    ).toBeEnabled();
  });

  test("조사되는 가게에는 아무 표시도 붙지 않는다", async ({ page }) => {
    await serveStores(page, [store(1, "조사되는 가게", null)]);

    await page.goto("/");

    await expect(page.getByText("조사되는 가게")).toBeVisible();
    await expect(page.getByRole("img", { name: /조사되지 않습니다/ })).toHaveCount(
      0,
    );
  });

  test("모달이 가게 수 아래에 몇 곳이 왜 빠지는지 미리 적는다", async ({
    page,
  }) => {
    await serveStores(page, [
      store(1, "대조 안 된 가게", "NO_NTS_CHECK"),
      store(2, "번호 틀린 가게", "DATA_PROBLEM"),
      store(3, "조사되는 가게", null),
    ]);

    await page.goto("/");
    await select(page, "대조 안 된 가게");
    await select(page, "번호 틀린 가게");
    await select(page, "조사되는 가게");

    const modal = await openSurveyModal(page);
    await expect(modal).toContainText("3개");
    await expect(modal).toContainText(
      "이 중 1곳은 국세청 대조 기록이 없어 조사되지 않습니다",
    );
    await expect(modal).toContainText(
      "이 중 1곳은 사업자등록번호가 없거나 국세청에 없는 번호라 조사되지 않습니다",
    );
  });

  test("빠지는 가게가 없으면 그 줄을 그리지 않는다", async ({ page }) => {
    await serveStores(page, [
      store(1, "조사되는 가게", null),
      store(2, "조사되는 다른 가게", null),
    ]);

    await page.goto("/");
    await select(page, "조사되는 가게");
    await select(page, "조사되는 다른 가게");

    const modal = await openSurveyModal(page);
    await expect(modal).toContainText("2개");
    /* "0곳은 빠집니다" 는 알려 줄 것이 없는데 자리만 차지하고, 무언가 잘못됐다는 인상을 준다. */
    await expect(modal).not.toContainText("조사되지 않습니다");
  });

  test("빠지는 가게만 골랐어도 그 수만큼 예고한다", async ({ page }) => {
    await serveStores(page, [
      store(1, "폐업 확인된 가게", "ALREADY_CLOSED"),
      store(2, "휴업 확인된 가게", "ALREADY_SUSPENDED"),
    ]);

    await page.goto("/");
    await select(page, "폐업 확인된 가게");
    await select(page, "휴업 확인된 가게");

    const modal = await openSurveyModal(page);
    await expect(modal).toContainText(
      "이 중 1곳은 우리와 국세청 모두 폐업으로 확인돼 조사되지 않습니다",
    );
    await expect(modal).toContainText(
      "이 중 1곳은 우리와 국세청 모두 휴업으로 확인돼 조사되지 않습니다",
    );
  });
});
