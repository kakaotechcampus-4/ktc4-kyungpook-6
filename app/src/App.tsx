import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Capacitor } from "@capacitor/core";
import UnauthorizedRedirect from "./components/UnauthorizedRedirect";
import SplashPage from "./pages/SplashPage";
import RoleSelectPage from "./pages/RoleSelectPage";
import OwnerLoginPage from "./pages/OwnerLoginPage";
import OwnerSignupLayout from "./pages/OwnerSignupLayout";
import OwnerSignupAccountPage from "./pages/OwnerSignupAccountPage";
import OwnerSignupInfoPage from "./pages/OwnerSignupInfoPage";
import OwnerSignupStorePage from "./pages/OwnerSignupStorePage";
import OwnerSignupHoursPage from "./pages/OwnerSignupHoursPage";
import OwnerSignupMenusPage from "./pages/OwnerSignupMenusPage";
import OwnerSignupCompletePage from "./pages/OwnerSignupCompletePage";

/*
  로그인 뒤 넘어갈 임시 화면. 점주 홈이 아직 없어서 둔다(hooks/login.ts 의 AFTER_LOGIN_PATH).
  점주 홈이 생기면 이 화면과 /home 경로를 지운다.
*/
function HomePage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-2 px-4 pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]">
      <h1 className="text-2xl font-bold">App</h1>
      {/* 웹(web)·android·ios 중 어디서 돌고 있는지 확인용 */}
      <p className="text-sm text-gray-500">platform: {Capacitor.getPlatform()}</p>
    </main>
  );
}

function App() {
  return (
    <BrowserRouter>
      <UnauthorizedRedirect />
      <Routes>
        {/* 앱을 열면 처음 뜨는 주소. 스플래시가 승인 대기 중이면 가입 완료 화면, 아니면 역할 선택으로 보낸다. */}
        <Route path="/" element={<SplashPage />} />
        <Route path="/home" element={<HomePage />} />
        <Route path="/role-select" element={<RoleSelectPage />} />
        <Route path="/owner/login" element={<OwnerLoginPage />} />
        <Route path="/owner/signup" element={<OwnerSignupLayout />}>
          <Route index element={<OwnerSignupAccountPage />} />
          <Route path="info" element={<OwnerSignupInfoPage />} />
          <Route path="store" element={<OwnerSignupStorePage />} />
          <Route path="store/hours" element={<OwnerSignupHoursPage />} />
          <Route path="store/menus" element={<OwnerSignupMenusPage />} />
          <Route path="complete" element={<OwnerSignupCompletePage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

export default App;
