import { useNavigate } from "react-router-dom";
import backIcon from "../../assets/back-icon.svg";

/** 화면 왼쪽 위의 뒤로가기 화살표. */
function BackButton() {
  const navigate = useNavigate();

  return (
    <button type="button" aria-label="뒤로가기" onClick={() => navigate(-1)}>
      <img src={backIcon} alt="" width={24} height={24} />
    </button>
  );
}

export default BackButton;
