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

/** 확인 전화 한 통의 진행 상태. 전화 API 의 status 를 그대로 쓴다. */
export type CallStatusResponse =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'FAILED';

/** 왜 걸었는가. CHANGE_DETECTED(변화가 잡혀서) · PERIODIC_RECHECK(90일 넘게 확인하지 않아서). */
export type CallReasonResponse = 'CHANGE_DETECTED' | 'PERIODIC_RECHECK';

/**
 * 전화가 왜 실패했는지를 **담당자가 할 일**로 묶은 값.
 *
 * 같은 "실패" 라도 할 일이 정반대다 — 통화 중이라 못 들은 것은 이따 다시 걸면 되고,
 * 없는 번호는 다시 걸어도 같아서 번호를 고쳐야 한다.
 *
 * `OUR_SIDE` 를 가려내는 것이 특히 중요하다. 전화 API 나 서버 문제를 다른 실패와 같은 모양으로
 * 보여 주면 담당자가 **가게 탓으로 읽고** 멀쩡한 가게를 "연락이 안 되는 가게" 로 넘긴다.
 */
export type CallFailureKindResponse =
  | 'RETRY_LATER'
  | 'FIX_NUMBER'
  | 'NOT_BY_PHONE'
  | 'PARTY_UNCONFIRMED'
  | 'OUR_SIDE'
  | 'UNKNOWN';

/** 통화에서 들은 답 한 건. */
export type CallAnswerResponse = {
  /** 우리 쪽 항목 키 — 수정안의 field 와 같은 값이다. 모르는 항목은 전화 API 의 키가 그대로 온다. */
  field: string;
  /** 통화에서 그 항목을 부른 이름("상호명" 등). 화면 라벨로 그대로 쓸 수 있다. */
  naturalField: string;
  /**
   * 점주가 답한 값.
   *
   * **영업 여부(`status`)만 `"true"`·`"false"` 다.** 전화로는 "지금도 영업하십니까" 한 가지만
   * 묻기 때문에 휴업과 폐업을 가르는 일은 담당자가 한다. 나머지는 들은 값 그대로다.
   */
  value: string | null;
  /**
   * 이 답이 가게에 그대로 반영됐는가.
   *
   * **영업 여부는 늘 false 다** — 전화로는 "지금도 영업하십니까" 참·거짓 하나만 물어서
   * 휴업인지 폐업인지 가릴 수 없다. 그 판단은 담당자가 한다.
   */
  applied: boolean;
};

/**
 * 조사 결과 한 건으로 건 확인 전화.
 *
 * **담당자 확인 기록(`verification`)과 다른 것이다.** 전화는 점주에게 물어본 결과이고
 * 확인 기록은 그 답을 읽은 담당자의 판단이다 — 전화 한 통으로 가게 확인일은 올라가지 않는다.
 */
export type CallResponse = {
  callId: number;
  status: CallStatusResponse;
  reason: CallReasonResponse;
  requestedAt: string | null;
  /** 통화 처리가 끝난 시각. 진행 중이면 null. */
  endedAt: string | null;
  /** 실패 사유 코드. 실패가 아니면 null. 전화 API 가 정하는 값이라 번역하지 않는다. */
  failureReason: string | null;
  /** 실패 사유를 담당자가 할 일로 묶은 값. 실패가 아니면 null. */
  failureKind: CallFailureKindResponse | null;
  /** 진행 중이거나 실패한 통화의 부분 결과도 들어온다. */
  answers: CallAnswerResponse[] | null;
};

/** Call API에서 기록되는 공개 대화·판정 로그. */
export type CallLogEntry =
  | {
      sequence: number;
      timestamp: string;
      turn: number;
      type: 'ASSISTANT' | 'USER';
      text: string;
    }
  | {
      sequence: number;
      timestamp: string;
      turn: number;
      type: 'JEV';
      verdict: 'PROCESS' | 'IGNORE' | 'INTERRUPT' | 'BACKCHANNEL';
      answer: 'YES' | 'NO' | 'VALUE' | 'CUSTOM' | 'UNCLEAR';
      answerConfidence: number;
      control: 'STOP' | 'WAIT' | 'CONTINUE' | 'REPEAT';
      controlConfidence: number;
    };

export type CallLogsResponse = {
  verificationId: string;
  status: CallStatusResponse;
  startedAt: string | null;
  connectedAt: string | null;
  endedAt: string | null;
  failureReason: string | null;
  logs: CallLogEntry[];
  nextCursor: number;
};

/** 한 번에 전화를 넣은 결과. */
export type CallBulkResponse = {
  /** 전화를 걸 만한 결과 수. */
  targetCount: number;
  /** 실제로 요청이 들어간 수. */
  requested: number;
  /** 번호가 없거나 물을 것이 없어 빠진 수. */
  skipped: number;
  calls: CallResponse[] | null;
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
  /** 이 결과로 건 확인 전화. 아직 걸지 않았으면 null. */
  call: CallResponse | null;
};

/** 아직 조사 결과 Task 가 만들어지지 않은 대상 가게. */
export type PendingStoreResponse = {
  storeId: number;
  storeName: string;
  storeAddress: string;
  storePhone: string | null;
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
  /** 조사 중인 Job 에서 아직 처리되지 않은 가게. 이전 API 응답에는 없을 수 있다. */
  pendingStores?: PendingStoreResponse[];
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

/**
 * 그 가게에 확인 전화를 요청한다. 통화가 끝나기를 기다리지 않고 바로 돌아온다(202).
 *
 * 결과는 조사 결과를 다시 조회해 각 카드의 `call` 로 본다 — 서버가 주기적으로 전화 API 에서
 * 받아 와 갱신한다.
 *
 * 걸 수 없는 경우는 409 다: 변화도 없고 최근에 확인한 결과(`NO_CHANGE`), 조사에 실패한 결과,
 * 이미 확인을 끝낸 카드, 가게 전화번호가 없는 가게(`call-target-not-eligible`), 이미 걸어 둔
 * 전화가 있는 경우(`call-already-requested`). 전화 API 주소·키를 아직 넣지 않은 환경은 503 이다.
 */
export const requestCall = async (taskId: number) =>
  postRequest<CallResponse>(`/api/tasks/${taskId}/call`, undefined);

/** callId로 통화 로그를 조회한다. 다음 조회에는 응답의 nextCursor를 after에 전달한다. */
export const getCallLogs = async (callId: number, after = 0) =>
  getRequest<CallLogsResponse>(`/api/calls/${callId}/logs`, { after });

/**
 * 그 조사 결과에서 **전화를 걸 만한 가게 전부**에 전화를 요청한다.
 *
 * 대기열은 전화 API 가 갖고 있어서, 한꺼번에 넣으면 전화 API 가 차례대로 발신한다 —
 * 화면이 한 통씩 기다리며 다음을 넣을 필요가 없다.
 */
export const requestCallsForJob = async (jobId: number) =>
  postRequest<CallBulkResponse>(`/api/jobs/${jobId}/calls`, undefined);
