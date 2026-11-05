import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import type { Order, OrderAction, OrderTab } from "../services/order";
import { orderTabOf, sortByFinishedDesc } from "../services/order";
import { toLocalDateTime } from "../utils/format";
import { toOrderErrorMessage } from "../utils/orderError";
import type { CheckInResultState } from "./checkInResult";
import { QR_SCAN_PATH } from "./checkInResult";
import { CHECK_IN_RESULT_PATH } from "./qrCheckIn";
import { useStoreOrders } from "./storeOrders";

const LOAD_ERROR_MESSAGE = "주문을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";

/** 다른 화면이 주문 화면으로 보낼 때 고를 탭. 체크인 결과 화면은 "조리 시작하기" 뒤 조리 중 탭을 연다. */
export type OwnerOrdersState = { tab?: OrderTab };

export type UseOwnerOrdersResult = {
  /** 지금 보고 있는 하위 탭(접수 대기·조리 중·완료). */
  tab: OrderTab;
  setTab: (tab: OrderTab) => void;
  /** 지금 탭의 주문. */
  orders: Order[];
  /** 탭 옆에 붙는 건수. */
  counts: Record<OrderTab, number>;
  isLoading: boolean;
  /** 체크인 방법을 고르는 중인 주문. 시트가 닫혀 있으면 null. */
  checkInTarget: Order | null;
  /** "체크인"을 누르면 방법 선택 시트를 연다. */
  openCheckIn: (order: Order) => void;
  closeCheckIn: () => void;
  /** 시트의 "QR로 확인": QR 스캔 화면으로 간다. 주문은 체크인 응답이 그 아동의 것으로 알려 준다. */
  checkInByQr: () => void;
  /** 시트의 "직접 확인": 주문번호로 확인했다고 보고 체크인 결과 화면으로 간다. */
  checkInManually: () => void;
  /** "주문 취소"(접수 대기). */
  cancelOrder: (order: Order) => void;
  /** "조리 완료"(조리 중). */
  completeOrder: (order: Order) => void;
  /** 상태를 바꾸는 중인 주문 ID. 그 카드의 버튼을 잠근다. */
  changingOrderId: number | null;
  /** 화면 위에 보여 줄 문구(불러오기·처리 실패). 없으면 null. */
  notice: string | null;
};

/**
 * 점주 "주문" 탭. 접수 대기·조리 중·완료 주문을 보여 주고, 접수 대기 주문은 체크인·취소, 조리 중 주문은 조리 완료를 한다.
 * 완료 탭에는 전달 완료와 취소한 주문이 함께 들어간다(피그마 "주문 -완료").
 */
export const useOwnerOrders = (): UseOwnerOrdersResult => {
  const navigate = useNavigate();
  const initialTab = (useLocation().state as OwnerOrdersState | null)?.tab ?? "RECEIVED";
  const [tab, setTab] = useState<OrderTab>(initialTab);
  const [checkInTarget, setCheckInTarget] = useState<Order | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const { orders, isLoading, isLoadError, changeStatus, changingOrderId } = useStoreOrders();

  const counts: Record<OrderTab, number> = { RECEIVED: 0, COOKING: 0, DONE: 0 };
  for (const order of orders) counts[orderTabOf(order.status)] += 1;

  const tabOrders = orders.filter((order) => orderTabOf(order.status) === tab);

  const act = (order: Order, action: OrderAction) => {
    setActionError(null);
    changeStatus(order, action).catch((error: unknown) => setActionError(toOrderErrorMessage(error)));
  };

  return {
    tab,
    setTab,
    orders: tab === "DONE" ? sortByFinishedDesc(tabOrders) : tabOrders,
    counts,
    isLoading,
    checkInTarget,
    openCheckIn: setCheckInTarget,
    closeCheckIn: () => setCheckInTarget(null),
    checkInByQr: () => {
      if (!checkInTarget) return;
      navigate(QR_SCAN_PATH);
    },
    checkInManually: () => {
      if (!checkInTarget) return;
      // 직접 확인은 따로 남길 API 가 없어 누른 시각을 확인 시각으로 넘긴다. 조리 시작은 결과 화면에서 한다.
      const state: CheckInResultState = { checkedInAt: toLocalDateTime(new Date()), order: checkInTarget };
      navigate(CHECK_IN_RESULT_PATH, { state });
    },
    cancelOrder: (order) => act(order, "cancel"),
    completeOrder: (order) => act(order, "complete"),
    changingOrderId,
    notice: isLoadError ? LOAD_ERROR_MESSAGE : actionError,
  };
};
