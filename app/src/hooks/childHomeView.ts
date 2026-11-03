import type { ChildHomeFilter, LatLng } from "./childHome";

/*
  아동 홈에서 보던 화면(검색어·칩·고른 가게·지도 위치)을 기억해 둔다.
  가게 정보에 갔다가 뒤로 오면 홈이 새로 그려져도 보던 그대로 돌아온다.
  주문을 마치면 지워서 다음에 홈에 오면 처음 화면으로 시작한다(hooks/childOrder.ts).
  앱을 껐다 켜면 사라진다(메모리에만 둔다).
*/
export type ChildHomeView = {
  query: string;
  filters: ChildHomeFilter[];
  selectedStoreId: number | null;
  /** 지도 가운데와 확대 단계. 지도를 아직 안 움직였으면 null. */
  map: { center: LatLng; level: number } | null;
};

let savedView: ChildHomeView | null = null;

/** 기억해 둔 화면. 없으면(처음 들어왔거나 주문을 마쳤으면) null. */
export const getChildHomeView = (): ChildHomeView | null => savedView;

export const saveChildHomeView = (view: Partial<ChildHomeView>) => {
  savedView = { query: "", filters: [], selectedStoreId: null, map: null, ...savedView, ...view };
};

export const clearChildHomeView = () => {
  savedView = null;
};
