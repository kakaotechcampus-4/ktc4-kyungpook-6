import { useEffect, useMemo, useState } from "react";
import { Geolocation } from "@capacitor/geolocation";
import { useNavigate } from "react-router-dom";
import { DEFAULT_CHILD_LOCATION, MOCK_CHILD_STORES } from "../services/childStore";
import { loadLastLocation, saveLastLocation } from "../services/lastLocation";
import { getChildStoreOpenStatus } from "../utils/childStoreStatus";
import { matchesQuery } from "../utils/childSearch";
import { MAX_DISTANCE_METERS, useNow } from "./childStoreList";
import type { ChildStoreListItem } from "./childStoreList";

export type ChildHomeFilter = "OPEN_ONLY" | "TAKEOUT";

export type LatLng = { lat: number; lng: number };

export type UseChildHomeResult = {
  query: string;
  setQuery: (value: string) => void;
  /** 검색어가 있으면 true. 지도 아래에 검색 결과 목록을 띄운다. */
  searching: boolean;
  /** 켜져 있는 칩. 비어 있으면 "전체". */
  filters: Set<ChildHomeFilter>;
  toggleFilter: (filter: ChildHomeFilter) => void;
  /** "전체". 칩을 모두 끈다. */
  clearFilters: () => void;
  /** 지도에 핀으로 꽂을 가게(검색어·칩으로 거른 것). 검색 결과 목록도 이것을 가까운 순으로 쓴다. */
  stores: ChildStoreListItem[];
  /** 고른 가게. 핀과 목록 줄이 노랗게 바뀐다. 검색 중이 아니면 그 가게 한 곳만 아래 목록에 띄운다. */
  selectedStoreId: number | null;
  /** 가게를 고른다. null 이면 고른 것을 푼다(지도 빈 곳을 눌렀을 때). */
  selectStore: (storeId: number | null) => void;
  /** 가게 정보 화면으로 간다. */
  openStore: (storeId: number) => void;
  /** 아이 위치. 위치를 받지 못하면 기준점(DEFAULT_CHILD_LOCATION). */
  userLocation: LatLng;
  /**
   * 들어온 뒤 지도를 옮겨 둘 곳. 저장해 둔 마지막 위치가 먼저 오고, 기기 위치를 받으면 그것으로 바뀐다.
   * 둘 다 없으면 null(기준점에 둔다).
   */
  startLocation: LatLng | null;
  /** 위치를 다시 받는다. 받은 위치(또는 받지 못하면 지금 위치)를 돌려준다. */
  locateUser: () => Promise<LatLng>;
};

/**
 * 기기 위치를 한 번 받는다. 거절했거나 받지 못하면 null. 받으면 다음에 켤 때 쓰도록 저장한다.
 * 앱(Android·iOS)에서는 처음 부를 때 위치 권한을 묻는다. 웹에서는 플러그인이 브라우저 위치 기능을 쓴다.
 */
const readDeviceLocation = async (): Promise<LatLng | null> => {
  try {
    const { coords } = await Geolocation.getCurrentPosition({
      enableHighAccuracy: true,
      timeout: 10_000,
      maximumAge: 60_000,
    });
    const location = { lat: coords.latitude, lng: coords.longitude };
    void saveLastLocation(location).catch(() => {});
    return location;
  } catch {
    return null;
  }
};

/**
 * 아동 홈(지도). 가까운 가게를 핀으로 보여 주고, 가게·메뉴 이름으로 찾으면 결과 목록을 띄운다.
 * 가게는 API 가 없어 가게 목록과 같은 목업을 쓴다(services/childStore.ts).
 */
export const useChildHome = (): UseChildHomeResult => {
  const navigate = useNavigate();
  const now = useNow();
  const [query, setQuery] = useState("");
  const [filters, setFilters] = useState<Set<ChildHomeFilter>>(() => new Set());
  const [selectedStoreId, setSelectedStoreId] = useState<number | null>(null);
  const [userLocation, setUserLocation] = useState<LatLng>(DEFAULT_CHILD_LOCATION);
  const [startLocation, setStartLocation] = useState<LatLng | null>(null);

  // 들어오면 저장해 둔 마지막 위치에서 먼저 시작하고, 기기 위치를 받으면 그리로 옮긴다.
  // 둘 다 없으면(처음 켰는데 거절 등) 기준점에 둔다.
  useEffect(() => {
    let cancelled = false;
    let deviceLocated = false;
    loadLastLocation()
      .then((saved) => {
        if (cancelled || deviceLocated || !saved) return;
        setUserLocation(saved);
        setStartLocation(saved);
      })
      .catch(() => {});
    readDeviceLocation().then((location) => {
      if (cancelled || !location) return;
      deviceLocated = true;
      setUserLocation(location);
      setStartLocation(location);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const stores = useMemo(
    () =>
      MOCK_CHILD_STORES.filter((store) => store.distanceMeters <= MAX_DISTANCE_METERS)
        .filter((store) => matchesQuery(store, query))
        .map((store) => ({ ...store, openStatus: getChildStoreOpenStatus(store, now) }))
        .filter((store) => !filters.has("OPEN_ONLY") || store.openStatus === "OPEN")
        .filter((store) => !filters.has("TAKEOUT") || store.takeoutAvailable)
        .sort((a, b) => a.distanceMeters - b.distanceMeters),
    [query, filters, now],
  );

  const toggleFilter = (filter: ChildHomeFilter) =>
    setFilters((current) => {
      const next = new Set(current);
      if (next.has(filter)) next.delete(filter);
      else next.add(filter);
      return next;
    });

  // 걸러져 지도에서 사라진 가게는 고른 상태도 푼다.
  const visibleSelectedId = stores.some((store) => store.storeId === selectedStoreId) ? selectedStoreId : null;

  return {
    query,
    setQuery,
    searching: query.trim() !== "",
    filters,
    toggleFilter,
    clearFilters: () => setFilters(new Set()),
    stores,
    selectedStoreId: visibleSelectedId,
    selectStore: setSelectedStoreId,
    openStore: (storeId) => navigate(`/child/stores/${storeId}`),
    userLocation,
    startLocation,
    locateUser: async () => {
      const location = await readDeviceLocation();
      if (location) setUserLocation(location);
      return location ?? userLocation;
    },
  };
};
