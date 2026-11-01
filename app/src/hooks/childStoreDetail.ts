import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { findMockChildStore } from "../services/childStore";
import type { ChildStore } from "../services/childStore";
import type { BusinessDay } from "../services/store";
import { dayOf, getChildStoreOpenStatus } from "../utils/childStoreStatus";
import type { ChildStoreOpenStatus } from "../utils/childStoreStatus";
import { useNow } from "./childStoreList";

export type UseChildStoreDetailResult = {
  /** 주소의 번호에 맞는 가게. 없는 번호면 null — 화면은 가게 목록으로 돌려보낸다. */
  store: ChildStore | null;
  openStatus: ChildStoreOpenStatus;
  /** 오늘 요일. 영업시간 목록에서 굵게 보인다. */
  today: BusinessDay;
  /** 영업시간 줄을 눌러 요일별 목록을 펼쳤는지. */
  isHoursExpanded: boolean;
  toggleHours: () => void;
  /** 영업중일 때만 주문할 수 있다. */
  canOrder: boolean;
  goOrder: () => void;
};

/**
 * 아동 가게 정보. 가게 목록에서 누른 가게(/child/stores/:storeId)를 보여 준다.
 * 가게는 API 가 없어 목록과 같은 목업을 쓴다(services/childStore.ts).
 */
export const useChildStoreDetail = (): UseChildStoreDetailResult => {
  const navigate = useNavigate();
  const { storeId } = useParams();
  const now = useNow();
  const [isHoursExpanded, setIsHoursExpanded] = useState(false);

  const store = findMockChildStore(Number(storeId)) ?? null;
  const openStatus = store ? getChildStoreOpenStatus(store, now) : "ENDED";
  const canOrder = openStatus === "OPEN";

  return {
    store,
    openStatus,
    today: dayOf(now),
    isHoursExpanded,
    toggleHours: () => setIsHoursExpanded((expanded) => !expanded),
    canOrder,
    goOrder: () => {
      if (!store || !canOrder) return;
      navigate(`/child/stores/${store.storeId}/order`);
    },
  };
};
