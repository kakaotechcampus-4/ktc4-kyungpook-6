import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  applyTask,
  confirmTask,
  getJobResult,
  requestCall,
} from '../services/analysis';
import type {
  CallFailureKindResponse,
  CallResponse,
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
import type { StatusDetail } from '../components/ui/StatusChip';
import { formatDateTime } from '../utils/date';
import {
  callAnswerLabel,
  changeValueLabel,
  evidenceSourceLabel,
  fieldLabel,
} from '../utils/changeLabel';
import { isNotFound, messageOf } from '../utils/apiError';

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

/**
 * 전화를 걸 수 있는 분류. 변화가 잡혔거나(우선확인) 확인한 지 90일이 넘은(추가확인) 가게다.
 * 변화도 없고 최근에 확인한 가게와 조사하지 못한 가게에는 걸지 않는다 — 백엔드도 409 로 막는다.
 */
const CALLABLE: TaskClassification[] = ['priority', 'additional'];

/**
 * 카드에 찍을 확인 전화 상태. 아직 걸지 않았으면 undefined.
 *
 * label 은 늘 보이는 한마디("전화 확인 중"), details 는 칩에 마우스를 올리거나 누르면 펼치는 세부다.
 * 사유 코드·들은 답까지 한 줄에 이어 붙이면 실패 줄이 카드 폭을 넘어 무엇이 요점인지 안 보였다.
 */
/**
 * 세부 중 "요청 시각" 줄의 라벨. 전화 단계 판(CallPhaseButton)이 이 줄만 칸에서 빼서 맨 아래 작은
 * 글자로 내린다 — 담당자가 읽을 것은 들은 답·할 일이고 시각은 곁들이는 정보다.
 */
export const REQUESTED_AT_LABEL = '요청 시각';

export type CallStatusLabel = {
  label: string;
  tone: 'progress' | 'done' | 'failed';
  /**
   * 카드의 전화 버튼 자리에 찍을 단계. 칩(tone)은 대기와 통화 중을 한 색으로 묶지만,
   * 버튼은 둘을 나눈다 — "아직 차례를 기다린다" 와 "지금 가게와 통화하고 있다" 는 담당자에게 다른 소식이다.
   */
  phase: CallPhase;
  details: StatusDetail[];
  /** 통화 내용(전사). 전화 중·전화 완료에만 담는다. 없으면 빈 배열. */
  transcript: TranscriptLine[];
};

/** 판에 찍는 전사 한 줄. agent: 우리 AI 상담원 · store: 가게 */
export type TranscriptLine = { speaker: 'agent' | 'store'; text: string };

function transcriptOf(call: CallResponse): TranscriptLine[] {
  return (call.transcript ?? [])
    .filter((line) => line.text.trim() !== '')
    .map((line) => ({ speaker: line.speaker === 'AGENT' ? 'agent' : 'store', text: line.text }));
}

/** waiting 대기(PENDING) · calling 통화 중(IN_PROGRESS) · done 완료 · failed 실패 */
export type CallPhase = 'waiting' | 'calling' | 'done' | 'failed';

/**
 * 실패했을 때 담당자가 할 일. 백엔드가 분류(`failureKind`)만 주고 문구는 화면이 붙인다.
 *
 * `OUR_SIDE` 만 "전화를 걸지 못했습니다" 로 시작한다 — 나머지와 같은 말로 적으면 담당자가
 * **가게 탓으로 읽고** 멀쩡한 가게를 "연락이 안 되는 가게" 로 넘긴다.
 */
const FAILURE_GUIDE: Record<CallFailureKindResponse, string> = {
  RETRY_LATER: '이따 다시 걸어 보세요',
  FIX_NUMBER: '번호가 잘못됐습니다 — 다시 걸어도 같습니다. 번호를 고쳐 주세요',
  NOT_BY_PHONE: '전화로는 확인할 수 없습니다 — 다른 방법으로 확인해 주세요',
  PARTY_UNCONFIRMED:
    '상대를 확인하지 못했습니다 — 다시 걸어 보고, 또 그러면 번호를 확인해 주세요',
  OUR_SIDE: '가게 문제가 아닙니다 — 전화 기능 쪽 문제입니다',
  UNKNOWN: '',
};

/**
 * 확인 전화 기록을 카드에 찍을 상태로 만든다.
 *
 * 끝난 전화는 **들은 답을 세부에 같이 담는다.** 답이 없다는 것도 적는다 — 빈 세부는 "아직 안 열어 봤다"
 * 와 구분되지 않는다.
 */
export function toCallStatusLabel(
  call: CallResponse | null,
): CallStatusLabel | undefined {
  if (!call) return undefined;

  const requestedAt = formatDateTime(call.requestedAt);
  const requestedDetail: StatusDetail[] = requestedAt
    ? [{ label: REQUESTED_AT_LABEL, value: requestedAt }]
    : [];

  if (call.status === 'PENDING' || call.status === 'IN_PROGRESS') {
    return {
      tone: 'progress',
      phase: call.status === 'PENDING' ? 'waiting' : 'calling',
      transcript: call.status === 'IN_PROGRESS' ? transcriptOf(call) : [],
      label: '전화 확인 중',
      details: requestedDetail,
    };
  }

  if (call.status === 'FAILED') {
    /*
      통화가 중간에 끊겨도 그때까지 들은 답은 온다(전화 API 명세: "진행 중이거나 실패한 통화의 부분
      결과도 포함될 수 있습니다"). 그 답을 버리면 담당자가 "상호명을 들었는데 끊긴 전화" 를
      그냥 실패로 보고 처음부터 다시 건다.

      다만 영업 여부는 적지 않는다. 끊기기 전에 들은 "영업 중/안 함" 한마디가 실패 판에 섞이면
      판정처럼 읽힌다 — 실패 판은 무슨 일이 있었고(사유 코드) 무엇을 할지(할 일)만 보인다.
      상호명 등 다른 항목의 부분 답은 그대로 둔다.
    */
    const partialAnswers = answerDetails({
      ...call,
      answers: (call.answers ?? []).filter((answer) => answer.field !== 'status'),
    });
    /* 사유 코드는 전화 API 가 정하는 값이라 번역하지 않는다 — 모르는 코드를 삼키면 원인이 사라진다. */
    const guide = FAILURE_GUIDE[call.failureKind ?? 'UNKNOWN'];
    return {
      tone: 'failed',
      phase: 'failed',
      transcript: [],
      label:
        call.failureKind === 'OUR_SIDE' ? '전화를 걸지 못했습니다' : '전화 확인 실패',
      details: [
        /* 무슨 일이 있었는지(사유 코드)를 먼저, 그래서 무엇을 할지(할 일)를 그 아래에 둔다. */
        ...(call.failureReason ? [{ label: '사유 코드', value: call.failureReason }] : []),
        ...(guide ? [{ label: '할 일', value: guide }] : []),
        ...partialAnswers,
        ...requestedDetail,
      ],
    };
  }

  const answers = answerDetails(call);

  return {
    tone: 'done',
    phase: 'done',
    transcript: transcriptOf(call),
    label: '전화 확인 완료',
    details: [
      ...(answers.length > 0 ? answers : [{ label: '들은 답', value: '없음' }]),
      ...requestedDetail,
    ],
  };
}

/**
 * 통화에서 들은 답을 세부 줄로 만든다. 아직 들은 것이 없으면 빈 배열.
 *
 * 가게에 반영된 답에는 "반영됨" 표시를 붙인다. 담당자가 **무엇이 이미 고쳐졌고 무엇이 자기 몫인지**
 * 알아야 한다 — 특히 영업 여부는 늘 담당자 몫이라(휴업과 폐업을 전화로 못 가린다)
 * 표시가 없으면 "전화가 알아서 했겠지" 하고 넘어간다.
 */
function answerDetails(call: CallResponse): StatusDetail[] {
  return (call.answers ?? []).map((answer) => ({
    label: answer.naturalField,
    value: callAnswerLabel(answer.field, answer.value),
    tag: answer.applied ? '반영됨' : undefined,
  }));
}

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
  /** 카드에 찍는 가게 전화번호. 번호가 없거나 걸 수 없는 모양이면 issue 로 알린다. */
  phone: StorePhoneLabel;
  classification: TaskClassification;
  evidences: SurveyEvidence[];
  changes: SurveyChange[];
  /** 조사 실패 이유. 성공이면 undefined. */
  failureReason?: string;
  /** 담당자가 이미 처리했으면 그 기록 글자. 아직이면 undefined. */
  verifiedLabel?: string;
  /** 반영할 수정안이 있는가. 없으면 "즉시 수정 반영하기"가 409가 된다. */
  hasChanges: boolean;
  /** 이 카드로 건 확인 전화 한 줄. 아직 걸지 않았으면 undefined. */
  callStatus?: CallStatusLabel;
  /**
   * 지금 전화를 걸 수 있는가. 전화는 되돌릴 수 없어서 걸 수 없는 카드에는 버튼을 그리지 않는다.
   *
   * 물을 질문이 만들어지지 않는 경우(휴업에서 폐업 제안, 미확인 상태)는 여기서 가릴 수 없다 —
   * 그 판단은 백엔드가 수정안을 보고 한다. 그때는 409 로 거절하고 화면이 그 문구를 띄운다.
   */
  canCall: boolean;
};

/**
 * 전화 API 가 받아 주는 번호 모양. 하이픈·공백·괄호를 지운 뒤 맨 앞 `+` 는 있어도 되고,
 * 숫자는 7~15자리여야 한다.
 *
 * 백엔드(`CallService.CALLABLE_PHONE`)와 같은 규칙을 화면도 본다. 걸 수 없는 번호에 버튼을
 * 그려 두면 담당자가 눌러 보고 "전화로 확인할 대상이 아닙니다" 를 받는데, 그 문구로는 번호가
 * 문제인지 카드가 문제인지 알 수 없다 — 누르기 전에 버튼을 내리는 쪽이 낫다.
 */
function isCallablePhone(phone: string | null | undefined): boolean {
  if (!phone) return false;
  return /^\+?\d{7,15}$/.test(phone.replace(/[-\s()]/g, ''));
}

/**
 * 카드에 찍는 전화번호 한 줄.
 *
 * 비어 있으면 아예 줄을 내리지 않고 "전화번호 없음" 을 적는다 — 빈 자리는 "아직 안 불러왔다" 와
 * 구분되지 않고, 전화하기 버튼이 왜 잠겼는지도 알 수 없다. 번호는 있는데 걸 수 없는 모양이면
 * 번호를 그대로 보여 주고 그 사실을 붙인다 — 담당자가 고칠 값이 무엇인지 보여야 한다.
 */
export type StorePhoneLabel = {
  value: string;
  issue?: 'missing' | 'invalid';
};

export function toStorePhoneLabel(phone: string | null | undefined): StorePhoneLabel {
  const trimmed = phone?.trim();
  if (!trimmed) return { value: '전화번호 없음', issue: 'missing' };
  return isCallablePhone(trimmed)
    ? { value: trimmed }
    : { value: trimmed, issue: 'invalid' };
}

/** 분류별로 나눈 Task 목록. 화면의 네 섹션과 1:1로 대응한다. */
export type AnalysisTaskGroups = Record<TaskClassification, AnalysisTask[]>;

/**
 * 아직 돌고 있는 조사인가. 이 동안에는 결과를 주기적으로 다시 받아 온다.
 *
 * 우하단 진행 표시(hooks/surveyProgress)도 같은 판단을 써야 한다 — 화면과 표시가
 * 서로 다른 기준으로 "도는 중" 을 정하면 한쪽만 폴링을 멈춘다.
 */
export function isJobRunning(
  status: JobResultResponse['status'] | undefined,
): boolean {
  return status === 'PENDING' || status === 'IN_PROGRESS';
}

/** 조사가 도는 동안 다시 물어보는 간격(ms). AI 는 가게 한 곳에 최대 60초를 쓴다. */
export const POLL_INTERVAL_MS = 3_000;

/**
 * 조사 결과 조회의 쿼리 키. jobId 가 없으면 가장 최근 조사(`/api/jobs/latest`)다.
 *
 * 분석 결과 화면과 우하단 진행 표시가 **같은 키**를 써야 한다. 키가 어긋나면 같은 조사를
 * 두 번 폴링하고, 조사를 시작한 뒤 한쪽 캐시만 갱신돼 표시가 안 뜬다.
 */
export const JOB_RESULT_QUERY_PREFIX = ['jobResult'] as const;

export const jobResultQueryKey = (jobId?: string) => [
  ...JOB_RESULT_QUERY_PREFIX,
  jobId ?? 'latest',
];

/**
 * 진행 상황 한 줄. 끝난 조사면 undefined 다.
 *
 * 이게 없으면 "조사 시작하기" 로 넘어온 담당자가 **멈춘 화면**을 본다 — 결과가 하나씩 쌓이는데
 * 화면은 그대로라, 새로고침해야 나머지가 보였다(PROMPT-150).
 */
export function progressLabelOf(
  result: JobResultResponse | undefined,
): string | undefined {
  if (!result) return undefined;
  if (result.status === 'FAILED') {
    return result.errorMessage
      ? `조사가 멈췄습니다 — ${result.errorMessage}`
      : '조사가 멈췄습니다.';
  }
  if (!isJobRunning(result.status)) return undefined;

  return `조사 중 · ${result.targetCount}곳 중 ${result.completedCount}곳 끝남`;
}

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
  /*
    판정이 없으면 조사 실패다. 예전에는 이 경우가 'unchanged'로 떨어져
    "변화 근거가 없는 가게"에 섞였다 — 조사를 못 한 가게와 변화가 없는 가게는
    담당자가 할 일이 서로 다르다(PROMPT-150).
  */
  const classification: TaskClassification = task.classification
    ? CLASSIFICATION_MAP[task.classification] ?? 'unchanged'
    : 'failed';

  return {
    id: String(task.taskId),
    taskId: task.taskId,
    storeId: task.storeId,
    storeName: task.storeName,
    address: task.storeAddress ?? '-',
    addressRoad: task.storeAddress,
    phone: toStorePhoneLabel(task.storePhone),
    classification,
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
    callStatus: toCallStatusLabel(task.call),
    /*
      전화를 걸 분류이고, 담당자가 아직 확인하지 않았고, 걸어 둔 전화가 없을 때만 걸 수 있다.
      실패한 전화는 다시 걸 수 있다 — 안 받았거나 끊긴 통화다.
    */
    canCall:
      CALLABLE.includes(classification) &&
      !task.verification &&
      isCallablePhone(task.storePhone) &&
      (!task.call || task.call.status === 'FAILED'),
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
 * 조사한 적이 없는 경우(404)도 오지 않는다 — 그건 고장이 아니라 빈 상태라 따로 알린다.
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
      storePhone: '053-123-4567',
      lastCheckedAt: null,
      classification: 'PRIORITY_CHECK',
      failureReason: null,
      verification: null,
      call: null,
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
      storePhone: '032-555-0142',
      lastCheckedAt: null,
      classification: 'PRIORITY_CHECK',
      failureReason: null,
      verification: null,
      call: null,
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
      call: null,
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
      storePhone: '053-950-0082',
      lastCheckedAt: null,
      classification: 'NO_CHANGE',
      failureReason: null,
      verification: null,
      call: null,
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
  /** 조회 실패 여부. 목업 결과로 대체해 보여준다. 아래 noJobYet 인 경우는 빼고 센다. */
  isError: boolean;
  /** 조사한 기록이 아직 없는가(404). 고장이 아니라 빈 상태라 목업을 깔지 않는다. */
  noJobYet: boolean;
  /** 수정안 반영 중인 Task id. 버튼을 잠그는 데 쓴다. */
  processingTaskId: number | null;
  /** 반영·확인·전화에 실패했을 때 담당자에게 보일 문구. */
  actionErrorMessage: string | undefined;
  /** "즉시 수정 반영하기". 반영 후 목록을 다시 불러온다. */
  applyChanges: (taskId: number) => void;
  /** "전화하기". 요청만 넣고 결과는 폴링으로 받는다. */
  requestOneCall: (taskId: number) => void;
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
  const queryKey = jobResultQueryKey(jobId);
  const [actionErrorMessage, setActionErrorMessage] = useState<
    string | undefined
  >(undefined);

  const { data, error, isPending, isError: isQueryError } = useQuery({
    queryKey,
    queryFn: () => getJobResult(jobId),
    retry: false,
    /* 조사가 도는 동안만 다시 물어본다. 끝나면 false 라 더 부르지 않는다. */
    refetchInterval: (query) =>
      isJobRunning(query.state.data?.status) ? POLL_INTERVAL_MS : false,
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

  /*
    확인 전화. 요청만 넣고 통화를 기다리지 않는다(202) — 끝나면 서버가 전화 API 에서 받아 와
    갱신하고, 화면은 조사 결과를 다시 조회해 각 카드의 call 로 본다.

    그래서 성공한 뒤 목록을 바로 다시 불러온다. 그 조회에 "전화 확인 중" 이 실려 와야
    담당자가 자기가 누른 것이 들어갔다는 것을 안다 — 아무 변화가 없으면 다시 누른다.
  */
  const callMutation = useMutation({
    mutationFn: (taskId: number) => requestCall(taskId),
    onMutate: () => setActionErrorMessage(undefined),
    onSuccess: () => queryClient.invalidateQueries({ queryKey }),
    onError: (error) => setActionErrorMessage(messageOf(error)),
  });

  /*
    아직 조사한 적이 없으면(404) 목업을 깔지 않는다. 운영에 처음 올린 날 실제로 그랬는데,
    화면은 "불러오지 못해 목업 데이터를 표시합니다" 와 함께 가짜 가게 셋을 띄웠다. 담당자에게는
    "조사한 적이 없다" 와 "서버가 죽었다" 가 똑같이 보인다(11주차 멘토 리뷰 🟡 6).
  */
  const noJobYet = isQueryError && isNotFound(error);
  const isError = isQueryError && !noJobYet;
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
    noJobYet,
    processingTaskId: mutation.isPending
      ? mutation.variables.taskId
      : callMutation.isPending
        ? callMutation.variables
        : null,
    actionErrorMessage,
    applyChanges,
    requestOneCall: (taskId: number) => callMutation.mutate(taskId),
  };
}
