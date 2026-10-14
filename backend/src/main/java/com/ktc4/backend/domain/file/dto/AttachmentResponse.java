package com.ktc4.backend.domain.file.dto;

import com.ktc4.backend.domain.file.entity.UploadFile;
import com.ktc4.backend.domain.file.enums.FileType;
import com.ktc4.backend.domain.file.enums.UploadKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 가입 신청에 첨부된 파일 한 개. 관리자가 신청을 검토할 때 무엇이 올라왔는지 보고 골라 여는 데 쓴다.
 *
 * <p>저장한 이름과 디스크 경로는 내보내지 않는다 — 파일은 {@code fileId} 로만 찾는다.
 */
public record AttachmentResponse(
        @Schema(description = "파일 ID — `GET /api/admin/files/{fileId}` 로 연다", example = "12") Long fileId,
        @Schema(description = "종류 (STORE_IMAGE: 가게 대표 사진, MENU_IMAGE: 메뉴 사진, BIZ_CERT: 사업자등록증)",
                example = "STORE_IMAGE")
        UploadKind kind,
        @Schema(description = "서버가 파일 내용으로 가린 형식", example = "JPEG") FileType fileType,
        @Schema(description = "크기(바이트)", example = "482133") long sizeBytes,
        @Schema(description = "올린 시각") LocalDateTime uploadedAt
) {
    public static AttachmentResponse from(UploadFile file) {
        return new AttachmentResponse(file.getUploadFileId(), file.getKind(), file.getFileType(),
                file.getSizeBytes(), file.getCreatedAt());
    }
}
