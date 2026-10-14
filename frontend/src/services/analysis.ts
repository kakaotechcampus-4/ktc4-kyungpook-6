import { getRequest, postRequest } from './api';

/**
 * 백엔드 Task.classification enum.
 *
 * **null 이 올 수 있다.** 조사에 실패한 Task 는 판정이 없고 failureReason 에 이유가 담긴다.
 */
export type TaskClassificationResponse =
  | 'PRIORITY_CHECK'
  | 'ADDITIONAL_CHECK'
  | 'NO_CHANGE';

/** 근거가 어디서 나왔는가. 라벨은 백엔드가 아니라 화면이 붙인다(sourceLabel 은 항상 null). */
export type EvidenceSource = 'NTS' | 'AI_WEB';

/** 조사 근거 한 건. */
export type TaskEvidenceResponse = {
  /** 어느 항목의 근거인가 — proposedChanges 의 field 와 같은 값. */
  field: string | null;
  /** NTS: 국세청 대조(1차) · AI_WEB: AI 웹검색(2차). */
  source: EvidenceSource | null;
  description: string;
  /** 지금은 항상 null 이다. 화면이 source 를 보고 붙인다. */
  sourceLabel: string | null;
  /** 외부 링크가 있을 때만 채워진다. 국세청 근거에는 없다. */
  sourceUrl: string | null;
};

/** 바꿀 항목 — 백엔드 Store 의 필드명이다. 한글 라벨은 화면이 붙인다. */
export type ChangeField = 'name' | 'status' | 'phone' | 'addressRoad';

/**
 * 수정안 한 건.
 *
 * **백엔드는 한글 라벨이 아니라 원본 값을 내려준다**(`{ field: "status", value: "CLOSED" }`).
 * 예전에는 이 타입을 `{ field, description }` 으로 손수 잡아 두어, 화면에 항목 이름만 나오고
 * 값이 통째로 비어 보였다(PROMPT-150). 라벨 매핑은 `utils/changeLabel.ts` 가 맡는다.
 */
export type ProposedChangeResponse = {
  field: ChangeField;
  /** 새 값. status 면 OPEN · SUSPENDED · CLOSED. */
  value: string;
};

/** 담당자의 확인 기록. 있으면 그 카드는 이미 처리된 건이다. */
export type VerificationResponse = {
  /** CHANGE_STATUS(영업 상태만 반영) · UPDATE_INFO(그 밖의 항목도 반영) · NO_ACTION(확인만). */
  action: 'CHANGE_STATUS' | 'UPDATE_INFO' | 'NO_ACTION';
  verifiedAt: string | null;
};

/** Job 에 속한 Task 한 건. */
export type TaskResponse = {
  taskId: number;
  storeId: number;
  storeName: string;
  storeAddress: string | null;
  storePhone: string | null;
  lastCheckedAt: string | null;
  /** null 이면 조사 실패다. failureReason 에 이유가 있다. */
  classification: TaskClassificationResponse | null;
  /** 조사 실패 이유. 성공이면 null. */
  failureReason: string | null;
  evidences: TaskEvidenceResponse[] | null;
  proposedChanges: ProposedChangeResponse[] | null;
  /** 담당자가 이미 확인했으면 그 기록. 아직이면 null. */
  verification: VerificationResponse | null;
};

/** 조사 1회(Job)의 결과. 분석 결과 화면이 통째로 받는 응답. */
export type JobResultResponse = {
  jobId: number;
  /** PENDING · IN_PROGRESS · DONE · FAILED. */
  status: 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'FAILED';
  targetCount: number;
  /** 끝난 가게 수(성공·실패 모두). 진행도 표시에 쓴다. */
  completedCount: number;
  createdAt: string | null;
  /** 조사 완료 시각(ISO). 끝나지 않았으면 null. */
  finishedAt: string | null;
  /** 조사를 멈춘 이유. FAILED 일 때만. */
  errorMessage: string | null;
  tasks: TaskResponse[];
};

/** 조사에서 빠진 가게와 이유. */
export type ExcludedStore = {
  storeId: number;
  reason: string;
};

/** 조사 시작 응답. */
export type JobCreateResponse = {
  jobId: number;
  /** 실제로 조사할 가게 수. 제외된 가게는 세지 않는다. */
  targetCount: number;
  excluded: ExcludedStore[] | null;
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

/**
 * 고른 가게로 조사를 시작한다.
 *
 * **조사는 한 번에 하나다.** 대기·진행 중인 조사가 있으면 백엔드가 409로 거절한다.
 * 중복 id 는 백엔드가 한 번만 다루고, 중복을 뺀 뒤 100곳까지 받는다.
 */
export const startJob = async (storeIds: number[]) =>
  postRequest<JobCreateResponse>('/api/jobs', { storeIds });

/**
 * 수정안을 그대로 반영한다. 반영과 동시에 확인 완료로 기록된다.
 *
 * 카드 하나는 한 번만 처리할 수 있다 — 이미 처리한 카드는 409 `task-already-confirmed`,
 * 반영할 수정안이 없으면 409 `nothing-to-apply` 다.
 */
export const applyTask = async (taskId: number) =>
  postRequest<TaskResponse>(`/api/tasks/${taskId}/apply`, undefined);

/** 가게 값은 그대로 두고 확인만 끝낸다. 이미 처리한 카드는 409다. */
export const confirmTask = async (taskId: number) =>
  postRequest<TaskResponse>(`/api/tasks/${taskId}/confirm`, undefined);
