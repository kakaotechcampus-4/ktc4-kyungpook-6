import logo from "../../assets/good-radar-logo.png";

type LogoHeaderProps = {
  title: string;
  description: string;
};

/** 화면 위쪽의 로고·제목·설명. 회원가입 단계 화면들이 같이 쓴다. */
function LogoHeader({ title, description }: LogoHeaderProps) {
  return (
    <header className="flex flex-col gap-4 pt-10 pb-8">
      <img src={logo} alt="" className="size-12" />
      <h1 className="pt-1 text-[22px] leading-[1.4] font-bold text-[#1f2937]">{title}</h1>
      <p className="text-sm leading-[normal] text-[#6b7280]">{description}</p>
    </header>
  );
}

export default LogoHeader;
