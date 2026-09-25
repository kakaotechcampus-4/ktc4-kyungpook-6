import { describe, expect, it } from 'vitest';
import { applySelection } from '../../src/hooks/store';

/** 1페이지에서 가게 2개를 골라 둔 상태. */
const PAGE1_PICKED = new Set(['p1-a', 'p1-b']);
const PAGE2_IDS = ['p2-a', 'p2-b', 'p2-c'];

describe('applySelection', () => {
  it('다른 페이지에서 전체 선택해도 1페이지 선택은 남는다', () => {
    const next = applySelection(PAGE1_PICKED, PAGE2_IDS, true);

    expect([...next].sort()).toEqual(['p1-a', 'p1-b', 'p2-a', 'p2-b', 'p2-c']);
  });

  it('다른 페이지에서 전체 해제해도 1페이지 선택은 남는다', () => {
    const selected = applySelection(PAGE1_PICKED, PAGE2_IDS, true);
    const next = applySelection(selected, PAGE2_IDS, false);

    expect([...next].sort()).toEqual(['p1-a', 'p1-b']);
  });

  it('현재 페이지에서 일부만 골라 둔 것도 전체 해제 때 함께 빠진다', () => {
    const selected = new Set(['p1-a', 'p2-a']);
    const next = applySelection(selected, PAGE2_IDS, false);

    expect([...next]).toEqual(['p1-a']);
  });

  it('이전 집합은 바꾸지 않는다', () => {
    const prev = new Set(PAGE1_PICKED);
    applySelection(prev, PAGE2_IDS, true);

    expect(prev).toEqual(PAGE1_PICKED);
  });
});
