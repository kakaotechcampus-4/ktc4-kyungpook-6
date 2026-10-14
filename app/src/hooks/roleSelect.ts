import { useState } from "react";
import { useNavigate } from "react-router-dom";

/** 앱을 쓰는 사람. 점주(OWNER)는 백엔드 MemberRole 과 이름을 맞췄다. */
export type AppUserRole = "OWNER" | "CHILD";

/*
  역할을 고른 뒤 넘어갈 화면.
  아동 인증 화면은 아직 없어서 임시 화면(/home)으로 둔다. 화면이 생기면 여기만 바꾼다.
  "/" 는 스플래시라 여기로 보내면 역할 선택으로 되돌아온다.
*/
const NEXT_PATH: Record<AppUserRole, string> = {
  OWNER: "/owner/login",
  CHILD: "/home",
};

export type UseRoleSelectResult = {
  /** 고른 역할. 아직 고르지 않았으면 null. */
  selectedRole: AppUserRole | null;
  selectRole: (role: AppUserRole) => void;
  /** "다음" 버튼. 고른 역할에 맞는 화면으로 넘긴다. */
  goNext: () => void;
};

export const useRoleSelect = (): UseRoleSelectResult => {
  const navigate = useNavigate();
  const [selectedRole, setSelectedRole] = useState<AppUserRole | null>(null);

  const goNext = () => {
    if (!selectedRole) return;
    navigate(NEXT_PATH[selectedRole]);
  };

  return { selectedRole, selectRole: setSelectedRole, goNext };
};
