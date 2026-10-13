import BackButton from "../ui/BackButton";

type ScanHeaderProps = {
  title: string;
};

/** 어두운 스캔 화면 위쪽의 뒤로가기·제목. */
function ScanHeader({ title }: ScanHeaderProps) {
  return (
    <header className="grid grid-cols-[24px_1fr_24px] items-center px-5 pt-3">
      <BackButton tone="light" />
      <h1 className="text-center text-base leading-[normal] font-semibold text-white">{title}</h1>
    </header>
  );
}

export default ScanHeader;
