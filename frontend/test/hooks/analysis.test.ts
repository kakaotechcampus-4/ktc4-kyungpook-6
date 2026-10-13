import { describe, expect, it } from 'vitest';
import { toAnalysisTask } from '../../src/hooks/analysis';
import type { TaskResponse } from '../../src/services/analysis';

/** 성공·변화 없음 기본값. 각 테스트는 보는 칸만 덮어쓴다. */
const BASE: TaskResponse = {
  taskId: 1,
  storeId: 44,
  storeName: '예시분식',
  storeAddress: '대구광역시 북구 대학로 80',
  storePhone: null,
  lastCheckedAt: null,
  classification: 'NO_CHANGE',
  failureReason: null,
  evidences: [],
  proposedChanges: [],
  verification: null,
};

const task = (patch: Partial<TaskResponse>): TaskResponse => ({ ...BASE, ...patch });

/**
 * 조사 결과 Task 를 카드 값으로 옮기는 변환.
 *
 * 여기가 어긋나서 화면에 수정안 값이 비어 보이고(= 담당자가 무엇으로 바뀌는지 모름),
 * 조사 실패가 "변화 근거가 없는 가게" 에 섞여 있었다(PROMPT-150).
 */
describe('toAnalysisTask — 수정안', () => {
  it('항목과 새 값을 한글로 옮긴다', () => {
    const result = toAnalysisTask(
      task({
        classification: 'PRIORITY_CHECK',
        proposedChanges: [
          { field: 'status', value: 'CLOSED' },
          { field: 'name', value: '신나는 자장면' },
        ],
      }),
    );

    expect(result.changes).toEqual([
      { label: '영업 상태', description: '폐업' },
      { label: '가게명', description: '신나는 자장면' },
    ]);
  });

  it('수정안이 없으면 hasChanges 가 false 다 — apply 를 부르면 409 가 된다', () => {
    expect(toAnalysisTask(task({ proposedChanges: null })).hasChanges).toBe(false);
    expect(
      toAnalysisTask(task({ proposedChanges: [{ field: 'name', value: 'A' }] }))
        .hasChanges,
    ).toBe(true);
  });
});

describe('toAnalysisTask — 조사 실패', () => {
  it('판정이 없으면 failed 로 간다 — 변화가 없는 것과 섞이면 안 된다', () => {
    const result = toAnalysisTask(
      task({ classification: null, failureReason: 'AI 가 이 가게를 조사하지 못했습니다' }),
    );

    expect(result.classification).toBe('failed');
    expect(result.failureReason).toBe('AI 가 이 가게를 조사하지 못했습니다');
  });

  it('성공한 Task 는 failureReason 이 없다', () => {
    expect(toAnalysisTask(task({})).failureReason).toBeUndefined();
    expect(toAnalysisTask(task({})).classification).toBe('unchanged');
  });
});

describe('toAnalysisTask — 확인 기록', () => {
  it('이미 처리한 카드는 확인 기록 글자를 만든다 — 주 버튼을 내리는 근거다', () => {
    const result = toAnalysisTask(
      task({
        verification: { action: 'UPDATE_INFO', verifiedAt: '2026-10-13T20:12:20' },
      }),
    );

    expect(result.verifiedLabel).toBe('확인 완료 · 정보수정 · 2026. 10. 13. 20:12');
  });

  it('아직 확인하지 않았으면 비워 둔다', () => {
    expect(toAnalysisTask(task({})).verifiedLabel).toBeUndefined();
  });
});

describe('toAnalysisTask — 근거 출처', () => {
  it('백엔드가 sourceLabel 을 null 로 주면 source 로 라벨을 붙인다', () => {
    const result = toAnalysisTask(
      task({
        evidences: [
          {
            field: 'status',
            source: 'NTS',
            description: '국세청에서 폐업으로 확인됨',
            sourceLabel: null,
            sourceUrl: null,
          },
        ],
      }),
    );

    expect(result.evidences[0].sourceLabel).toBe('근거 - 국세청 사업자등록 상태');
  });
});
