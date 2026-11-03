import { expect, test } from "@playwright/test";

/**
 * 조사 결과 카드의 주 버튼.
 *
 * "메시지 보내기"(additional)는 아직 보낼 길이 없어 핸들러를 연결하지 않았다.
 * 그런데 버튼이 활성 색 그대로여서, 눌러도 아무 일이 없어 먹통처럼 보였다.
 * 여기서는 핸들러가 없는 쪽이 실제로 눌리지 않는지, 있는 쪽은 그대로 눌리는지를 본다.
 */

test.describe("조사 결과 카드 주 버튼", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/analysis");

    /*
      조회가 끝나기를 기다린 뒤에 본다.
      조회 중에는 Skeleton 카드가 깔리는데 거기에는 가게 이름이 없다 —
      이름이 나오는 것을 그려졌다는 신호로 쓴다.
    */
    await expect(page.getByRole("heading", { name: "맛나 치킨" })).toBeVisible();
  });

  test("보낼 길이 없는 '메시지 보내기' 는 눌리지 않는다", async ({ page }) => {
    await expect(
      page.getByRole("button", { name: "메시지 보내기" }).first(),
    ).toBeDisabled();
  });

  test("핸들러가 있는 '즉시 수정 반영하기' 는 그대로 눌린다", async ({ page }) => {
    await expect(
      page.getByRole("button", { name: "즉시 수정 반영하기" }).first(),
    ).toBeEnabled();
  });

  /*
    주 버튼을 막는 조건이 같은 카드의 '직접 수정하기' 까지 번지지 않아야 한다.
    그 버튼은 onEdit 을 쓰므로 onPrimaryAction 과 아무 상관이 없다.
  */
  test("주 버튼이 막힌 카드에서도 '직접 수정하기' 는 눌린다", async ({ page }) => {
    const card = page
      .getByRole("article")
      .filter({ has: page.getByRole("heading", { name: "라멘", exact: true }) });

    await expect(card.getByRole("button", { name: "메시지 보내기" })).toBeDisabled();
    await expect(card.getByRole("button", { name: "직접 수정하기" })).toBeEnabled();
  });
});
