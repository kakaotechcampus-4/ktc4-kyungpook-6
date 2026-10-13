import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getMe } from "../services/auth";
import type { BusinessDay, BusinessHour, StoreDetailResponse } from "../services/store";
import {
  getStoreDetail,
  isNotImplemented,
  mockStoreDetail,
  saveOperatingInfo,
} from "../services/store";
import {
  MAX_MENU_COUNT,
  MAX_MENU_NAME_LENGTH,
  formatTimeInput,
  nextBusinessHour,
  toSaveErrorMessage,
  validateOperatingInfo,
} from "../utils/storeManage";

/*
  VITE_STORE_MOCK=true 로 띄우면 백엔드 없이 목업만으로 화면을 본다(로그인도 필요 없다).
  화면을 다듬을 때만 쓴다. 꺼져 있어도 백엔드가 501(구현 전)을 주면 조회는 목업으로 채운다(services/store.ts).
*/
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";
const MOCK_LINKED_STORE = { storeId: 0, name: "맛나 치킨" };

/** VITE_STORE_MOCK 화면에서 "저장"을 눌렀을 때. 서버에 보내지 않는다. */
class MockSaveError extends Error {}

/** 화면에서 고치는 영업시간 한 줄. key 는 줄을 지우거나 더할 때 React 가 줄을 구분하는 값이다. */
export type BusinessHourDraft = BusinessHour & { key: number };

/** 화면에서 고치는 메뉴 한 개. 새로 등록한 메뉴는 menuId 가 없다. */
export type MenuDraft = { key: number; menuId?: number; name: string; onSale: boolean };

type StoreDraft = {
  phone: string;
  businessHours: BusinessHourDraft[];
  menus: MenuDraft[];
};

let nextKey = 0;
const newKey = () => ++nextKey;

const toDraft = (store: StoreDetailResponse): StoreDraft => ({
  phone: store.phone ?? "",
  businessHours: store.businessHours.map((hour) => ({ ...hour, key: newKey() })),
  menus: store.menus.map((menu) => ({ ...menu, key: newKey() })),
});

type StoreDetailResult = { store: StoreDetailResponse; isMock: boolean };

export type Notice = { tone: "success" | "error"; text: string };

export type UseStoreManageResult = {
  /** 처음 불러오는 중. */
  isLoading: boolean;
  /** 불러오지 못했을 때의 문구. 연결된 가게가 없을 때도 여기 담긴다. */
  loadErrorMessage: string | null;
  /** 목업으로 채운 화면인지. 백엔드가 구현되면 항상 false 다. */
  isMock: boolean;
  name: string;
  addressRoad: string;
  phone: string;
  setPhone: (value: string) => void;
  businessHours: BusinessHourDraft[];
  addBusinessHour: () => void;
  changeBusinessHour: (key: number, change: Partial<Pick<BusinessHour, "open" | "close">> & { day?: BusinessDay }) => void;
  removeBusinessHour: (key: number) => void;
  /** 메뉴 등록 칸에 입력 중인 이름. */
  newMenuName: string;
  setNewMenuName: (value: string) => void;
  canRegisterMenu: boolean;
  registerMenu: () => void;
  menus: MenuDraft[];
  toggleMenuOnSale: (key: number) => void;
  removeMenu: (key: number) => void;
  /** 고른 대표 사진의 미리보기 주소. 고르지 않았으면 null. */
  photoUrl: string | null;
  selectPhoto: (file: File) => void;
  removePhoto: () => void;
  isSaving: boolean;
  save: () => void;
  /** 저장 결과나 입력 오류 문구. 다시 고치기 시작하면 지운다. */
  notice: Notice | null;
};

/**
 * 가게 관리 화면. 내 정보(GET /api/auth/me)의 첫 번째 가게를 불러와(GET /api/stores/{storeId})
 * 전화번호·영업시간·메뉴를 고치고 "저장"(PUT /api/stores/{storeId}/operating-info)으로 한 번에 보낸다.
 * 메뉴 등록·삭제·판매 토글도 "저장"을 눌러야 반영된다(백엔드 API 설명과 같다). 상호명·주소는 점주가 바꿀 수 없다.
 */
export const useStoreManage = (): UseStoreManageResult => {
  const queryClient = useQueryClient();

  const meQuery = useQuery({
    queryKey: ["me"],
    queryFn: () => getMe(),
    enabled: !USE_STORE_MOCK,
  });
  const linkedStore = USE_STORE_MOCK ? MOCK_LINKED_STORE : meQuery.data?.stores[0];

  const storeQueryKey = ["store", linkedStore?.storeId];
  const storeQuery = useQuery({
    queryKey: storeQueryKey,
    enabled: linkedStore !== undefined,
    queryFn: async (): Promise<StoreDetailResult> => {
      const { storeId, name } = linkedStore!;
      if (USE_STORE_MOCK) return { store: mockStoreDetail(storeId, name), isMock: true };
      try {
        return { store: await getStoreDetail(storeId), isMock: false };
      } catch (error) {
        if (isNotImplemented(error)) return { store: mockStoreDetail(storeId, name), isMock: true };
        throw error;
      }
    },
  });

  const loaded = storeQuery.data;
  // 고치기 전에는 불러온 값을 그대로 보여 주고, 처음 고칠 때 복사해서 draft 를 만든다.
  // 불러온 값이 바뀔 때만 새로 만든다. 렌더마다 만들면 줄 key 가 바뀌어 입력 칸이 다시 그려진다.
  const initialDraft = useMemo(() => (loaded ? toDraft(loaded.store) : null), [loaded]);
  const [draft, setDraft] = useState<StoreDraft | null>(null);
  const current = draft ?? initialDraft;
  const [newMenuName, setNewMenuNameState] = useState("");
  const [notice, setNotice] = useState<Notice | null>(null);

  /*
    대표 사진. TODO(백엔드 대표 사진 API): 백엔드에 사진 칸·업로드 API 가 아직 없어 고른 사진은 서버로 보내지 않고
    이 화면에서 미리보기만 한다. API 가 생기면 저장할 때 같이 보낸다.
    미리보기 주소(blob:)는 바꾸거나 화면을 떠날 때 풀어 준다. 안 풀면 사진을 바꿀 때마다 메모리에 남는다.
  */
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const photoUrlRef = useRef<string | null>(null);
  const replacePhotoUrl = (next: string | null) => {
    if (photoUrlRef.current) URL.revokeObjectURL(photoUrlRef.current);
    photoUrlRef.current = next;
    setPhotoUrl(next);
    setNotice(null);
  };
  useEffect(() => () => {
    if (photoUrlRef.current) URL.revokeObjectURL(photoUrlRef.current);
  }, []);

  const update = (change: (prev: StoreDraft) => StoreDraft) => {
    if (!current) return;
    setNotice(null);
    setDraft(change(draft ?? current));
  };

  const saveMutation = useMutation({
    mutationFn: async (target: StoreDraft) => {
      const payload = {
        phone: target.phone.trim(),
        businessHours: target.businessHours.map(({ day, open, close }) => ({ day, open, close })),
        menus: target.menus.map(({ menuId, name, onSale }) => ({ menuId, name, onSale })),
      };
      // 501 로 목업을 채운 화면은 실제로 보내 본다 — 백엔드가 구현되기 전이면 "준비 중" 문구가 뜬다.
      if (USE_STORE_MOCK) throw new MockSaveError();
      return saveOperatingInfo(linkedStore!.storeId, payload);
    },
    onSuccess: (saved) => {
      queryClient.setQueryData<StoreDetailResult>(storeQueryKey, { store: saved, isMock: false });
      setDraft(null);
      setNotice({
        tone: "success",
        text: photoUrlRef.current ? "저장했어요. 대표 사진은 아직 저장되지 않아요." : "저장했어요.",
      });
    },
    onError: (error) => {
      setNotice({
        tone: "error",
        text: error instanceof MockSaveError ? "목업 화면이라 저장하지 않았어요." : toSaveErrorMessage(error),
      });
    },
  });

  const trimmedNewMenuName = newMenuName.trim();
  const canRegisterMenu =
    trimmedNewMenuName !== "" && trimmedNewMenuName.length <= MAX_MENU_NAME_LENGTH && (current?.menus.length ?? 0) < MAX_MENU_COUNT;

  const loadError = meQuery.error ?? storeQuery.error;
  let loadErrorMessage: string | null = null;
  if (loadError) loadErrorMessage = "가게 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";
  else if (meQuery.data && !linkedStore) loadErrorMessage = "연결된 가게가 없어요.";

  return {
    isLoading: !current && !loadErrorMessage,
    loadErrorMessage,
    isMock: loaded?.isMock ?? false,
    name: loaded?.store.name ?? "",
    addressRoad: loaded?.store.addressRoad ?? "",
    phone: current?.phone ?? "",
    setPhone: (value) => update((prev) => ({ ...prev, phone: value })),
    businessHours: current?.businessHours ?? [],
    addBusinessHour: () =>
      update((prev) => ({
        ...prev,
        businessHours: [...prev.businessHours, { ...nextBusinessHour(prev.businessHours), key: newKey() }],
      })),
    changeBusinessHour: (key, change) =>
      update((prev) => ({
        ...prev,
        businessHours: prev.businessHours.map((hour) => {
          if (hour.key !== key) return hour;
          return {
            ...hour,
            ...change,
            ...(change.open !== undefined && { open: formatTimeInput(change.open) }),
            ...(change.close !== undefined && { close: formatTimeInput(change.close) }),
          };
        }),
      })),
    removeBusinessHour: (key) =>
      update((prev) => ({ ...prev, businessHours: prev.businessHours.filter((hour) => hour.key !== key) })),
    newMenuName,
    setNewMenuName: (value) => {
      setNotice(null);
      setNewMenuNameState(value);
    },
    canRegisterMenu,
    registerMenu: () => {
      if (!canRegisterMenu) return;
      update((prev) => ({
        ...prev,
        menus: [...prev.menus, { key: newKey(), name: trimmedNewMenuName, onSale: true }],
      }));
      setNewMenuNameState("");
    },
    menus: current?.menus ?? [],
    toggleMenuOnSale: (key) =>
      update((prev) => ({
        ...prev,
        menus: prev.menus.map((menu) => (menu.key === key ? { ...menu, onSale: !menu.onSale } : menu)),
      })),
    removeMenu: (key) => update((prev) => ({ ...prev, menus: prev.menus.filter((menu) => menu.key !== key) })),
    photoUrl,
    selectPhoto: (file) => replacePhotoUrl(URL.createObjectURL(file)),
    removePhoto: () => replacePhotoUrl(null),
    isSaving: saveMutation.isPending,
    save: () => {
      if (!current || saveMutation.isPending) return;
      const invalid = validateOperatingInfo(current);
      if (invalid) {
        setNotice({ tone: "error", text: invalid });
        return;
      }
      setNotice(null);
      saveMutation.mutate(current);
    },
    notice,
  };
};
