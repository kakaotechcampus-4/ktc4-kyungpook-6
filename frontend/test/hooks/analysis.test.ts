import { describe, expect, it } from 'vitest';
import { progressLabelOf, toAnalysisTask } from '../../src/hooks/analysis';
import type { JobResultResponse, TaskResponse } from '../../src/services/analysis';

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

const JOB: JobResultResponse = {
  jobId: 1,
  status: 'IN_PROGRESS',
  targetCount: 3,
  completedCount: 1,
  createdAt: '2026-10-13T20:55:00',
  finishedAt: null,
  errorMessage: null,
  tasks: [],
};

/**
 * 조사는 가게 한 곳에 최대 60초가 걸린다. 진행 상황이 없으면 "조사 시작하기" 로 넘어온
 * 담당자가 멈춘 화면을 보고 새로고침해야 했다.
 */
describe('progressLabelOf — 진행 상황', () => {
  it('도는 중이면 끝난 곳 수를 알려 준다', () => {
    expect(progressLabelOf(JOB)).toBe('조사 중 · 3곳 중 1곳 끝남');
  });

  it('아직 시작 전(PENDING)도 도는 중으로 본다', () => {
    expect(progressLabelOf({ ...JOB, status: 'PENDING', completedCount: 0 })).toBe(
      '조사 중 · 3곳 중 0곳 끝남',
    );
  });

  it('끝났으면 아무것도 적지 않는다', () => {
    expect(progressLabelOf({ ...JOB, status: 'DONE', completedCount: 3 })).toBeUndefined();
  });

  it('멈춘 조사는 이유를 보여준다 — 결과가 적은 게 정상인지 아닌지 알아야 한다', () => {
    expect(
      progressLabelOf({ ...JOB, status: 'FAILED', errorMessage: 'AI 사용량 한도를 넘었습니다' }),
    ).toBe('조사가 멈췄습니다 — AI 사용량 한도를 넘었습니다');
  });

  it('조사를 못 받아 왔으면 아무것도 적지 않는다', () => {
    expect(progressLabelOf(undefined)).toBeUndefined();
  });
});
