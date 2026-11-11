import { expect, test } from "@playwright/test";

/**
 * 조사 결과 카드의 주 버튼.
 *
 * 추가확인 카드에는 주 버튼이 없다. 원래 "메시지 보내기" 였는데 보낼 길이 없어
 * 비활성으로 떠 있었고, PROMPT-165 에서 그 자리를 "전화하기" 가 넘겨받으며 지웠다
 * (components/AgentSurveyResultCard 의 VARIANT.additional.primaryActionLabel = null).
 *
 * 눌러도 아무 일이 없는 버튼을 활성처럼 보여주지 않는다 — 그 약속을 여기서 지킨다.
 * 주 버튼이 있는 쪽(우선확인)은 그대로 눌리고, 걸 수 없는 전화는 비활성이어야 한다.
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

  test("추가확인 카드에는 주 버튼이 없다", async ({ page }) => {
    /* 지워진 버튼이 되살아나면 비활성이 아니라 "없음" 이 깨진다. 이름으로 직접 막는다. */
    await expect(
      page.getByRole("button", { name: "메시지 보내기" }),
    ).toHaveCount(0);
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
  test("전화번호가 없는 카드는 '전화하기' 가 막히고 '직접 수정하기' 는 눌린다", async ({
    page,
  }) => {
    const card = page
      .getByRole("article")
      .filter({ has: page.getByRole("heading", { name: "라멘", exact: true }) });

    /* 추가확인이라 주 버튼은 아예 없다. */
    await expect(card.getByRole("button", { name: "메시지 보내기" })).toHaveCount(0);
    /* 번호가 없어 걸 수 없다 — 눌러도 409 가 날 버튼을 열어 두지 않는다. */
    await expect(card.getByRole("button", { name: "전화하기" })).toBeDisabled();
    /* 전화를 막는 조건이 '직접 수정하기' 까지 번지면 안 된다. onEdit 은 별개다. */
    await expect(card.getByRole("button", { name: "직접 수정하기" })).toBeEnabled();
  });
});
