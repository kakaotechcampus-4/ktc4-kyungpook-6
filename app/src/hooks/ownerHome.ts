import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { getMe } from "../services/auth";
import type { Order } from "../services/order";
import type { OpenMode, OpenSetting, StoreDetailResponse } from "../services/store";
import { getStoreDetail, mockStoreDetail, saveOpenSetting } from "../services/store";
import { toLocalDateTime } from "../utils/format";
import type { ScheduleStatus } from "../utils/openStatus";
import { getScheduleStatus } from "../utils/openStatus";
import { toOrderErrorMessage } from "../utils/orderError";
import type { CheckInResultState } from "./checkInResult";
import { CHECK_IN_RESULT_PATH } from "./qrCheckIn";
import { useStoreOrders } from "./storeOrders";

/** VITE_STORE_MOCK=true 면 백엔드 없이 목업 가게로 화면을 본다(hooks/storeManage.ts 와 같다). */
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";
const MOCK_LINKED_STORE = { storeId: 0, name: "맛나 치킨" };

/** 영업시간대로 볼 때 영업 상태를 다시 계산하는 간격(ms). */
const CLOCK_TICK_MS = 30_000;

const OPEN_SETTING_ERROR_MESSAGE = "영업 상태를 저장하지 못했어요. 잠시 후 다시 시도해 주세요.";

/** 영업시간대로일 때 카드에 보이는 상태. 피그마 "div.status-card". 오늘 영업시간이 하나도 없으면 휴무중이다. */
const SCHEDULE_LABEL: Record<ScheduleStatus, string> = {
  OPEN: "영업중",
  BEFORE_OPEN: "영업전",
  ENDED: "영업종료",
  CLOSED_TODAY: "휴무중",
};

export type UseOwnerHomeResult = {
  isLoading: boolean;
  /** 불러오지 못했을 때의 문구. 연결된 가게가 없을 때도 여기 담긴다. */
  loadErrorMessage: string | null;
  storeName: string;
  addressRoad: string;
  /**
   * 카드의 큰 글자. 영업시간대로면 영업시간과 기기 시각으로 영업전·영업중·영업종료·휴무중,
   * 직접 켜고 끄기면 주문 ON·주문 OFF(OFF 면 아동 앱에 "쉬는중").
   */
  statusLabel: string;
  openMode: OpenMode;
  /** 직접 켜고 끄기의 스위치가 켜져 있는가. */
  manualOpen: boolean;
  /** 영업 상태를 저장하는 중이면 true. 스위치·방식 버튼을 잠근다. */
  isSavingOpenSetting: boolean;
  setOpenMode: (mode: OpenMode) => void;
  /** 직접 켜고 끄기의 스위치. */
  toggleOpen: () => void;
  /** 접수 대기 주문 수. */
  waitingCount: number;
  /** 조리 중 주문 수. */
  cookingCount: number;
  /** 전달 완료 주문 수. 취소는 세지 않는다. */
  doneCount: number;
  /** 가장 최근에 들어온 대기 주문. 없으면 null — 화면은 카드를 그리지 않는다. */
  latestOrder: Order | null;
  /** 최근 주문의 "직접 확인": 체크인 결과 화면으로 간다. */
  checkInLatest: () => void;
  /** 최근 주문의 "주문 취소"(POST cancel). */
  cancelLatest: () => void;
  /** 취소하는 중이면 true. 최근 주문 카드의 버튼을 잠근다. */
  isCancelingLatest: boolean;
  notice: string | null;
};

/** 기기 시각. 영업시간대로 볼 때 시간이 지나면 영업 상태가 바뀌도록 주기적으로 새로 읽는다. */
const useNow = () => {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), CLOCK_TICK_MS);
    return () => window.clearInterval(id);
  }, []);
  return now;
};

/**
 * 점주 홈. 내 정보(GET /api/auth/me)의 첫 번째 가게를 불러와(GET /api/stores/{storeId}) 상호명·주소와
 * 영업 상태를 보여 주고, 오늘 주문 수와 최근 대기 주문 한 건을 보여 준다.
 *
 * 영업 상태 방식·켜고 끈 값은 가게 상세의 openMode·manualOpen 으로 읽고, 바꾸면 PUT open-setting 으로 저장한다.
 * 화면은 먼저 바꿔 두고, 저장에 실패하면 문구를 띄우고 서버 값으로 되돌린다.
 *
 * 주문은 주문 화면과 같은 목록을 쓴다(hooks/storeOrders.ts).
 */
export const useOwnerHome = (): UseOwnerHomeResult => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const now = useNow();
  const [notice, setNotice] = useState<string | null>(null);

  const meQuery = useQuery({ queryKey: ["me"], queryFn: () => getMe(), enabled: !USE_STORE_MOCK });
  const linkedStore = USE_STORE_MOCK ? MOCK_LINKED_STORE : meQuery.data?.stores[0];

  // 가게 관리에서 저장한 영업시간이 바로 보이도록 홈에 들어올 때마다 다시 불러온다(기본값).
  const storeQueryKey = ["store-detail", linkedStore?.storeId];
  const storeQuery = useQuery({
    queryKey: storeQueryKey,
    enabled: linkedStore !== undefined,
    queryFn: () =>
      USE_STORE_MOCK
        ? mockStoreDetail(linkedStore!.storeId, linkedStore!.name)
        : getStoreDetail(linkedStore!.storeId),
    // 목업은 다시 불러오면 바꾼 영업 상태가 처음으로 돌아가서 막는다.
    ...(USE_STORE_MOCK && { staleTime: Infinity }),
  });

  const writeOpenSetting = ({ mode, manualOpen }: OpenSetting) =>
    queryClient.setQueryData<StoreDetailResponse>(storeQueryKey, (store) =>
      store ? { ...store, openMode: mode, manualOpen } : store,
    );

  const openSettingMutation = useMutation({
    mutationFn: (next: OpenSetting) =>
      USE_STORE_MOCK ? Promise.resolve(next) : saveOpenSetting(linkedStore!.storeId, next),
    onMutate: (next) => {
      setNotice(null);
      writeOpenSetting(next);
    },
    onSuccess: writeOpenSetting,
    onError: () => {
      setNotice(OPEN_SETTING_ERROR_MESSAGE);
      void queryClient.invalidateQueries({ queryKey: storeQueryKey });
    },
  });
  const changeOpenSetting = (next: OpenSetting) => openSettingMutation.mutate(next);

  // 주문 화면과 같은 목록이라 거기서 바꾼 상태가 홈 수에도 바로 반영된다.
  const { orders, changeStatus, changingOrderId } = useStoreOrders();

  const store = storeQuery.data;
  const openMode = store?.openMode ?? "SCHEDULE";
  const manualOpen = store?.manualOpen ?? true;
  const scheduleStatus = store ? getScheduleStatus(store.businessHours, now) : "ENDED";
  const statusLabel =
    openMode === "MANUAL" ? (manualOpen ? "주문 ON" : "주문 OFF") : SCHEDULE_LABEL[scheduleStatus];

  const waiting = orders.filter((order) => order.status === "RECEIVED");
  // orderedAt 은 같은 모양의 LocalDateTime 문자열이라 글자 순서가 곧 시간 순서다.
  const latestOrder = waiting.reduce<Order | null>(
    (latest, order) => (latest === null || order.orderedAt > latest.orderedAt ? order : latest),
    null,
  );

  const loadError = meQuery.error ?? storeQuery.error;
  let loadErrorMessage: string | null = null;
  if (loadError) loadErrorMessage = "가게 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";
  else if (meQuery.data && !linkedStore) loadErrorMessage = "연결된 가게가 없어요.";

  return {
    isLoading: !store && !loadErrorMessage,
    loadErrorMessage,
    storeName: store?.name ?? "",
    addressRoad: store?.addressRoad ?? "",
    statusLabel,
    openMode,
    manualOpen,
    isSavingOpenSetting: openSettingMutation.isPending,
    setOpenMode: (mode) => {
      if (mode === openMode) return;
      // 직접으로 바꿀 때는 지금 영업중인지에서 시작해 손님 쪽 상태가 갑자기 바뀌지 않게 한다.
      // 영업시간대로로 바꿀 때는 켜고 끈 값을 그대로 보낸다(서버가 기억해 둔다).
      changeOpenSetting({ mode, manualOpen: mode === "MANUAL" ? scheduleStatus === "OPEN" : manualOpen });
    },
    toggleOpen: () => changeOpenSetting({ mode: openMode, manualOpen: !manualOpen }),
    waitingCount: waiting.length,
    cookingCount: orders.filter((order) => order.status === "COOKING").length,
    doneCount: orders.filter((order) => order.status === "DONE").length,
    latestOrder,
    checkInLatest: () => {
      if (!latestOrder) return;
      // 직접 확인은 따로 남길 API 가 없어 누른 시각을 확인 시각으로 넘긴다(주문 화면과 같다).
      const state: CheckInResultState = { checkedInAt: toLocalDateTime(new Date()), order: latestOrder };
      navigate(CHECK_IN_RESULT_PATH, { state });
    },
    cancelLatest: () => {
      if (!latestOrder) return;
      setNotice(null);
      changeStatus(latestOrder, "cancel").catch((error: unknown) => setNotice(toOrderErrorMessage(error)));
    },
    isCancelingLatest: latestOrder !== null && changingOrderId === latestOrder.orderId,
    notice,
  };
};
