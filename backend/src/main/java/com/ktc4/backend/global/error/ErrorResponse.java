package com.ktc4.backend.global.error;

import io.swagger.v3.oas.annotations.media.Schema;

public record ErrorResponse(
        @Schema(description = "에러 코드", example = "INVALID_REQUEST")
        String code,

        @Schema(description = "에러 메시지", example = "잘못된 요청입니다")
        String message
) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }
}
