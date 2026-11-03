import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { CHILD_ACTIVE_ORDER_QUERY_KEY, MOCK_CHILD_ACTIVE_ORDER, isOrderFinished } from "../services/childQr";
import type { ChildActiveOrder } from "../services/childQr";

/** 2시간이 지났는지 다시 보는 주기. */
const CLOCK_TICK_MS = 60_000;

/* 아동 가게 목록. "가게 찾아보기"로 간다. */
const STORE_LIST_PATH = "/child/stores";

export type UseChildQrResult = {
  /** 진행 중인 주문. 없거나 취소했거나 조리 시작 뒤 2시간이 지났으면 null — 화면은 빈 안내를 보여 준다. */
  order: ChildActiveOrder | null;
  /** 조리가 시작되기 전에만 취소할 수 있다. */
  canCancel: boolean;
  cancelOrder: () => void;
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

/**
 * 아동 내 QR. 점주가 찍을 QR 과 진행 중인 주문의 상태를 보여 준다.
 * 주문은 API 가 없어 목업을 react-query 캐시에 두고 쓴다(services/childQr.ts).
 */
export const useChildQr = (): UseChildQrResult => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const now = useNow();
  const { data: storedOrder = null } = useQuery<ChildActiveOrder | null>({
    queryKey: CHILD_ACTIVE_ORDER_QUERY_KEY,
    queryFn: () => MOCK_CHILD_ACTIVE_ORDER,
    initialData: MOCK_CHILD_ACTIVE_ORDER,
    staleTime: Infinity,
    gcTime: Infinity,
  });

  const order = storedOrder && !isOrderFinished(storedOrder, now) ? storedOrder : null;
  const canCancel = order?.status === "RECEIVED";

  return {
    order,
    canCancel,
    cancelOrder: () => {
      if (!canCancel) return;
      queryClient.setQueryData(CHILD_ACTIVE_ORDER_QUERY_KEY, null);
    },
    goStores: () => navigate(STORE_LIST_PATH),
  };
};
