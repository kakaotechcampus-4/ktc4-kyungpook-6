import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import {
  approveOwner,
  getOwnerApplications,
  getStoreCandidates,
  rejectOwner,
} from '../services/owner';
import type { MemberStatus } from '../services/owner';
import { messageOf } from '../utils/apiError';

/** 화면 위쪽 탭. 승인 대기를 먼저 본다 — 담당자가 처리할 것이 그쪽이다. */
export const STATUS_TABS: { status: MemberStatus; label: string }[] = [
  { status: 'PENDING', label: '승인 대기' },
  { status: 'APPROVED', label: '승인됨' },
  { status: 'REJECTED', label: '거절됨' },
];

export type UseOwnerApprovalPageResult = {
  status: MemberStatus;
  setStatus: (status: MemberStatus) => void;
  applications: ReturnType<typeof useApplications>['data'];
  isPending: boolean;
  isError: boolean;
  /** 펼쳐 둔 신청의 회원 ID. 후보 가게는 펼칠 때 불러온다. */
  openedMemberId: number | null;
  toggleOpen: (memberId: number) => void;
  /** 처리 중인 신청의 회원 ID. 버튼을 잠그는 데 쓴다. */
  processingMemberId: number | null;
  errorMessage: string | undefined;
  approve: (memberId: number, storeId: number) => void;
  reject: (memberId: number) => void;
};

function useApplications(status: MemberStatus) {
  return useQuery({
    queryKey: ['ownerApplications', status],
    queryFn: () => getOwnerApplications(status),
    retry: false,
  });
}

/**
 * 고른 신청의 후보 가게를 불러온다. 펼쳤을 때만 부른다 —
 * 목록을 열자마자 신청 수만큼 조회가 나가면 느리고, 대부분은 펼쳐 보지도 않는다.
 */
export function useStoreCandidates(memberId: number | null) {
  return useQuery({
    queryKey: ['storeCandidates', memberId],
    queryFn: () => getStoreCandidates(memberId as number),
    enabled: memberId !== null,
    retry: false,
  });
}

/**
 * 점주 가입 관리 화면의 상태를 모은다.
 *
 * 승인은 **가게를 함께 고르는 일**이라 목록 조회와 떼어 둘 수 없다. 승인하면 그 신청이
 * 대기 목록에서 빠지고 가게의 연결된 점주 수가 바뀌므로, 끝나면 두 조회를 함께 무효화한다.
 */
export function useOwnerApprovalPage(): UseOwnerApprovalPageResult {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<MemberStatus>('PENDING');
  const [openedMemberId, setOpenedMemberId] = useState<number | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | undefined>(undefined);

  const { data: applications, isPending, isError } = useApplications(status);

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['ownerApplications'] });
    queryClient.invalidateQueries({ queryKey: ['storeCandidates'] });
    /* 승인하면 그 가게에 점주가 붙으므로 가게 목록도 다시 본다. */
    queryClient.invalidateQueries({ queryKey: ['stores'] });
  };

  const mutation = useMutation({
    mutationFn: (input: { memberId: number; storeId?: number }) =>
      input.storeId === undefined
        ? rejectOwner(input.memberId)
        : approveOwner(input.memberId, input.storeId),
    onMutate: () => setErrorMessage(undefined),
    onSuccess: () => {
      setOpenedMemberId(null);
      invalidate();
    },
    onError: (error) => setErrorMessage(messageOf(error)),
  });

  return {
    status,
    setStatus: (next) => {
      setStatus(next);
      setOpenedMemberId(null);
      setErrorMessage(undefined);
    },
    applications,
    isPending,
    isError,
    openedMemberId,
    toggleOpen: (memberId) => {
      setErrorMessage(undefined);
      setOpenedMemberId((prev) => (prev === memberId ? null : memberId));
    },
    processingMemberId: mutation.isPending ? mutation.variables.memberId : null,
    errorMessage,
    approve: (memberId, storeId) => mutation.mutate({ memberId, storeId }),
    reject: (memberId) => mutation.mutate({ memberId }),
  };
}
