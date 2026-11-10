import { createContext, useContext, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { getJobResult } from '../services/analysis';
import type { JobResultResponse } from '../services/analysis';
import { getAccessToken } from '../services/authToken';
import {
  POLL_INTERVAL_MS,
  isJobRunning,
  jobResultQueryKey,
  progressLabelOf,
} from './analysis';

/** 우하단 표시가 띄우는 세 가지 모양. 조사가 없으면 아무것도 띄우지 않는다(undefined). */
export type SurveyProgressState = 'running' | 'done' | 'failed';

/** 우하단 진행 표시 한 건. 화면이 그릴 값만 담는다. */
export type SurveyProgress = {
  state: SurveyProgressState;
  jobId: number;
  /** 진행률 0~100 정수. 원형 링과 "33%" 표기에 쓴다. */
  percent: number;
  /** 원 아래 라벨. '작업 중' · '작업 완료' · '작업 실패' */
  label: string;
  /** 눌러 보거나 스크린리더로 읽을 한 줄. 분석 결과 화면의 진행 문구를 그대로 쓴다. */
  description: string;
};

/** 진행률 계산에 필요한 두 칸만 본다. 테스트에서 통째로 만들지 않아도 되게 좁혀 둔다. */
type ProgressCounts = Pick<JobResultResponse, 'targetCount' | 'completedCount'>;

/**
 * 진행률(0~100 정수). `completedCount / targetCount` 다.
 *
 * 내림한다 — 6곳 중 2곳이면 33% 다. 올리면 마지막 한 곳이 남았는데 100% 로 보인다.
 * 조사 대상이 0곳이면 나눌 수 없어 0% 로 둔다(백엔드가 0을 줄 일은 없지만 화면이 NaN 을 그리면 안 된다).
 */
export function surveyProgressPercent(result: ProgressCounts): number {
  const { targetCount, completedCount } = result;
  if (targetCount <= 0) return 0;

  const percent = Math.floor((completedCount / targetCount) * 100);
  return Math.min(100, Math.max(0, percent));
}

/** 끝난 조사를 알릴지 가리는 데 쓰는 세션 상태. */
export type SurveyProgressSeen = {
  /** 이 세션에서 **도는 것을 본** 조사 id. 끝난 조사를 알릴 대상을 가린다. */
  watchedJobId: number | null;
  /** 담당자가 닫은(확인한) 조사 id. */
  dismissedJobId: number | null;
};

/**
 * 조사 결과 응답을 우하단 표시로 옮긴다.
 *
 * **끝난 조사는 이 세션에서 도는 것을 본 조사만 알린다.** `/api/jobs/latest` 는 조사가 끝난
 * 뒤에도 영영 그 조사를 돌려주므로, 그러지 않으면 화면을 새로 열 때마다 지난주 조사의
 * "작업 완료" 가 다시 뜬다. 방금 끝난 일을 알리는 표시이지 조사 이력이 아니다.
 */
export function surveyProgressOf(
  result: JobResultResponse | undefined,
  { watchedJobId, dismissedJobId }: SurveyProgressSeen,
): SurveyProgress | undefined {
  if (!result) return undefined;
  if (result.jobId === dismissedJobId) return undefined;

  const percent = surveyProgressPercent(result);

  if (isJobRunning(result.status)) {
    return {
      state: 'running',
      jobId: result.jobId,
      percent,
      label: '작업 중',
      /* 분석 결과 화면이 쓰는 문구와 같다 — 같은 조사를 두 군데서 다르게 설명하지 않는다. */
      description: progressLabelOf(result) ?? '조사 중',
    };
  }

  if (result.jobId !== watchedJobId) return undefined;

  if (result.status === 'FAILED') {
    return {
      state: 'failed',
      jobId: result.jobId,
      percent,
      label: '작업 실패',
      description: progressLabelOf(result) ?? '조사가 멈췄습니다.',
    };
  }

  return {
    state: 'done',
    jobId: result.jobId,
    /*
      끝났으면 링을 꽉 채운다. completedCount 가 targetCount 보다 적게 와도(백엔드가 중간에
      집계를 멈춘 경우) "작업 완료" 옆에 98% 가 붙으면 담당자는 아직 도는 줄 안다.
    */
    percent: 100,
    label: '작업 완료',
    description: `조사를 마쳤습니다 · ${result.targetCount}곳`,
  };
}

export type UseSurveyProgressResult = {
  /** 띄울 표시. 없으면 undefined — 이때는 기존 로봇 버튼이 그 자리를 쓴다. */
  progress: SurveyProgress | undefined;
  /** 끝난 조사 알림 닫기(X). */
  dismiss: () => void;
  /** 표시를 눌렀을 때. 그 조사의 결과 화면으로 간다. */
  openResult: () => void;
};

/**
 * 우하단 진행 표시가 쓰는 조사 상태. 앱 전체에 하나만 둔다(SurveyProgressProvider).
 *
 * 조사는 한 번에 하나뿐이라 가장 최근 조사(`/api/jobs/latest`)만 보면 된다.
 * 쿼리 키·폴링 간격은 분석 결과 화면과 같은 값을 쓰므로, 그 화면을 보고 있는 동안에는
 * 요청이 한 번만 나간다.
 */
export function useSurveyProgress(): UseSurveyProgressResult {
  const navigate = useNavigate();
  const [seen, setSeen] = useState<SurveyProgressSeen>({
    watchedJobId: null,
    dismissedJobId: null,
  });

  const { data } = useQuery({
    queryKey: jobResultQueryKey(),
    queryFn: () => getJobResult(),
    /*
      토큰이 없으면 부르지 않는다. 로그인 화면에서 부르면 401 이 나고 응답 인터셉터가
      로그인 화면으로 다시 보낸다(hooks/user.ts 와 같은 이유).
    */
    enabled: Boolean(getAccessToken()),
    /* 조사한 적이 없으면 404 다. 다시 물어볼 이유가 없다. */
    retry: false,
    refetchInterval: (query) =>
      isJobRunning(query.state.data?.status) ? POLL_INTERVAL_MS : false,
  });

  const jobId = data?.jobId;

  /*
    도는 것을 본 조사를 적어 둔다. 이 값이 있어야 끝난 뒤 "작업 완료" 를 띄운다.
    새 조사를 보면 닫아 둔 것도 함께 푼다 — 새 조사는 아직 아무도 확인하지 않았다.

    렌더 중에 state 를 맞춘다. React 가 권하는 "값이 바뀔 때 state 조정" 방식이고
    (조건이 맞을 때만 부르므로 바로 다시 렌더하고 끝난다), useEffect 로 하면 폴링 응답마다
    렌더가 한 번씩 더 돈다.
  */
  if (
    jobId !== undefined &&
    isJobRunning(data?.status) &&
    seen.watchedJobId !== jobId
  ) {
    setSeen({ watchedJobId: jobId, dismissedJobId: null });
  }

  const progress = surveyProgressOf(data, seen);

  const dismissJob = (dismissedJobId: number) =>
    setSeen((previous) => ({ ...previous, dismissedJobId }));

  return {
    progress,
    dismiss: () => {
      if (progress) dismissJob(progress.jobId);
    },
    openResult: () => {
      if (!progress) return;
      /*
        끝난 조사는 눌러서 결과를 봤으면 알림도 닫는다 — 확인한 알림이 결과 화면 위에
        계속 떠 있을 이유가 없다. 도는 중인 조사는 남겨 둔다(진행률을 계속 봐야 한다).
      */
      if (progress.state !== 'running') dismissJob(progress.jobId);
      navigate(`/analysis/${progress.jobId}`);
    },
  };
}

/**
 * 표시를 두 군데서 봐야 한다 — 우하단 표시 자신과, 그 자리를 비켜 줘야 하는 로봇 버튼.
 * 훅을 두 번 부르면 "닫았다 · 도는 것을 봤다" 가 둘로 갈려 한쪽만 닫힌다.
 *
 * 값을 담는 Provider 는 components/SurveyProgressFloat 에 있다.
 */
export const SurveyProgressContext = createContext<UseSurveyProgressResult>({
  progress: undefined,
  dismiss: () => {},
  openResult: () => {},
});

/** Provider 밖에서 부르면 "조사 없음" 으로 동작한다 — 화면이 깨지지 않는다. */
export function useSurveyProgressContext(): UseSurveyProgressResult {
  return useContext(SurveyProgressContext);
}
