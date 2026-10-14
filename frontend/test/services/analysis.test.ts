import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  applyTask,
  confirmTask,
  getJobResult,
  startJob,
} from '../../src/services/analysis';
import { getRequest, postRequest } from '../../src/services/api';

/* 실제 요청은 보내지 않고, 어떤 주소로 무엇을 넘겼는지만 본다. */
vi.mock('../../src/services/api', () => ({
  getRequest: vi.fn(),
  postRequest: vi.fn(),
}));

/**
 * 조사 API 호출 계약.
 *
 * 경로가 백엔드와 어긋나면 화면은 멀쩡한데 요청만 조용히 404 가 된다.
 * 실제로 "조사 시작하기" 가 아무 요청도 보내지 않은 채 모달만 닫히고 있었다(PROMPT-150).
 */
describe('startJob — POST /api/jobs', () => {
  beforeEach(() => vi.clearAllMocks());

  it('고른 가게 id 를 storeIds 로 담아 보낸다', () => {
    startJob([3, 7, 11]);

    expect(postRequest).toHaveBeenCalledWith('/api/jobs', {
      storeIds: [3, 7, 11],
    });
  });
});

describe('applyTask · confirmTask — POST /api/tasks/{taskId}/...', () => {
  beforeEach(() => vi.clearAllMocks());

  it('수정안 반영은 /apply 로 보낸다', () => {
    applyTask(5);

    expect(postRequest).toHaveBeenCalledWith('/api/tasks/5/apply', undefined);
  });

  it('확인만 하는 건 /confirm 으로 보낸다', () => {
    confirmTask(6);

    expect(postRequest).toHaveBeenCalledWith('/api/tasks/6/confirm', undefined);
  });
});

describe('getJobResult — 조사 결과 조회', () => {
  beforeEach(() => vi.clearAllMocks());

  it('jobId 가 있으면 그 조사를 부른다', () => {
    getJobResult('12');

    expect(getRequest).toHaveBeenCalledWith('/api/jobs/12');
  });

  it('jobId 가 없으면 가장 최근 조사를 부른다', () => {
    getJobResult();

    expect(getRequest).toHaveBeenCalledWith('/api/jobs/latest');
  });
});
