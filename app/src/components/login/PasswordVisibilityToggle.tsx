import eyeIcon from "../../assets/eye-icon.svg";

type PasswordVisibilityToggleProps = {
  visible: boolean;
  onToggle: () => void;
};

/**
 * 비밀번호 칸 오른쪽의 눈 아이콘. 누르면 비밀번호를 보이거나 가린다.
 *
 * 상태마다 색을 바꾸려고 아이콘을 마스크로 쓰고 배경색으로 칠한다(img 로는 색을 바꿀 수 없다).
 * 가렸을 때는 회색, 보일 때는 원래 아이콘 색이다.
 */
function PasswordVisibilityToggle({ visible, onToggle }: PasswordVisibilityToggleProps) {
  return (
    <button
      type="button"
      aria-label={visible ? "비밀번호 가리기" : "비밀번호 보기"}
      aria-pressed={visible}
      onClick={onToggle}
      className="shrink-0"
    >
      <span
        aria-hidden="true"
        className={[
          "block size-5 mask-contain mask-center mask-no-repeat",
          visible ? "bg-[#1f2937]" : "bg-[#a9afbb]",
        ].join(" ")}
        style={{ maskImage: `url("${eyeIcon}")` }}
      />
    </button>
  );
}

export default PasswordVisibilityToggle;
