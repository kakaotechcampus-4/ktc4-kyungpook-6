import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Capacitor } from "@capacitor/core";
import UnauthorizedRedirect from "./components/UnauthorizedRedirect";
import SplashPage from "./pages/SplashPage";
import RoleSelectPage from "./pages/RoleSelectPage";
import OwnerLoginPage from "./pages/OwnerLoginPage";
import OwnerSignupLayout from "./pages/OwnerSignupLayout";
import OwnerSignupAccountPage from "./pages/OwnerSignupAccountPage";
import OwnerSignupInfoPage from "./pages/OwnerSignupInfoPage";
import OwnerSignupCompletePage from "./pages/OwnerSignupCompletePage";

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
        <Route path="/" element={<HomePage />} />
        <Route path="/splash" element={<SplashPage />} />
        <Route path="/role-select" element={<RoleSelectPage />} />
        <Route path="/owner/login" element={<OwnerLoginPage />} />
        <Route path="/owner/signup" element={<OwnerSignupLayout />}>
          <Route index element={<OwnerSignupAccountPage />} />
          <Route path="info" element={<OwnerSignupInfoPage />} />
          <Route path="complete" element={<OwnerSignupCompletePage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

export default App;
