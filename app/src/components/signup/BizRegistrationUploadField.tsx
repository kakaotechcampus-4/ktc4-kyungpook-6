import type { ChangeEvent } from "react";
import uploadIcon from "../../assets/upload-icon.svg";

type BizRegistrationUploadFieldProps = {
  id: string;
  file: File | null;
  onChange: (file: File | null) => void;
};

/** 기본 정보 화면의 "사업자 등록증" 업로드 칸. 고른 파일 이름을 보여 준다. */
function BizRegistrationUploadField({ id, file, onChange }: BizRegistrationUploadFieldProps) {
  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    onChange(event.target.files?.[0] ?? null);
  };

  return (
    <div>
      <p className="pb-2 text-sm leading-[normal] font-semibold text-[#6b7280]">사업자 등록증</p>
      <label
        htmlFor={id}
        className="flex h-13 cursor-pointer items-center gap-1 rounded-xl bg-[#f1f4f8] px-4"
      >
        <img src={uploadIcon} alt="" width={20} height={20} />
        <span
          className={[
            "truncate text-[15px] leading-[normal] font-medium",
            file ? "text-[#1f2937]" : "text-[#a9afbb]",
          ].join(" ")}
        >
          {file ? file.name : "사업자 등록증 업로드"}
        </span>
      </label>
      <input
        id={id}
        type="file"
        accept="image/*,application/pdf"
        className="sr-only"
        onChange={handleChange}
      />
    </div>
  );
}

export default BizRegistrationUploadField;
