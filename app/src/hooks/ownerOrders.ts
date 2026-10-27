import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import type { Order, OrderStatus } from "../services/order";
import { MOCK_ORDERS, ORDERS_QUERY_KEY } from "../services/order";
import { toLocalDateTime } from "../utils/format";
import type { CheckInResultState } from "./checkInResult";
import { QR_SCAN_PATH } from "./checkInResult";
import { CHECK_IN_RESULT_PATH } from "./qrCheckIn";

const CANCEL_NOT_READY_MESSAGE = "주문 취소는 아직 준비 중이에요.";

export type UseOwnerOrdersResult = {
  /** 지금 보고 있는 하위 탭(대기·완료). */
  status: OrderStatus;
  setStatus: (status: OrderStatus) => void;
  /** 지금 탭의 주문. */
  orders: Order[];
  /** 탭 옆에 붙는 건수. */
  counts: Record<OrderStatus, number>;
  /** 체크인 방법을 고르는 중인 주문. 시트가 닫혀 있으면 null. */
  checkInTarget: Order | null;
  /** "체크인"을 누르면 방법 선택 시트를 연다. */
  openCheckIn: (order: Order) => void;
  closeCheckIn: () => void;
  /** 시트의 "QR로 확인": 고른 주문을 들고 QR 스캔 화면으로 간다. */
  checkInByQr: () => void;
  /** 시트의 "직접 확인": 주문번호로 확인했다고 보고 체크인 결과 화면으로 간다. */
  checkInManually: () => void;
  /** "주문 취소". */
  cancelOrder: (order: Order) => void;
  /** 화면 위에 잠깐 보여 줄 문구. 없으면 null. */
  notice: string | null;
};

/**
 * 점주 "주문" 탭. 대기·완료 주문을 보여 주고, 대기 주문의 체크인 방법을 고르게 한다.
 *
 * 주문 API 가 없어서(services/order.ts) 목업 주문을 보여 주고, 주문 취소는 안내 문구만 띄운다.
 * 목록은 react-query 캐시에 둬서, 체크인 결과 화면의 "조리 시작하기"가 그 주문을 완료로 옮길 수 있다.
 */
export const useOwnerOrders = (): UseOwnerOrdersResult => {
  const navigate = useNavigate();
  const [status, setStatus] = useState<OrderStatus>("WAITING");
  const [checkInTarget, setCheckInTarget] = useState<Order | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // 목업이라 다시 불러오거나 캐시를 비우면 완료한 주문이 대기로 되돌아간다. 그래서 둘 다 막는다.
  const { data: allOrders = MOCK_ORDERS } = useQuery({
    queryKey: ORDERS_QUERY_KEY,
    queryFn: () => MOCK_ORDERS,
    initialData: MOCK_ORDERS,
    staleTime: Infinity,
    gcTime: Infinity,
  });

  const counts: Record<OrderStatus, number> = {
    WAITING: allOrders.filter((order) => order.status === "WAITING").length,
    DONE: allOrders.filter((order) => order.status === "DONE").length,
  };

  return {
    status,
    setStatus,
    orders: allOrders.filter((order) => order.status === status),
    counts,
    checkInTarget,
    openCheckIn: setCheckInTarget,
    closeCheckIn: () => setCheckInTarget(null),
    checkInByQr: () => {
      if (!checkInTarget) return;
      navigate(QR_SCAN_PATH, { state: { order: checkInTarget } });
    },
    checkInManually: () => {
      if (!checkInTarget) return;
      // 직접 확인은 서버에 남길 API 가 아직 없어 누른 시각을 확인 시각으로 넘긴다.
      const state: CheckInResultState = { checkedInAt: toLocalDateTime(new Date()), order: checkInTarget };
      navigate(CHECK_IN_RESULT_PATH, { state });
    },
    cancelOrder: () => setNotice(CANCEL_NOT_READY_MESSAGE),
    notice,
  };
};
