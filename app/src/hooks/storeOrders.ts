import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getMe } from "../services/auth";
import type { Order, OrderAction } from "../services/order";
import { MOCK_ORDERS, ORDERS_QUERY_KEY, applyMockAction, changeOrderStatus, getStoreOrders, replaceOrder } from "../services/order";
import { toLocalDateTime } from "../utils/format";

/** VITE_STORE_MOCK=true 면 백엔드 없이 목업 주문으로 화면을 본다(hooks/storeManage.ts 와 같다). */
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";
const MOCK_STORE_ID = 0;

export type UseStoreOrdersResult = {
  /** 내 가게의 주문(최근 순). 불러오기 전이면 빈 목록. */
  orders: Order[];
  isLoading: boolean;
  /** 목록을 불러오지 못했는가. 연결된 가게가 없을 때도 true 다. */
  isLoadError: boolean;
  /** 주문 상태를 바꾼다. 실패하면 reject 한다 — 문구는 부른 쪽이 utils/orderError 로 만든다. */
  changeStatus: (order: Order, action: OrderAction) => Promise<Order>;
  /** 상태를 바꾸는 중인 주문 ID. 없으면 null — 그 카드의 버튼을 잠근다. */
  changingOrderId: number | null;
};

/**
 * 내 가게(GET /api/auth/me 의 첫 번째 가게)의 주문 목록과 상태 바꾸기. 홈·주문·체크인 결과 화면이 같이 쓴다.
 *
 * 상태를 바꾸면 돌아온 주문으로 목록을 바로 고치고, 다른 주문도 바뀌었을 수 있어 목록을 다시 불러온다.
 * 실패해도 다시 불러온다 — 409 는 다른 기기나 아이가 먼저 상태를 바꾼 것이라 화면이 낡았다.
 */
export const useStoreOrders = (): UseStoreOrdersResult => {
  const queryClient = useQueryClient();

  const meQuery = useQuery({ queryKey: ["me"], queryFn: () => getMe(), enabled: !USE_STORE_MOCK });
  const storeId = USE_STORE_MOCK ? MOCK_STORE_ID : meQuery.data?.stores[0]?.storeId;
  const queryKey = [...ORDERS_QUERY_KEY, storeId];

  const ordersQuery = useQuery({
    queryKey,
    enabled: storeId !== undefined,
    queryFn: () => (USE_STORE_MOCK ? MOCK_ORDERS : getStoreOrders(storeId!)),
    // 목업은 다시 불러오면 바꾼 상태가 처음으로 돌아가서 막는다.
    ...(USE_STORE_MOCK && { staleTime: Infinity, gcTime: Infinity }),
  });

  const mutation = useMutation({
    mutationFn: ({ order, action }: { order: Order; action: OrderAction }) =>
      USE_STORE_MOCK
        ? Promise.resolve(applyMockAction(order, action, toLocalDateTime(new Date())))
        : changeOrderStatus(storeId!, order.orderId, action),
    onSuccess: (updated) => queryClient.setQueryData<Order[]>(queryKey, (orders = []) => replaceOrder(orders, updated)),
    onSettled: () => {
      if (!USE_STORE_MOCK) void queryClient.invalidateQueries({ queryKey });
    },
  });

  const noLinkedStore = meQuery.isSuccess && storeId === undefined;

  return {
    orders: ordersQuery.data ?? [],
    isLoading: ordersQuery.isPending && !noLinkedStore && !meQuery.isError,
    isLoadError: meQuery.isError || ordersQuery.isError || noLinkedStore,
    changeStatus: (order, action) => mutation.mutateAsync({ order, action }),
    changingOrderId: mutation.isPending ? mutation.variables.order.orderId : null,
  };
};
