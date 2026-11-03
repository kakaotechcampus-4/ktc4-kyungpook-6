import type { ChangeEvent } from "react";
import { useObjectUrl } from "../../hooks/objectUrl";
import { BIZ_CERT_ACCEPT } from "./bizCertAccept";

type BizCertDocCardProps = {
  id: string;
  file: File;
  readCount: number;
  /** "다시 올리기"로 새 파일을 골랐을 때. */
  onReupload: (file: File) => void;
  /** 읽는 중이면 "다시 올리기"를 막는다. */
  disabled: boolean;
};

/** 고른 사업자등록증: 썸네일(PDF 는 빈 칸)·파일 이름·인식한 항목 수·다시 올리기. */
function BizCertDocCard({ id, file, readCount, onReupload, disabled }: BizCertDocCardProps) {
  const previewUrl = useObjectUrl(file.type.startsWith("image/") ? file : null);

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    const next = event.target.files?.[0];
    // 같은 파일을 다시 골라도 change 가 오도록 비운다.
    event.target.value = "";
    if (next) onReupload(next);
  };

  return (
    <div className="flex items-center gap-2.5 rounded-2xl border border-[#e6e9ee] p-3.5">
      <div className="size-[72px] shrink-0 overflow-hidden rounded-lg bg-white shadow-[0_2px_6px_0_rgba(30,38,51,0.08),0_0_0_1px_#e6e9ee]">
        {previewUrl && <img src={previewUrl} alt="" className="size-full object-cover" />}
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="truncate text-sm leading-[normal] font-semibold text-[#1f2937]">{file.name}</p>
        <p className="text-xs leading-[normal] text-[#6b7280]">
          {readCount > 0 ? `${readCount}개 항목 인식 완료` : "인식한 항목이 없어요"}
        </p>
      </div>
      <label
        htmlFor={id}
        className={[
          "shrink-0 text-xs leading-[normal] text-[#6b7280] underline",
          disabled ? "pointer-events-none opacity-40" : "cursor-pointer",
        ].join(" ")}
      >
        다시 올리기
      </label>
      <input
        id={id}
        type="file"
        accept={BIZ_CERT_ACCEPT}
        className="sr-only"
        disabled={disabled}
        onChange={handleChange}
      />
    </div>
  );
}

export default BizCertDocCard;
