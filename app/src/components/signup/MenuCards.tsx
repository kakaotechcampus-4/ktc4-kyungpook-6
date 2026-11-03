import type { ChangeEvent, KeyboardEvent } from "react";
import menuPlaceholder from "../../assets/menu-placeholder.jpg";
import plusIcon from "../../assets/plus-icon.svg";
import removeLightIcon from "../../assets/remove-light-icon.svg";
import { useObjectUrl } from "../../hooks/objectUrl";
import { MENU_NAME_MAX_LENGTH } from "../../utils/signupValidation";

const CARD_BASE = "flex items-center gap-3.5 rounded-2xl border border-[#e5e7eb] p-3";

type NewMenuCardProps = {
  name: string;
  onNameChange: (value: string) => void;
  photo: File | null;
  onPhotoChange: (file: File | null) => void;
  canRegister: boolean;
  onRegister: () => void;
};

/** "새 메뉴" 칸: 사진(선택) · 이름 · 등록. 엔터로도 등록한다. */
export function NewMenuCard({
  name,
  onNameChange,
  photo,
  onPhotoChange,
  canRegister,
  onRegister,
}: NewMenuCardProps) {
  const previewUrl = useObjectUrl(photo);

  const handlePhotoChange = (event: ChangeEvent<HTMLInputElement>) => {
    onPhotoChange(event.target.files?.[0] ?? null);
    // 같은 사진을 다시 골라도 change 가 오도록 비운다.
    event.target.value = "";
  };

  // 화면 전체가 form 이라 엔터가 "다음"(가입 신청)을 누르지 않게 막고 등록한다.
  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== "Enter" || event.nativeEvent.isComposing) return;
    event.preventDefault();
    onRegister();
  };

  return (
    <div className={`${CARD_BASE} bg-[#fafbfc]`}>
      <label
        htmlFor="signup-new-menu-photo"
        aria-label={photo ? "메뉴 사진 바꾸기" : "메뉴 사진 고르기"}
        className="flex size-14 shrink-0 cursor-pointer items-center justify-center overflow-hidden rounded-xl bg-[#f1f4f8]"
      >
        {previewUrl ? (
          <img src={previewUrl} alt="" className="size-full object-cover" />
        ) : (
          <img src={plusIcon} alt="" width={22} height={22} />
        )}
      </label>
      <input
        id="signup-new-menu-photo"
        type="file"
        accept="image/jpeg,image/png"
        className="sr-only"
        onChange={handlePhotoChange}
      />
      <input
        aria-label="메뉴 이름"
        placeholder="메뉴 이름 입력"
        value={name}
        onChange={(event) => onNameChange(event.target.value)}
        onKeyDown={handleKeyDown}
        maxLength={MENU_NAME_MAX_LENGTH}
        className="min-w-0 flex-1 bg-transparent text-[15px] leading-[normal] font-medium text-[#1f2937] outline-none placeholder:text-[#a9afbb]"
      />
      <button
        type="button"
        onClick={onRegister}
        disabled={!canRegister}
        className="shrink-0 px-1.5 text-[15px] leading-[normal] font-semibold text-[#b8891a] disabled:text-[#b8891a]/40"
      >
        등록
      </button>
    </div>
  );
}

type MenuCardProps = {
  name: string;
  photo: File | null;
  onRemove: () => void;
};

/** 등록한 메뉴 한 개. 사진을 고르지 않았으면 기본 그림을 보여 준다. */
export function MenuCard({ name, photo, onRemove }: MenuCardProps) {
  const previewUrl = useObjectUrl(photo);

  return (
    <li className={`${CARD_BASE} bg-white`}>
      <img src={previewUrl ?? menuPlaceholder} alt="" className="size-14 shrink-0 rounded-xl object-cover" />
      <span className="min-w-0 flex-1 truncate text-[15px] leading-[normal] font-semibold text-[#1f2937]">
        {name}
      </span>
      <button
        type="button"
        aria-label={`${name} 지우기`}
        onClick={onRemove}
        className="flex size-7 shrink-0 items-center justify-center"
      >
        <img src={removeLightIcon} alt="" width={18} height={18} />
      </button>
    </li>
  );
}
