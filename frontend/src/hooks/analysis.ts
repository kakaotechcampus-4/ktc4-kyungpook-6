import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { applyTask, confirmTask, getJobResult } from '../services/analysis';
import type {
  JobResultResponse,
  TaskClassificationResponse,
  TaskResponse,
  VerificationResponse,
} from '../services/analysis';
import type {
  SurveyChange,
  SurveyEvidence,
  TaskClassification,
} from '../components/AgentSurveyResultCard';
import { formatDateTime } from '../utils/date';
import {
  changeValueLabel,
  evidenceSourceLabel,
  fieldLabel,
} from '../utils/changeLabel';
import { messageOf } from '../utils/apiError';

/** 백엔드 enum을 카드가 쓰는 소문자 값으로 옮긴다. */
const CLASSIFICATION_MAP: Record<TaskClassificationResponse, TaskClassification> =
  {
    PRIORITY_CHECK: 'priority',
    ADDITIONAL_CHECK: 'additional',
    NO_CHANGE: 'unchanged',
  };

/** 확인 조치 → 화면 라벨. docs/도메인_용어집.md 의 "화면 라벨" 칸 그대로다. */
const ACTION_LABEL: Record<VerificationResponse['action'], string> = {
  UPDATE_INFO: '정보수정',
  CHANGE_STATUS: '상태변경',
  NO_ACTION: '조치없음',
};

/** 분석 결과 화면이 카드 하나에 넘기는 값. */
export type AnalysisTask = {
  id: string;
  /** 수정 API를 부를 때 쓰는 Task 식별자. */
  taskId: number;
  /** 수정 API를 부를 때 쓰는 가게 식별자. */
  storeId: number;
  storeName: string;
  /** 카드에 찍는 주소. 값이 없으면 "-"다. */
  address: string;
  /**
   * 수정 모달에 넘길 주소 원본. 값이 없으면 null 그대로 둔다.
   * 표기용 "-"를 그대로 넘기면 담당자가 저장할 때 주소가 "-"로 덮인다.
   */
  addressRoad: string | null;
  classification: TaskClassification;
  evidences: SurveyEvidence[];
  changes: SurveyChange[];
  /** 조사 실패 이유. 성공이면 undefined. */
  failureReason?: string;
  /** 담당자가 이미 처리했으면 그 기록 글자. 아직이면 undefined. */
  verifiedLabel?: string;
  /** 반영할 수정안이 있는가. 없으면 "즉시 수정 반영하기"가 409가 된다. */
  hasChanges: boolean;
};

/** 분류별로 나눈 Task 목록. 화면의 네 섹션과 1:1로 대응한다. */
export type AnalysisTaskGroups = Record<TaskClassification, AnalysisTask[]>;

/** 확인 기록을 카드에 찍을 한 줄로 만든다. */
function toVerifiedLabel(
  verification: VerificationResponse | null,
): string | undefined {
  if (!verification) return undefined;

  const at = formatDateTime(verification.verifiedAt);
  const action = ACTION_LABEL[verification.action] ?? verification.action;
  return at ? `확인 완료 · ${action} · ${at}` : `확인 완료 · ${action}`;
}

/** 백엔드 Task 하나를 카드가 쓰는 값으로 옮긴다. 테스트에서 직접 부른다(applySelection 과 같은 방식). */
export function toAnalysisTask(task: TaskResponse): AnalysisTask {
  const changes = task.proposedChanges ?? [];

  return {
    id: String(task.taskId),
    taskId: task.taskId,
    storeId: task.storeId,
    storeName: task.storeName,
    address: task.storeAddress ?? '-',
    addressRoad: task.storeAddress,
    /*
      판정이 없으면 조사 실패다. 예전에는 이 경우가 'unchanged'로 떨어져
      "변화 근거가 없는 가게"에 섞였다 — 조사를 못 한 가게와 변화가 없는 가게는
      담당자가 할 일이 서로 다르다(PROMPT-150).
    */
    classification: task.classification
      ? CLASSIFICATION_MAP[task.classification] ?? 'unchanged'
      : 'failed',
    evidences:
      task.evidences?.map((evidence) => ({
        description: evidence.description,
        /* 백엔드는 sourceLabel 을 항상 null 로 주고, 라벨은 화면이 붙이기로 했다. */
        sourceLabel:
          evidence.sourceLabel ??
          evidenceSourceLabel(evidence.source) ??
          undefined,
        sourceUrl: evidence.sourceUrl ?? undefined,
      })) ?? [],
    changes: changes.map((change) => ({
      label: fieldLabel(change.field),
      description: changeValueLabel(change.field, change.value),
    })),
    failureReason: task.failureReason ?? undefined,
    verifiedLabel: toVerifiedLabel(task.verification),
    hasChanges: changes.length > 0,
  };
}

function groupByClassification(tasks: AnalysisTask[]): AnalysisTaskGroups {
  const groups: AnalysisTaskGroups = {
    priority: [],
    additional: [],
    unchanged: [],
    failed: [],
  };

  for (const task of tasks) {
    groups[task.classification].push(task);
  }

  return groups;
}

/**
 * API 조회 실패 시 표시할 목업 결과. MockupDataPage의 FALLBACK_STORES와 같은 역할이다.
 * Figma 112:4993의 예시 데이터를 그대로 옮겼다.
 *
 * 로그인이 풀린 경우(401)는 여기로 오지 않는다 — services/api.ts가 로그인 화면으로 보낸다.
 * 백엔드가 죽었을 때 가짜 결과를 보여주는 게 맞는지는 PROMPT-62(에러 상황 UI)에서 정한다.
 */
const FALLBACK_RESULT: JobResultResponse = {
  jobId: 0,
  status: 'DONE',
  targetCount: 4,
  completedCount: 4,
  createdAt: '2026-08-20T17:00:00',
  finishedAt: '2026-08-20T18:00:00',
  errorMessage: null,
  tasks: [
    {
      taskId: 1,
      storeId: 1,
      storeName: '맛나 치킨',
      storeAddress: '대구광역시 북구 대학로 80',
      storePhone: null,
      lastCheckedAt: null,
      classification: 'PRIORITY_CHECK',
      failureReason: null,
      verification: null,
      evidences: [
        {
          field: 'status',
          source: 'NTS',
          description: '국세청 사업자등록 상태에서 운영 상태가 휴업으로 확인됨',
          sourceLabel: null,
          sourceUrl: null,
        },
        {
          field: 'addressRoad',
          source: 'AI_WEB',
          description: '공개 지도 정보에서 주소가 DB와 다르게 확인됨',
          sourceLabel: null,
          sourceUrl: 'https://map.kakao.com',
        },
      ],
      proposedChanges: [
        { field: 'status', value: 'SUSPENDED' },
        { field: 'addressRoad', value: '대구광역시 북구 대학로 79' },
      ],
    },
    {
      taskId: 2,
      storeId: 2,
      storeName: '짜장면',
      storeAddress: '인천광역시 부평구 부평동 32',
      storePhone: null,
      lastCheckedAt: null,
      classification: 'PRIORITY_CHECK',
      failureReason: null,
      verification: null,
      evidences: [
        {
          field: 'name',
          source: 'AI_WEB',
          description: "검색 결과에서 상호명이 '신나는 자장면'으로 변경됨이 확인됨",
          sourceLabel: null,
          sourceUrl: 'https://blog.naver.com',
        },
      ],
      proposedChanges: [{ field: 'name', value: '신나는 자장면' }],
    },
    {
      taskId: 3,
      storeId: 3,
      storeName: '라멘',
      storeAddress: '부산광역시 사하구 하단동 89',
      storePhone: null,
      lastCheckedAt: null,
      classification: 'ADDITIONAL_CHECK',
      failureReason: null,
      verification: null,
      evidences: [
        {
          field: null,
          source: null,
          description: '마지막 자체 확인 후 90일이 지남',
          sourceLabel: '자체 확인일: 120일 전',
          sourceUrl: null,
        },
      ],
      proposedChanges: [],
    },
    {
      taskId: 4,
      storeId: 4,
      storeName: '정든 국밥집',
      storeAddress: '대구광역시 북구 대학로 82',
      storePhone: null,
      lastCheckedAt: null,
      classification: 'NO_CHANGE',
      failureReason: null,
      verification: null,
      evidences: [
        {
          field: null,
          source: 'AI_WEB',
          description: '공개 지도 정보와 DB가 일치함',
          sourceLabel: null,
          sourceUrl: 'https://map.naver.com',
        },
      ],
      proposedChanges: null,
    },
  ],
};

export type UseAnalysisResultPageResult = {
  /** 브레드크럼에 표시할 조사 완료 시각. 없으면 undefined. */
  finishedAtLabel: string | undefined;
  /** 분류별 Task 목록. 화면의 네 섹션이 그대로 쓴다. */
  groups: AnalysisTaskGroups;
  /** 조사 결과 조회 중 여부. Skeleton 노출 여부에 쓰인다. */
  isPending: boolean;
  /** 조회 실패 여부. 목업 결과로 대체해 보여준다. */
  isError: boolean;
  /** 수정안 반영 중인 Task id. 버튼을 잠그는 데 쓴다. */
  processingTaskId: number | null;
  /** 반영·확인에 실패했을 때 담당자에게 보일 문구. */
  actionErrorMessage: string | undefined;
  /** "즉시 수정 반영하기". 반영 후 목록을 다시 불러온다. */
  applyChanges: (taskId: number) => void;
};

/**
 * 분석 결과 페이지(AnalysisResultPage)가 쓰는 조사 결과를 모은다.
 *
 * 페이지는 이 훅 하나만 부르고 props로는 className만 받는다.
 * 어느 가게 목록을 보는지는 URL(:jobId)이 정하므로 훅이 직접 useParams로 읽는다.
 * 사이드바 내비·로그인 사용자는 이 화면에만 걸린 관심사가 아니라
 * 레이아웃 전역 값이라 Sidebar가 직접 부른다.
 */
export function useAnalysisResultPage(): UseAnalysisResultPageResult {
  const { jobId } = useParams<{ jobId?: string }>();
  const queryClient = useQueryClient();
  const queryKey = ['jobResult', jobId ?? 'latest'];
  const [actionErrorMessage, setActionErrorMessage] = useState<
    string | undefined
  >(undefined);

  const { data, isPending, isError } = useQuery({
    queryKey,
    queryFn: () => getJobResult(jobId),
    retry: false,
  });

  /*
    반영·확인은 같은 처리라 한 mutation 으로 묶는다. 수정안이 있으면 apply, 없으면 confirm 이다
    (수정안이 없는데 apply 를 부르면 백엔드가 409 nothing-to-apply 로 거절한다).
    끝나면 목록을 다시 불러온다 — 확인 기록이 생겨 카드 모양이 바뀌기 때문이다.
  */
  const mutation = useMutation({
    mutationFn: ({ taskId, hasChanges }: { taskId: number; hasChanges: boolean }) =>
      hasChanges ? applyTask(taskId) : confirmTask(taskId),
    onMutate: () => setActionErrorMessage(undefined),
    onSuccess: () => queryClient.invalidateQueries({ queryKey }),
    onError: (error) => setActionErrorMessage(messageOf(error)),
  });

  const result = isError ? FALLBACK_RESULT : data;
  const groups = groupByClassification(result?.tasks.map(toAnalysisTask) ?? []);

  const applyChanges = (taskId: number) => {
    const task = Object.values(groups)
      .flat()
      .find((candidate) => candidate.taskId === taskId);
    mutation.mutate({ taskId, hasChanges: task?.hasChanges ?? false });
  };

  return {
    /* 브레드크럼 표기. Figma 112:5450 "2026. 08. 20. 18:00" */
    finishedAtLabel: formatDateTime(result?.finishedAt),
    groups,
    isPending,
    isError,
    processingTaskId: mutation.isPending ? mutation.variables.taskId : null,
    actionErrorMessage,
    applyChanges,
  };
}
