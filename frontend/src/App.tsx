import { BrowserRouter, Route, Routes } from "react-router-dom";
import MockupDataPage from "./pages/MockupDataPage";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<MockupDataPage />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
