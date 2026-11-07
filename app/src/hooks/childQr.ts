import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  CHILD_ACTIVE_ORDER_QUERY_KEY,
  MOCK_CHILD_ACTIVE_ORDER,
  cancelChildOrder,
  getActiveChildOrder,
  isOrderFinished,
} from "../services/childQr";
import type { ChildActiveOrder } from "../services/childQr";
import { getOrIssueQrPayload } from "../services/childQrToken";
import { toChildOrderErrorType } from "../utils/childOrderError";

/** VITE_STORE_MOCK=true 면 서버 없이 시안의 주문을 보여 준다. */
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";

/** 2시간이 지났는지 다시 보는 주기. */
const CLOCK_TICK_MS = 60_000;

/** 접수 대기 중에 점주가 QR 을 찍었는지(조리 중이 됐는지) 다시 보는 주기. */
const RECEIVED_POLL_MS = 10_000;

/* 아동 가게 목록. "가게 찾아보기"로 간다. */
const STORE_LIST_PATH = "/child/stores";

export type UseChildQrResult = {
  /** 처음 불러오는 중. 빈 안내를 잠깐 보여 주지 않게 한다. */
  isLoading: boolean;
  /** 진행 중인 주문. 없거나 취소했거나 조리 시작 뒤 2시간이 지났으면 null — 화면은 빈 안내를 보여 준다. */
  order: ChildActiveOrder | null;
  /**
   * QR 에 담을 값. 목업이면 null(시안 그림을 보여 준다). 받는 중이거나 받지 못했어도 null.
   * 진행 중 주문이 있고 조리 전일 때만 받는다.
   */
  qrPayload: string | null;
  /** 조리가 시작되기 전이고 요청 중이 아닐 때만 취소할 수 있다. */
  canCancel: boolean;
  cancelOrder: () => void;
  /** 불러오기·취소 실패 문구. 없으면 null. */
  errorMessage: string | null;
  goStores: () => void;
};

/** 기기 시각. 화면을 켜 둔 채 2시간이 지나도 비워지도록 주기적으로 새로 읽는다. */
const useNow = () => {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), CLOCK_TICK_MS);
    return () => window.clearInterval(id);
  }, []);
  return now;
};

const toCancelErrorMessage = (error: unknown) =>
  toChildOrderErrorType(error) === "order-status-conflict"
    ? "이미 조리가 시작돼서 취소할 수 없어요."
    : "주문을 취소하지 못했어요. 잠시 후 다시 시도해 주세요.";

/**
 * 아동 내 QR. 점주가 찍을 QR 과 진행 중인 주문의 상태를 보여 준다.
 * 진행 중 주문은 GET /api/children/me/orders/active 로 읽고, 접수 대기 중에는 점주가 찍었는지 주기적으로 다시 읽는다.
 * 취소는 POST /api/children/me/orders/{orderId}/cancel.
 * QR 값은 처음 한 번 발급받아 폰에 저장해 두고 계속 쓴다(services/childQrToken.ts).
 */
export const useChildQr = (): UseChildQrResult => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const now = useNow();
  const orderQuery = useQuery<ChildActiveOrder | null>({
    queryKey: CHILD_ACTIVE_ORDER_QUERY_KEY,
    queryFn: () => (USE_STORE_MOCK ? MOCK_CHILD_ACTIVE_ORDER : getActiveChildOrder()),
    ...(USE_STORE_MOCK && { staleTime: Infinity, gcTime: Infinity }),
    refetchInterval: (query) => (!USE_STORE_MOCK && query.state.data?.status === "RECEIVED" ? RECEIVED_POLL_MS : false),
  });

  const cancelMutation = useMutation({
    mutationFn: async (orderId: number) => {
      if (!USE_STORE_MOCK) await cancelChildOrder(orderId);
    },
    onSuccess: () => queryClient.setQueryData(CHILD_ACTIVE_ORDER_QUERY_KEY, null),
    // 이미 조리가 시작됐으면 바뀐 상태를 다시 읽어 보여 준다.
    onError: () => {
      if (!USE_STORE_MOCK) void queryClient.invalidateQueries({ queryKey: CHILD_ACTIVE_ORDER_QUERY_KEY });
    },
  });

  const storedOrder = orderQuery.data ?? null;
  const order = storedOrder && !isOrderFinished(storedOrder, now) ? storedOrder : null;
  const canCancel = order?.status === "RECEIVED" && !cancelMutation.isPending;

  // 저장한 값을 읽거나 처음이면 발급받는다. 한 번 받으면 바뀌지 않으니 다시 부르지 않는다.
  const qrQuery = useQuery({
    queryKey: ["child-qr-payload"],
    enabled: !USE_STORE_MOCK && order?.status === "RECEIVED",
    queryFn: getOrIssueQrPayload,
    staleTime: Infinity,
    gcTime: Infinity,
  });

  let errorMessage: string | null = null;
  if (cancelMutation.isError) errorMessage = toCancelErrorMessage(cancelMutation.error);
  else if (orderQuery.isError) errorMessage = "주문을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";
  else if (qrQuery.isError) errorMessage = "QR을 받지 못했어요. 잠시 후 다시 열어 주세요.";

  return {
    isLoading: orderQuery.isPending,
    order,
    qrPayload: qrQuery.data ?? null,
    canCancel,
    cancelOrder: () => {
      if (!canCancel || !order) return;
      cancelMutation.mutate(order.orderId);
    },
    errorMessage,
    goStores: () => navigate(STORE_LIST_PATH),
  };
};
