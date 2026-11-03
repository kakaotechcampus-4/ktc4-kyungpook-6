import { AxiosError, AxiosHeaders } from 'axios';
import { describe, expect, it } from 'vitest';
import { isNotFound, isUnauthorized } from '../../src/utils/apiError';

/**
 * 실패 응답을 종류별로 가르는 부분만 본다.
 *
 * 가르지 못하면 화면이 어긋난다 — "아직 조사한 적이 없다"(404)를 "서버가 죽었다"로 보고 그 자리에
 * 목업을 깔면, 담당자가 가짜 가게를 진짜로 읽는다. 운영에 처음 올린 날 실제로 그랬다.
 */
function responseError(status: number): AxiosError {
  const error = new AxiosError('실패', 'ERR_BAD_REQUEST');
  error.response = {
    status,
    statusText: '',
    data: {},
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  };
  return error;
}

describe('isNotFound — 아직 없는 것과 닿지 못한 것을 가른다', () => {
  it('404 면 참이다', () => {
    expect(isNotFound(responseError(404))).toBe(true);
  });

  it.each([401, 403, 500, 503])('%i 는 거짓이다 — 없는 게 아니라 다른 고장이다', (status) => {
    expect(isNotFound(responseError(status))).toBe(false);
  });

  it('응답이 아예 없으면(연결 실패·타임아웃) 거짓이다 — 서버에 닿지 못한 것이다', () => {
    expect(isNotFound(new AxiosError('timeout of 15000ms exceeded', 'ECONNABORTED'))).toBe(false);
  });

  it('axios 가 아닌 오류는 거짓이다', () => {
    expect(isNotFound(new Error('그냥 오류'))).toBe(false);
    expect(isNotFound(undefined)).toBe(false);
  });
});

describe('isUnauthorized — 로그인이 풀린 것만 가른다', () => {
  it('401 이면 참이다', () => {
    expect(isUnauthorized(responseError(401))).toBe(true);
  });

  it('404 는 거짓이다 — 둘을 섞으면 빈 화면에서 로그인 화면으로 튕긴다', () => {
    expect(isUnauthorized(responseError(404))).toBe(false);
  });
});
