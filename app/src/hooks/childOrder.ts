import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { findMockChildStore } from "../services/childStore";
import type { ChildStore } from "../services/childStore";
import type { OrderReceiveType } from "../services/order";
import { getChildStoreOpenStatus } from "../utils/childStoreStatus";
import { useNow } from "./childStoreList";
import { clearChildHomeView } from "./childHomeView";

/*
  주문한 뒤 넘어갈 주문 완료 화면.
  주문 API 가 아직 없어서, 주문하기를 누르면 서버를 부르지 않고 바로 넘긴다. API 가 생기면 submit 만 바꾼다.
*/
const AFTER_ORDER_PATH = "/child/order/complete";

export type UseChildOrderResult = {
  /** 주소의 번호에 맞는 가게. 없는 번호면 null — 화면은 가게 목록으로 돌려보낸다. */
  store: ChildStore | null;
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
  /** 메뉴를 골랐을 때만 true. */
  canSubmit: boolean;
  submit: () => void;
};

/**
 * 아동 주문. 메뉴 하나와 받는 방법(포장·매장)을 고른다.
 * 가게는 API 가 없어 목록과 같은 목업을 쓴다(services/childStore.ts).
 */
export const useChildOrder = (): UseChildOrderResult => {
  const navigate = useNavigate();
  const { storeId } = useParams();
  const now = useNow();
  const store = findMockChildStore(Number(storeId)) ?? null;

  const isReceiveTypeAvailable = (type: OrderReceiveType) =>
    store !== null && (type === "TAKEOUT" ? store.takeoutAvailable : store.dineInAvailable);

  const [selectedMenuId, setSelectedMenuId] = useState<number | null>(null);
  // 포장이 안 되는 가게는 매장으로 시작한다.
  const [receiveType, setReceiveType] = useState<OrderReceiveType>(() =>
    store && !store.takeoutAvailable ? "DINE_IN" : "TAKEOUT",
  );

  const isOpen = store !== null && getChildStoreOpenStatus(store, now) === "OPEN";
  const canSubmit = isOpen && selectedMenuId !== null;

  return {
    store,
    isOpen,
    selectedMenuId,
    selectMenu: (menuId) => {
      if (store?.menus.find((menu) => menu.menuId === menuId)?.onSale) setSelectedMenuId(menuId);
    },
    receiveType,
    selectReceiveType: (type) => {
      if (isReceiveTypeAvailable(type)) setReceiveType(type);
    },
    isReceiveTypeAvailable,
    canSubmit,
    submit: () => {
      if (!canSubmit) return;
      // 주문을 마쳤으니 홈은 다음에 처음 화면(검색어·칩 없음, 내 위치)으로 시작한다.
      clearChildHomeView();
      navigate(AFTER_ORDER_PATH, { replace: true });
    },
  };
};
