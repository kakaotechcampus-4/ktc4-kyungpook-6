import { useState } from "react";
import { CustomOverlayMap, Map, useKakaoLoader } from "react-kakao-maps-sdk";
import locateIcon from "../assets/child-locate-icon.svg";
import searchIcon from "../assets/child-map-search-icon.svg";
import ChildMapMe from "../components/child/ChildMapMe";
import ChildMapPin from "../components/child/ChildMapPin";
import ChildSearchSheet from "../components/child/ChildSearchSheet";
import ChildTabBar from "../components/child/ChildTabBar";
import { useChildHome } from "../hooks/childHome";
import { useDeviceHeading } from "../hooks/deviceHeading";
import type { ChildHomeFilter, LatLng } from "../hooks/childHome";
import { DEFAULT_CHILD_LOCATION } from "../services/childStore";

const FILTER_CHIPS: { filter: ChildHomeFilter; label: string }[] = [
  { filter: "OPEN_ONLY", label: "영업중만" },
  { filter: "TAKEOUT", label: "포장 가능" },
];

const CHIP_BASE =
  "h-8 shrink-0 rounded-2xl border px-[13px] text-[13px] leading-[normal] drop-shadow-[0px_3px_5px_rgba(30,38,51,0.12)]";
const chipClass = (selected: boolean) =>
  `${CHIP_BASE} ${selected ? "border-[#1f2937] bg-[#1f2937] text-white" : "border-transparent bg-white text-[#1f2937]"}`;

/** 처음 지도 확대 단계. */
const DEFAULT_LEVEL = 4;
/** 가게를 고르거나 내 위치로 옮겨 갈 때 확대 단계. 숫자가 작을수록 가깝다. */
const FOCUS_LEVEL = 3;
/** 옮겨 간 뒤 확대하는 시간(ms). */
const ZOOM_DURATION_MS = 300;
/** 이만큼(위도·경도) 안이면 이미 가운데로 본다. 약 0.1m. */
const CENTER_EPSILON = 0.000001;

/** 아래 탭 바 높이(components/child/ChildTabBar.tsx). 지도는 그 위까지만 그린다. */
const TAB_BAR_HEIGHT = "calc(52px + max(32px, env(safe-area-inset-bottom)))";

/** 아동 홈. 가까운 가게를 지도에 핀으로 보여 주고, 가게나 메뉴 이름으로 찾는다. */
function ChildHomePage() {
  const {
    query,
    setQuery,
    searching,
    filters,
    toggleFilter,
    clearFilters,
    stores,
    selectedStoreId,
    selectStore,
    openStore,
    userLocation,
    locateUser,
  } = useChildHome();
  const [loading, error] = useKakaoLoader({ appkey: import.meta.env.VITE_KAKAO_JS_KEY });
  const [map, setMap] = useState<kakao.maps.Map | null>(null);
  const { heading, requestPermission: requestHeadingPermission } = useDeviceHeading();

  /**
   * 그 위치로 부드럽게 옮겨 간 뒤, 다 옮기면 가운데를 기준으로 확대한다.
   * 이동과 확대를 한꺼번에 걸면 서로 부딪혀 끊기고 가운데에서 비켜나서 차례로 한다.
   * 이미 그 확대 단계면 옮기기만 한다.
   */
  const flyTo = ({ lat, lng }: LatLng) => {
    if (!map) return;
    const position = new kakao.maps.LatLng(lat, lng);
    const zoom = () => map.setLevel(FOCUS_LEVEL, { anchor: position, animate: { duration: ZOOM_DURATION_MS } });
    const center = map.getCenter();
    const alreadyCentered =
      Math.abs(center.getLat() - lat) < CENTER_EPSILON && Math.abs(center.getLng() - lng) < CENTER_EPSILON;

    // 이미 가운데면 옮길 것이 없어 끝 신호(idle)가 오지 않으니 바로 확대한다.
    if (alreadyCentered) {
      if (map.getLevel() !== FOCUS_LEVEL) zoom();
      return;
    }
    if (map.getLevel() !== FOCUS_LEVEL) {
      const zoomAfterPan = () => {
        kakao.maps.event.removeListener(map, "idle", zoomAfterPan);
        zoom();
      };
      kakao.maps.event.addListener(map, "idle", zoomAfterPan);
    }
    map.panTo(position);
  };

  const handleSelect = (storeId: number) => {
    selectStore(storeId);
    const store = stores.find((item) => item.storeId === storeId);
    if (store) flyTo(store);
  };

  // iOS 는 방향 센서 권한을 버튼을 누른 때에만 물을 수 있어 내 위치 버튼에서 함께 묻는다.
  const handleLocate = async () => {
    void requestHeadingPermission();
    flyTo(await locateUser());
  };

  // 검색 중이면 결과 전체를, 아니면 핀으로 고른 가게 한 곳만 아래에 띄운다.
  const selectedStore = stores.find((store) => store.storeId === selectedStoreId);
  const sheetStores = searching ? stores : selectedStore ? [selectedStore] : [];

  return (
    <main className="relative h-dvh overflow-hidden bg-white">
      <div
        className="absolute inset-x-0 top-[env(safe-area-inset-top)] bg-[#e9edf2]"
        style={{ bottom: TAB_BAR_HEIGHT }}
      >
        {loading || error ? (
          <p className="flex size-full items-center justify-center text-sm leading-[normal] text-[#6b7280]">
            {error ? "지도를 불러오지 못했어요." : "지도를 불러오는 중이에요."}
          </p>
        ) : (
          <Map
            center={DEFAULT_CHILD_LOCATION}
            level={DEFAULT_LEVEL}
            isPanto
            onCreate={setMap}
            onClick={() => selectStore(null)}
            style={{ width: "100%", height: "100%" }}
          >
            <CustomOverlayMap position={userLocation} zIndex={0}>
              <ChildMapMe heading={heading} />
            </CustomOverlayMap>
            {stores.map((store) => {
              const selected = store.storeId === selectedStoreId;
              return (
                <CustomOverlayMap
                  key={store.storeId}
                  position={{ lat: store.lat, lng: store.lng }}
                  yAnchor={1}
                  zIndex={selected ? 2 : 1}
                  // 핀을 누른 것이 지도 클릭(고른 것 풀기)으로 넘어가지 않게 막는다.
                  clickable
                >
                  <ChildMapPin
                    name={store.name}
                    status={store.openStatus}
                    selected={selected}
                    onClick={() => handleSelect(store.storeId)}
                  />
                </CustomOverlayMap>
              );
            })}
          </Map>
        )}

        {/* 지도 위 검색창과 칩 */}
        <div className="absolute inset-x-4 top-1.5 z-10 flex flex-col gap-3">
          <label className="flex h-[46px] items-center gap-2 rounded-[14px] bg-white px-3.5 drop-shadow-[0px_4px_7px_rgba(30,38,51,0.14)]">
            <img src={searchIcon} alt="" width={18} height={18} />
            <input
              type="search"
              aria-label="가게나 메뉴 검색"
              placeholder="가게나 메뉴 검색"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              className="min-w-0 flex-1 bg-transparent text-[15px] leading-[normal] text-[#1f2937] outline-none placeholder:text-[#757575] [&::-webkit-search-cancel-button]:hidden"
            />
            {query !== "" && (
              <button
                type="button"
                aria-label="검색어 지우기"
                onClick={() => setQuery("")}
                className="flex size-[22px] shrink-0 items-center justify-center rounded-full bg-[#f1f3f6] text-[13px] leading-[normal] text-[#6b7280]"
              >
                ✕
              </button>
            )}
          </label>
          <div className="flex gap-2 overflow-x-auto">
            <button type="button" aria-pressed={filters.size === 0} onClick={clearFilters} className={chipClass(filters.size === 0)}>
              전체
            </button>
            {FILTER_CHIPS.map(({ filter, label }) => (
              <button
                key={filter}
                type="button"
                aria-pressed={filters.has(filter)}
                onClick={() => toggleFilter(filter)}
                className={chipClass(filters.has(filter))}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        {/* 지도 아래 내 위치 버튼과 검색 결과. 빈 곳은 지도를 그대로 움직일 수 있게 터치를 통과시킨다. */}
        <div className="pointer-events-none absolute inset-x-3 bottom-4 z-10 flex flex-col items-end gap-3.5 [&>*]:pointer-events-auto">
          <button
            type="button"
            aria-label="내 위치로 이동"
            onClick={handleLocate}
            className="mr-1 flex size-11 items-center justify-center rounded-full bg-white drop-shadow-[0px_4px_7px_rgba(30,38,51,0.15)]"
          >
            <img src={locateIcon} alt="" width={22} height={22} />
          </button>
          {(searching || selectedStore) && (
            <ChildSearchSheet
              key={searching ? `search:${query.trim()}` : `store:${selectedStoreId}`}
              title={searching ? `‘${query.trim()}’ 검색 결과 ${stores.length}곳` : undefined}
              query={query}
              stores={sheetStores}
              selectedStoreId={selectedStoreId}
              onSelect={handleSelect}
              onOpen={openStore}
            />
          )}
        </div>
      </div>

      <ChildTabBar />
    </main>
  );
}

export default ChildHomePage;
