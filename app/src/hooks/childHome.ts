import { useEffect, useMemo, useState } from "react";
import { Geolocation } from "@capacitor/geolocation";
import { useNavigate } from "react-router-dom";
import { DEFAULT_CHILD_LOCATION } from "../services/childStore";
import { loadLastLocation, saveLastLocation } from "../services/lastLocation";
import { getChildStoreOpenStatus } from "../utils/childStoreStatus";
import { matchesQuery } from "../utils/childSearch";
import { useNearbyStores } from "./childNearbyStores";
import { MAX_DISTANCE_METERS, useNow } from "./childStoreList";
import { getChildHomeView, saveChildHomeView } from "./childHomeView";
import type { ChildHomeView } from "./childHomeView";
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
  /** 가게 정보에서 뒤로 왔을 때 되살릴 지도 위치. 있으면 지도를 여기서 시작하고 내 위치로 옮기지 않는다. */
  restoredMap: ChildHomeView["map"];
  /** 지도를 다 움직였을 때 위치를 기억해 둔다. */
  saveMapView: (map: NonNullable<ChildHomeView["map"]>) => void;
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
 * 가게는 내 위치의 주변 가게다(GET /api/stores/nearby). 위치를 새로 받으면 그 위치로 다시 부른다.
 */
export const useChildHome = (): UseChildHomeResult => {
  const navigate = useNavigate();
  const now = useNow();
  // 가게 정보에서 뒤로 왔으면 보던 화면(hooks/childHomeView.ts)을 되살린다.
  const [restored] = useState(getChildHomeView);
  const [query, setQuery] = useState(restored?.query ?? "");
  const [filters, setFilters] = useState<Set<ChildHomeFilter>>(() => new Set(restored?.filters));
  const [selectedStoreId, setSelectedStoreId] = useState<number | null>(restored?.selectedStoreId ?? null);
  const [userLocation, setUserLocation] = useState<LatLng>(DEFAULT_CHILD_LOCATION);
  const [startLocation, setStartLocation] = useState<LatLng | null>(null);

  useEffect(() => {
    saveChildHomeView({ query, filters: [...filters], selectedStoreId });
  }, [query, filters, selectedStoreId]);

  // 들어오면 저장해 둔 마지막 위치에서 먼저 시작하고, 기기 위치를 받으면 그리로 옮긴다.
  // 둘 다 없으면(처음 켰는데 거절 등) 기준점에 둔다. 보던 지도를 되살렸으면 내 위치 표시만 옮기고 지도는 그대로 둔다.
  useEffect(() => {
    const keepMap = restored?.map != null;
    let cancelled = false;
    let deviceLocated = false;
    loadLastLocation()
      .then((saved) => {
        if (cancelled || deviceLocated || !saved) return;
        setUserLocation(saved);
        if (!keepMap) setStartLocation(saved);
      })
      .catch(() => {});
    readDeviceLocation().then((location) => {
      if (cancelled || !location) return;
      deviceLocated = true;
      setUserLocation(location);
      if (!keepMap) setStartLocation(location);
    });
    return () => {
      cancelled = true;
    };
  }, [restored]);

  const nearbyStores = useNearbyStores(userLocation).data;

  const stores = useMemo(
    () =>
      (nearbyStores ?? []).filter((store) => store.distanceMeters <= MAX_DISTANCE_METERS)
        .filter((store) => matchesQuery(store, query))
        .map((store) => ({ ...store, openStatus: getChildStoreOpenStatus(store, now) }))
        .filter((store) => !filters.has("OPEN_ONLY") || store.openStatus === "OPEN")
        .filter((store) => !filters.has("TAKEOUT") || store.takeoutAvailable)
        .sort((a, b) => a.distanceMeters - b.distanceMeters),
    [nearbyStores, query, filters, now],
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
    restoredMap: restored?.map ?? null,
    saveMapView: (map) => saveChildHomeView({ map }),
    locateUser: async () => {
      const location = await readDeviceLocation();
      if (location) setUserLocation(location);
      return location ?? userLocation;
    },
  };
};
