import SplashBrand from "../components/splash/SplashBrand";
import { useSplashRedirect } from "../hooks/splash";

function SplashPage() {
  // 다음 화면(로그인 등)이 생기면 여기 경로만 바꾼다.
  useSplashRedirect("/");

  return (
    <main className="relative flex min-h-screen flex-col items-center justify-center bg-[#e3b23c] px-4 pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]">
      <SplashBrand />
      <p className="absolute inset-x-0 bottom-[calc(47px+env(safe-area-inset-bottom))] text-center text-sm leading-[normal] text-white opacity-80">
        우리 동네 선한 가게를 이어줘요
      </p>
    </main>
  );
}

export default SplashPage;
