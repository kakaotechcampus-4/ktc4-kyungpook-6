import type { ChangeEvent } from "react";
import uploadIcon from "../../assets/upload-icon.svg";
import { useObjectUrl } from "../../hooks/objectUrl";
import type { BizCertStatus } from "../../hooks/signupInfo";

type BizRegistrationUploadFieldProps = {
  id: string;
  file: File | null;
  onChange: (file: File | null) => void;
  status: BizCertStatus;
};

/**
 * 문구 색. 채웠으면 가게 관리 "저장" 성공과 같은 녹색, 못 읽었을 때는 직접 입력해야 하니 눈에 띄게,
 * 올리지 못했을 때만 오류 색을 쓴다.
 */
const TONE_CLASS_NAME = {
  info: "text-[#6b7280]",
  success: "text-[#1f9d55]",
  notice: "text-[#b8891a]",
  error: "text-[#e5484d]",
} as const;

type StatusText = { text: string; tone: keyof typeof TONE_CLASS_NAME };

/** 칸 아래 안내 문구. 실패해도 직접 입력하면 되므로 막지 않는다. */
const statusText = (status: BizCertStatus): StatusText | null => {
  switch (status.kind) {
    case "reading":
      return { text: "사업자 등록증을 읽고 있어요. 잠시만 기다려주세요.", tone: "info" };
    case "read":
      return { text: "읽은 내용을 빈 칸에 채웠어요. 맞는지 확인해주세요.", tone: "success" };
    case "unreadable":
      return { text: "사업자 등록증을 읽지 못했어요. 직접 입력해주세요.", tone: "notice" };
    case "upload-failed":
      return { text: status.message || "사업자 등록증을 올리지 못했어요.", tone: "error" };
    default:
      return null;
  }
};

/**
 * 기본 정보 화면의 "사업자 등록증" 업로드 칸. 고르면 사진은 썸네일로, PDF 는 표시만 보여 주고 읽기 상태를 붙인다.
 * 채워진 값을 등록증과 견줘 볼 수 있게 하려는 것이다. 칸을 누르면 다른 파일을 다시 고른다.
 */
function BizRegistrationUploadField({ id, file, onChange, status }: BizRegistrationUploadFieldProps) {
  const message = statusText(status);
  // PDF 첫 장을 그리려면 큰 라이브러리가 필요해서 사진만 미리 보여 준다.
  const previewUrl = useObjectUrl(file?.type.startsWith("image/") ? file : null);
  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    onChange(event.target.files?.[0] ?? null);
  };

  return (
    <div>
      <p className="pb-2 text-sm leading-[normal] font-semibold text-[#6b7280]">사업자 등록증</p>
      {file ? (
        <label
          htmlFor={id}
          aria-label="사업자 등록증 다시 고르기"
          className="flex cursor-pointer items-center gap-3 rounded-xl bg-[#f1f4f8] p-2 pr-4"
        >
          <span className="flex size-16 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-white">
            {previewUrl ? (
              <img src={previewUrl} alt="" className="size-full object-cover" />
            ) : (
              <span className="text-xs font-semibold text-[#6b7280]">PDF</span>
            )}
          </span>
          <span className="flex min-w-0 flex-col gap-1">
            <span className="truncate text-[15px] leading-[normal] font-medium text-[#1f2937]">{file.name}</span>
            <span className="text-[13px] leading-[normal] text-[#a9afbb]">눌러서 다른 파일 고르기</span>
          </span>
        </label>
      ) : (
        <label
          htmlFor={id}
          className="flex h-13 cursor-pointer items-center gap-1 rounded-xl bg-[#f1f4f8] px-4"
        >
          <img src={uploadIcon} alt="" width={20} height={20} />
          <span className="truncate text-[15px] leading-[normal] font-medium text-[#a9afbb]">
            사업자 등록증 업로드
          </span>
        </label>
      )}
      <input
        id={id}
        type="file"
        accept="image/jpeg,image/png,application/pdf"
        className="sr-only"
        onChange={handleChange}
      />
      {message && (
        <p
          role={message.tone === "error" ? "alert" : "status"}
          className={["pt-2 text-[13px] leading-[1.4]", TONE_CLASS_NAME[message.tone]].join(" ")}
        >
          {message.text}
        </p>
      )}
    </div>
  );
}

export default BizRegistrationUploadField;
