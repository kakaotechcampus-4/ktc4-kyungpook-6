import { Navigate } from "react-router-dom";
import checkCircleIcon from "../assets/check-circle-icon.svg";
import BackButton from "../components/ui/BackButton";
import PrimaryButton from "../components/ui/PrimaryButton";
import ReceiveTypeBadge from "../components/order/ReceiveTypeBadge";
import CheckInInfoCard from "../components/qr/CheckInInfoCard";
import type { CheckInInfoRow } from "../components/qr/CheckInInfoCard";
import { QR_SCAN_PATH, useCheckInResult } from "../hooks/checkInResult";

/**
 * 체크인 결과. 아동이 확인됐다.
 * 이 가게에 낸 접수 대기 주문이 없으면(주문 없이 왔거나 이미 조리 중) 조리할 게 없다고 알리고 주문 목록으로만 보낸다 —
 * 피그마에 이 경우 시안이 없어 같은 틀에 문구만 바꿨다.
 */
function OwnerCheckInResultPage() {
  const { checkedAt, order, startCooking, isStarting, errorMessage } = useCheckInResult();

  // 주소로 바로 들어오는 등 체크인 결과가 없으면 보여 줄 게 없다.
  if (checkedAt === null) return <Navigate to={QR_SCAN_PATH} replace />;

  const rows: CheckInInfoRow[] = [
    ...(order
      ? [
          { label: "주문번호", value: `#${order.orderId}` },
          { label: "메뉴", value: order.menuName },
          { label: "받는 방법", value: <ReceiveTypeBadge receiveType={order.receiveType} /> },
        ]
      : []),
    { label: "확인 시각", value: checkedAt },
  ];

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      {/* QR 화면처럼 왼쪽 위에 뒤로가기. QR 은 스캔 화면을 replace 로 넘어오므로 어느 쪽이든 주문·홈으로 돌아간다. */}
      <header className="pt-3">
        <BackButton />
      </header>

      <div className="flex justify-center pt-10 pb-5">
        <div className="flex size-20 items-center justify-center rounded-full bg-[#ddf5e7]">
          <img src={checkCircleIcon} alt="" width={40} height={40} />
        </div>
      </div>

      <div className="flex flex-col gap-1.5 pb-7 text-center">
        <h1 className="text-[22px] leading-[30.8px] font-bold text-[#1f2937]">
          {order ? "주문한 아동이 맞아요" : "접수 대기 중인 주문이 없어요"}
        </h1>
        <p className="text-sm leading-[normal] text-[#6b7280]">
          {order ? "확인된 주문을 조리해주세요" : "주문하지 않았거나 이미 조리 중인 아동이에요"}
        </p>
      </div>

      <CheckInInfoCard rows={rows} />

      <div className="flex-1" />

      {errorMessage && (
        <p role="alert" className="pb-3 text-center text-sm leading-[normal] text-[#e5484d]">
          {errorMessage}
        </p>
      )}

      <PrimaryButton onClick={startCooking} disabled={isStarting}>
        {order ? "조리 시작하기" : "주문 목록 보기"}
      </PrimaryButton>
    </main>
  );
}

export default OwnerCheckInResultPage;
