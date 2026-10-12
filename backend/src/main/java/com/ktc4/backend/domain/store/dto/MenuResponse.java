package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가게 메뉴 한 개. 저장할 때 이 {@code menuId} 를 다시 실어 보내면 같은 메뉴를 고친다.
 */
public record MenuResponse(
        @Schema(description = "메뉴 ID", example = "3") Long menuId,
        @Schema(description = "메뉴 이름", example = "김치찌개") String name,
        @Schema(description = "판매 중인지", example = "true") boolean onSale
) {
}
