package com.ktc4.backend.domain.file.dto;

import com.ktc4.backend.domain.file.entity.PendingUpload;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 파일을 올린 결과. {@code fileId} 를 가입 신청에 싣는다.
 */
public record UploadResponse(
        @Schema(description = "파일 ID — 가입 신청의 storeImageFileId·bizCertFileId·menus[].photoFileId 에 넣는다",
                example = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f")
        String fileId,
        @Schema(description = "종류", example = "STORE_IMAGE") UploadKind kind,
        @Schema(description = "서버가 파일 내용으로 가린 형식", example = "JPEG") FileType fileType,
        @Schema(description = "저장한 크기(바이트). 사진은 부가 정보를 지운 뒤의 크기다", example = "482133") long sizeBytes
) {
    public static UploadResponse from(PendingUpload upload) {
        return new UploadResponse(upload.getFileKey(), upload.getKind(), upload.getFileType(), upload.getSizeBytes());
    }
}
