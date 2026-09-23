import { AxiosError, AxiosHeaders } from 'axios';
import { describe, expect, it } from 'vitest';
import { buildPatch, toErrorMessage, validate } from '../../src/hooks/storeEdit';
import type { StoreEditFormValues, StoreEditTarget } from '../../src/hooks/storeEdit';

/** 목록에서 연 경우처럼 서버 값이 다 채워진 가게. */
const FILLED_TARGET: StoreEditTarget = {
  storeId: 1,
  name: '맛나 치킨',
  addressRoad: '대구광역시 북구 대학로 80',
  phone: '053-999-0000',
  status: 'OPEN',
  lastCheckedAt: '2026-05-12T09:30:00',
};

/** 조사 결과 화면에서 연 경우. 전화번호·상태·확인일을 모른다. */
const PARTIAL_TARGET: StoreEditTarget = {
  storeId: 1,
  name: '맛나 치킨',
  addressRoad: '대구광역시 북구 대학로 80',
};

/** 모달을 막 열었을 때의 입력 칸 값. 여기서 한 칸씩 바꿔가며 확인한다. */
function openedWith(target: StoreEditTarget): StoreEditFormValues {
  return {
    name: target.name ?? '',
    addressRoad: target.addressRoad ?? '',
    phone: target.phone ?? '',
    status: target.status ?? 'UNKNOWN',
  };
}

function axiosErrorWith(status: number): AxiosError {
  return new AxiosError('failed', undefined, undefined, undefined, {
    status,
    statusText: '',
    data: undefined,
    headers: {},
    config: { headers: new AxiosHeaders() },
  });
}

/**
 * 저장 버튼이 서버로 무엇을 보내는지 정하는 함수들.
 *
 * <p>백엔드 StoreUpdateRequest 는 "담아 보낸 필드만 바꾸고 빠뜨린 필드는 기존 값을 유지"하는
 * 부분 수정이다. 그래서 담지 "않는" 것이 곧 기능이고, 여기가 깨지면 담당자가 저장 한 번에
 * 건드리지도 않은 값을 지우게 된다. StoreControllerTest 의 요청 검증 계약과 짝을 이룬다.
 */
describe('buildPatch — 바뀐 칸만 골라낸다', () => {
  it('아무것도 안 고치면 빈 객체를 낸다 — 보낼 요청 자체가 없다', () => {
    expect(buildPatch(FILLED_TARGET, openedWith(FILLED_TARGET))).toEqual({});
  });

  it('전화번호만 고치면 전화번호만 담는다 — 나머지 세 필드는 키조차 없어야 한다', () => {
    const values = { ...openedWith(FILLED_TARGET), phone: '053-111-2222' };

    expect(buildPatch(FILLED_TARGET, values)).toEqual({
      phone: '053-111-2222',
    });
  });

  it('여러 칸을 고치면 고친 만큼만 담는다', () => {
    const values = {
      ...openedWith(FILLED_TARGET),
      name: '맛나 치킨 2호점',
      status: 'SUSPENDED' as const,
    };

    expect(buildPatch(FILLED_TARGET, values)).toEqual({
      name: '맛나 치킨 2호점',
      status: 'SUSPENDED',
    });
  });

  it('값을 모르는 칸을 빈 칸 그대로 두면 담지 않는다 — 담으면 서버가 "지워라"로 읽는다', () => {
    // 조사 결과 화면에서 연 경우. 전화번호 칸이 비어 있지만 서버에는 값이 있을 수 있다.
    const values = { ...openedWith(PARTIAL_TARGET), name: '맛나 치킨 2호점' };

    const patch = buildPatch(PARTIAL_TARGET, values);

    expect(patch).toEqual({ name: '맛나 치킨 2호점' });
    expect(patch).not.toHaveProperty('phone');
    expect(patch).not.toHaveProperty('status');
  });

  it('운영 상태를 모르는 채로 미확인을 그대로 두면 담지 않는다 — UNKNOWN 으로 덮어쓰면 안 된다', () => {
    expect(buildPatch(PARTIAL_TARGET, openedWith(PARTIAL_TARGET))).toEqual({});
  });

  it('전화번호를 비우면 빈 문자열을 담는다 — 전화번호는 지울 수 있는 값이다', () => {
    const values = { ...openedWith(FILLED_TARGET), phone: '' };

    expect(buildPatch(FILLED_TARGET, values)).toEqual({ phone: '' });
  });

  it('고쳤다가 원래 값으로 되돌리면 담지 않는다', () => {
    const values = { ...openedWith(FILLED_TARGET), name: '맛나 치킨' };

    expect(buildPatch(FILLED_TARGET, values)).toEqual({});
  });
});

/**
 * 보내기 전 검증.
 *
 * <p>경계값은 백엔드 StoreUpdateRequest 의 제약(@Size, @Pattern)과 같은 숫자를 쓴다.
 * StoreControllerTest 가 200자는 통과·201자는 400 으로 고정해 두었으므로 여기도 같아야 한다.
 * 프론트 기준이 더 느슨하면 400 을 받고 나서야 알게 되고, 더 빡빡하면 서버가 받아주는 값을 막는다.
 */
describe('validate — 서버와 같은 기준으로 미리 거른다', () => {
  it('담기지 않은 필드는 검사하지 않는다 — 빈 본문도 서버가 받아준다', () => {
    expect(validate({})).toBeUndefined();
  });

  it('상호명이 공백뿐이면 막는다 — 이름은 지울 수 없는 값이다', () => {
    expect(validate({ name: '   ' })).toBe('상호명은 공백만으로 채울 수 없습니다.');
  });

  it('상호명이 빈 문자열이면 막는다', () => {
    expect(validate({ name: '' })).toBe('상호명은 공백만으로 채울 수 없습니다.');
  });

  it('상호명이 정확히 200자면 통과한다 — 경계 바로 안쪽', () => {
    expect(validate({ name: '가'.repeat(200) })).toBeUndefined();
  });

  it('상호명이 201자면 막는다 — 경계 바로 바깥', () => {
    expect(validate({ name: '가'.repeat(201) })).toBe(
      '상호명은 200자를 넘을 수 없습니다.'
    );
  });

  it('상호명이 한 글자여도 통과한다 — 최소 경계', () => {
    expect(validate({ name: '곰' })).toBeUndefined();
  });

  it('주소가 공백뿐이면 막는다', () => {
    expect(validate({ addressRoad: '  ' })).toBe(
      '주소는 공백만으로 채울 수 없습니다.'
    );
  });

  it('주소가 정확히 500자면 통과한다 — 경계 바로 안쪽', () => {
    expect(validate({ addressRoad: '가'.repeat(500) })).toBeUndefined();
  });

  it('주소가 501자면 막는다 — 경계 바로 바깥', () => {
    expect(validate({ addressRoad: '가'.repeat(501) })).toBe(
      '주소는 500자를 넘을 수 없습니다.'
    );
  });

  it('전화번호는 빈 문자열이어도 통과한다 — 상호명·주소와 다르게 지울 수 있다', () => {
    expect(validate({ phone: '' })).toBeUndefined();
  });

  it('전화번호가 정확히 20자면 통과한다 — 경계 바로 안쪽', () => {
    expect(validate({ phone: '0'.repeat(20) })).toBeUndefined();
  });

  it('전화번호가 21자면 막는다 — 경계 바로 바깥', () => {
    expect(validate({ phone: '0'.repeat(21) })).toBe(
      '전화번호는 20자를 넘을 수 없습니다.'
    );
  });

  it('여러 칸이 동시에 잘못되면 상호명부터 알린다 — 화면에 한 줄만 보여주기 때문', () => {
    expect(validate({ name: '', addressRoad: '' })).toBe(
      '상호명은 공백만으로 채울 수 없습니다.'
    );
  });
});

/**
 * 실패 응답 → 담당자가 읽을 문구.
 *
 * <p>400 응답 본문은 아직 스프링 기본 검증 오류 형태라 규격이 없다. 본문 대신 상태 코드만
 * 보고 고르는 구조인지 여기서 고정한다.
 */
describe('toErrorMessage — 상태 코드별 안내 문구', () => {
  it('501 이면 아직 구현 전이라고 알린다 — 지금 저장하기를 누르면 나오는 문구', () => {
    expect(toErrorMessage(axiosErrorWith(501))).toBe(
      '아직 서버에 저장 기능이 없습니다. 화면 확인용으로만 동작합니다.'
    );
  });

  it('400 이면 입력값을 다시 보라고 알린다', () => {
    expect(toErrorMessage(axiosErrorWith(400))).toBe(
      '입력한 값을 서버가 받지 못했습니다. 내용을 다시 확인해 주세요.'
    );
  });

  it('404 면 가게가 없다고 알린다', () => {
    expect(toErrorMessage(axiosErrorWith(404))).toBe(
      '가게를 찾을 수 없습니다. 목록을 새로 고친 뒤 다시 시도해 주세요.'
    );
  });

  it('500 처럼 정해두지 않은 코드면 일반 문구를 낸다', () => {
    expect(toErrorMessage(axiosErrorWith(500))).toBe(
      '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    );
  });

  it('서버가 안 떠 있어 응답 자체가 없어도 문구를 낸다 — 지금 로컬에서 가장 흔한 경우', () => {
    expect(toErrorMessage(new AxiosError('Network Error'))).toBe(
      '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    );
  });

  it('axios 와 무관한 오류가 와도 문구를 낸다', () => {
    expect(toErrorMessage(new Error('예상 못 한 오류'))).toBe(
      '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    );
  });
});
