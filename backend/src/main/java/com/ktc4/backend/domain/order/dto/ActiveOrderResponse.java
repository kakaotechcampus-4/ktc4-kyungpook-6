package com.ktc4.backend.domain.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 아동의 진행 중 주문. 없으면 {@code order} 가 null 이다 — 빈 본문(204) 대신 늘 같은 모양으로 준다(앱의 요청 함수가 빈 본문을
 * 빈 문자열로 돌려줘 다루기 번거롭다).
 *
 * @param order 진행 중(접수 대기·조리 중) 주문. 없으면 null
 */
public record ActiveOrderResponse(
        @Schema(description = "진행 중(접수 대기·조리 중) 주문. 없으면 null", nullable = true) OrderResponse order
) {
}
