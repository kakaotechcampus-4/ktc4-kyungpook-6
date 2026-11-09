import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router-dom";
import { createChildOrder } from "../services/childOrder";
import type { ChildOrderRequest } from "../services/childOrder";
import { CHILD_ACTIVE_ORDER_QUERY_KEY } from "../services/childQr";
import type { ChildStore } from "../services/childStore";
import type { OrderReceiveType } from "../services/order";
import { toChildOrderErrorMessage } from "../utils/childOrderError";
import { getChildStoreOpenStatus } from "../utils/childStoreStatus";
import { useChildStore } from "./childNearbyStores";
import { useNow } from "./childStoreList";
import { clearChildHomeView } from "./childHomeView";

/** VITE_STORE_MOCK=true 면 서버를 부르지 않고 주문 완료로 넘긴다(목업 가게는 서버에 없다). */
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";

/** 주문한 뒤 넘어갈 주문 완료 화면. */
const AFTER_ORDER_PATH = "/child/order/complete";

export type UseChildOrderResult = {
  /** 주소의 번호에 맞는 가게. 없는 번호면 null — 화면은 가게 목록으로 돌려보낸다. */
  store: ChildStore | null;
  /** 가게를 불러오는 중. 이때는 돌려보내지 않는다. */
  isLoading: boolean;
  /** 영업중인지. 아니면 화면은 가게 정보로 돌려보낸다(주소로 바로 들어온 경우). */
  isOpen: boolean;
  /** 고른 메뉴. 아직 안 골랐으면 null. */
  selectedMenuId: number | null;
  /** 판매하지 않는 메뉴는 고를 수 없다. */
  selectMenu: (menuId: number) => void;
  receiveType: OrderReceiveType;
  /** 가게가 받지 않는 방법은 고를 수 없다. */
  selectReceiveType: (type: OrderReceiveType) => void;
  isReceiveTypeAvailable: (type: OrderReceiveType) => boolean;
  /** 메뉴를 골랐고 요청 중이 아닐 때만 true. */
  canSubmit: boolean;
  submit: () => void;
  /** 주문 실패 문구. 메뉴나 받는 방법을 다시 고르면 지운다. */
  errorMessage: string | null;
};

/**
 * 아동 주문. 메뉴 하나와 받는 방법(포장·매장)을 골라 주문한다(POST /api/children/me/orders).
 * 주문하면 내 QR 의 진행 중 주문을 다시 불러오게 하고 주문 완료로 간다.
 * 가게 상세 API 가 없어 주변 가게 목록에서 찾는다(hooks/childNearbyStores.ts).
 */
export const useChildOrder = (): UseChildOrderResult => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { storeId } = useParams();
  const now = useNow();
  const { store, isLoading } = useChildStore(Number(storeId));

  const isReceiveTypeAvailable = (type: OrderReceiveType) =>
    store !== null && (type === "TAKEOUT" ? store.takeoutAvailable : store.dineInAvailable);

  const [selectedMenuId, setSelectedMenuId] = useState<number | null>(null);
  // 포장으로 시작하되, 가게가 포장을 안 받으면 매장으로 본다. 가게는 처음 그릴 때 아직 없을 수 있어 그릴 때마다 맞춘다.
  const [chosenReceiveType, setReceiveType] = useState<OrderReceiveType>("TAKEOUT");
  const receiveType: OrderReceiveType = isReceiveTypeAvailable(chosenReceiveType)
    ? chosenReceiveType
    : chosenReceiveType === "TAKEOUT"
      ? "DINE_IN"
      : "TAKEOUT";

  const orderMutation = useMutation({
    mutationFn: async (payload: ChildOrderRequest) => {
      if (!USE_STORE_MOCK) await createChildOrder(payload);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: CHILD_ACTIVE_ORDER_QUERY_KEY });
      // 주문을 마쳤으니 홈은 다음에 처음 화면(검색어·칩 없음, 내 위치)으로 시작한다.
      clearChildHomeView();
      navigate(AFTER_ORDER_PATH, { replace: true });
    },
  });
  const clearError = () => {
    if (orderMutation.isError) orderMutation.reset();
  };

  const isOpen = store !== null && getChildStoreOpenStatus(store, now) === "OPEN";
  const canSubmit = isOpen && selectedMenuId !== null && !orderMutation.isPending;

  return {
    store,
    isLoading,
    isOpen,
    selectedMenuId,
    selectMenu: (menuId) => {
      if (!store?.menus.find((menu) => menu.menuId === menuId)?.onSale) return;
      clearError();
      setSelectedMenuId(menuId);
    },
    receiveType,
    selectReceiveType: (type) => {
      if (!isReceiveTypeAvailable(type)) return;
      clearError();
      setReceiveType(type);
    },
    isReceiveTypeAvailable,
    canSubmit,
    submit: () => {
      if (!canSubmit || !store || selectedMenuId === null) return;
      orderMutation.mutate({ storeId: store.storeId, menuId: selectedMenuId, receiveType });
    },
    errorMessage: orderMutation.isError ? toChildOrderErrorMessage(orderMutation.error) : null,
  };
};
