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

  test("완료된 전화는 한 번 조회해 발화와 Jev 판정을 순서대로 보여 준다", async ({ page }) => {
    const job = structuredClone(FAILED_CALL_JOB);
    job.tasks[0].call.status = "COMPLETED";
    let requests = 0;
    await page.route("**/api/jobs/latest", (route) => route.fulfill({ json: job }));
    await page.route("**/api/calls/2/logs*", (route) => {
      requests += 1;
      return route.fulfill({ json: {
        verificationId: "2", status: "COMPLETED", startedAt: null, connectedAt: null,
        endedAt: null, failureReason: null, nextCursor: 3,
        logs: [
          { sequence: 1, timestamp: "2026-11-10T11:29:01Z", turn: 0, type: "ASSISTANT", text: "안녕하세요." },
          { sequence: 2, timestamp: "2026-11-10T11:29:05Z", turn: 1, type: "USER", text: "네 맞습니다." },
          { sequence: 3, timestamp: "2026-11-10T11:29:06Z", turn: 1, type: "JEV", verdict: "PROCESS", answer: "YES", answerConfidence: 0.9, control: "CONTINUE", controlConfidence: 1 },
        ],
      } });
    });
    await page.reload();

    const button = page.getByRole("button", { name: "전화 완료" });
    await button.click();
    const transcript = page.getByRole("region", { name: "통화 내용" });
    await expect(transcript.getByText("안녕하세요.")).toBeVisible();
    await expect(transcript.getByText("네 맞습니다.")).toBeVisible();
    await expect(transcript.getByText("Jev 판정 · 턴 1")).toBeVisible();
    await expect(transcript.getByText("처리 PROCESS")).toBeVisible();
    await expect(transcript.getByText("답변 YES · 신뢰도 0.90")).toBeVisible();
    await expect(transcript.getByText("대화 제어 CONTINUE · 신뢰도 1.00")).toBeVisible();
    await expect(transcript.locator("li")).toHaveCount(3);
    const rows = await transcript.locator("li").allTextContents();
    expect(rows[0]).toContain("안녕하세요.");
    expect(rows[1]).toContain("네 맞습니다.");
    expect(rows[2]).toContain("Jev 판정");
    await page.waitForTimeout(1_300);
    expect(requests).toBe(1);
    await button.click();
    await button.click();
    await expect(transcript.getByText("네 맞습니다.")).toBeVisible();
    expect(requests).toBe(1);
  });

  test("진행 중인 전화는 열린 동안 1초마다 새 발화를 조회한다", async ({ page }) => {
    const job = structuredClone(FAILED_CALL_JOB);
    job.tasks[0].call.status = "IN_PROGRESS";
    let requests = 0;
    await page.route("**/api/jobs/latest", (route) => route.fulfill({ json: job }));
    await page.route("**/api/calls/2/logs*", (route) => {
      requests += 1;
      return route.fulfill({ json: {
        verificationId: "2", status: "IN_PROGRESS", startedAt: null, connectedAt: null,
        endedAt: null, failureReason: null, nextCursor: requests >= 2 ? 3 : 1,
        logs: [
          { sequence: 1, timestamp: "2026-11-10T11:29:01Z", turn: 0, type: "ASSISTANT", text: "안녕하세요." },
          ...(requests >= 2 ? [{ sequence: 2, timestamp: "2026-11-10T11:29:05Z", turn: 1, type: "USER", text: "새 발화입니다." }] : []),
          ...(requests >= 2 ? [{ sequence: 3, timestamp: "2026-11-10T11:29:06Z", turn: 1, type: "JEV", verdict: "PROCESS", answer: "VALUE", answerConfidence: 0.49, control: "CONTINUE", controlConfidence: 0.97 }] : []),
        ],
      } });
    });
    await page.reload();

    const button = page.getByRole("button", { name: "전화 중" });
    await button.click();
    const transcript = page.getByRole("region", { name: "통화 내용" });
    await expect(transcript.getByText("안녕하세요.")).toBeVisible();
    await expect(transcript.getByText("새 발화입니다.")).toBeVisible({ timeout: 2_500 });
    await expect(transcript.getByText("답변 VALUE · 신뢰도 0.49")).toBeVisible();
    expect(requests).toBeGreaterThanOrEqual(2);
    await button.click();
    const requestsAfterClose = requests;
    await page.waitForTimeout(1_300);
    expect(requests).toBe(requestsAfterClose);
  });
});
