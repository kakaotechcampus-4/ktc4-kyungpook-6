import type { ChangeEvent } from "react";
import photoRemoveIcon from "../../assets/photo-remove-icon.svg";
import StoreMascot from "./StoreMascot";

type StorePhotoFieldProps = {
  id: string;
  /** 보여 줄 대표 사진 주소(새로 고른 사진이나 가입 때 올린 사진). 없으면 마스코트 빈 자리를 보여 준다. */
  photoUrl: string | null;
  onSelect: (file: File) => void;
  onRemove: () => void;
};

/** 가게 관리의 "대표 사진" 칸. 빈 자리를 누르면 사진을 고르고, X 로 지운다. */
function StorePhotoField({ id, photoUrl, onSelect, onRemove }: StorePhotoFieldProps) {
  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) onSelect(file);
    // 지운 뒤 같은 사진을 다시 골라도 onChange 가 오도록 비운다.
    event.target.value = "";
  };

  return (
    <div>
      <label htmlFor={id} className="block pb-2 text-sm leading-[normal] font-medium text-[#4b5563]">
        대표 사진
      </label>
      <div className="relative size-[120px]">
        <label
          htmlFor={id}
          className="flex size-full cursor-pointer items-center justify-center overflow-hidden rounded-[14px] bg-[#fbf6e6]"
        >
          {photoUrl ? (
            <img src={photoUrl} alt="고른 대표 사진" className="size-full object-cover" />
          ) : (
            <StoreMascot size="large" />
          )}
        </label>
        {photoUrl && (
          <button
            type="button"
            aria-label="대표 사진 지우기"
            onClick={onRemove}
            className="absolute top-1 right-1 flex size-7 items-center justify-center"
          >
            <img src={photoRemoveIcon} alt="" width={18} height={18} />
          </button>
        )}
      </div>
      <input id={id} type="file" accept="image/*" className="sr-only" onChange={handleChange} />
    </div>
  );
}

export default StorePhotoField;
