import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
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
import OwnerHomePage from "./pages/OwnerHomePage";
import OwnerStorePage from "./pages/OwnerStorePage";
import OwnerOrdersPage from "./pages/OwnerOrdersPage";
import OwnerQrScanPage from "./pages/OwnerQrScanPage";
import OwnerCheckInResultPage from "./pages/OwnerCheckInResultPage";

function App() {
  return (
    <BrowserRouter>
      <UnauthorizedRedirect />
      <Routes>
        {/* 앱을 열면 처음 뜨는 주소. 스플래시가 승인 대기 중이면 가입 완료 화면, 아니면 역할 선택으로 보낸다. */}
        <Route path="/" element={<SplashPage />} />
        {/* 점주 홈. 로그인하면 여기로 온다(hooks/login.ts). */}
        <Route path="/home" element={<OwnerHomePage />} />
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
        <Route path="/owner/orders" element={<OwnerOrdersPage />} />
        <Route path="/owner/store" element={<OwnerStorePage />} />
        <Route path="/owner/qr" element={<OwnerQrScanPage />} />
        <Route path="/owner/qr/result" element={<OwnerCheckInResultPage />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
