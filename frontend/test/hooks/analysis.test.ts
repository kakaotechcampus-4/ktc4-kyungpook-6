import { describe, expect, it } from 'vitest';
import {
  progressLabelOf,
  toAnalysisTask,
  toCallStatusLabel,
} from '../../src/hooks/analysis';
import type {
  CallResponse,
  JobResultResponse,
  TaskResponse,
} from '../../src/services/analysis';

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
  call: null,
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

/** 확인 전화 기본값. 각 테스트는 보는 칸만 덮어쓴다. */
const CALL: CallResponse = {
  callId: 9,
  status: 'COMPLETED',
  reason: 'CHANGE_DETECTED',
  requestedAt: '2026-11-04T11:03:00',
  endedAt: '2026-11-04T11:04:10',
  failureReason: null,
  failureKind: null,
  answers: [],
};

const call = (patch: Partial<CallResponse>): CallResponse => ({ ...CALL, ...patch });

/**
 * 확인 전화 한 줄.
 *
 * 끝난 전화는 들은 답을 그 줄에 같이 적는다 — 답을 따로 열어 봐야 하면 담당자가 전화를 건 카드와
 * 안 건 카드만 구분하게 되고, 정작 점주가 뭐라고 했는지는 읽지 않는다.
 */
describe('toCallStatusLabel — 확인 전화 한 줄', () => {
  it('아직 걸지 않았으면 줄이 없다', () => {
    expect(toCallStatusLabel(null)).toBeUndefined();
  });

  it.each(['PENDING', 'IN_PROGRESS'] as const)(
    '%s 는 요청 시각과 함께 "확인 중" 으로 적는다',
    (status) => {
      const result = toCallStatusLabel(call({ status }));

      expect(result?.tone).toBe('progress');
      expect(result?.label).toContain('전화 확인 중');
      expect(result?.label).toContain('11:03');
    },
  );

  it('영업 여부 답을 영업중/폐업이 아니라 영업 중·영업 안 함으로 적는다', () => {
    // 전화로는 "지금도 영업하십니까" 한 가지만 물어서 답이 true/false 로 온다.
    // 휴업인지 폐업인지는 전화로 가를 수 없고 담당자가 정한다
    const result = toCallStatusLabel(
      call({
        answers: [{ field: 'status', naturalField: '영업 여부', value: 'false' }],
      }),
    );

    expect(result?.tone).toBe('done');
    expect(result?.label).toBe('전화 확인 완료 · 영업 여부: 영업 안 함');
  });

  it('여러 답을 한 줄에 이어 적는다', () => {
    const result = toCallStatusLabel(
      call({
        answers: [
          { field: 'status', naturalField: '영업 여부', value: 'true' },
          { field: 'name', naturalField: '상호명', value: '신나는분식' },
        ],
      }),
    );

    expect(result?.label).toBe(
      '전화 확인 완료 · 영업 여부: 영업 중 · 상호명: 신나는분식',
    );
  });

  it('실패 사유 코드는 번역하지 않고 그대로 보여준다 — 삼키면 원인이 사라진다', () => {
    const result = toCallStatusLabel(
      call({ status: 'FAILED', failureReason: 'NO_ANSWER', failureKind: 'RETRY_LATER' }),
    );

    expect(result?.tone).toBe('failed');
    expect(result?.label).toContain('NO_ANSWER');
  });

  it.each([
    ['RETRY_LATER', '이따 다시'],
    ['FIX_NUMBER', '번호를 고쳐'],
    ['NOT_BY_PHONE', '다른 방법'],
  ] as const)('%s 는 담당자가 할 일을 같이 적는다', (failureKind, expected) => {
    const result = toCallStatusLabel(
      call({ status: 'FAILED', failureReason: 'X', failureKind }),
    );

    expect(result?.label).toContain(expected);
  });

  it('우리 쪽 문제는 "전화를 걸지 못했습니다" 로 가른다 — 가게 탓으로 읽히면 멀쩡한 가게가 넘어간다', () => {
    const result = toCallStatusLabel(
      call({ status: 'FAILED', failureReason: 'PROCESSING_ERROR', failureKind: 'OUR_SIDE' }),
    );

    expect(result?.label).toContain('전화를 걸지 못했습니다');
    expect(result?.label).not.toContain('전화 확인 실패');
    expect(result?.label).toContain('가게 문제가 아닙니다');
  });

  it('모르는 코드는 받은 코드만 보여주고 엉뚱한 안내를 붙이지 않는다', () => {
    const result = toCallStatusLabel(
      call({ status: 'FAILED', failureReason: 'WAT', failureKind: 'UNKNOWN' }),
    );

    expect(result?.label).toBe('전화 확인 실패 · WAT');
  });

  it('통화가 중간에 끊겨도 그때까지 들은 답을 버리지 않는다', () => {
    // 전화 API 명세: "진행 중이거나 실패한 통화의 부분 결과도 포함될 수 있습니다"
    const result = toCallStatusLabel(
      call({
        status: 'FAILED',
        failureReason: 'HANGUP',
        failureKind: 'RETRY_LATER',
        answers: [{ field: 'status', naturalField: '영업 여부', value: 'false' }],
      }),
    );

    expect(result?.label).toContain('들은 것 — 영업 여부: 영업 안 함');
  });
});

/**
 * 전화를 걸 수 있는 카드인가.
 *
 * 전화는 되돌릴 수 없다 — 걸 수 없는 카드에 버튼을 열어 두면 눌러서 가게에 실제로 전화가 가거나,
 * 아무 일도 안 일어나는 먹통 버튼이 된다.
 */
describe('toAnalysisTask — 전화를 걸 수 있는가', () => {
  it.each([
    ['PRIORITY_CHECK', true],
    ['ADDITIONAL_CHECK', true],
    ['NO_CHANGE', false],
  ] as const)('%s 는 %s', (classification, expected) => {
    expect(toAnalysisTask(task({ classification })).canCall).toBe(expected);
  });

  it('조사하지 못한 가게에는 걸지 않는다 — 무엇을 물어야 할지 모르는 상태다', () => {
    expect(
      toAnalysisTask(
        task({ classification: null, failureReason: 'AI 응답 시간이 초과됐습니다' }),
      ).canCall,
    ).toBe(false);
  });

  it('담당자가 이미 확인한 카드에는 걸지 않는다', () => {
    expect(
      toAnalysisTask(
        task({
          classification: 'PRIORITY_CHECK',
          verification: { action: 'CHANGE_STATUS', verifiedAt: '2026-11-04T11:00:00' },
        }),
      ).canCall,
    ).toBe(false);
  });

  it.each(['PENDING', 'IN_PROGRESS', 'COMPLETED'] as const)(
    '걸어 둔 전화가 %s 면 다시 걸지 않는다',
    (status) => {
      expect(
        toAnalysisTask(
          task({ classification: 'PRIORITY_CHECK', call: call({ status }) }),
        ).canCall,
      ).toBe(false);
    },
  );

  it('실패한 전화는 다시 걸 수 있다 — 안 받았거나 끊긴 통화다', () => {
    expect(
      toAnalysisTask(
        task({
          classification: 'PRIORITY_CHECK',
          call: call({ status: 'FAILED', failureReason: 'NO_ANSWER' }),
        }),
      ).canCall,
    ).toBe(true);
  });
});
