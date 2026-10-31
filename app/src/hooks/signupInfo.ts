import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { useSignupDraft } from "./signupDraft";
import { readBizCert, uploadFile } from "../services/file";
import type { BizCertReading } from "../services/file";
import { fillBlank, hasAnyReading } from "../utils/bizCertReading";
import { formatBizNo, formatPhone } from "../utils/format";
import { toSignupError } from "../utils/signupError";
import { validateBizNo, validatePhone, validateRepresentativeName } from "../utils/signupValidation";

/** 기본 정보 다음 화면(가게 등록 1). */
const NEXT_PATH = "/owner/signup/store";

/**
 * 사업자 등록증을 고른 뒤의 상태.
 * - reading: 올리고 읽는 중
 * - read: 읽은 값을 빈 칸에 채웠다(못 읽은 칸은 비어 있을 수 있다)
 * - unreadable: 올렸지만 읽지 못했다. 직접 입력하면 된다
 * - upload-failed: 올리지 못했다. 가입 신청을 보낼 때 다시 올린다
 */
export type BizCertStatus =
  | { kind: "idle" }
  | { kind: "reading" }
  | { kind: "read" }
  | { kind: "unreadable" }
  | { kind: "upload-failed"; message: string };

export type UseSignupInfoResult = {
  representativeName: string;
  setRepresentativeName: (value: string) => void;
  phone: string;
  /** 입력하는 대로 010-1234-5678 모양으로 하이픈을 넣는다. */
  setPhone: (value: string) => void;
  bizNo: string;
  /** 입력하는 대로 XXX-XX-XXXXX 모양으로 하이픈을 넣는다. */
  setBizNo: (value: string) => void;
  bizRegistrationFile: File | null;
  /** 고르면 바로 올리고 읽어서, 읽은 값을 빈 칸에 채운다. */
  setBizRegistrationFile: (file: File | null) => void;
  bizCertStatus: BizCertStatus;
  /** 세 칸이 모두 맞고 사업자 등록증을 골랐고, 읽는 중이 아닐 때만 true. 칸별 에러 문구는 따로 보여 주지 않는다. */
  canProceed: boolean;
  /** "다음" 버튼. 입력값을 보관하고 가게 등록으로 넘어간다. 가입 신청은 마지막 단계(메뉴)에서 보낸다. */
  goNext: () => void;
};

type BizCertUpload = { fileId: string; reading: BizCertReading | null };

/** 올리기에 실패하면 예외를 그대로 올리고, 읽기에 실패하면 reading 을 null 로 준다(직접 입력으로 넘어간다). */
const uploadAndRead = async (file: File): Promise<BizCertUpload> => {
  const fileId = await uploadFile("BIZ_CERT", file);
  try {
    return { fileId, reading: await readBizCert(fileId) };
  } catch {
    return { fileId, reading: null };
  }
};

export const useSignupInfo = (): UseSignupInfoResult => {
  const navigate = useNavigate();
  const { info, setInfo } = useSignupDraft();

  // 뒤 단계에서 돌아왔거나 중복 이메일(409)로 1단계에 다녀왔으면 적어 둔 값을 다시 채운다.
  const [representativeName, setRepresentativeName] = useState(info?.representativeName ?? "");
  const [phone, setPhone] = useState(info?.phone ?? "");
  const [bizNo, setBizNo] = useState(info?.bizNo ?? "");
  // 필수다. 고르면 바로 올려 파일 ID 를 받아 둔다. 가입 신청에는 이 ID 를 싣는다(hooks/signupMenus.ts).
  const [bizRegistrationFile, setBizRegistrationFile] = useState<File | null>(
    info?.bizRegistrationFile ?? null,
  );
  const [bizCertFileId, setBizCertFileId] = useState<string | null>(info?.bizCertFileId ?? null);
  const [bizCertReading, setBizCertReading] = useState<BizCertReading | null>(info?.bizCertReading ?? null);
  const [bizCertStatus, setBizCertStatus] = useState<BizCertStatus>({ kind: "idle" });

  const bizCertMutation = useMutation({ mutationFn: uploadAndRead });

  const selectBizRegistrationFile = (file: File | null) => {
    setBizRegistrationFile(file);
    // 다른 파일을 고르면 앞 파일에서 받은 ID·읽은 값은 버린다.
    setBizCertFileId(null);
    setBizCertReading(null);
    if (!file) {
      setBizCertStatus({ kind: "idle" });
      return;
    }
    setBizCertStatus({ kind: "reading" });
    // mutate 에 넘긴 콜백은 마지막으로 고른 파일의 결과에만 불린다. 읽는 중에 다시 고르면 앞 결과는 버려진다.
    bizCertMutation.mutate(file, {
      onSuccess: ({ fileId, reading }) => {
        setBizCertFileId(fileId);
        if (!reading || !hasAnyReading(reading)) {
          setBizCertStatus({ kind: "unreadable" });
          return;
        }
        setBizCertReading(reading);
        // 읽는 동안 점주가 적은 값이 있으면 그대로 둔다.
        setRepresentativeName((current) => fillBlank(current, reading.representativeName));
        setBizNo((current) => formatBizNo(fillBlank(current, reading.bizNo)));
        setBizCertStatus({ kind: "read" });
      },
      onError: (error) => {
        const signupError = toSignupError(error);
        setBizCertStatus({
          kind: "upload-failed",
          message: signupError.kind === "message" ? signupError.message : "",
        });
      },
    });
  };

  const canProceed =
    bizRegistrationFile !== null &&
    bizCertStatus.kind !== "reading" &&
    [validateRepresentativeName(representativeName), validatePhone(phone), validateBizNo(bizNo)].every(
      (message) => message === null,
    );

  const goNext = () => {
    if (!canProceed) return;
    setInfo({ representativeName, phone, bizNo, bizRegistrationFile, bizCertFileId, bizCertReading });
    navigate(NEXT_PATH);
  };

  return {
    representativeName,
    setRepresentativeName,
    phone,
    setPhone: (value) => setPhone(formatPhone(value)),
    bizNo,
    setBizNo: (value) => setBizNo(formatBizNo(value)),
    bizRegistrationFile,
    setBizRegistrationFile: selectBizRegistrationFile,
    bizCertStatus,
    canProceed,
    goNext,
  };
};
