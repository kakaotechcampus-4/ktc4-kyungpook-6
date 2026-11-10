import { expect, test } from "@playwright/test";

/**
 * 전화 단계 버튼(components/analysis/CallPhaseButton).
 *
 * 전화를 건 카드는 "전화하기" 자리가 단계 버튼(전화 실패 등)이 되고, 누르면 바로 아래에
 * 세부(사유 코드·할 일)를 펼친다. 요청 시각은 판 맨 위에 한 줄로 둔다.
 *
 * 운영(2026-11-11)에서 요청 시각을 오른쪽 위에 절대 배치했더니 판 폭이 버튼 폭(≈300px)이라
 * 첫 값(`WRONG_TARGET`)과 겹쳤다. 두 글상자가 겹치지 않는 것을 여기서 지킨다.
 */

const FAILED_CALL_JOB = {
  jobId: 4,
  status: "DONE",
  targetCount: 1,
  completedCount: 1,
  createdAt: "2026-11-10T10:30:00",
  finishedAt: "2026-11-10T10:34:00",
  errorMessage: null,
  tasks: [
    {
      taskId: 64,
      storeId: 64,
      storeName: "샵도쿄시장",
      storeAddress: "대구광역시 중구 동성로4길 43",
      storePhone: "01096319003",
      lastCheckedAt: null,
      classification: "PRIORITY_CHECK",
      failureReason: null,
      verification: null,
      call: {
        callId: 2,
        status: "FAILED",
        requestedAt: "2026-11-10T11:29:00",
        endedAt: "2026-11-10T11:30:00",
        failureReason: "WRONG_TARGET",
        failureKind: "FIX_NUMBER",
        answers: [],
      },
      evidences: [
        {
          field: "phone",
          source: "AI_WEB",
          description: "전화번호: 확인할 근거를 찾지 못함",
          sourceLabel: null,
          sourceUrl: null,
        },
      ],
      proposedChanges: [],
    },
  ],
};

test.describe("전화 단계 버튼", () => {
  test.beforeEach(async ({ page }) => {
    await page.route("**/api/jobs/latest", (route) => route.fulfill({ json: FAILED_CALL_JOB }));
    await page.goto("/analysis");
    await expect(page.getByRole("heading", { name: "샵도쿄시장" })).toBeVisible();
  });

  test("실패한 전화는 '전화 실패' 버튼이고, 누르면 사유 코드와 할 일이 펼쳐진다", async ({ page }) => {
    const card = page
      .getByRole("article")
      .filter({ has: page.getByRole("heading", { name: "샵도쿄시장" }) });
    const button = card.getByRole("button", { name: "전화 실패" });

    await expect(card.getByRole("button", { name: "전화하기" })).toHaveCount(0);
    await expect(button).toHaveAttribute("aria-expanded", "false");

    await button.click();
    await expect(button).toHaveAttribute("aria-expanded", "true");
    await expect(card.getByText("WRONG_TARGET")).toBeVisible();
    await expect(card.getByText("번호가 잘못됐습니다", { exact: false })).toBeVisible();

    /* 요청 시각과 첫 값이 겹치지 않는다 — 운영에서 겹쳤던 그 자리다. */
    const footnote = card.getByText("2026. 11. 10. 11:29 요청");
    await expect(footnote).toBeVisible();
    const a = await footnote.boundingBox();
    const b = await card.getByText("WRONG_TARGET").boundingBox();
    expect(a && b).toBeTruthy();
    const overlap =
      a!.x < b!.x + b!.width && b!.x < a!.x + a!.width && a!.y < b!.y + b!.height && b!.y < a!.y + a!.height;
    expect(overlap).toBe(false);

    await page.keyboard.press("Escape");
    await expect(button).toHaveAttribute("aria-expanded", "false");
    await expect(card.getByText("WRONG_TARGET")).toHaveCount(0);
  });
});
