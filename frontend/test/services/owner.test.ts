import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  approveOwner,
  getLinkedStores,
  getOwnerApplications,
  getStoreCandidates,
  linkStore,
  rejectOwner,
  reopenOwner,
  unlinkStore,
} from '../../src/services/owner';
import { deleteRequest, getRequest, postRequest } from '../../src/services/api';

vi.mock('../../src/services/api', () => ({
  getRequest: vi.fn(),
  postRequest: vi.fn(),
  deleteRequest: vi.fn(),
}));

/**
 * 점주 가입 관리 API 호출 계약.
 *
 * 백엔드에는 이 API 가 여섯 개 다 있는데 화면에서 부르는 곳이 하나도 없었다(PROMPT-150).
 * 경로가 어긋나면 화면은 멀쩡한데 요청만 조용히 404 가 되므로 여기서 고정한다.
 */
describe('가입 신청 조회', () => {
  beforeEach(() => vi.clearAllMocks());

  it('상태를 주지 않으면 승인 대기를 본다', () => {
    getOwnerApplications();

    expect(getRequest).toHaveBeenCalledWith('/api/admin/owners', {
      status: 'PENDING',
      page: 0,
      limit: 20,
    });
  });

  it('상태를 주면 그 상태로 조회한다', () => {
    getOwnerApplications('REJECTED', 2, 50);

    expect(getRequest).toHaveBeenCalledWith('/api/admin/owners', {
      status: 'REJECTED',
      page: 2,
      limit: 50,
    });
  });

  it('연결된 가게는 회원 ID 로 조회한다 — 끊을 대상을 고르는 목록이다', () => {
    getLinkedStores(7);

    expect(getRequest).toHaveBeenCalledWith('/api/admin/owners/7/stores');
  });

  it('후보 가게는 회원 ID 로 조회한다', () => {
    getStoreCandidates(7);

    expect(getRequest).toHaveBeenCalledWith('/api/admin/owners/7/store-candidates');
  });
});

describe('승인·거절', () => {
  beforeEach(() => vi.clearAllMocks());

  it('승인은 연결할 가게를 함께 보낸다 — 가게 없이는 승인할 수 없다', () => {
    approveOwner(3, 123);

    expect(postRequest).toHaveBeenCalledWith('/api/admin/owners/3/approve', {
      storeId: 123,
    });
  });

  it('되돌리기는 /reopen 으로 보낸다 — 거절된 신청만 되돌릴 수 있다', () => {
    reopenOwner(3);

    expect(postRequest).toHaveBeenCalledWith('/api/admin/owners/3/reopen', undefined);
  });

  it('거절은 본문이 없다', () => {
    rejectOwner(3);

    expect(postRequest).toHaveBeenCalledWith('/api/admin/owners/3/reject', undefined);
  });
});

describe('가게 연결·끊기', () => {
  beforeEach(() => vi.clearAllMocks());

  it('연결은 POST', () => {
    linkStore(3, 9);

    expect(postRequest).toHaveBeenCalledWith('/api/admin/owners/3/stores/9', undefined);
  });

  it('끊기는 DELETE', () => {
    unlinkStore(3, 9);

    expect(deleteRequest).toHaveBeenCalledWith('/api/admin/owners/3/stores/9');
  });
});
