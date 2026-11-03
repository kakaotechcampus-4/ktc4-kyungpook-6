import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { MOCK_CHILD_STORES } from "../services/childStore";
import type { ChildStore } from "../services/childStore";
import { getChildStoreOpenStatus } from "../utils/childStoreStatus";
import type { ChildStoreOpenStatus } from "../utils/childStoreStatus";
import { matchesQuery } from "../utils/childSearch";

/** 이 거리(m) 안의 가게만 목록·지도에 띄운다. */
export const MAX_DISTANCE_METERS = 2000;

/** 영업 상태를 다시 계산하는 주기. 분 단위로 바뀌니 30초면 충분하다. */
const CLOCK_TICK_MS = 30_000;

export type ChildStoreListItem = ChildStore & { openStatus: ChildStoreOpenStatus };

export type ChildStoreFilter = "NEAREST" | "OPEN_ONLY" | "TAKEOUT";

export type UseChildStoreListResult = {
  query: string;
  setQuery: (value: string) => void;
  /** 켜져 있는 칩. */
  filters: Set<ChildStoreFilter>;
  toggleFilter: (filter: ChildStoreFilter) => void;
  stores: ChildStoreListItem[];
  /** 가게를 누르면 가게 정보로 간다. */
  openStore: (storeId: number) => void;
};

/** 기기 시각. 시간이 지나면 영업 상태가 바뀌도록 주기적으로 새로 읽는다. 가게 정보 화면도 같이 쓴다. */
export const useNow = () => {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), CLOCK_TICK_MS);
    return () => window.clearInterval(id);
  }, []);
  return now;
};


/**
 * 아동 가게 목록. 적정 거리 안의 가게만, 검색어와 칩으로 거른다.
 * 가게는 API 가 없어 목업을 쓴다(services/childStore.ts).
 */
export const useChildStoreList = (): UseChildStoreListResult => {
  const navigate = useNavigate();
  const now = useNow();
  const [query, setQuery] = useState("");
  const [filters, setFilters] = useState<Set<ChildStoreFilter>>(() => new Set(["NEAREST"]));

  const stores = useMemo(() => {
    const items = MOCK_CHILD_STORES.filter((store) => store.distanceMeters <= MAX_DISTANCE_METERS)
      .filter((store) => matchesQuery(store, query))
      .map((store) => ({ ...store, openStatus: getChildStoreOpenStatus(store, now) }))
      .filter((store) => !filters.has("OPEN_ONLY") || store.openStatus === "OPEN")
      .filter((store) => !filters.has("TAKEOUT") || store.takeoutAvailable);
    // 가까운 순이 꺼져 있으면 받은 순서(등록 순) 그대로 둔다.
    return filters.has("NEAREST") ? items.sort((a, b) => a.distanceMeters - b.distanceMeters) : items;
  }, [query, filters, now]);

  const toggleFilter = (filter: ChildStoreFilter) =>
    setFilters((current) => {
      const next = new Set(current);
      if (next.has(filter)) next.delete(filter);
      else next.add(filter);
      return next;
    });

  return {
    query,
    setQuery,
    filters,
    toggleFilter,
    stores,
    openStore: (storeId) => navigate(`/child/stores/${storeId}`),
  };
};
