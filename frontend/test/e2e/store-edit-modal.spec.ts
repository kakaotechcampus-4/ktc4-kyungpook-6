import { expect, test } from "@playwright/test";
import type { Page, Route } from "@playwright/test";

/**
 * 가게 정보 수정 모달 E2E. Figma Modal(102:4996)
 *
 * 단위 테스트가 "무엇을 보낼지"를 정하는 함수들을 따로 검증한다면, 여기서는
 * 담당자가 실제로 화면을 눌러서 그 흐름이 이어지는지를 본다 —
 * 버튼을 누르면 모달이 뜨고, 고친 칸만 요청에 실리고, 실패하면 문구가 뜨는지.
 */

const STORE_PATH = /\/api\/stores\/\d+$/;
const CONFIRM_PATH = /\/api\/stores\/\d+\/confirm$/;

/** 백엔드가 지금 돌려주는 응답(스펙 선공개 단계). */
async function respondNotImplemented(route: Route) {
  await route.fulfill({ status: 501 });
}

/** 조사 결과 화면에서 첫 카드의 수정 모달을 연다. */
async function openModalFromAnalysis(page: Page) {
  await page.goto("/analysis");

  /*
    조회가 끝나기를 기다린 뒤에 누른다.
    조회 중에는 Skeleton 카드가 깔리는데 거기에도 "직접 수정하기" 버튼이 있고,
    그 버튼은 아직 아무 데도 연결돼 있지 않아 눌러도 모달이 열리지 않는다.
    가게 이름은 응답이 그려진 뒤에만 나오므로 이것을 신호로 쓴다.
  */
  await expect(page.getByRole("heading", { name: "맛나 치킨" })).toBeVisible();

  await page.getByRole("button", { name: "직접 수정하기" }).first().click();

  const modal = page.getByRole("dialog");
  await expect(modal).toBeVisible();
  return modal;
}

test.describe("모달 열기", () => {
  test("직접 수정하기를 누르면 그 가게 이름이 채워진 모달이 뜬다", async ({
    page,
  }) => {
    const modal = await openModalFromAnalysis(page);

    await expect(modal.getByRole("heading", { name: "가게 정보 수정" })).toBeVisible();
    await expect(modal.getByLabel("상호명")).toHaveValue("맛나 치킨");
    await expect(modal.getByLabel("주소")).toHaveValue("대구광역시 북구 대학로 80");
  });

  test("조사 결과 화면에서 열면 전화번호·운영상태·확인일이 비어 있다 — 조사 결과 API가 그 값을 주지 않는다", async ({
    page,
  }) => {
    const modal = await openModalFromAnalysis(page);

    await expect(modal.getByLabel("전화번호")).toHaveValue("");
    await expect(modal.getByLabel("운영 상태")).toHaveValue("UNKNOWN");
    await expect(modal.getByText("-", { exact: true })).toBeVisible();
  });

  test("처음에는 모달이 떠 있지 않다", async ({ page }) => {
    await page.goto("/analysis");
    await expect(page.getByRole("heading", { name: "맛나 치킨" })).toBeVisible();

    await expect(page.getByRole("dialog")).toBeHidden();
  });
});

test.describe("닫기", () => {
  test("X 버튼을 누르면 닫힌다", async ({ page }) => {
    const modal = await openModalFromAnalysis(page);

    await modal.getByRole("button", { name: "닫기" }).click();

    await expect(modal).toBeHidden();
  });

  test("ESC 를 눌러도 닫힌다 — 네이티브 dialog 의 기본 동작을 막지 않았는지 본다", async ({
    page,
  }) => {
    const modal = await openModalFromAnalysis(page);

    await page.keyboard.press("Escape");

    await expect(modal).toBeHidden();
  });

  test("닫았다가 다시 열 수 있다 — 열림 상태가 한쪽으로만 흘러가면 두 번째 열기가 안 된다", async ({
    page,
  }) => {
    const modal = await openModalFromAnalysis(page);
    await page.keyboard.press("Escape");
    await expect(modal).toBeHidden();

    await page.getByRole("button", { name: "직접 수정하기" }).first().click();

    await expect(modal).toBeVisible();
  });
});

test.describe("저장하기", () => {
  test("고친 칸만 요청에 실린다 — 건드리지 않은 칸은 키조차 없어야 한다", async ({
    page,
  }) => {
    await page.route(STORE_PATH, respondNotImplemented);
    const modal = await openModalFromAnalysis(page);

    await modal.getByLabel("전화번호").fill("053-111-2222");
    await modal.getByLabel("운영 상태").selectOption("SUSPENDED");

    const [request] = await Promise.all([
      page.waitForRequest(
        (req) => STORE_PATH.test(req.url()) && req.method() === "PATCH"
      ),
      modal.getByRole("button", { name: "저장하기" }).click(),
    ]);

    expect(request.postDataJSON()).toEqual({
      phone: "053-111-2222",
      status: "SUSPENDED",
    });
  });

  test("501 이면 모달이 닫히지 않고 안내 문구가 뜬다 — 저장된 것처럼 보이면 안 된다", async ({
    page,
  }) => {
    await page.route(STORE_PATH, respondNotImplemented);
    const modal = await openModalFromAnalysis(page);

    await modal.getByLabel("전화번호").fill("053-111-2222");
    await modal.getByRole("button", { name: "저장하기" }).click();

    await expect(modal.getByRole("alert")).toHaveText(
      "아직 서버에 저장 기능이 없습니다. 화면 확인용으로만 동작합니다."
    );
    await expect(modal).toBeVisible();
  });

  test("성공하면 모달이 닫힌다", async ({ page }) => {
    await page.route(STORE_PATH, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ storeId: 1, name: "맛나 치킨" }),
      })
    );
    const modal = await openModalFromAnalysis(page);

    await modal.getByLabel("전화번호").fill("053-111-2222");
    await modal.getByRole("button", { name: "저장하기" }).click();

    await expect(modal).toBeHidden();
  });

  test("아무것도 안 고치고 저장하면 요청 없이 닫힌다", async ({ page }) => {
    let requested = false;
    await page.route(STORE_PATH, (route) => {
      requested = true;
      return respondNotImplemented(route);
    });
    const modal = await openModalFromAnalysis(page);

    await modal.getByRole("button", { name: "저장하기" }).click();

    await expect(modal).toBeHidden();
    expect(requested).toBe(false);
  });

  test("상호명을 공백으로 지우면 요청 전에 막고 문구를 띄운다", async ({
    page,
  }) => {
    let requested = false;
    await page.route(STORE_PATH, (route) => {
      requested = true;
      return respondNotImplemented(route);
    });
    const modal = await openModalFromAnalysis(page);

    await modal.getByLabel("상호명").fill("   ");
    await modal.getByRole("button", { name: "저장하기" }).click();

    await expect(modal.getByRole("alert")).toHaveText(
      "상호명은 공백만으로 채울 수 없습니다."
    );
    expect(requested).toBe(false);
  });
});

test.describe("자체 확인 완료", () => {
  test("본문 없이 confirm 경로로 POST 한다 — 확인 시각은 서버가 정한다", async ({
    page,
  }) => {
    await page.route(CONFIRM_PATH, respondNotImplemented);
    const modal = await openModalFromAnalysis(page);

    const [request] = await Promise.all([
      page.waitForRequest(
        (req) => CONFIRM_PATH.test(req.url()) && req.method() === "POST"
      ),
      modal.getByRole("button", { name: "자체 확인 완료" }).click(),
    ]);

    expect(request.postData()).toBeNull();
  });

  test("성공해도 모달은 열려 있고 확인일만 갱신된다 — 이어서 값을 고칠 수 있어야 한다", async ({
    page,
  }) => {
    await page.route(CONFIRM_PATH, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          storeId: 1,
          name: "맛나 치킨",
          lastCheckedAt: "2026-05-12T09:30:00",
        }),
      })
    );
    const modal = await openModalFromAnalysis(page);

    await modal.getByRole("button", { name: "자체 확인 완료" }).click();

    await expect(modal.getByText("2026. 05. 12.")).toBeVisible();
    await expect(modal).toBeVisible();
  });
});
