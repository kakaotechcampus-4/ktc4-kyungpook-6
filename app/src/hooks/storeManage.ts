import { useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { getMe } from "../services/auth";
import { getImageBlob, getStoreImages } from "../services/file";
import type { BusinessDay, BusinessHour, CompanionPolicy, StoreDetailResponse } from "../services/store";
import { getStoreDetail, mockStoreDetail, saveOperatingInfo } from "../services/store";
import {
  MAX_MENU_COUNT,
  MAX_MENU_NAME_LENGTH,
  formatTimeInput,
  nextBusinessHour,
  toSaveErrorMessage,
  validateOperatingInfo,
} from "../utils/storeManage";
import { useObjectUrl } from "./objectUrl";

/*
  VITE_STORE_MOCK=true 로 띄우면 백엔드 없이 목업만으로 화면을 본다(로그인도 필요 없다).
  화면을 다듬을 때만 쓴다.
*/
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";
const MOCK_LINKED_STORE = { storeId: 0, name: "맛나 치킨" };

/** VITE_STORE_MOCK 화면에서 "저장"을 눌렀을 때. 서버에 보내지 않는다. */
class MockSaveError extends Error {}

/** 화면에서 고치는 영업시간 한 줄. key 는 줄을 지우거나 더할 때 React 가 줄을 구분하는 값이다. */
export type BusinessHourDraft = BusinessHour & { key: number };

/**
 * 화면에서 고치는 메뉴 한 개. 새로 등록한 메뉴는 menuId 가 없다.
 * photoFileId 는 서버에 있는 사진, photoFile 은 이 화면에서 새로 고른 사진(아직 저장할 API 가 없다).
 */
export type MenuDraft = {
  key: number;
  menuId?: number;
  name: string;
  onSale: boolean;
  photoFileId?: string | null;
  photoFile?: File;
};

/** 화면에 그리는 메뉴. photo 는 새로 고른 사진이나 서버에서 받아 온 사진. 없으면 null. */
export type MenuView = MenuDraft & { photo: Blob | null };

type StoreDraft = {
  phone: string;
  businessHours: BusinessHourDraft[];
  menus: MenuDraft[];
  companion: CompanionPolicy;
  takeoutAvailable: boolean;
  dineInAvailable: boolean;
  ownerNote: string;
};

let nextKey = 0;
const newKey = () => ++nextKey;

const toDraft = (store: StoreDetailResponse): StoreDraft => ({
  phone: store.phone ?? "",
  businessHours: store.businessHours.map((hour) => ({ ...hour, key: newKey() })),
  menus: store.menus.map((menu) => ({ ...menu, key: newKey() })),
  companion: store.companion,
  takeoutAvailable: store.takeoutAvailable,
  dineInAvailable: store.dineInAvailable,
  ownerNote: store.ownerNote ?? "",
});

type StoreDetailResult = { store: StoreDetailResponse; isMock: boolean };

export type Notice = { tone: "success" | "error"; text: string };

export type UseStoreManageResult = {
  /** 처음 불러오는 중. */
  isLoading: boolean;
  /** 불러오지 못했을 때의 문구. 연결된 가게가 없을 때도 여기 담긴다. */
  loadErrorMessage: string | null;
  /** VITE_STORE_MOCK 목업으로 채운 화면인지. */
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
  /** 메뉴 등록 칸에서 고른 사진. "등록"하면 그 메뉴에 붙는다. */
  newMenuPhoto: File | null;
  setNewMenuPhoto: (file: File) => void;
  canRegisterMenu: boolean;
  registerMenu: () => void;
  menus: MenuView[];
  toggleMenuOnSale: (key: number) => void;
  removeMenu: (key: number) => void;
  /** 아이와 함께 올 수 있는 인원. */
  companion: CompanionPolicy;
  setCompanion: (value: CompanionPolicy) => void;
  takeoutAvailable: boolean;
  dineInAvailable: boolean;
  /** 포장·매장 이용을 켜고 끈다. 하나만 켜져 있으면 그건 끄지 않는다(둘 다 꺼지면 아이가 이용할 방법이 없다). */
  toggleTakeout: () => void;
  toggleDineIn: () => void;
  /** 사장님 한마디. 서버가 한 줄만 받아 줄바꿈은 띄어쓰기로 바꾼다. */
  ownerNote: string;
  setOwnerNote: (value: string) => void;
  /** 대표 사진 주소. 새로 고른 사진이 있으면 그 미리보기, 없으면 가입 때 올린 사진. 둘 다 없으면 null. */
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
 * 전화번호·동반 인원·이용 방법·영업시간·메뉴를 고치고 "저장"(PUT /api/stores/{storeId}/operating-info)으로 한 번에 보낸다.
 * 메뉴 등록·삭제·판매 토글도 "저장"을 눌러야 반영된다(백엔드 API 설명과 같다). 상호명·주소는 점주가 바꿀 수 없다.
 * 대표 사진은 가입 때 올린 것을 보여 준다(GET /api/stores/{storeId}/images → GET /api/files/{fileId}).
 * 메뉴 사진은 menus[].photoFileId 로 받아 보여 준다. 새로 고른 대표·메뉴 사진은 저장할 API 가 없어 미리보기만 한다.
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
      return { store: await getStoreDetail(storeId), isMock: false };
    },
  });

  // 가입 때 올린 대표 사진. 토큰이 필요한 주소라 Blob 으로 받아 띄운다. 못 받아도 화면은 그대로 쓸 수 있게 빈 자리로 둔다.
  const storeImageQuery = useQuery({
    queryKey: ["store-image", linkedStore?.storeId],
    enabled: linkedStore !== undefined && !USE_STORE_MOCK,
    queryFn: async () => {
      const images = await getStoreImages(linkedStore!.storeId);
      const storeImage = images.find((image) => image.kind === "STORE_IMAGE");
      return storeImage ? getImageBlob(storeImage.fileId) : null;
    },
  });
  const serverPhotoUrl = useObjectUrl(storeImageQuery.data ?? null);
  // 서버 사진을 X 로 지웠는지. 아직 서버에 반영할 API 가 없어 이 화면에서만 숨긴다.
  const [serverPhotoHidden, setServerPhotoHidden] = useState(false);

  const loaded = storeQuery.data;
  // 고치기 전에는 불러온 값을 그대로 보여 주고, 처음 고칠 때 복사해서 draft 를 만든다.
  // 불러온 값이 바뀔 때만 새로 만든다. 렌더마다 만들면 줄 key 가 바뀌어 입력 칸이 다시 그려진다.
  const initialDraft = useMemo(() => (loaded ? toDraft(loaded.store) : null), [loaded]);
  const [draft, setDraft] = useState<StoreDraft | null>(null);
  const current = draft ?? initialDraft;
  const [newMenuName, setNewMenuNameState] = useState("");
  const [newMenuPhoto, setNewMenuPhotoState] = useState<File | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);

  /*
    새로 고른 대표 사진. TODO(백엔드 대표 사진 변경 API): 가게 관리 저장(operating-info)에 사진 칸이 없어
    고른 사진은 서버로 보내지 않고 이 화면에서 미리보기만 한다. API 가 생기면 저장할 때 같이 보낸다.
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
        companion: target.companion,
        takeoutAvailable: target.takeoutAvailable,
        dineInAvailable: target.dineInAvailable,
        ownerNote: target.ownerNote.trim(),
      };
      if (USE_STORE_MOCK) throw new MockSaveError();
      return saveOperatingInfo(linkedStore!.storeId, payload);
    },
    onSuccess: (saved, target) => {
      const unsavedPhoto =
        photoUrlRef.current !== null || serverPhotoHidden || target.menus.some((menu) => menu.photoFile);
      queryClient.setQueryData<StoreDetailResult>(storeQueryKey, { store: saved, isMock: false });
      setDraft(null);
      setNotice({
        tone: "success",
        text: unsavedPhoto ? "저장했어요. 새로 고른 사진은 아직 저장되지 않아요." : "저장했어요.",
      });
    },
    onError: (error) => {
      setNotice({
        tone: "error",
        text: error instanceof MockSaveError ? "목업 화면이라 저장하지 않았어요." : toSaveErrorMessage(error),
      });
    },
  });

  // 서버에 있는 메뉴 사진. 토큰이 필요해 Blob 으로 받는다. 못 받으면 빈 자리(마스코트)로 둔다.
  const currentMenus = current?.menus ?? [];
  const menuPhotoQueries = useQueries({
    queries: currentMenus.map((menu) => ({
      queryKey: ["menu-photo", menu.photoFileId],
      enabled: !USE_STORE_MOCK && !!menu.photoFileId && !menu.photoFile,
      queryFn: () => getImageBlob(menu.photoFileId!),
      staleTime: Infinity,
    })),
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
        menus: [
          ...prev.menus,
          { key: newKey(), name: trimmedNewMenuName, onSale: true, photoFile: newMenuPhoto ?? undefined },
        ],
      }));
      setNewMenuNameState("");
      setNewMenuPhotoState(null);
    },
    newMenuPhoto,
    setNewMenuPhoto: (file) => {
      setNotice(null);
      setNewMenuPhotoState(file);
    },
    menus: currentMenus.map((menu, index) => ({
      ...menu,
      photo: menu.photoFile ?? menuPhotoQueries[index]?.data ?? null,
    })),
    toggleMenuOnSale: (key) =>
      update((prev) => ({
        ...prev,
        menus: prev.menus.map((menu) => (menu.key === key ? { ...menu, onSale: !menu.onSale } : menu)),
      })),
    removeMenu: (key) => update((prev) => ({ ...prev, menus: prev.menus.filter((menu) => menu.key !== key) })),
    companion: current?.companion ?? "NONE",
    setCompanion: (value) => update((prev) => ({ ...prev, companion: value })),
    takeoutAvailable: current?.takeoutAvailable ?? true,
    dineInAvailable: current?.dineInAvailable ?? true,
    toggleTakeout: () =>
      update((prev) =>
        prev.takeoutAvailable && !prev.dineInAvailable ? prev : { ...prev, takeoutAvailable: !prev.takeoutAvailable },
      ),
    toggleDineIn: () =>
      update((prev) =>
        prev.dineInAvailable && !prev.takeoutAvailable ? prev : { ...prev, dineInAvailable: !prev.dineInAvailable },
      ),
    ownerNote: current?.ownerNote ?? "",
    setOwnerNote: (value) => update((prev) => ({ ...prev, ownerNote: value.replace(/[\r\n]+/g, " ") })),
    photoUrl: photoUrl ?? (serverPhotoHidden ? null : serverPhotoUrl),
    selectPhoto: (file) => replacePhotoUrl(URL.createObjectURL(file)),
    removePhoto: () => {
      replacePhotoUrl(null);
      setServerPhotoHidden(true);
    },
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
