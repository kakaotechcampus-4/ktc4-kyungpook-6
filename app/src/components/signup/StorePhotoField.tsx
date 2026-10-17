import type { ChangeEvent } from "react";
import plusLargeIcon from "../../assets/plus-large-icon.svg";
import { useObjectUrl } from "../../hooks/objectUrl";

type StorePhotoFieldProps = {
  id: string;
  file: File | null;
  onChange: (file: File | null) => void;
};

/** 가게 등록 1의 "대표 사진" 칸. 고르기 전에는 +, 고르면 그 사진을 보여 준다. */
function StorePhotoField({ id, file, onChange }: StorePhotoFieldProps) {
  const previewUrl = useObjectUrl(file);

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    onChange(event.target.files?.[0] ?? null);
  };

  return (
    <div>
      <p className="pb-3 text-sm leading-[normal] font-semibold text-[#6b7280]">대표 사진</p>
      <label
        htmlFor={id}
        aria-label={file ? "대표 사진 바꾸기" : "대표 사진 고르기"}
        className="flex size-25 cursor-pointer items-center justify-center overflow-hidden rounded-xl bg-[#f1f4f8]"
      >
        {previewUrl ? (
          <img src={previewUrl} alt="" className="size-full object-cover" />
        ) : (
          <img src={plusLargeIcon} alt="" width={32} height={32} />
        )}
      </label>
      <input id={id} type="file" accept="image/*" className="sr-only" onChange={handleChange} />
    </div>
  );
}

export default StorePhotoField;
