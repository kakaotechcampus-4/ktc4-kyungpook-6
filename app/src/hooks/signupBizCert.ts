import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { newDraftItemId, useSignupDraft } from "./signupDraft";
import { readBizCert, uploadFile } from "../services/file";
import type { BizCertReading } from "../services/file";
import { fillInfoBlanks, fillStoreBlanks, hasAnyReading, toAutofillItems } from "../utils/bizCertReading";
import type { AutofillItem } from "../utils/bizCertReading";
import { toSignupError } from "../utils/signupError";

/** 읽은 결과를 보여 주는 화면. */
const RESULT_PATH = "/owner/signup/biz-cert/result";
/** 결과 화면 다음(기본 정보). */
const INFO_PATH = "/owner/signup/info";

const UPLOAD_FAILED_MESSAGE = "사업자등록증을 올리지 못했어요. 잠시 후 다시 시도해 주세요.";

type BizCertUpload = { fileId: string; reading: BizCertReading | null };

/**
 * 올리고(POST /api/files) 그 파일 ID 로 읽는다(POST /api/files/{fileId}/biz-cert-extraction).
 * 올리기에 실패하면 예외를 그대로 올리고, 읽기에 실패하면(503 biz-cert-unreadable, 429 등) reading 을 null 로 준다.
 * 아무것도 못 읽은 200(모든 칸 null)도 null 로 본다. 둘 다 직접 입력으로 넘어간다.
 */
const uploadAndRead = async (file: File): Promise<BizCertUpload> => {
  const fileId = await uploadFile("BIZ_CERT", file);
  try {
    const reading = await readBizCert(fileId);
    return { fileId, reading: hasAnyReading(reading) ? reading : null };
  } catch {
    return { fileId, reading: null };
  }
};

export type UseBizCertReaderResult = {
  /** 파일을 고르면 부른다. 읽고 나면 결과 화면으로 간다. */
  read: (file: File) => void;
  /** 올리고 읽는 동안 true. 몇 초 걸릴 수 있어 버튼을 잠근다. */
  isReading: boolean;
  /** 올리기 실패 문구. 이때는 결과 화면으로 넘어가지 않는다. */
  errorMessage: string | null;
};

/** 사업자등록증을 올리고 읽어 초안에 담는다. 올리기 화면과 결과 화면의 "다시 올리기"가 같이 쓴다. */
export const useBizCertReader = (): UseBizCertReaderResult => {
  const navigate = useNavigate();
  const { setBizCert, info, setInfo, store, setStore } = useSignupDraft();

  const readMutation = useMutation({
    mutationFn: uploadAndRead,
    onSuccess: ({ fileId, reading }, file) => {
      setBizCert({ file, fileId, reading });
      // 기본 정보·가게 정보를 적고 돌아와 다시 올렸으면 빈 칸만 새 값으로 채운다. 적은 값은 덮어쓰지 않는다.
      if (reading && info) setInfo(fillInfoBlanks(info, reading, newDraftItemId));
      if (reading && store) setStore(fillStoreBlanks(store, reading));
      // 결과 화면에서 다시 올렸으면 같은 화면을 다시 그리므로 기록을 쌓지 않는다.
      navigate(RESULT_PATH, { replace: window.location.pathname === RESULT_PATH });
    },
  });

  const uploadError = readMutation.error ? toSignupError(readMutation.error, UPLOAD_FAILED_MESSAGE) : null;

  return {
    read: (file) => {
      if (!readMutation.isPending) readMutation.mutate(file);
    },
    isReading: readMutation.isPending,
    errorMessage: uploadError?.kind === "message" ? uploadError.message : null,
  };
};

export type UseBizCertResultResult = {
  file: File | null;
  items: AutofillItem[];
  /** 읽은 항목 수. 0 이면 "직접 입력" 문구를 보여 준다. */
  readCount: number;
  goNext: () => void;
};

/** 정보를 가져왔어요: 읽은 항목을 보여 주고 기본 정보로 넘어간다. */
export const useBizCertResult = (): UseBizCertResultResult => {
  const navigate = useNavigate();
  const { bizCert } = useSignupDraft();
  const items = toAutofillItems(bizCert?.reading ?? null);

  return {
    file: bizCert?.file ?? null,
    items,
    readCount: items.filter((item) => item.read).length,
    goNext: () => navigate(INFO_PATH),
  };
};
