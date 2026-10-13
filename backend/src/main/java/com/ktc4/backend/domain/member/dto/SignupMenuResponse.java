package com.ktc4.backend.domain.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가입 신청서에 적힌 메뉴 한 개.
 */
public record SignupMenuResponse(
        @Schema(description = "메뉴 이름", example = "김치찌개") String name
) {
}
