import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  CHILD_ACTIVE_ORDER_QUERY_KEY,
  MOCK_CHILD_ACTIVE_ORDER,
  cancelChildOrder,
  getActiveChildOrder,
  reconcileActiveOrder,
} from "../services/childQr";
import type { ChildActiveOrder } from "../services/childQr";
import { getOrIssueQrPayload } from "../services/childQrToken";
import { toChildOrderErrorType } from "../utils/childOrderError";

/** VITE_STORE_MOCK=true 면 서버 없이 시안의 주문을 보여 준다. */
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";

/** 점주가 QR 을 찍었는지(조리 중), 조리를 끝냈는지(조리 완료) 다시 보는 주기. */
const STATUS_POLL_MS = 10_000;

/* 아동 가게 목록. "가게 찾아보기"로 간다. */
const STORE_LIST_PATH = "/child/stores";

export type UseChildQrResult = {
  /** 처음 불러오는 중. 빈 안내를 잠깐 보여 주지 않게 한다. */
  isLoading: boolean;
  /** 진행 중인 주문(또는 방금 조리 완료된 주문). 없거나 취소했으면 null — 화면은 빈 안내를 보여 준다. */
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

const toCancelErrorMessage = (error: unknown) =>
  toChildOrderErrorType(error) === "order-status-conflict"
    ? "이미 조리가 시작돼서 취소할 수 없어요."
    : "주문을 취소하지 못했어요. 잠시 후 다시 시도해 주세요.";

/**
 * 아동 내 QR. 점주가 찍을 QR 과 진행 중인 주문의 상태를 보여 준다.
 * 진행 중 주문은 GET /api/children/me/orders/active 로 읽고, 조리 완료 전까지 주기적으로 다시 읽는다.
 * 조리 중이던 주문이 사라지면 조리 완료로 보여 준다(services/childQr.ts 의 reconcileActiveOrder).
 * 취소는 POST /api/children/me/orders/{orderId}/cancel.
 * QR 값은 처음 한 번 발급받아 폰에 저장해 두고 계속 쓴다(services/childQrToken.ts).
 */
export const useChildQr = (): UseChildQrResult => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const orderQuery = useQuery<ChildActiveOrder | null>({
    queryKey: CHILD_ACTIVE_ORDER_QUERY_KEY,
    queryFn: async () => {
      if (USE_STORE_MOCK) return MOCK_CHILD_ACTIVE_ORDER;
      const previous = queryClient.getQueryData<ChildActiveOrder | null>(CHILD_ACTIVE_ORDER_QUERY_KEY);
      return reconcileActiveOrder(previous, await getActiveChildOrder());
    },
    ...(USE_STORE_MOCK && { staleTime: Infinity, gcTime: Infinity }),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return !USE_STORE_MOCK && (status === "RECEIVED" || status === "COOKING") ? STATUS_POLL_MS : false;
    },
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

  const order = orderQuery.data ?? null;
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
