import { useCallback, useEffect, useState } from "react";
import { isAxiosError } from "axios";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { getMe } from "../services/auth";
import { clearStatusToken, getStatusToken } from "../services/statusToken";

export type ApprovalStatus = "PENDING" | "APPROVED";

const LOGIN_PATH = "/owner/login";

export type UseSignupCompleteResult = {
  /** 관리자 승인 여부. 가입 직후에는 항상 PENDING 이다. */
  status: ApprovalStatus;
  /** "로그인하러 가기". 승인된 뒤에만 누를 수 있다. */
  goLogin: () => void;
};

/**
 * 가입 완료(승인 대기) 화면. 화면에 들어올 때와 앱으로 돌아올 때마다 승인됐는지 확인한다.
 *
 * 가입 응답으로 받은 가입 상태 확인용 토큰(services/statusToken.ts)으로 GET /api/auth/me 를 불러 status 를 본다
 * (백엔드 PR #87). 비밀번호는 들고 있지 않는다.
 * - PENDING: 그대로 기다린다
 * - APPROVED: "로그인하러 가기"를 켠다. 이 토큰은 접근 토큰이 아니라서 로그인해야 한다
 * - REJECTED: 화면은 대기·승인 두 상태만 있어 따로 보여 주지 않는다(대기 화면 그대로).
 * - 토큰이 없거나 만료·무효(401): 더 확인할 수 없어 로그인 화면으로 보낸다. 승인 전이면 로그인이 403 승인 대기를 알려 준다
 */
export const useSignupComplete = (): UseSignupCompleteResult => {
  const navigate = useNavigate();
  const { clear: clearDraft } = useSignupDraft();
  const [status, setStatus] = useState<ApprovalStatus>("PENDING");

  // 가입이 끝났으니 1·2단계 입력값(비밀번호 포함)을 메모리에서 지운다.
  useEffect(() => {
    clearDraft();
    // 화면에 처음 들어올 때 한 번만 지운다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const goLoginAfterClearing = useCallback(async () => {
    await clearStatusToken();
    navigate(LOGIN_PATH, { replace: true });
  }, [navigate]);

  useEffect(() => {
    if (status === "APPROVED") return;

    const checkApproval = async () => {
      if (document.visibilityState !== "visible") return;

      const statusToken = await getStatusToken();
      if (!statusToken) {
        await goLoginAfterClearing();
        return;
      }
      try {
        const me = await getMe(statusToken);
        if (me.status === "APPROVED") setStatus("APPROVED");
      } catch (error) {
        // 401 은 api.ts 인터셉터가 로그인 화면으로 보낸다. 여기서는 쓸모없어진 토큰만 지운다.
        if (isAxiosError(error) && error.response?.status === 401) await clearStatusToken();
        // 연결 실패 등은 다음에 돌아올 때 다시 확인한다.
      }
    };

    checkApproval();
    document.addEventListener("visibilitychange", checkApproval);
    return () => document.removeEventListener("visibilitychange", checkApproval);
  }, [status, goLoginAfterClearing]);

  return {
    status,
    goLogin: () => {
      if (status === "APPROVED") goLoginAfterClearing();
    },
  };
};
