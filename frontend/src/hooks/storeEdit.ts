import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { isAxiosError } from 'axios';
import { confirmStore, updateStore } from '../services/store';
import type { StoreStatus, StoreUpdateRequest } from '../services/store';
import { formatDate } from '../utils/date';
import { useModal } from './modal';

/**
 * 수정 모달을 열 때 넘기는 가게의 현재 값.
 *
 * storeId 말고는 모두 선택이다. 호출하는 화면마다 알고 있는 값이 다른데
 * (조사 결과 화면은 상호명·주소만 안다), 부분 수정이라 모르는 칸은
 * 건드리지 않고 두면 그만이기 때문이다.
 */
export type StoreEditTarget = {
  storeId: number;
  name?: string | null;
  addressRoad?: string | null;
  phone?: string | null;
  status?: StoreStatus | null;
  lastCheckedAt?: string | null;
};

/** 모달의 입력 칸. 서버에 보내기 전까지는 전부 문자열로 들고 있는다. */
export type StoreEditFormValues = {
  name: string;
  addressRoad: string;
  phone: string;
  status: StoreStatus;
};

/** 모르는 값은 빈 칸으로 둔다. 운영 상태의 "모름"은 UNKNOWN(미확인)이다. */
const EMPTY_VALUES: StoreEditFormValues = {
  name: '',
  addressRoad: '',
  phone: '',
  status: 'UNKNOWN',
};

function toFormValues(target: StoreEditTarget): StoreEditFormValues {
  return {
    name: target.name ?? '',
    addressRoad: target.addressRoad ?? '',
    phone: target.phone ?? '',
    status: target.status ?? 'UNKNOWN',
  };
}

/**
 * 처음 값과 달라진 칸만 골라낸다.
 *
 * 화면이 값을 모르는 칸(전화번호처럼 빈 칸으로 열린 경우)을 담아 보내면
 * 서버는 "지워달라"로 읽는다. 담당자가 실제로 고친 칸만 보내야 하는 이유다.
 */
export function buildPatch(
  target: StoreEditTarget,
  values: StoreEditFormValues
): StoreUpdateRequest {
  const initial = toFormValues(target);
  const patch: StoreUpdateRequest = {};

  if (values.name !== initial.name) patch.name = values.name;
  if (values.addressRoad !== initial.addressRoad) {
    patch.addressRoad = values.addressRoad;
  }
  if (values.phone !== initial.phone) patch.phone = values.phone;
  if (values.status !== initial.status) patch.status = values.status;

  return patch;
}

/**
 * 보내기 전에 서버와 같은 기준으로 한 번 거른다(백엔드 StoreUpdateRequest).
 * 400을 받고 나서 알려주는 것보다 입력 칸에서 바로 막는 편이 낫다.
 * 담아 보내지 않는 칸은 검사하지 않는다 — 비어 있어도 기존 값이 유지되기 때문이다.
 */
export function validate(patch: StoreUpdateRequest): string | undefined {
  if (patch.name !== undefined) {
    if (!patch.name.trim()) return '상호명은 공백만으로 채울 수 없습니다.';
    if (patch.name.length > 200) return '상호명은 200자를 넘을 수 없습니다.';
  }

  if (patch.addressRoad !== undefined) {
    if (!patch.addressRoad.trim()) return '주소는 공백만으로 채울 수 없습니다.';
    if (patch.addressRoad.length > 500) {
      return '주소는 500자를 넘을 수 없습니다.';
    }
  }

  /* 전화번호만 빈 문자열로 지울 수 있어 길이만 본다. */
  if (patch.phone !== undefined && patch.phone.length > 20) {
    return '전화번호는 20자를 넘을 수 없습니다.';
  }

  return undefined;
}

/**
 * 실패 응답을 담당자가 읽을 문구로 바꾼다.
 *
 * 400 응답 본문은 아직 스프링 기본 검증 오류 형태라 규격이 정해지지 않았다
 * (에러 규격화 티켓에서 ErrorResponse로 통일된다). 그때까지 본문을 읽지 않고
 * 상태 코드만 보고 문구를 고른다.
 */
export function toErrorMessage(error: unknown): string {
  const status = isAxiosError(error) ? error.response?.status : undefined;

  switch (status) {
    case 400:
      return '입력한 값을 서버가 받지 못했습니다. 내용을 다시 확인해 주세요.';
    case 404:
      return '가게를 찾을 수 없습니다. 목록을 새로 고친 뒤 다시 시도해 주세요.';
    case 501:
      return '아직 서버에 저장 기능이 없습니다. 화면 확인용으로만 동작합니다.';
    default:
      return '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
  }
}

export type UseStoreEditModalResult = {
  /** 모달 열림 여부. */
  isOpen: boolean;
  /** 카드의 "직접 수정하기"에서 부른다. 넘긴 값으로 입력 칸을 채운다. */
  open: (target: StoreEditTarget) => void;
  /** 저장 없이 닫는다. ESC로도 같은 동작을 한다. */
  close: () => void;
  /** 입력 칸의 현재 값. */
  values: StoreEditFormValues;
  /** 입력 칸 하나를 고친다. */
  setValue: <K extends keyof StoreEditFormValues>(
    field: K,
    value: StoreEditFormValues[K]
  ) => void;
  /** "자체 확인일"에 표시할 문구. 값이 없으면 undefined. */
  lastCheckedAtLabel: string | undefined;
  /** "저장하기". 고친 칸만 PATCH로 보낸다. */
  save: () => void;
  /** "자체 확인 완료". 확인 시각만 서버에 기록한다. */
  confirm: () => void;
  /** 저장 요청 중 여부. */
  isSaving: boolean;
  /** 확인 기록 요청 중 여부. */
  isConfirming: boolean;
  /** 검증·요청 실패 안내. 없으면 undefined. */
  errorMessage: string | undefined;
};

/**
 * 가게 정보 수정 모달(StoreEditModal)의 상태를 모은다. Figma Modal(102:4996)
 *
 * 모달을 띄우는 화면이 여럿이라(조사 결과 카드, 이후 가게 목록 행) 훅으로 뺀다.
 * 어느 가게를 고치는지는 열 때 받은 값이 전부라 URL이나 별도 조회에 기대지 않는다.
 *
 * @example
 * const storeEdit = useStoreEditModal();
 * <button onClick={() => storeEdit.open({ storeId: 1, name: '맛나 치킨' })} />
 */
export function useStoreEditModal(): UseStoreEditModalResult {
  const queryClient = useQueryClient();
  const modal = useModal();
  const [target, setTarget] = useState<StoreEditTarget | undefined>(undefined);
  const [values, setValues] = useState<StoreEditFormValues>(EMPTY_VALUES);
  const [errorMessage, setErrorMessage] = useState<string | undefined>(
    undefined
  );

  const close = () => {
    modal.close();
    setErrorMessage(undefined);
  };

  /*
    가게 정보가 바뀌면 그 값을 보여주던 화면도 같이 틀어진다.
    목록(stores)과 조사 결과(jobResult) 둘 다 상호명·주소를 그리므로 함께 무효화한다.
  */
  const invalidateStoreQueries = () => {
    queryClient.invalidateQueries({ queryKey: ['stores'] });
    queryClient.invalidateQueries({ queryKey: ['jobResult'] });
  };

  const saveMutation = useMutation({
    mutationFn: ({
      storeId,
      patch,
    }: {
      storeId: number;
      patch: StoreUpdateRequest;
    }) => updateStore(storeId, patch),
    onSuccess: () => {
      invalidateStoreQueries();
      close();
    },
    onError: (error) => setErrorMessage(toErrorMessage(error)),
  });

  const confirmMutation = useMutation({
    mutationFn: (storeId: number) => confirmStore(storeId),
    onSuccess: (store) => {
      invalidateStoreQueries();
      /*
        확인만 기록하고 모달은 열어둔다. 확인일 옆 버튼이라 "확인했다"는 표시일 뿐이고,
        담당자는 이어서 값을 고쳐 저장할 수 있어야 한다.
        갱신된 확인일은 서버가 돌려준 값으로 바로 바꿔 끼운다.
      */
      setTarget((prev) =>
        prev ? { ...prev, lastCheckedAt: store?.lastCheckedAt } : prev
      );
    },
    onError: (error) => setErrorMessage(toErrorMessage(error)),
  });

  const open = (nextTarget: StoreEditTarget) => {
    setTarget(nextTarget);
    setValues(toFormValues(nextTarget));
    setErrorMessage(undefined);
    modal.open();
  };

  const setValue = <K extends keyof StoreEditFormValues>(
    field: K,
    value: StoreEditFormValues[K]
  ) => {
    setValues((prev) => ({ ...prev, [field]: value }));
    setErrorMessage(undefined);
  };

  const save = () => {
    if (!target) return;

    const patch = buildPatch(target, values);

    /* 고친 칸이 없으면 보낼 것도 없다. 빈 PATCH를 보내지 않고 닫는다. */
    if (Object.keys(patch).length === 0) {
      close();
      return;
    }

    const message = validate(patch);
    if (message) {
      setErrorMessage(message);
      return;
    }

    saveMutation.mutate({ storeId: target.storeId, patch });
  };

  const confirm = () => {
    if (!target) return;

    confirmMutation.mutate(target.storeId);
  };

  return {
    isOpen: modal.isOpen,
    open,
    close,
    values,
    setValue,
    lastCheckedAtLabel: formatDate(target?.lastCheckedAt),
    save,
    confirm,
    isSaving: saveMutation.isPending,
    isConfirming: confirmMutation.isPending,
    errorMessage,
  };
}
