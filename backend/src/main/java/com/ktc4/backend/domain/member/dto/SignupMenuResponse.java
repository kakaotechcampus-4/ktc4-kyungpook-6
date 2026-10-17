package com.ktc4.backend.domain.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가입 신청서에 적힌 메뉴 한 개.
 */
public record SignupMenuResponse(
        @Schema(description = "메뉴 이름", example = "김치찌개") String name,
        @Schema(description = "메뉴 사진의 파일 ID. `GET /api/admin/files/{fileId}` 로 연다. 사진이 없으면 null",
                example = "9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", nullable = true) String photoFileId
) {
}
