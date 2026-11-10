import { Navigate } from "react-router-dom";
import BackButton from "../components/ui/BackButton";
import PrimaryButton from "../components/ui/PrimaryButton";
import ChildMenuOption from "../components/child/ChildMenuOption";
import { useChildOrder } from "../hooks/childOrder";
import type { OrderReceiveType } from "../services/order";

const RECEIVE_OPTIONS: { type: OrderReceiveType; label: string }[] = [
  { type: "TAKEOUT", label: "포장해 갈게요" },
  { type: "DINE_IN", label: "가게에서 먹을게요" },
];

/** 아동 주문. 가게 정보에서 주문하기를 누르면 온다. */
function ChildOrderPage() {
  const {
    store,
    isLoading,
    isOpen,
    selectedMenuId,
    selectMenu,
    receiveType,
    selectReceiveType,
    isReceiveTypeAvailable,
    canSubmit,
    submit,
    errorMessage,
  } = useChildOrder();

  if (isLoading) return <main className="min-h-screen bg-white" />;
  if (!store) return <Navigate to="/child/stores" replace />;
  // 주소로 바로 들어왔는데 영업중이 아니면 가게 정보로 돌려보낸다. 주문하기 버튼이 꺼져 있는 곳이다.
  if (!isOpen) return <Navigate to={`/child/stores/${store.storeId}`} replace />;

  return (
    <main className="flex min-h-screen flex-col bg-white pt-[env(safe-area-inset-top)]">
      <div className="flex h-12 items-center gap-2 px-5">
        <BackButton />
        <h1 className="text-base leading-[normal] font-bold text-[#1f2937]">{store.name}</h1>
      </div>

      <div className="flex flex-1 flex-col px-5">
        <h2 className="pt-3 pb-2.5 text-[15px] leading-[normal] font-bold text-[#1f2937]">메뉴 고르기</h2>
        <div role="radiogroup" aria-label="메뉴 고르기" className="flex flex-col gap-2.5">
          {store.menus.map((menu) => (
            <ChildMenuOption
              key={menu.menuId}
              menu={menu}
              selected={menu.menuId === selectedMenuId}
              onSelect={() => selectMenu(menu.menuId)}
            />
          ))}
        </div>

        <h2 className="pt-6 pb-2.5 text-[15px] leading-[normal] font-bold text-[#1f2937]">어떻게 먹을까요?</h2>
        {/* 가게가 받지 않는 방법은 눌러도 바뀌지 않는다. */}
        <div role="radiogroup" aria-label="어떻게 먹을까요?" className="flex rounded-xl bg-[#f1f3f6] p-1">
          {RECEIVE_OPTIONS.map(({ type, label }) => {
            const selected = type === receiveType;
            return (
              <button
                key={type}
                type="button"
                role="radio"
                aria-checked={selected}
                disabled={!isReceiveTypeAvailable(type)}
                onClick={() => selectReceiveType(type)}
                className={`h-10 flex-1 rounded-[9px] text-[13px] leading-[normal] disabled:text-[#c4c9d1] ${
                  selected
                    ? "bg-white font-bold text-[#1f2937] drop-shadow-[0px_1px_2px_rgba(0,0,0,0.08)]"
                    : "text-[#6b7280]"
                }`}
              >
                {label}
              </button>
            );
          })}
        </div>

        <p className="mt-4 rounded-[10px] bg-[#eaf0fd] px-3.5 py-3 text-xs leading-[normal] text-[#3f66cc]">
          가게에 도착하면 사장님께 내 QR을 보여주세요.
        </p>
      </div>

      <div className="px-5 pt-3 pb-[calc(30px+env(safe-area-inset-bottom))]">
        {errorMessage && (
          <p role="alert" className="pb-3 text-center text-[13px] leading-[normal] text-[#e5484d]">
            {errorMessage}
          </p>
        )}
        <PrimaryButton disabled={!canSubmit} onClick={submit}>
          주문하기
        </PrimaryButton>
      </div>
    </main>
  );
}

export default ChildOrderPage;
