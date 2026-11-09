import { useQuery } from "@tanstack/react-query";
import { DEFAULT_CHILD_LOCATION, MOCK_CHILD_STORES, NEARBY_RADIUS_METERS, getNearbyStores } from "../services/childStore";
import type { ChildStore } from "../services/childStore";
import { loadLastLocation } from "../services/lastLocation";

/** VITE_STORE_MOCK=true 면 백엔드 없이 목업 가게로 화면을 본다(점주 화면과 같은 스위치). */
const USE_STORE_MOCK = import.meta.env.VITE_STORE_MOCK === "true";

export type LatLng = { lat: number; lng: number };

/** 주변 가게 목록을 담는 react-query 키. 뒤에 위치를 붙인다. */
const NEARBY_STORES_QUERY_KEY = ["child-nearby-stores"];

/*
  위치가 몇 미터 흔들릴 때마다 다시 부르지 않게 소수 넷째 자리(약 10m)로 묶는다.
  홈이 받은 위치는 마지막 위치로도 저장되니(services/lastLocation.ts), 가게 정보·주문 화면이 같은 키를 쓰게 된다.
*/
const roundCoord = (value: number) => Math.round(value * 10_000) / 10_000;

/**
 * 그 위치의 주변 가게(GET /api/stores/nearby). 위치가 아직 없으면 부르지 않는다.
 * 목업이면 서버를 부르지 않고 시안 가게 중 반경 안의 것만 준다.
 */
export const useNearbyStores = (location: LatLng | null) => {
  const lat = location ? roundCoord(location.lat) : null;
  const lng = location ? roundCoord(location.lng) : null;
  return useQuery({
    queryKey: [...NEARBY_STORES_QUERY_KEY, lat, lng],
    enabled: lat !== null && lng !== null,
    queryFn: async (): Promise<ChildStore[]> => {
      if (USE_STORE_MOCK) return MOCK_CHILD_STORES.filter((store) => store.distanceMeters <= NEARBY_RADIUS_METERS);
      return getNearbyStores({ lat: lat!, lng: lng! });
    },
  });
};

/** 마지막으로 받은 내 위치. 없으면 기준점. 가게 목록·정보·주문 화면이 이 위치로 주변 가게를 찾는다. */
export const useSavedChildLocation = () =>
  useQuery({
    queryKey: ["child-saved-location"],
    queryFn: async (): Promise<LatLng> => (await loadLastLocation().catch(() => null)) ?? DEFAULT_CHILD_LOCATION,
  }).data ?? null;

/**
 * 가게 한 곳. 가게 상세 API 가 없어 주변 가게 목록에서 찾는다.
 * 불러오는 중이면 isLoading, 다 불렀는데 없으면 store 가 null 이다(반경 밖이거나 없는 가게).
 */
export const useChildStore = (storeId: number) => {
  const location = useSavedChildLocation();
  const query = useNearbyStores(location);
  return {
    store: query.data?.find((store) => store.storeId === storeId) ?? null,
    isLoading: location === null || query.isPending,
    isError: query.isError,
  };
};
