import { getRequest, postRequest } from "./api";

/** 백엔드 UploadKind. 가게 대표 사진·메뉴 사진·사업자등록증. */
export type UploadKind = "STORE_IMAGE" | "MENU_IMAGE" | "BIZ_CERT";

/** 백엔드 UploadResponse. fileId 를 가입 신청에 싣는다. */
export type UploadResponse = {
  fileId: string;
  kind: UploadKind;
  fileType: "JPEG" | "PNG" | "PDF";
  sizeBytes: number;
};

/** 백엔드 StoreImageResponse. */
export type StoreImage = {
  fileId: string;
  kind: Exclude<UploadKind, "BIZ_CERT">;
};

/**
 * 가입 신청에 붙일 파일 하나를 올린다(POST /api/files, 토큰 없이 부른다).
 *
 * - 사진은 JPEG·PNG 5MB, 사업자등록증은 PDF 10MB 또는 JPEG·PNG 5MB. 형식은 서버가 파일 내용으로 본다(HEIC·WebP 거절)
 * - 받은 fileId 는 세 시간 안에 한 번만 쓸 수 있다. 그래서 고를 때가 아니라 가입 신청을 보낼 때 올린다
 */
export const uploadFile = async (kind: UploadKind, file: File) => {
  const form = new FormData();
  form.append("kind", kind);
  form.append("file", file);
  // 인스턴스 기본값이 JSON 이라 그대로 두면 axios 가 FormData 를 JSON 으로 바꿔 버린다. 경계값(boundary)은 브라우저가 붙인다.
  const response = await postRequest<UploadResponse>("/api/files", form, {
    headers: { "Content-Type": "multipart/form-data" },
  });
  return response.fileId;
};

/**
 * 백엔드 BizCertExtractionResponse. 칸 이름은 가입 신청(OwnerSignupRequest)과 같고, 못 읽은 칸은 null 이다.
 * ntsVerification 은 국세청 진위확인 결과인데, 백엔드가 아직 확인하지 않아 지금은 늘 UNVERIFIED 다.
 */
export type BizCertReading = {
  /** 숫자 10자리, 하이픈 없이. */
  bizNo: string | null;
  storeName: string | null;
  representativeName: string | null;
  /** 사업장 소재지. 실제 가게 주소와 다를 수 있다. */
  storeAddress: string | null;
  /** "yyyy-MM-dd". */
  openDate: string | null;
  corpName: string | null;
  businessType: string | null;
  businessItem: string | null;
  ntsVerification: "VERIFIED" | "MISMATCH" | "UNVERIFIED";
};

/**
 * 올린 사업자등록증을 읽어 가입 칸에 채울 값을 받는다(POST /api/files/{fileId}/biz-cert-extraction, 토큰 없이 부른다).
 *
 * - 몇 초 걸릴 수 있고, 실패하면 직접 입력으로 넘어간다(503 biz-cert-unreadable, 429 too-many-requests 등)
 * - 가입 신청에 아직 쓰지 않은 파일만 읽는다. 같은 파일 ID 로 세 번까지다
 */
export const readBizCert = (fileId: string) => {
  return postRequest<BizCertReading>(`/api/files/${fileId}/biz-cert-extraction`, undefined);
};

/** 가게 사진 목록(대표 사진·메뉴 사진). 관리자와 그 가게에 연결된 점주만 부를 수 있다(아니면 403). */
export const getStoreImages = (storeId: number) => {
  return getRequest<StoreImage[]>(`/api/stores/${storeId}/images`);
};

/** 가게 사진 내용. 토큰이 필요해 <img src> 에 주소를 바로 넣지 못하고, 받아 온 Blob 으로 띄운다. */
export const getImageBlob = (fileId: string) => {
  return getRequest<Blob>(`/api/files/${fileId}`, { responseType: "blob" });
};
