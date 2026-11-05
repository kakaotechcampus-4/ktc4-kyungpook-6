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
 * 결과 화면이 location.state 로 받는 값. QR 체크인은 CheckInResponse 를, 주문 화면의 "직접 확인"은 누른 시각을 넘긴다.
 * 주문 화면에서 들어왔으면 체크인한 주문(order)도 함께 넘긴다. 아래 탭 "QR 확인"으로 바로 찍었으면 주문이 없다.
 */
export type CheckInResultState = Pick<CheckInResponse, "checkedInAt"> & { order?: Order };

export type UseCheckInResultResult = {
  /** 확인 시각("12:31"). 결과 없이 들어왔으면 null — 화면은 QR 스캔으로 돌려보낸다. */
  checkedAt: string | null;
  /** 체크인한 주문. 주문 화면을 거치지 않았으면 null. */
  order: Order | null;
  /** "조리 시작하기". 주문이 있으면 조리를 시작하고(POST start-cooking) 주문 화면의 조리 중 탭으로 간다. */
  startCooking: () => void;
  /** 조리 시작 요청 중이면 true. 버튼을 잠근다. */
  isStarting: boolean;
  /** 조리 시작 실패 문구. 없으면 null. */
  errorMessage: string | null;
};

/**
 * 체크인 결과 화면. QR 스캔 화면이나 주문 화면이 location.state 로 넘긴 CheckInResultState 를 읽는다.
 *
 * 체크인 API 는 주문 정보를 주지 않아서(백엔드 PR #50) 주문 정보는 주문 화면이 넘긴 것을 쓴다.
 * 주문 없이(아래 탭 "QR 확인") 들어왔으면 어느 주문을 시작할지 몰라 주문 화면으로만 보낸다.
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
