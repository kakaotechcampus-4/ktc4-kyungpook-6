import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Capacitor } from "@capacitor/core";
import SplashPage from "./pages/SplashPage";
import RoleSelectPage from "./pages/RoleSelectPage";

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
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/splash" element={<SplashPage />} />
        <Route path="/role-select" element={<RoleSelectPage />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
