package com.ktc4.backend.domain.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 주변 가게의 메뉴 — 아동 앱이 "오늘은 안 돼요" 로 흐리게 보일지 정한다.
 *
 * <p>메뉴 사진은 담지 않는다 — 사진 파일은 점주·관리자만 받을 수 있다.
 */
public record NearbyStoreMenuResponse(
        @Schema(description = "메뉴 ID", example = "301") Long menuId,
        @Schema(description = "메뉴 이름", example = "떡볶이") String name,
        @Schema(description = "오늘 판매하는지. false 면 판매하지 않는 메뉴", example = "true") boolean onSale
) {
}
