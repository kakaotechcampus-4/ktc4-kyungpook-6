import { describe, expect, it } from 'vitest';
import {
  progressLabelOf,
  toAnalysisTask,
  toCallStatusLabel,
  toStorePhoneLabel,
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
  storePhone: '053-123-4567',
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

/** 세부를 "라벨: 값" 줄로 펴서 본다. 어느 줄에 무엇이 들었는지만 확인하면 된다. */
const lines = (result: ReturnType<typeof toCallStatusLabel>) =>
  (result?.details ?? []).map(
    (detail) => `${detail.label}: ${detail.value}${detail.tag ? ` (${detail.tag})` : ''}`,
  );

/**
 * 확인 전화 상태.
 *
 * 요약 한마디(label)는 늘 보이고, 요청 시각·들은 답·사유 코드는 세부(details)로 펼친다.
 * 끝난 전화는 들은 답을 세부에 담는다 — 답이 없으면 없다는 것도 적는다.
 */
describe('toCallStatusLabel — 확인 전화 상태', () => {
  it('아직 걸지 않았으면 상태가 없다', () => {
    expect(toCallStatusLabel(null)).toBeUndefined();
  });

  it.each(['PENDING', 'IN_PROGRESS'] as const)(
    '%s 는 "확인 중" 으로 적고 요청 시각을 세부에 둔다',
    (status) => {
      const result = toCallStatusLabel(call({ status }));

      expect(result?.tone).toBe('progress');
      expect(result?.label).toBe('전화 확인 중');
      expect(lines(result).join('\n')).toContain('11:03');
    },
  );

  it('영업 여부 답을 영업중/폐업이 아니라 영업 중·영업 안 함으로 적는다', () => {
    // 전화로는 "지금도 영업하십니까" 한 가지만 물어서 답이 true/false 로 온다.
    // 휴업인지 폐업인지는 전화로 가를 수 없고 담당자가 정한다
    const result = toCallStatusLabel(
      call({
        answers: [{ field: 'status', naturalField: '영업 여부', value: 'false', applied: false }],
      }),
    );

    expect(result?.tone).toBe('done');
    expect(result?.label).toBe('전화 확인 완료');
    expect(lines(result)).toContain('영업 여부: 영업 안 함');
  });

  it('여러 답을 한 줄씩 담고, 가게에 반영된 답에는 반영됨 을 붙인다', () => {
    const result = toCallStatusLabel(
      call({
        answers: [
          { field: 'status', naturalField: '영업 여부', value: 'true', applied: false },
          { field: 'name', naturalField: '상호명', value: '신나는분식', applied: true },
        ],
      }),
    );

    expect(lines(result)).toEqual(
      expect.arrayContaining(['영업 여부: 영업 중', '상호명: 신나는분식 (반영됨)']),
    );
  });

  it('들은 답이 없으면 없다고 적는다 — 빈 세부는 아직 안 열어 본 것과 구분되지 않는다', () => {
    expect(lines(toCallStatusLabel(call({ answers: [] })))).toContain('들은 답: 없음');
  });

  it('실패 사유 코드는 번역하지 않고 그대로 보여준다 — 삼키면 원인이 사라진다', () => {
    const result = toCallStatusLabel(
      call({ status: 'FAILED', failureReason: 'NO_ANSWER_OR_REJECTED', failureKind: 'RETRY_LATER' }),
    );

    expect(result?.tone).toBe('failed');
    expect(result?.label).toBe('전화 확인 실패');
    expect(lines(result)).toContain('사유 코드: NO_ANSWER_OR_REJECTED');
  });

  it.each([
    ['RETRY_LATER', '이따 다시'],
    ['FIX_NUMBER', '번호를 고쳐'],
    ['NOT_BY_PHONE', '다른 방법'],
  ] as const)('%s 는 담당자가 할 일을 같이 적는다', (failureKind, expected) => {
    const result = toCallStatusLabel(
      call({ status: 'FAILED', failureReason: 'X', failureKind }),
    );

    expect(lines(result).join('\n')).toContain(expected);
  });

  it('우리 쪽 문제는 "전화를 걸지 못했습니다" 로 가른다 — 가게 탓으로 읽히면 멀쩡한 가게가 넘어간다', () => {
    const result = toCallStatusLabel(
      call({ status: 'FAILED', failureReason: 'PROCESSING_ERROR', failureKind: 'OUR_SIDE' }),
    );

    expect(result?.label).toBe('전화를 걸지 못했습니다');
    expect(lines(result).join('\n')).toContain('가게 문제가 아닙니다');
  });

  it('모르는 코드는 받은 코드만 보여주고 엉뚱한 안내를 붙이지 않는다', () => {
    const result = toCallStatusLabel(
      call({ status: 'FAILED', failureReason: 'WAT', failureKind: 'UNKNOWN' }),
    );

    expect(result?.label).toBe('전화 확인 실패');
    expect(lines(result).filter((line) => !line.startsWith('요청 시각'))).toEqual([
      '사유 코드: WAT',
    ]);
  });

  it('통화가 중간에 끊기면 영업 여부는 적지 않고, 다른 항목의 들은 답은 남긴다', () => {
    // 전화 API 명세: "진행 중이거나 실패한 통화의 부분 결과도 포함될 수 있습니다"
    const result = toCallStatusLabel(
      call({
        status: 'FAILED',
        failureReason: 'HANGUP',
        failureKind: 'RETRY_LATER',
        answers: [
          { field: 'status', naturalField: '영업 여부', value: 'false', applied: false },
          { field: 'name', naturalField: '상호명', value: '신나는 자장면', applied: false },
        ],
      }),
    );

    expect(lines(result).some((line) => line.startsWith('영업 여부'))).toBe(false);
    expect(lines(result)).toContain('상호명: 신나는 자장면');
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
          call: call({ status: 'FAILED', failureReason: 'NO_ANSWER_OR_REJECTED' }),
        }),
      ).canCall,
    ).toBe(true);
  });

  /*
    번호 모양은 백엔드(CallService.CALLABLE_PHONE)와 같은 규칙으로 본다. 화면이 보지 않으면
    걸 수 없는 번호에도 버튼이 떠서, 눌러 본 담당자가 "전화로 확인할 대상이 아닙니다" 만 받는다 —
    그 문구로는 번호가 문제인지 카드가 문제인지 알 수 없다.
  */
  it.each([
    ['053-123-4567', true],
    ['0531234567', true],
    ['053 123 4567', true],
    ['(053) 123-4567', true],
    ['+82 53 123 4567', true],
    ['1234567', true],
    ['123456', false],
    ['1234567890123456', false],
    ['053-123-456A', false],
    ['', false],
  ] as const)('번호가 %s 면 %s', (storePhone, expected) => {
    expect(
      toAnalysisTask(task({ classification: 'PRIORITY_CHECK', storePhone })).canCall,
    ).toBe(expected);
  });

  it('번호가 없으면 걸지 않는다 — 버튼을 그리지 않아야 눌러 보고 409 를 받지 않는다', () => {
    expect(
      toAnalysisTask(task({ classification: 'PRIORITY_CHECK', storePhone: null })).canCall,
    ).toBe(false);
  });
});

describe('toStorePhoneLabel — 카드 주소 아래 전화번호', () => {
  it('걸 수 있는 번호는 그대로 보여 준다', () => {
    expect(toStorePhoneLabel('053-123-4567')).toEqual({ value: '053-123-4567' });
  });

  it.each([null, undefined, '', '   '])('번호가 %j 면 "전화번호 없음" 이다', (phone) => {
    expect(toStorePhoneLabel(phone)).toEqual({ value: '전화번호 없음', issue: 'missing' });
  });

  it('걸 수 없는 모양이면 번호를 그대로 두고 그 사실을 붙인다', () => {
    expect(toStorePhoneLabel('123456')).toEqual({ value: '123456', issue: 'invalid' });
  });

  it('카드 값에도 실린다', () => {
    expect(toAnalysisTask(task({ storePhone: null })).phone.issue).toBe('missing');
  });
});

describe('toCallStatusLabel — 전화 버튼 단계', () => {
  it.each([
    ['PENDING', 'waiting'],
    ['IN_PROGRESS', 'calling'],
    ['COMPLETED', 'done'],
    ['FAILED', 'failed'],
  ] as const)('%s 면 %s', (status, phase) => {
    expect(toCallStatusLabel(call({ status }))?.phase).toBe(phase);
  });
});

describe('toCallStatusLabel — 통화 내용(전사)', () => {
  const transcript = [
    { speaker: 'AGENT', text: '선한영향력 가게 확인차 연락드렸습니다.' },
    { speaker: 'STORE', text: '네, 말씀하세요.' },
    { speaker: 'STORE', text: '   ' },
  ] as const;

  it.each(['IN_PROGRESS', 'COMPLETED'] as const)('%s 면 빈 줄을 빼고 담는다', (status) => {
    expect(toCallStatusLabel(call({ status, transcript: [...transcript] }))?.transcript).toEqual([
      { speaker: 'agent', text: '선한영향력 가게 확인차 연락드렸습니다.' },
      { speaker: 'store', text: '네, 말씀하세요.' },
    ]);
  });

  it.each(['PENDING', 'FAILED'] as const)('%s 면 담지 않는다', (status) => {
    expect(toCallStatusLabel(call({ status, transcript: [...transcript] }))?.transcript).toEqual([]);
  });

  it('응답에 칸이 없어도 빈 배열이다 — 백엔드에 아직 없는 칸이다', () => {
    expect(toCallStatusLabel(call({ status: 'COMPLETED' }))?.transcript).toEqual([]);
  });
});
