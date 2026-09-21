import { getRequest } from './api';

/**
 * 백엔드 Task.classification enum.
 * 실제 문자열이 확정되면 이 유니온만 고치고 CLASSIFICATION_MAP을 맞춘다.
 */
export type TaskClassificationResponse =
  | 'PRIORITY_CHECK'
  | 'ADDITIONAL_CHECK'
  | 'NO_CHANGE';

/**
 * 조사 근거 한 건.
 *
 * API 초안의 Task 테이블에는 아직 근거를 담을 컬럼이 없다.
 * 화면(Figma 112:5478)이 요구하는 모양으로 먼저 잡아두고,
 * 백엔드와 합의되면 필드명만 맞춘다.
 */
export type TaskEvidenceResponse = {
  description: string;
  /** 출처 표기. 예: "근거 링크 - 국세청", "자체 확인일: 6개월 전" */
  sourceLabel: string | null;
  /** 외부 링크가 있을 때만 채워진다. 자체 데이터로 판단한 근거는 null이다. */
  sourceUrl: string | null;
};

/**
 * Task.proposed_changes(JSON)의 한 항목.
 *
 * 초안은 JSON이라 모양이 열려 있는데, 화면은 "가게 상태: '휴업' 갱신"처럼
 * 항목명과 내용이 나뉜 문자열을 그린다. 서버가 한글 라벨까지 내려주는 쪽으로 잡았다.
 * { status: "SUSPENDED" } 같은 원본 갱신값을 내려주기로 하면
 * enum·필드명의 한글 라벨 매핑이 프론트에 추가로 필요하다.
 */
export type ProposedChangeResponse = {
  /** 항목명. 예: "가게 상태" */
  field: string;
  /** 항목 내용. 예: "'휴업' 갱신" */
  description: string;
};

/** Job에 속한 Task 한 건. Store 정보는 서버가 조인해 내려준다고 보고 잡았다. */
export type TaskResponse = {
  taskId: number;
  storeId: number;
  storeName: string;
  storeAddress: string | null;
  classification: TaskClassificationResponse;
  evidences: TaskEvidenceResponse[] | null;
  proposedChanges: ProposedChangeResponse[] | null;
};

/** 조사 1회(Job)의 결과. 분석 결과 화면이 통째로 받는 응답. */
export type JobResultResponse = {
  jobId: number;
  /** 조사 완료 시각(ISO). 브레드크럼에 표시한다. */
  finishedAt: string | null;
  tasks: TaskResponse[];
};

/**
 * 조사 결과를 조회한다. jobId를 주지 않으면 가장 최근 조사를 받는다.
 *
 * @example
 * const { tasks } = await getJobResult('12');
 */
export const getJobResult = async (jobId?: string) => {
  const path = jobId ? `/api/jobs/${jobId}` : '/api/jobs/latest';
  return getRequest<JobResultResponse>(path);
};
