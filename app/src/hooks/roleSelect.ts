import { useState } from "react";
import { useNavigate } from "react-router-dom";

/** 앱을 쓰는 사람. 점주(OWNER)는 백엔드 MemberRole 과 이름을 맞췄다. */
export type AppUserRole = "OWNER" | "CHILD";

/*
  역할을 고른 뒤 넘어갈 화면.
  점주 로그인·아동 인증 화면이 아직 없어서 둘 다 홈으로 둔다. 화면이 생기면 여기만 바꾼다.
*/
const NEXT_PATH: Record<AppUserRole, string> = {
  OWNER: "/",
  CHILD: "/",
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
