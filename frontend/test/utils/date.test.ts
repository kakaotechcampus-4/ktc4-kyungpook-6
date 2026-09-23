import { describe, expect, it } from 'vitest';
import { formatDate, formatDateTime } from '../../src/utils/date';

/**
 * 날짜 표기 변환.
 *
 * 서버는 "2026-05-12T09:30:00" 같은 ISO 문자열을 주고 화면은 Figma 표기를 그린다.
 * 값이 없거나 깨진 경우 화면이 "Invalid Date"를 그리지 않는 것까지 여기서 고정한다.
 */
describe('formatDate — "2026. 05. 12." 표기 (Figma 103:147)', () => {
  it('ISO 문자열을 ko-KR 날짜 표기로 바꾼다', () => {
    expect(formatDate('2026-05-12T09:30:00')).toBe('2026. 05. 12.');
  });

  it('한 자리 월·일도 0을 채워 두 자리로 낸다 — 자릿수가 흔들리면 표가 들쭉날쭉해진다', () => {
    expect(formatDate('2026-01-02T00:00:00')).toBe('2026. 01. 02.');
  });

  it('시각만 있는 차이는 표기에 영향을 주지 않는다', () => {
    expect(formatDate('2026-05-12T23:59:59')).toBe('2026. 05. 12.');
  });

  it('null 이면 undefined 를 낸다 — "-" 같은 대체 문구는 화면이 정한다', () => {
    expect(formatDate(null)).toBeUndefined();
  });

  it('undefined 면 undefined 를 낸다', () => {
    expect(formatDate(undefined)).toBeUndefined();
  });

  it('빈 문자열이면 undefined 를 낸다', () => {
    expect(formatDate('')).toBeUndefined();
  });

  it('날짜로 읽을 수 없는 값이면 undefined 를 낸다 — "Invalid Date"가 화면에 나가면 안 된다', () => {
    expect(formatDate('이상한값')).toBeUndefined();
  });
});

describe('formatDateTime — "2026. 08. 20. 18:00" 표기 (Figma 112:5450)', () => {
  it('날짜 뒤에 시:분을 붙인다', () => {
    expect(formatDateTime('2026-08-20T18:00:00')).toBe('2026. 08. 20. 18:00');
  });

  it('오후 시각을 24시간제로 낸다 — 12시간제로 나오면 "오후 6:00"이 되어 Figma와 어긋난다', () => {
    expect(formatDateTime('2026-08-20T13:05:00')).toBe('2026. 08. 20. 13:05');
  });

  it('자정을 24:00 이 아니라 00:00 으로 낸다 — ko-KR 기본값이 24시로 나오던 자리다', () => {
    expect(formatDateTime('2026-08-20T00:00:00')).toBe('2026. 08. 20. 00:00');
  });

  it('값이 없거나 깨졌으면 undefined 를 낸다', () => {
    expect(formatDateTime(null)).toBeUndefined();
    expect(formatDateTime(undefined)).toBeUndefined();
    expect(formatDateTime('이상한값')).toBeUndefined();
  });
});
