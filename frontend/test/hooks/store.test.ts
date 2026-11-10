import { describe, expect, it } from 'vitest';
import { applySelection } from '../../src/hooks/store';
import type { SelectableStore, StoreSelection } from '../../src/hooks/store';
import type { InvestigationExclusionReason } from '../../src/services/store';

/** 조사에서 빠지지 않는 가게 — 선택이 기억할 사유가 없다. */
const target = (id: string): SelectableStore => ({ id, exclusionReason: null });

const excluded = (
  id: string,
  reason: InvestigationExclusionReason,
): SelectableStore => ({ id, exclusionReason: reason });

/** 1페이지에서 가게 2개를 골라 둔 상태. */
const PAGE1_PICKED: StoreSelection = new Map([
  ['p1-a', null],
  ['p1-b', null],
]);
const PAGE2_STORES = [target('p2-a'), target('p2-b'), target('p2-c')];

describe('applySelection', () => {
  it('다른 페이지에서 전체 선택해도 1페이지 선택은 남는다', () => {
    const next = applySelection(PAGE1_PICKED, PAGE2_STORES, true);

    expect([...next.keys()].sort()).toEqual([
      'p1-a',
      'p1-b',
      'p2-a',
      'p2-b',
      'p2-c',
    ]);
  });

  it('다른 페이지에서 전체 해제해도 1페이지 선택은 남는다', () => {
    const selected = applySelection(PAGE1_PICKED, PAGE2_STORES, true);
    const next = applySelection(selected, PAGE2_STORES, false);

    expect([...next.keys()].sort()).toEqual(['p1-a', 'p1-b']);
  });

  it('현재 페이지에서 일부만 골라 둔 것도 전체 해제 때 함께 빠진다', () => {
    const selected: StoreSelection = new Map([
      ['p1-a', null],
      ['p2-a', null],
    ]);
    const next = applySelection(selected, PAGE2_STORES, false);

    expect([...next.keys()]).toEqual(['p1-a']);
  });

  it('이전 선택은 바꾸지 않는다', () => {
    const prev = new Map(PAGE1_PICKED);
    applySelection(prev, PAGE2_STORES, true);

    expect(prev).toEqual(PAGE1_PICKED);
  });

  /*
    조사 대상 수는 페이지와 무관하게 전체 선택 수로 센다. 1페이지에서 고른 가게는 3페이지로 넘어가면
    화면 데이터에 없으므로, 고를 때 제외 사유를 같이 적어 두지 않으면 모달이 "이 중 몇 곳이 빠지는지"를
    셀 수 없다.
  */
  it('고를 때 백엔드가 준 제외 사유를 함께 기억한다', () => {
    const next = applySelection(
      new Map(),
      [excluded('1', 'NO_NTS_CHECK'), target('2')],
      true,
    );

    expect(next.get('1')).toBe('NO_NTS_CHECK');
    expect(next.get('2')).toBeNull();
  });

  it('해제한 가게의 사유는 남기지 않는다', () => {
    const selected = applySelection(
      new Map(),
      [excluded('1', 'ALREADY_CLOSED')],
      true,
    );
    const next = applySelection(selected, [excluded('1', 'ALREADY_CLOSED')], false);

    expect(next.has('1')).toBe(false);
  });
});
