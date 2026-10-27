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

/** 가게 사진 목록(대표 사진·메뉴 사진). 관리자와 그 가게에 연결된 점주만 부를 수 있다(아니면 403). */
export const getStoreImages = (storeId: number) => {
  return getRequest<StoreImage[]>(`/api/stores/${storeId}/images`);
};

/** 가게 사진 내용. 토큰이 필요해 <img src> 에 주소를 바로 넣지 못하고, 받아 온 Blob 으로 띄운다. */
export const getImageBlob = (fileId: string) => {
  return getRequest<Blob>(`/api/files/${fileId}`, { responseType: "blob" });
};
