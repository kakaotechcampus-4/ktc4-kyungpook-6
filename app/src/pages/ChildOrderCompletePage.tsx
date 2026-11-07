import { useNavigate } from "react-router-dom";
import logo from "../assets/good-radar-logo.png";
import PrimaryButton from "../components/ui/PrimaryButton";

/* 로고 뒤의 노란 빛. 회원가입 완료 화면(OwnerSignupCompletePage)과 같은 피그마 값이다. */
const GLOW_BACKGROUND =
  "radial-gradient(circle closest-side, rgb(255 248 225) 0%, rgb(253 239 196) 60%, rgb(253 239 196 / 0) 100%)";

/** 아동 내 QR 화면. */
const MY_QR_PATH = "/child/qr";

/** 아동 주문 완료. 주문하기를 누르면 온다. */
function ChildOrderCompletePage() {
  const navigate = useNavigate();

  return (
    <main className="flex min-h-screen flex-col bg-white px-5 pt-[env(safe-area-inset-top)] pb-[calc(40px+env(safe-area-inset-bottom))]">
      <section className="flex flex-1 flex-col items-center justify-center">
        <div
          className="mb-6 flex size-[140px] items-center justify-center rounded-full"
          style={{ background: GLOW_BACKGROUND }}
        >
          <img src={logo} alt="" className="size-20" />
        </div>
        <h1 className="pb-2 text-center text-[22px] leading-[normal] font-bold text-[#1f2937]">주문이 완료됐어요</h1>
        <p className="text-center text-[15px] leading-6 text-[#6b7280]">가게에 도착하면 사장님께 내 QR을 보여주세요.</p>
      </section>

      <PrimaryButton onClick={() => navigate(MY_QR_PATH, { replace: true })}>내 QR로 이동하기</PrimaryButton>
    </main>
  );
}

export default ChildOrderCompletePage;
