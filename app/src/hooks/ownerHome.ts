import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { getMe } from "../services/auth";
import type { Order } from "../services/order";
import { MOCK_ORDERS, ORDERS_QUERY_KEY } from "../services/order";
import { getStoreDetail, mockStoreDetail } from "../services/store";
import { toLocalDateTime } from "../utils/format";
import { isOpenAt } from "../utils/openStatus";
import type { CheckInResultState } from "./checkInResult";
import { CHECK_IN_RESULT_PATH } from "./qrCheckIn";

/** VITE_STORE_MOCK=true 면 백엔드 없이 목업 가게로 화면을 본다(hooks/storeManage.ts 와 같다). */
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";
const MOCK_LINKED_STORE = { storeId: 0, name: "맛나 치킨" };

const CANCEL_NOT_READY_MESSAGE = "주문 취소는 아직 준비 중이에요.";

/** 영업시간대로 볼 때 영업 상태를 다시 계산하는 간격(ms). */
const CLOCK_TICK_MS = 30_000;

/** 영업시간대로(가게 관리의 영업시간을 따름) · 직접 켜고 끄기. */
export type OpenMode = "SCHEDULE" | "MANUAL";

type OpenSetting = { mode: OpenMode; manualOpen: boolean };

/*
  TODO(백엔드 영업 상태 API): 영업 상태를 저장하는 API 가 없어 고른 방법·켜고 끈 값은 앱 안(react-query 캐시)에만 둔다.
  탭을 오가도 남지만 앱을 다시 켜면 처음(영업시간대로)으로 돌아가고, 손님 쪽에는 전해지지 않는다.
*/
const OPEN_SETTING_QUERY_KEY = ["open-setting"];
const DEFAULT_OPEN_SETTING: OpenSetting = { mode: "SCHEDULE", manualOpen: true };

export type UseOwnerHomeResult = {
  isLoading: boolean;
  /** 불러오지 못했을 때의 문구. 연결된 가게가 없을 때도 여기 담긴다. */
  loadErrorMessage: string | null;
  storeName: string;
  addressRoad: string;
  /** 지금 영업 중인지. 영업시간대로면 영업시간과 기기 시각으로, 직접이면 켜고 끈 대로. */
  isOpen: boolean;
  openMode: OpenMode;
  setOpenMode: (mode: OpenMode) => void;
  /** 직접 켜고 끄기의 스위치. */
  toggleOpen: () => void;
  waitingCount: number;
  doneCount: number;
  /** 가장 최근에 들어온 대기 주문. 없으면 null — 화면은 카드를 그리지 않는다. */
  latestOrder: Order | null;
  /** 최근 주문의 "직접 확인": 체크인 결과 화면으로 간다. */
  checkInLatest: () => void;
  cancelLatest: () => void;
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
 * 영업시간대로의 영업 상태를 보여 주고, 오늘 주문 수와 최근 대기 주문 한 건을 보여 준다.
 *
 * 주문은 API 가 없어 주문 화면과 같은 목업을 같은 react-query 키로 쓴다(services/order.ts).
 */
export const useOwnerHome = (): UseOwnerHomeResult => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const now = useNow();
  const [notice, setNotice] = useState<string | null>(null);

  const meQuery = useQuery({ queryKey: ["me"], queryFn: () => getMe(), enabled: !USE_STORE_MOCK });
  const linkedStore = USE_STORE_MOCK ? MOCK_LINKED_STORE : meQuery.data?.stores[0];

  // 가게 관리에서 저장한 영업시간이 바로 보이도록 홈에 들어올 때마다 다시 불러온다(기본값).
  const storeQuery = useQuery({
    queryKey: ["store-detail", linkedStore?.storeId],
    enabled: linkedStore !== undefined,
    queryFn: () =>
      USE_STORE_MOCK
        ? mockStoreDetail(linkedStore!.storeId, linkedStore!.name)
        : getStoreDetail(linkedStore!.storeId),
  });

  const { data: openSetting = DEFAULT_OPEN_SETTING } = useQuery({
    queryKey: OPEN_SETTING_QUERY_KEY,
    queryFn: () => DEFAULT_OPEN_SETTING,
    initialData: DEFAULT_OPEN_SETTING,
    staleTime: Infinity,
    gcTime: Infinity,
  });
  const saveOpenSetting = (next: OpenSetting) => queryClient.setQueryData(OPEN_SETTING_QUERY_KEY, next);

  // 주문 화면(hooks/ownerOrders.ts)과 같은 키·같은 설정이라 조리 시작으로 완료한 주문이 홈 수에도 반영된다.
  const { data: orders = MOCK_ORDERS } = useQuery({
    queryKey: ORDERS_QUERY_KEY,
    queryFn: () => MOCK_ORDERS,
    initialData: MOCK_ORDERS,
    staleTime: Infinity,
    gcTime: Infinity,
  });

  const store = storeQuery.data;
  const scheduleOpen = store ? isOpenAt(store.businessHours, now) : false;
  const isOpen = openSetting.mode === "MANUAL" ? openSetting.manualOpen : scheduleOpen;

  const waiting = orders.filter((order) => order.status === "WAITING");
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
    isOpen,
    openMode: openSetting.mode,
    // 직접으로 바꿀 때는 지금 보이는 상태에서 시작해 화면이 갑자기 바뀌지 않게 한다.
    setOpenMode: (mode) => saveOpenSetting({ mode, manualOpen: mode === "MANUAL" ? isOpen : openSetting.manualOpen }),
    toggleOpen: () => saveOpenSetting({ ...openSetting, manualOpen: !openSetting.manualOpen }),
    waitingCount: waiting.length,
    doneCount: orders.length - waiting.length,
    latestOrder,
    checkInLatest: () => {
      if (!latestOrder) return;
      // 직접 확인은 서버에 남길 API 가 아직 없어 누른 시각을 확인 시각으로 넘긴다(주문 화면과 같다).
      const state: CheckInResultState = { checkedInAt: toLocalDateTime(new Date()), order: latestOrder };
      navigate(CHECK_IN_RESULT_PATH, { state });
    },
    cancelLatest: () => setNotice(CANCEL_NOT_READY_MESSAGE),
    notice,
  };
};
