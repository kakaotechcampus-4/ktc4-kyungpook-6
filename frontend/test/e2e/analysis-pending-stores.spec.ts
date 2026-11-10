import { expect, test } from '@playwright/test';

const completedTask = {
  taskId: 1,
  storeId: 11,
  storeName: '조사 완료 가게',
  storeAddress: '대구 북구 대학로 80',
  storePhone: '053-111-1111',
  lastCheckedAt: null,
  classification: 'NO_CHANGE',
  failureReason: null,
  evidences: [],
  proposedChanges: [],
  verification: null,
  call: null,
};

test('아직 완료된 조사가 없어도 조사 전 가게 목록을 보여 준다', async ({ page }) => {
  await page.route('**/api/jobs/latest', (route) => route.fulfill({ json: {
    jobId: 7,
    status: 'PENDING',
    targetCount: 1,
    completedCount: 0,
    createdAt: '2026-11-10T10:00:00',
    finishedAt: null,
    errorMessage: null,
    tasks: [],
    pendingStores: [{ storeId: 12, storeName: '조사 전 가게', storeAddress: '대구 중구 동성로 1', storePhone: null }],
  } }));

  await page.goto('/analysis');

  await expect(page.getByRole('heading', { name: '조사 전인 가게 (1)' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '조사 전 가게' })).toBeVisible();
  await expect(page.getByText('전화번호 없음')).toBeVisible();
  await expect(page.getByText('조사 차례를 기다리고 있습니다.')).toBeVisible();
});

test('조사 전 가게가 결과 섹션 맨 아래에 보이고 완료되면 결과 카드로 이동한다', async ({ page }) => {
  let finished = false;
  await page.route('**/api/jobs/latest', (route) => route.fulfill({ json: {
    jobId: 7,
    status: finished ? 'DONE' : 'IN_PROGRESS',
    targetCount: 2,
    completedCount: finished ? 2 : 1,
    createdAt: '2026-11-10T10:00:00',
    finishedAt: finished ? '2026-11-10T10:10:00' : null,
    errorMessage: null,
    tasks: finished ? [completedTask, { ...completedTask, taskId: 2, storeId: 12, storeName: '조사 전 가게' }] : [completedTask],
    pendingStores: finished ? [] : [{ storeId: 12, storeName: '조사 전 가게', storeAddress: '대구 중구 동성로 1', storePhone: '053-222-2222' }],
  } }));

  await page.goto('/analysis');

  const sections = page.locator('main section');
  await expect(page.getByRole('heading', { name: '조사 전인 가게 (1)' })).toBeVisible();
  await expect(sections.last().getByRole('heading', { name: '조사 전 가게' })).toBeVisible();
  await expect(sections.last().getByText('조사 차례를 기다리고 있습니다.')).toBeVisible();
  await expect(sections.last().getByRole('button', { name: '직접 수정하기' })).toHaveCount(0);
  expect(await sections.allTextContents()).toEqual(expect.arrayContaining([
    expect.stringContaining('조사 완료 가게'),
    expect.stringContaining('조사 전 가게'),
  ]));

  finished = true;
  await expect(page.getByRole('heading', { name: '조사 전인 가게 (1)' })).toHaveCount(0, { timeout: 5_000 });
  await expect(page.getByRole('heading', { name: '조사 전 가게' })).toBeVisible();
  await expect(page.getByText('조사 차례를 기다리고 있습니다.')).toHaveCount(0);
});
