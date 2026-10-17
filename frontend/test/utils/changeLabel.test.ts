import { describe, expect, it } from 'vitest';
import {
  changeValueLabel,
  evidenceSourceLabel,
  fieldLabel,
} from '../../src/utils/changeLabel';

/**
 * 백엔드는 수정안을 `{ field: "status", value: "CLOSED" }` 처럼 코드 값 그대로 내려준다.
 * 화면이 그대로 찍으면 담당자가 "status: CLOSED" 를 읽게 된다.
 */
describe('fieldLabel — 항목 이름', () => {
  it('용어집의 화면 라벨로 옮긴다', () => {
    expect(fieldLabel('name')).toBe('가게명');
    expect(fieldLabel('status')).toBe('영업 상태');
    expect(fieldLabel('phone')).toBe('전화번호');
    expect(fieldLabel('addressRoad')).toBe('주소');
  });

  it('모르는 항목은 받은 값을 그대로 보여준다 — 화면에서 조용히 사라지면 수정안이 없는 줄 안다', () => {
    expect(fieldLabel('ownerPhone')).toBe('ownerPhone');
  });
});

describe('changeValueLabel — 새 값', () => {
  it('영업 상태만 enum 이라 한글로 옮긴다', () => {
    expect(changeValueLabel('status', 'CLOSED')).toBe('폐업');
    expect(changeValueLabel('status', 'SUSPENDED')).toBe('휴업');
  });

  it('나머지 항목은 값을 그대로 쓴다', () => {
    expect(changeValueLabel('name', '신나는 자장면')).toBe('신나는 자장면');
    expect(changeValueLabel('phone', '053-964-0022')).toBe('053-964-0022');
  });

  it('빈 값은 "(비움)" 으로 적는다 — 전화번호는 빈 문자열로 지울 수 있다', () => {
    expect(changeValueLabel('phone', '')).toBe('(비움)');
    expect(changeValueLabel('phone', null)).toBe('(비움)');
  });

  it('모르는 상태 값은 그대로 보여준다', () => {
    expect(changeValueLabel('status', 'ARCHIVED')).toBe('ARCHIVED');
  });
});

describe('evidenceSourceLabel — 근거 출처', () => {
  it('백엔드가 sourceLabel 을 항상 null 로 주므로 화면이 붙인다', () => {
    expect(evidenceSourceLabel('NTS')).toBe('근거 - 국세청 사업자등록 상태');
    expect(evidenceSourceLabel('AI_WEB')).toBe('근거 - AI 웹검색');
  });

  it('출처가 없거나 모르는 값이면 라벨을 만들지 않는다', () => {
    expect(evidenceSourceLabel(null)).toBeUndefined();
    expect(evidenceSourceLabel('SOMETHING_NEW')).toBeUndefined();
  });
});
