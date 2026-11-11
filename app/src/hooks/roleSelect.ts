import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { LOGIN_PATHS } from "../appRole";

import type { AppUserRole } from "../appRole";

// 역할 타입은 appRole.ts 가 갖고 있다. 여기서 쓰던 코드가 있어 그대로 내보낸다.
export type { AppUserRole };

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
    navigate(LOGIN_PATHS[selectedRole]);
  };

  return { selectedRole, selectRole: setSelectedRole, goNext };
};
