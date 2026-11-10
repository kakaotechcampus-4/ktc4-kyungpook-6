import { BrowserRouter, Route, Routes } from "react-router-dom";
import MockupDataPage from "./pages/MockupDataPage";
import AnalysisResultPage from "./pages/AnalysisResultPage";
import LoginPage from "./pages/LoginPage";
import OwnerApprovalPage from "./pages/OwnerApprovalPage";
import SurveyProgressFloat, {
  SurveyProgressProvider,
} from "./components/SurveyProgressFloat";

function App() {
  return (
    <BrowserRouter>
      {/*
        조사 진행 상태는 화면이 바뀌어도 이어져야 한다 — 조사를 걸어 두고 가게 목록으로
        돌아갔을 때 진행 상황이 보여야 의미가 있다. 그래서 Routes 바깥(라우터 안)에 둔다.
        Provider 는 라우터 안에 있어야 한다(표시를 누르면 결과 화면으로 이동한다).
      */}
      <SurveyProgressProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<MockupDataPage />} />
          {/* jobId가 없으면 가장 최근 조사 결과를 본다. */}
          <Route path="/analysis" element={<AnalysisResultPage />} />
          <Route path="/analysis/:jobId" element={<AnalysisResultPage />} />
          <Route path="/owners" element={<OwnerApprovalPage />} />
        </Routes>

        <SurveyProgressFloat />
      </SurveyProgressProvider>
    </BrowserRouter>
  );
}

export default App;
