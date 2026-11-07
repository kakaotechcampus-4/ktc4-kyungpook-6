import qrMock from "../assets/child-qr-mock.svg";
import ChildOrderSteps from "../components/child/ChildOrderSteps";
import ChildQrCode from "../components/child/ChildQrCode";
import { useChildQr } from "../hooks/childQr";
import type { OrderReceiveType } from "../services/order";

const RECEIVE_LABEL: Record<OrderReceiveType, string> = {
  TAKEOUT: "포장",
  DINE_IN: "매장",
};

const BOX_CLASS = "w-full rounded-[18px] border border-[#e6e9ee] bg-white p-5";

/*
  아동 내 QR. 점주가 이 QR 을 찍어 체크인한다(점주 QR 확인 화면).
  아래 탭 바는 가게 목록 브랜치(PROMPT-180)의 ChildTabBar 를 머지한 뒤 붙인다. 겹치지 않게 아래 여백은 미리 둔다.
*/
function ChildQrPage() {
  const { isLoading, order, qrPayload, canCancel, cancelOrder, errorMessage, goStores } = useChildQr();
  // 조리가 시작됐으면(조리 중·조리 완료) 이미 체크인한 뒤라 QR 을 감춘다.
  const isCooking = order !== null && order.status !== "RECEIVED";

  return (
    <main className="flex min-h-screen flex-col bg-[#f3f5f8] pt-[env(safe-area-inset-top)] pb-[calc(84px+env(safe-area-inset-bottom))]">
      <div className="flex h-12 items-center bg-white pr-3.5 pl-5">
        <h1 className="text-xl leading-[normal] font-bold text-[#1f2937]">내 QR</h1>
      </div>

      {errorMessage && (
        <p role="alert" className="px-5 pt-3 text-center text-[13px] leading-[normal] text-[#e5484d]">
          {errorMessage}
        </p>
      )}

      {isLoading ? null : order ? (
        <div className="flex flex-col gap-3 px-4 pt-3">
          <section className={`${BOX_CLASS} flex flex-col items-center gap-1.5`}>
            <div className="flex flex-col items-center gap-1 text-center">
              <p className="text-xs leading-[normal] text-[#6b7280]">
                {order.storeName} · 주문 #{order.orderNo}
              </p>
              {/*
                조리 시작 뒤 시안에는 "가게 정보와 다른 점이 있었나요?" 와 "정보 피드백하기" 버튼이 있다.
                피드백 기능은 할지 정해지지 않아 아직 만들지 않는다. 이 칸은 조리 시작 뒤 2시간 동안만 보인다.
              */}
              {!isCooking && (
                <strong className="text-base leading-[normal] font-bold text-[#1f2937]">사장님께 보여주세요</strong>
              )}
            </div>

            {/* 서버가 준 QR 값으로 그린다. 목업 화면에서는 시안의 QR 그림이라 찍어도 체크인되지 않는다. */}
            {!isCooking && (
              <div className="px-2 pt-5 pb-2">
                {qrPayload ? (
                  <ChildQrCode value={qrPayload} size={164} />
                ) : import.meta.env.VITE_STORE_MOCK === "true" ? (
                  <img src={qrMock} alt="내 QR 코드" width={164} height={164} />
                ) : (
                  <div className="size-[164px]" />
                )}
              </div>
            )}

            <p className="pt-2 text-center text-xs leading-[normal] text-[#6b7280]">
              {order.menuName} · {RECEIVE_LABEL[order.receiveType]}
            </p>
            {canCancel && (
              <button
                type="button"
                onClick={cancelOrder}
                className="flex h-10 items-center justify-center rounded-[10px] border border-[#e5e7eb] bg-white px-3.5 text-sm leading-[normal] font-semibold text-[#1f2937]"
              >
                주문 취소
              </button>
            )}
          </section>

          <section className={`${BOX_CLASS} flex flex-col gap-[18px]`}>
            <h2 className="text-[15px] leading-[normal] font-bold text-[#1f2937]">주문 상태</h2>
            <ChildOrderSteps status={order.status} />
          </section>
        </div>
      ) : (
        <div className="flex flex-col items-center gap-5 px-[30px] pt-[79px] pb-20">
          <p className="text-center text-sm leading-[23.8px] text-[#6b7280]">
            아직 주문한 메뉴가 없어요.
            <br />
            가게를 고르고 주문하면 여기에 QR이 생겨요.
          </p>
          <button
            type="button"
            onClick={goStores}
            className="flex h-11 items-center justify-center rounded-xl bg-[#e3b23c] px-6 text-sm leading-[normal] font-medium text-white"
          >
            가게 찾아보기
          </button>
        </div>
      )}
    </main>
  );
}

export default ChildQrPage;
