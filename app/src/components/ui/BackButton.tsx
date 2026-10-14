import { useNavigate } from "react-router-dom";
import backIcon from "../../assets/back-icon.svg";
import backWhiteIcon from "../../assets/back-white-icon.svg";

type BackButtonProps = {
  /** 화살표 색. 흰 화면은 dark(기본), QR 스캔처럼 어두운 화면은 light. */
  tone?: "dark" | "light";
};

/** 화면 왼쪽 위의 뒤로가기 화살표. */
function BackButton({ tone = "dark" }: BackButtonProps) {
  const navigate = useNavigate();

  return (
    <button type="button" aria-label="뒤로가기" onClick={() => navigate(-1)}>
      <img src={tone === "light" ? backWhiteIcon : backIcon} alt="" width={24} height={24} />
    </button>
  );
}

export default BackButton;
