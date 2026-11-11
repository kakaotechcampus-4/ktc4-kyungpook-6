import { describe, expect, it } from 'vitest';
import {
  surveyProgressOf,
  surveyProgressPercent,
} from '../../src/hooks/surveyProgress';
import type { SurveyProgressSeen } from '../../src/hooks/surveyProgress';
import { isSurveyTriggerVisible } from '../../src/hooks/agentSurvey';
import type { JobResultResponse } from '../../src/services/analysis';

/** 6곳을 조사하는 중인 조사 하나. 각 테스트는 보는 칸만 덮어쓴다. */
const BASE: JobResultResponse = {
  jobId: 7,
  status: 'IN_PROGRESS',
  targetCount: 6,
  completedCount: 2,
  createdAt: '2026-11-10T10:00:00',
  finishedAt: null,
  errorMessage: null,
  tasks: [],
};

const job = (patch: Partial<JobResultResponse>): JobResultResponse => ({
  ...BASE,
  ...patch,
});

/** 7번 조사가 도는 것을 보고, 아직 아무것도 닫지 않은 세션. */
const WATCHING_7: SurveyProgressSeen = {
  watchedJobId: 7,
  dismissedJobId: null,
};

describe('surveyProgressPercent', () => {
  it('끝난 가게 수 / 조사 대상 수를 정수 퍼센트로 준다', () => {
    expect(surveyProgressPercent({ targetCount: 6, completedCount: 0 })).toBe(0);
    expect(surveyProgressPercent({ targetCount: 6, completedCount: 2 })).toBe(
      33,
    );
    expect(surveyProgressPercent({ targetCount: 6, completedCount: 6 })).toBe(
      100,
    );
  });

  it('내림한다 — 마지막 한 곳이 남았는데 100% 로 보이면 안 된다', () => {
    expect(surveyProgressPercent({ targetCount: 3, completedCount: 2 })).toBe(
      66,
    );
    expect(surveyProgressPercent({ targetCount: 100, completedCount: 99 })).toBe(
      99,
    );
  });

  it('조사 대상이 0곳이면 0% 다 — NaN 을 화면에 그리지 않는다', () => {
    expect(surveyProgressPercent({ targetCount: 0, completedCount: 0 })).toBe(0);
  });

  it('값이 어긋나도 0~100 을 벗어나지 않는다', () => {
    expect(surveyProgressPercent({ targetCount: 2, completedCount: 5 })).toBe(
      100,
    );
    expect(surveyProgressPercent({ targetCount: 2, completedCount: -1 })).toBe(
      0,
    );
  });
});

describe('surveyProgressOf — 작업 중', () => {
  it('PENDING·IN_PROGRESS 는 진행률과 함께 "작업 중" 이다', () => {
    for (const status of ['PENDING', 'IN_PROGRESS'] as const) {
      const progress = surveyProgressOf(job({ status }), WATCHING_7);

      expect(progress).toEqual({
        state: 'running',
        jobId: 7,
        percent: 33,
        label: '작업 중',
        description: '조사 중 · 6곳 중 2곳 끝남',
      });
    }
  });

  it('도는 조사는 이 세션에서 본 적이 없어도 띄운다 — 새로고침해도 진행 상황이 보여야 한다', () => {
    const progress = surveyProgressOf(job({}), {
      watchedJobId: null,
      dismissedJobId: null,
    });

    expect(progress?.state).toBe('running');
  });
});

describe('surveyProgressOf — 진행에서 완료·실패로', () => {
  it('DONE 이면 "작업 완료" 로 바뀌고 링을 꽉 채운다', () => {
    const progress = surveyProgressOf(
      job({
        status: 'DONE',
        completedCount: 6,
        finishedAt: '2026-11-10T10:10:00',
      }),
      WATCHING_7,
    );

    expect(progress).toEqual({
      state: 'done',
      jobId: 7,
      percent: 100,
      label: '작업 완료',
      description: '조사를 마쳤습니다 · 6곳',
    });
  });

  it('집계가 덜 찬 채 DONE 이 와도 100% 로 보인다 — 끝났는데 98% 면 아직 도는 줄 안다', () => {
    const progress = surveyProgressOf(
      job({ status: 'DONE', completedCount: 5 }),
      WATCHING_7,
    );

    expect(progress?.percent).toBe(100);
  });

  it('FAILED 면 "작업 실패" 로 바뀌고 멈춘 이유를 함께 준다', () => {
    const progress = surveyProgressOf(
      job({
        status: 'FAILED',
        completedCount: 1,
        errorMessage: 'AI 서버가 응답하지 않습니다',
      }),
      WATCHING_7,
    );

    expect(progress).toEqual({
      state: 'failed',
      jobId: 7,
      percent: 16,
      label: '작업 실패',
      description: '조사가 멈췄습니다 — AI 서버가 응답하지 않습니다',
    });
  });

  it('이유 없이 FAILED 면 이유 없는 문구만 쓴다', () => {
    const progress = surveyProgressOf(
      job({ status: 'FAILED', errorMessage: null }),
      WATCHING_7,
    );

    expect(progress?.description).toBe('조사가 멈췄습니다.');
  });
});

describe('surveyProgressOf — 띄우지 않는 경우', () => {
  it('조사 결과가 없으면 아무것도 띄우지 않는다', () => {
    expect(surveyProgressOf(undefined, WATCHING_7)).toBeUndefined();
  });

  /*
    /api/jobs/latest 는 조사가 끝난 뒤에도 영영 그 조사를 돌려준다. 이 가드가 없으면
    화면을 새로 열 때마다 지난 조사의 "작업 완료" 가 다시 뜬다.
  */
  it('이 세션에서 도는 것을 보지 못한 끝난 조사는 띄우지 않는다', () => {
    const finished = job({ status: 'DONE', completedCount: 6 });

    expect(
      surveyProgressOf(finished, { watchedJobId: null, dismissedJobId: null }),
    ).toBeUndefined();
    expect(
      surveyProgressOf(finished, { watchedJobId: 6, dismissedJobId: null }),
    ).toBeUndefined();
  });

  it('담당자가 닫은 조사는 다시 띄우지 않는다', () => {
    const finished = job({ status: 'DONE', completedCount: 6 });

    expect(
      surveyProgressOf(finished, { watchedJobId: 7, dismissedJobId: 7 }),
    ).toBeUndefined();
  });
});

/*
  우하단 한 자리를 로봇 버튼(조사 시작)과 진행 표시가 나눠 쓴다.
  조사가 없을 때 기존 버튼이 그대로 뜨는지가 이 기능의 하한선이다.
*/
describe('isSurveyTriggerVisible', () => {
  it('조사가 없으면 가게를 고른 그대로 로봇 버튼이 뜬다', () => {
    expect(isSurveyTriggerVisible(3, false)).toBe(true);
  });

  it('가게를 하나도 고르지 않았으면 조사가 없어도 뜨지 않는다', () => {
    expect(isSurveyTriggerVisible(0, false)).toBe(false);
  });

  it('진행 표시가 떠 있으면 자리를 비킨다 — 둘이 겹치면 안 된다', () => {
    expect(isSurveyTriggerVisible(3, true)).toBe(false);
    expect(isSurveyTriggerVisible(0, true)).toBe(false);
  });
});
