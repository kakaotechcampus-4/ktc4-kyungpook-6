import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { newDraftItemId, useSignupDraft } from "./signupDraft";
import type { SignupMenu } from "./signupDraft";
import { signupOwner } from "../services/auth";
import type { BusinessDay, OwnerSignupRequest } from "../services/auth";
import { saveStatusToken } from "../services/statusToken";
import { toSignupError } from "../utils/signupError";
import { MENU_MAX_COUNT, validateMenuName } from "../utils/signupValidation";

/** 가입 신청이 받아들여진 뒤 보여 줄 완료(승인 대기) 화면. */
const COMPLETE_PATH = "/owner/signup/complete";
/** 이미 가입된 이메일(409)일 때 돌아갈 1단계. */
const ACCOUNT_STEP_PATH = "/owner/signup";

export type UseSignupMenusResult = {
  /** "새 메뉴" 칸에 적고 있는 이름. */
  newMenuName: string;
  setNewMenuName: (value: string) => void;
  /** "새 메뉴" 칸에 고른 사진. */
  newMenuPhoto: File | null;
  setNewMenuPhoto: (file: File | null) => void;
  /** 이름이 맞고 50개(백엔드 상한)가 안 됐을 때만 true. */
  canRegister: boolean;
  /** "등록". 새 메뉴를 목록에 올리고 칸을 비운다. */
  register: () => void;
  menus: SignupMenu[];
  removeMenu: (id: string) => void;
  /** 메뉴가 하나 이상이고 요청 중이 아닐 때만 true. */
  canProceed: boolean;
  /** "다음" 버튼. 앞 단계 값을 모두 모아 가입 신청을 보낸다. */
  goNext: () => void;
  /** 가입 신청 실패 문구. 중복 이메일은 1단계로 돌아가므로 여기 오지 않는다. */
  errorMessage: string | null;
};

/** 가입 신청 성공 확인과 함께 가입 상태 확인용 토큰을 저장한다. 완료 화면이 이 토큰으로 승인 여부를 묻는다. */
const submitSignup = async (payload: OwnerSignupRequest) => {
  const response = await signupOwner(payload);
  await saveStatusToken(response);
  return response;
};

/** 가게 등록 3: 메뉴. 마지막 단계라 여기서 가입 신청을 보낸다. */
export const useSignupMenus = (): UseSignupMenusResult => {
  const navigate = useNavigate();
  const { account, info, store, businessHours, menus: savedMenus, setMenus: saveMenus, markEmailTaken } =
    useSignupDraft();

  const [menus, setMenus] = useState<SignupMenu[]>(savedMenus ?? []);
  const [newMenuName, setNewMenuName] = useState("");
  /*
    TODO(백엔드 메뉴 사진 업로드): 가입 요청의 메뉴(SignupMenuRequest)는 이름만 받아 사진은 들고만 있다.
    업로드 API 가 생기면 보낸다.
  */
  const [newMenuPhoto, setNewMenuPhoto] = useState<File | null>(null);

  const signupMutation = useMutation({
    mutationFn: submitSignup,
    onSuccess: () => navigate(COMPLETE_PATH, { replace: true }),
    onError: (error, payload) => {
      if (toSignupError(error).kind === "duplicate-email") {
        markEmailTaken(payload.email);
        navigate(ACCOUNT_STEP_PATH);
      }
    },
  });

  // 목록이 바뀌면 지난 실패 문구를 지운다.
  const updateMenus = (next: SignupMenu[]) => {
    if (signupMutation.isError) signupMutation.reset();
    setMenus(next);
  };

  const canRegister = validateMenuName(newMenuName) === null && menus.length < MENU_MAX_COUNT;

  const register = () => {
    if (!canRegister) return;
    updateMenus([...menus, { id: newDraftItemId(), name: newMenuName.trim(), photo: newMenuPhoto }]);
    setNewMenuName("");
    setNewMenuPhoto(null);
  };

  const canProceed = menus.length > 0 && !signupMutation.isPending;

  const goNext = () => {
    // 앞 단계 값은 화면이 확인하고 들여보내므로(OwnerSignupMenusPage) 여기서는 타입만 좁힌다.
    if (!canProceed || !account || !info || !store || !businessHours) return;
    // 409 로 1단계에 다녀와도 목록이 남도록 보관한다.
    saveMenus(menus);
    // 백엔드는 이름 앞뒤 공백을 지워 저장하고, bizNo·phone 의 하이픈은 지우고 저장한다. 보이는 그대로 보낸다.
    signupMutation.mutate({
      email: account.email,
      password: account.password,
      representativeName: info.representativeName,
      phone: info.phone,
      bizNo: info.bizNo,
      storeName: store.storeName,
      storeAddress: store.storeAddress,
      storePhone: store.storePhone,
      // 영업시간 화면을 통과한 줄은 요일을 골랐다(validateBusinessHour).
      businessHours: businessHours.map(({ day, open, close }) => ({ day: day as BusinessDay, open, close })),
      menus: menus.map(({ name }) => ({ name })),
    });
  };

  const signupError = signupMutation.error ? toSignupError(signupMutation.error) : null;

  return {
    newMenuName,
    setNewMenuName,
    newMenuPhoto,
    setNewMenuPhoto,
    canRegister,
    register,
    menus,
    removeMenu: (id) => updateMenus(menus.filter((menu) => menu.id !== id)),
    canProceed,
    goNext,
    errorMessage: signupError?.kind === "message" ? signupError.message : null,
  };
};
