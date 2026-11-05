import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import type { CheckInResponse } from "../services/checkIn";
import type { Order } from "../services/order";
import { formatHourMinute } from "../utils/format";
import { toOrderErrorMessage } from "../utils/orderError";
import type { OwnerOrdersState } from "./ownerOrders";
import { useStoreOrders } from "./storeOrders";

/** 결과 없이 들어왔을 때 돌려보낼 QR 스캔 화면. */
export const QR_SCAN_PATH = "/owner/qr";

/** "조리 시작하기"를 누른 뒤 넘어갈 점주 주문 화면. */
const AFTER_CHECK_IN_PATH = "/owner/orders";

/**
 * 결과 화면이 location.state 로 받는 값.
 * - QR 체크인: 체크인 시각과, 서버가 찾아 준 그 아동의 접수 대기 주문. 주문 없이 왔거나 이미 조리 중이면 주문이 없다
 * - 주문 화면의 "직접 확인": 누른 시각과 고른 주문
 */
export type CheckInResultState = Pick<CheckInResponse, "checkedInAt"> & { order?: Order };

export type UseCheckInResultResult = {
  /** 확인 시각("12:31"). 결과 없이 들어왔으면 null — 화면은 QR 스캔으로 돌려보낸다. */
  checkedAt: string | null;
  /** 체크인한 주문. 없으면(주문 없이 온 아동) null. */
  order: Order | null;
  /**
   * 아래 버튼. 주문이 있으면 "조리 시작하기" — 조리를 시작하고(POST start-cooking) 주문 화면의 조리 중 탭으로 간다.
   * 없으면 "주문 목록 보기" — 주문 화면으로만 간다.
   */
  startCooking: () => void;
  /** 조리 시작 요청 중이면 true. 버튼을 잠근다. */
  isStarting: boolean;
  /** 조리 시작 실패 문구. 없으면 null. */
  errorMessage: string | null;
};

/**
 * 체크인 결과 화면. QR 스캔 화면이나 주문 화면이 location.state 로 넘긴 CheckInResultState 를 읽는다.
 *
 * 주문이 없으면(주문 없이 온 아동, 이미 조리 중) 시작할 주문이 없어 주문 화면으로만 보낸다.
 */
export const useCheckInResult = (): UseCheckInResultResult => {
  const navigate = useNavigate();
  const result = useLocation().state as CheckInResultState | null;
  const order = result?.order ?? null;
  const { changeStatus, changingOrderId } = useStoreOrders();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const goOrders = (state?: OwnerOrdersState) => navigate(AFTER_CHECK_IN_PATH, { replace: true, state });

  return {
    checkedAt: result?.checkedInAt ? formatHourMinute(result.checkedInAt) : null,
    order,
    startCooking: () => {
      if (!order) {
        goOrders();
        return;
      }
      setErrorMessage(null);
      changeStatus(order, "start-cooking")
        .then(() => goOrders({ tab: "COOKING" }))
        .catch((error: unknown) => setErrorMessage(toOrderErrorMessage(error)));
    },
    isStarting: order !== null && changingOrderId === order.orderId,
    errorMessage,
  };
};
