import searchIcon from "../assets/child-search-icon.svg";
import ChildStoreRow from "../components/child/ChildStoreRow";
import ChildTabBar from "../components/child/ChildTabBar";
import { useChildStoreList } from "../hooks/childStoreList";
import type { ChildStoreFilter } from "../hooks/childStoreList";

const FILTER_CHIPS: { filter: ChildStoreFilter; label: string }[] = [
  { filter: "NEAREST", label: "가까운 순" },
  { filter: "OPEN_ONLY", label: "영업중만" },
  { filter: "TAKEOUT", label: "포장 가능" },
];

/** 아동 가게 목록. 가까운 가게를 보여 주고, 가게나 메뉴 이름으로 찾는다. */
function ChildStoreListPage() {
  const { query, setQuery, filters, toggleFilter, stores, isLoading, loadErrorMessage, openStore } = useChildStoreList();

  return (
    <main className="min-h-screen bg-white pt-[env(safe-area-inset-top)] pb-[calc(84px+env(safe-area-inset-bottom))]">
      <h1 className="px-5 pt-3 text-[22px] leading-[1.4] font-bold text-[#1f2937]">
        오늘은 어디서
        <br />
        먹을까요?
      </h1>

      <div className="px-5 pt-4">
        <label className="flex h-11 items-center gap-2 rounded-xl bg-[#f1f3f6] px-3.5">
          <img src={searchIcon} alt="" width={16} height={16} />
          <input
            type="search"
            aria-label="가게나 메뉴 찾기"
            placeholder="가게나 메뉴 찾기"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="min-w-0 flex-1 bg-transparent text-sm leading-[normal] text-[#1f2937] outline-none placeholder:text-[#757575]"
          />
        </label>
      </div>

      <div className="flex gap-2 overflow-x-auto px-5 pt-3 pb-1">
        {FILTER_CHIPS.map(({ filter, label }) => {
          const selected = filters.has(filter);
          return (
            <button
              key={filter}
              type="button"
              aria-pressed={selected}
              onClick={() => toggleFilter(filter)}
              className={`h-8 shrink-0 rounded-2xl border px-[13px] text-[13px] leading-[normal] ${
                selected ? "border-[#1f2937] bg-[#1f2937] text-white" : "border-[#e6e9ee] bg-white text-[#1f2937]"
              }`}
            >
              {label}
            </button>
          );
        })}
      </div>

      {isLoading || loadErrorMessage ? (
        <p className="px-5 pt-16 text-center text-sm leading-[normal] text-[#6b7280]">
          {loadErrorMessage ?? "불러오는 중…"}
        </p>
      ) : stores.length > 0 ? (
        <ul className="px-5">
          {stores.map((store) => (
            <li key={store.storeId} className="border-b border-[#e6e9ee] last:border-b-0">
              <ChildStoreRow store={store} onSelect={() => openStore(store.storeId)} />
            </li>
          ))}
        </ul>
      ) : (
        <p className="px-5 pt-16 text-center text-sm leading-[normal] text-[#6b7280]">찾는 가게가 없어요.</p>
      )}

      <ChildTabBar />
    </main>
  );
}

export default ChildStoreListPage;
