import { BrowserRouter, Route, Routes } from "react-router-dom";
import MockupDataPage from "./pages/MockupDataPage";
import AnalysisResultPage from "./pages/AnalysisResultPage";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<MockupDataPage />} />
        {/* jobId가 없으면 가장 최근 조사 결과를 본다. */}
        <Route path="/analysis" element={<AnalysisResultPage />} />
        <Route path="/analysis/:jobId" element={<AnalysisResultPage />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
