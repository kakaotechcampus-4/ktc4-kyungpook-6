import { describe, expect, it } from 'vitest';
import {
  exclusionNotices,
  exclusionReasonLabel,
  exclusionReasonTitle,
} from '../../src/utils/exclusionLabel';

/**
 * 조사 제외 사유의 화면 문구.
 *
 * 담당자가 가게 60곳을 골라 조사를 시작했는데 화면에 "56곳"이 떴다. 차이 4곳은 국세청 대조 기록이
 * 없어 빠진 가게였는데, 화면이 미리도 나중에도 그걸 설명하지 않았다. 사유는 백엔드가 코드 값으로
 * 내려주고 문구는 이 파일 하나가 정하므로, 그 문구가 어긋나면 여기가 먼저 깨지게 고정한다.
 */
describe('exclusionReasonLabel', () => {
  it('사유마다 담당자가 읽을 문구가 있다', () => {
    expect(exclusionReasonLabel('NO_NTS_CHECK')).toBe('국세청 대조 기록 없음');
    expect(exclusionReasonLabel('DATA_PROBLEM')).toBe(
      '사업자등록번호 없음·오류',
    );
    expect(exclusionReasonLabel('ALREADY_CLOSED')).toBe('폐업 확인됨');
    expect(exclusionReasonLabel('ALREADY_SUSPENDED')).toBe('휴업 확인됨');
    expect(exclusionReasonLabel('STORE_NOT_FOUND')).toBe('없는 가게');
  });

  it('빠지지 않는 가게는 문구가 없다', () => {
    expect(exclusionReasonLabel(null)).toBeUndefined();
    expect(exclusionReasonLabel(undefined)).toBeUndefined();
  });

  /* 백엔드에 사유가 늘었을 때 화면에서 조용히 사라지면, 담당자는 그 가게가 조사된 줄 알고 넘어간다. */
  it('모르는 사유는 버리지 않고 받은 값을 그대로 보여준다', () => {
    expect(exclusionReasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('exclusionReasonTitle', () => {
  it('왜 빠지는지까지 한 줄로 적는다', () => {
    expect(exclusionReasonTitle('NO_NTS_CHECK')).toBe(
      '국세청 대조 기록 없음 — 지금 조사를 시작하면 이 가게는 조사되지 않습니다',
    );
  });

  it('빠지지 않는 가게에는 붙이지 않는다', () => {
    expect(exclusionReasonTitle(null)).toBeUndefined();
  });
});

describe('exclusionNotices', () => {
  it('같은 사유로 빠지는 가게를 세어 한 줄로 알린다', () => {
    const reasons = [
      ...Array<string | null>(56).fill(null),
      ...Array<string>(4).fill('NO_NTS_CHECK'),
    ];

    expect(exclusionNotices(reasons)).toEqual([
      '이 중 4곳은 국세청 대조 기록이 없어 조사되지 않습니다',
    ]);
  });

  /* 빠지는 가게가 0곳이면 알려 줄 것이 없다 — 모달은 이 값이 비면 줄을 아예 그리지 않는다. */
  it('빠지는 가게가 없으면 아무 줄도 만들지 않는다', () => {
    expect(exclusionNotices([null, null, null])).toEqual([]);
    expect(exclusionNotices([])).toEqual([]);
  });

  it('사유가 여러 가지면 많은 사유부터 한 줄씩 만든다', () => {
    const notices = exclusionNotices([
      'ALREADY_CLOSED',
      'NO_NTS_CHECK',
      'NO_NTS_CHECK',
      null,
    ]);

    expect(notices).toEqual([
      '이 중 2곳은 국세청 대조 기록이 없어 조사되지 않습니다',
      '이 중 1곳은 우리와 국세청 모두 폐업으로 확인돼 조사되지 않습니다',
    ]);
  });

  /* 선택 순서에 따라 줄 순서가 바뀌면 같은 선택을 다시 열었을 때 화면이 달라 보인다. */
  it('수가 같으면 고른 순서와 무관하게 같은 순서로 만든다', () => {
    const forward = exclusionNotices(['NO_NTS_CHECK', 'ALREADY_CLOSED']);
    const backward = exclusionNotices(['ALREADY_CLOSED', 'NO_NTS_CHECK']);

    expect(forward).toEqual(backward);
  });

  it('모르는 사유도 줄을 만든다 — 조용히 빼지 않는다', () => {
    expect(exclusionNotices(['SOMETHING_NEW'])).toEqual([
      '이 중 1곳은 SOMETHING_NEW 이라 조사되지 않습니다',
    ]);
  });
});
