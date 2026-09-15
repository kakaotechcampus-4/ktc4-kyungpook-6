import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { getJobResult } from '../services/analysis';
import type {
  JobResultResponse,
  TaskClassificationResponse,
  TaskResponse,
} from '../services/analysis';
import type {
  SurveyChange,
  SurveyEvidence,
  TaskClassification,
} from '../components/AgentSurveyResultCard';
import { useCurrentUser } from './user';
import type { UseCurrentUserResult } from './user';
import { useSidebarNav } from './sidebarNav';
import type { UseSidebarNavResult } from './sidebarNav';

/** 백엔드 enum을 카드가 쓰는 소문자 값으로 옮긴다. */
const CLASSIFICATION_MAP: Record<TaskClassificationResponse, TaskClassification> =
  {
    PRIORITY_CHECK: 'priority',
    ADDITIONAL_CHECK: 'additional',
    NO_CHANGE: 'unchanged',
  };

/** 분석 결과 화면이 카드 하나에 넘기는 값. */
export type AnalysisTask = {
  id: string;
  storeName: string;
  address: string;
  classification: TaskClassification;
  evidences: SurveyEvidence[];
  changes: SurveyChange[];
};

/** 분류별로 나눈 Task 목록. 화면의 세 섹션과 1:1로 대응한다. */
export type AnalysisTaskGroups = Record<TaskClassification, AnalysisTask[]>;

function toAnalysisTask(task: TaskResponse): AnalysisTask {
  return {
    id: String(task.taskId),
    storeName: task.storeName,
    address: task.storeAddress ?? '-',
    classification: CLASSIFICATION_MAP[task.classification] ?? 'unchanged',
    evidences:
      task.evidences?.map((evidence) => ({
        description: evidence.description,
        sourceLabel: evidence.sourceLabel ?? undefined,
        sourceUrl: evidence.sourceUrl ?? undefined,
      })) ?? [],
    changes: task.proposedChanges ?? [],
  };
}

function groupByClassification(tasks: AnalysisTask[]): AnalysisTaskGroups {
  const groups: AnalysisTaskGroups = {
    priority: [],
    additional: [],
    unchanged: [],
  };

  for (const task of tasks) {
    groups[task.classification].push(task);
  }

  return groups;
}

/**
 * 조사 완료 시각을 브레드크럼 표기로 바꾼다. Figma 112:5450 "2026. 08. 20. 18:00"
 * ko-KR 포맷이 이미 "2026. 08. 20." 모양이라 시각만 이어 붙인다.
 */
function formatFinishedAt(finishedAt: string | null): string | undefined {
  if (!finishedAt) return undefined;

  const date = new Date(finishedAt);
  if (Number.isNaN(date.getTime())) return undefined;

  const day = new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
  const time = new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);

  return `${day} ${time}`;
}

/**
 * API 조회 실패 시 표시할 목업 결과. MockupDataPage의 FALLBACK_STORES와 같은 역할이다.
 * 조사 결과 API가 아직 없어 화면이 빈 채로 나오는 걸 막는다.
 * Figma 112:4993의 예시 데이터를 그대로 옮겼다.
 */
const FALLBACK_RESULT: JobResultResponse = {
  jobId: 0,
  finishedAt: '2026-08-20T18:00:00',
  tasks: [
    {
      taskId: 1,
      storeId: 1,
      storeName: '맛나 치킨',
      storeAddress: '대구광역시 북구 대학로 80',
      classification: 'PRIORITY_CHECK',
      evidences: [
        {
          description: '국세청 사업자등록 상태에서 운영 상태가 휴업으로 확인됨',
          sourceLabel: '근거 링크 - 국세청',
          sourceUrl: 'https://www.hometax.go.kr',
        },
        {
          description: '공개 지도 정보에서 주소가 DB와 다르게 확인됨',
          sourceLabel: '근거 링크 - 카카오맵',
          sourceUrl: 'https://map.kakao.com',
        },
      ],
      proposedChanges: [
        { field: '가게 상태', description: "'휴업' 갱신" },
        { field: '주소', description: "'대구광역시 북구 대학로 79' 갱신" },
      ],
    },
    {
      taskId: 2,
      storeId: 2,
      storeName: '짜장면',
      storeAddress: '인천광역시 부평구 부평동 32',
      classification: 'PRIORITY_CHECK',
      evidences: [
        {
          description: "검색 결과에서 상호명이 '신나는 자장면'으로 변경됨이 확인됨",
          sourceLabel: '근거 링크 - 네이버 블로그',
          sourceUrl: 'https://blog.naver.com',
        },
      ],
      proposedChanges: [{ field: '상호명', description: "'신나는 자장면' 갱신" }],
    },
    {
      taskId: 3,
      storeId: 3,
      storeName: '라멘',
      storeAddress: '부산광역시 사하구 하단동 89',
      classification: 'ADDITIONAL_CHECK',
      evidences: [
        {
          description: '마지막 자체 확인 이후 장기간이 지남',
          sourceLabel: '자체 확인일: 6개월 전',
          sourceUrl: null,
        },
      ],
      proposedChanges: [
        { field: '자체 확인 요망', description: '010-XXXX-XXXX' },
      ],
    },
    {
      taskId: 4,
      storeId: 4,
      storeName: '정든 국밥집',
      storeAddress: '대구광역시 북구 대학로 82',
      classification: 'NO_CHANGE',
      evidences: [
        {
          description: '공개 지도 정보와 DB가 일치함',
          sourceLabel: '근거 링크 - 네이버 지도',
          sourceUrl: 'https://map.naver.com',
        },
      ],
      proposedChanges: null,
    },
  ],
};

export type UseAnalysisResultPageResult = UseSidebarNavResult &
  UseCurrentUserResult & {
    /** 브레드크럼에 표시할 조사 완료 시각. 없으면 undefined. */
    finishedAtLabel: string | undefined;
    /** 분류별 Task 목록. 화면의 세 섹션이 그대로 쓴다. */
    groups: AnalysisTaskGroups;
    /** 조사 결과 조회 중 여부. Skeleton 노출 여부에 쓰인다. */
    isPending: boolean;
    /** 조회 실패 여부. 목업 결과로 대체해 보여준다. */
    isError: boolean;
  };

/**
 * 분석 결과 페이지(AnalysisResultPage)가 쓰는 값 전부를 모은다.
 *
 * 페이지는 이 훅 하나만 부르고 props로는 className만 받는다.
 * 조사 결과 조회는 여기서, 사이드바 내비와 사용자 정보는 useSidebarNav·useCurrentUser를
 * 합성해 함께 내려준다. 어느 가게 목록을 보는지는 URL(:jobId)이 정하므로
 * 훅이 직접 useParams로 읽는다.
 */
export function useAnalysisResultPage(): UseAnalysisResultPageResult {
  const { jobId } = useParams<{ jobId?: string }>();
  const sidebarNav = useSidebarNav();
  const currentUser = useCurrentUser();

  const { data, isPending, isError } = useQuery({
    queryKey: ['jobResult', jobId ?? 'latest'],
    queryFn: () => getJobResult(jobId),
    retry: false,
  });

  const result = isError ? FALLBACK_RESULT : data;

  return {
    ...sidebarNav,
    ...currentUser,
    finishedAtLabel: formatFinishedAt(result?.finishedAt ?? null),
    groups: groupByClassification(result?.tasks.map(toAnalysisTask) ?? []),
    isPending,
    isError,
  };
}
