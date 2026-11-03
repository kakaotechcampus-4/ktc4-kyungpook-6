import { useCallback, useRef } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useLocation, useNavigate } from "react-router-dom";
import { getMe } from "../services/auth";
import { checkIn } from "../services/checkIn";
import type { CheckInResponse } from "../services/checkIn";
import type { Order } from "../services/order";
import type { CheckInResultState } from "./checkInResult";
import { toCheckInErrorMessage } from "../utils/checkInError";

/** 체크인이 끝나면 넘어갈 화면. 결과(CheckInResultState)를 location.state 로 넘긴다. */
export const CHECK_IN_RESULT_PATH = "/owner/qr/result";

/** 실패한 QR 이 화면에 머물러 있어도 이 시간(ms) 동안은 다시 보내지 않는다. */
const RETRY_SAME_QR_AFTER_MS = 3000;

const NO_STORE_MESSAGE = "연결된 가게가 없어 체크인할 수 없어요.";
const ME_ERROR_MESSAGE = "가게 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";

export type UseQrCheckInResult = {
  /** 스캐너가 QR 을 읽을 때마다 부른다. */
  handleScan: (qrPayload: string) => void;
  /** 체크인 요청 중이면 true. */
  isChecking: boolean;
  /** 체크인 실패 문구. 실패하지 않았으면 null. */
  errorMessage: string | null;
};

/**
 * 아동 QR 체크인. 읽은 QR 을 내 가게로 체크인하고, 성공하면 결과 화면으로 넘어간다.
 *
 * - 가게는 GET /api/auth/me 의 stores 첫 번째다(가게를 고르는 화면이 없어서. 백엔드 PR #96)
 * - 스캐너는 같은 QR 을 계속 읽으므로, 요청 중이거나 방금 실패한 QR 이면 보내지 않는다
 * - 실패 문구는 다음 QR 을 읽어 요청을 보낼 때 지운다
 */
export const useQrCheckIn = (): UseQrCheckInResult => {
  const navigate = useNavigate();
  // 주문 화면의 "QR로 확인"으로 들어왔으면 그 주문을 결과 화면까지 넘긴다.
  const order = (useLocation().state as { order?: Order } | null)?.order;
  const meQuery = useQuery({ queryKey: ["me"], queryFn: () => getMe() });
  const storeId = meQuery.data?.stores[0]?.storeId;

  const lastFailedRef = useRef<{ qrPayload: string; at: number } | null>(null);
  // 스캐너는 화면이 다시 그려지기 전에도 여러 번 부른다. isPending 만 보면 요청이 겹쳐 나가서 ref 로도 막는다.
  const inFlightRef = useRef(false);

  const checkInMutation = useMutation({
    mutationFn: (qrPayload: string) => {
      if (storeId === undefined) throw new Error("no linked store");
      return checkIn(storeId, qrPayload);
    },
    onSuccess: (result: CheckInResponse) => {
      const state: CheckInResultState = { checkedInAt: result.checkedInAt, order };
      navigate(CHECK_IN_RESULT_PATH, { replace: true, state });
    },
    onError: (_error, qrPayload) => {
      lastFailedRef.current = { qrPayload, at: Date.now() };
    },
    onSettled: () => {
      inFlightRef.current = false;
    },
  });

  const { mutate } = checkInMutation;

  const handleScan = useCallback(
    (qrPayload: string) => {
      // 가게를 아직 모르면(내 정보 조회 중이거나 연결된 가게가 없으면) 보내지 않는다.
      if (inFlightRef.current || storeId === undefined) return;

      const lastFailed = lastFailedRef.current;
      if (
        lastFailed &&
        lastFailed.qrPayload === qrPayload &&
        Date.now() - lastFailed.at < RETRY_SAME_QR_AFTER_MS
      ) {
        return;
      }
      // 스캔한 값을 손대지 않고 보낸다(trim 하지 않는다).
      inFlightRef.current = true;
      mutate(qrPayload);
    },
    [storeId, mutate],
  );

  const toErrorMessage = () => {
    if (meQuery.isError) return ME_ERROR_MESSAGE;
    if (meQuery.isSuccess && storeId === undefined) return NO_STORE_MESSAGE;
    if (checkInMutation.error) return toCheckInErrorMessage(checkInMutation.error);
    return null;
  };

  return {
    handleScan,
    isChecking: checkInMutation.isPending,
    errorMessage: toErrorMessage(),
  };
};
