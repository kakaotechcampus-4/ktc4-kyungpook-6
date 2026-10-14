import { Navigate } from "react-router-dom";
import checkCircleIcon from "../assets/check-circle-icon.svg";
import PrimaryButton from "../components/ui/PrimaryButton";
import CheckInInfoCard from "../components/qr/CheckInInfoCard";
import { QR_SCAN_PATH, useCheckInResult } from "../hooks/checkInResult";

/** QR 체크인 성공: 아동이 확인됐다. */
function OwnerCheckInResultPage() {
  const { checkedAt, startCooking } = useCheckInResult();

  // 주소로 바로 들어오는 등 체크인 결과가 없으면 보여 줄 게 없다.
  if (checkedAt === null) return <Navigate to={QR_SCAN_PATH} replace />;

  /*
    피그마에는 주문번호·메뉴·받는 방법도 있지만 체크인 API 가 주지 않는다(주문 기능 전).
    주문 API 가 생기면 여기에 줄을 더한다.
  */
  const rows = [{ label: "확인 시각", value: checkedAt }];

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <div className="flex justify-center pt-10 pb-5">
        <div className="flex size-20 items-center justify-center rounded-full bg-[#ddf5e7]">
          <img src={checkCircleIcon} alt="" width={40} height={40} />
        </div>
      </div>

      <div className="flex flex-col gap-1.5 pb-7 text-center">
        <h1 className="text-[22px] leading-[30.8px] font-bold text-[#1f2937]">주문한 아동이 맞아요</h1>
        <p className="text-sm leading-[normal] text-[#6b7280]">확인된 주문을 조리해주세요</p>
      </div>

      <CheckInInfoCard rows={rows} />

      <div className="flex-1" />

      <PrimaryButton onClick={startCooking}>조리 시작하기</PrimaryButton>
    </main>
  );
}

export default OwnerCheckInResultPage;
