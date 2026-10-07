import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { login } from "../services/auth";

export type ApprovalStatus = "PENDING" | "APPROVED";

const LOGIN_PATH = "/owner/login";

export type UseSignupCompleteResult = {
  /** 관리자 승인 여부. 가입 직후에는 항상 PENDING 이다. */
  status: ApprovalStatus;
  /** "로그인하러 가기". 승인된 뒤에만 누를 수 있다. */
  goLogin: () => void;
};

/**
 * 가입 완료(승인 대기) 화면. 앱으로 돌아올 때마다 승인됐는지 확인한다.
 *
 * 로그인하지 않고 승인 여부를 묻는 API 가 백엔드에 없어서, 방금 가입한 아이디·비밀번호로
 * 로그인을 시도해 본다. 승인 전이면 403 owner-pending-approval, 승인 뒤면 200 이다.
 * 받은 토큰은 쓰지 않는다 — 사용자가 "로그인하러 가기"로 직접 로그인한다.
 *
 * TODO(백엔드 상태 확인 API): 승인 여부를 묻는 API 가 생기면 checkApproved 만 그 호출로 바꾼다.
 * 비밀번호는 이 화면이 떠 있는 동안 메모리(SignupDraft)에만 있고 저장하지 않는다.
 * 그래서 앱을 완전히 껐다 켜면 확인할 수 없고, 로그인 화면에서 승인 대기 문구로 알 수 있다.
 */
export const useSignupComplete = (): UseSignupCompleteResult => {
  const navigate = useNavigate();
  const { account } = useSignupDraft();
  const [status, setStatus] = useState<ApprovalStatus>("PENDING");

  useEffect(() => {
    if (!account || status === "APPROVED") return;

    const checkApproved = async () => {
      if (document.visibilityState !== "visible") return;
      try {
        await login(account);
        setStatus("APPROVED");
      } catch {
        // 403(승인 대기·거절), 연결 실패 모두 지금 상태 그대로 둔다.
      }
    };

    document.addEventListener("visibilitychange", checkApproved);
    return () => document.removeEventListener("visibilitychange", checkApproved);
  }, [account, status]);

  return {
    status,
    goLogin: () => {
      if (status === "APPROVED") navigate(LOGIN_PATH, { replace: true });
    },
  };
};
