package com.ktc4.backend.domain.file.dto;

import com.ktc4.backend.domain.file.entity.UploadFile;
import com.ktc4.backend.domain.file.enums.UploadKind;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가게 사진 한 장. 파일을 여는 데 필요한 것만 담는다 — 올린 사람, 올린 시각, 저장한 이름은 내보내지 않는다.
 */
public record StoreImageResponse(
        @Schema(description = "파일 ID — `GET /api/files/{fileId}` 로 연다", example = "3f2a9c1e-5b7d-4e8a-9c0f-1a2b3c4d5e6f")
        String fileId,
        @Schema(description = "종류 (STORE_IMAGE: 가게 대표 사진, MENU_IMAGE: 메뉴 사진)", example = "STORE_IMAGE")
        UploadKind kind
) {
    public static StoreImageResponse from(UploadFile file) {
        return new StoreImageResponse(file.getFileKey(), file.getKind());
    }
}
