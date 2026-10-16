package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가게 메뉴 한 개. 저장할 때 이 {@code menuId} 를 다시 실어 보내면 같은 메뉴를 고친다.
 */
public record MenuResponse(
        @Schema(description = "메뉴 ID", example = "3") Long menuId,
        @Schema(description = "메뉴 이름", example = "김치찌개") String name,
        @Schema(description = "판매 중인지", example = "true") boolean onSale,
        @Schema(description = """
                메뉴 사진의 파일 ID. `GET /api/files/{fileId}` 로 연다(토큰 필요). 사진이 없으면 null.
                사진을 올린 점주의 가게 연결이 끊기면 값은 남아도 열면 404 다""",
                example = "9c8b7a6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", nullable = true) String photoFileId
) {
}
