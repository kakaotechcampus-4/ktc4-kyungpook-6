import type { ChangeEvent } from "react";
import closeIcon from "../../assets/close-icon.svg";
import plusIcon from "../../assets/plus-icon.svg";
import { useObjectUrl } from "../../hooks/objectUrl";
import ToggleSwitch from "../ui/ToggleSwitch";
import StoreMascot from "./StoreMascot";

type MenuRowProps = {
  name: string;
  /** 메뉴 사진. 없으면 마스코트를 보여 준다. */
  photo: Blob | null;
  onSale: boolean;
  onToggleSale: () => void;
  onRemove: () => void;
};

/** 등록한 메뉴 한 개. 판매를 끄면 썸네일과 이름이 흐려진다. */
export function MenuRow({ name, photo, onSale, onToggleSale, onRemove }: MenuRowProps) {
  const dimmed = onSale ? "" : "opacity-45";
  const photoUrl = useObjectUrl(photo);

  return (
    <li className="flex items-center gap-3.5 rounded-2xl border border-[#e5e7eb] p-3">
      <div
        className={`flex size-14 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-[#fbf6e6] ${dimmed}`}
      >
        {photoUrl ? (
          <img src={photoUrl} alt={`${name} 사진`} className="size-full object-cover" />
        ) : (
          <StoreMascot size="small" />
        )}
      </div>
      <p className={`min-w-0 flex-1 truncate text-[15px] leading-[normal] font-semibold text-[#1f2937] ${dimmed}`}>
        {name}
      </p>
      <ToggleSwitch checked={onSale} onChange={onToggleSale} label={`${name} 판매`} />
      <button
        type="button"
        aria-label={`${name} 삭제`}
        onClick={onRemove}
        className="flex size-7 shrink-0 items-center justify-center"
      >
        <img src={closeIcon} alt="" width={18} height={18} />
      </button>
    </li>
  );
}

type MenuRegisterRowProps = {
  value: string;
  onChange: (value: string) => void;
  /** 고른 메뉴 사진. 없으면 + 자리를 보여 준다. */
  photo: File | null;
  onSelectPhoto: (file: File) => void;
  canRegister: boolean;
  onRegister: () => void;
  maxLength: number;
};

/** 왼쪽 칸을 눌러 메뉴 사진을 고르고, 메뉴 이름을 적고 "등록"을 누르는 칸. 엔터로도 등록된다. */
export function MenuRegisterRow({
  value,
  onChange,
  photo,
  onSelectPhoto,
  canRegister,
  onRegister,
  maxLength,
}: MenuRegisterRowProps) {
  const photoUrl = useObjectUrl(photo);
  const handlePhotoChange = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (file) onSelectPhoto(file);
    // 같은 사진을 다시 골라도 onChange 가 오도록 비운다.
    event.target.value = "";
  };

  return (
    <form
      className="flex items-center gap-3.5 rounded-2xl border border-[#e5e7eb] bg-[#fafbfc] p-3"
      onSubmit={(event) => {
        event.preventDefault();
        onRegister();
      }}
    >
      <label
        htmlFor="new-menu-photo"
        aria-label="메뉴 사진 고르기"
        className="flex size-14 shrink-0 cursor-pointer items-center justify-center overflow-hidden rounded-xl bg-[#f1f4f8]"
      >
        {photoUrl ? (
          <img src={photoUrl} alt="고른 메뉴 사진" className="size-full object-cover" />
        ) : (
          <img src={plusIcon} alt="" width={22} height={22} />
        )}
      </label>
      <input id="new-menu-photo" type="file" accept="image/*" className="sr-only" onChange={handlePhotoChange} />
      <input
        id="new-menu-name"
        aria-label="메뉴 이름"
        placeholder="메뉴 이름 입력"
        value={value}
        maxLength={maxLength}
        onChange={(event) => onChange(event.target.value)}
        className="min-w-0 flex-1 bg-transparent text-[15px] leading-[normal] font-medium text-[#1f2937] outline-none placeholder:text-[#9ca3af]"
      />
      <button
        type="submit"
        disabled={!canRegister}
        className="shrink-0 px-1.5 text-[15px] leading-[normal] font-semibold text-[#b8891a]"
      >
        등록
      </button>
    </form>
  );
}
