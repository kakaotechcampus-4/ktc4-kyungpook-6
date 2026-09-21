package com.ktc4.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    BIZNO_API_ERROR(HttpStatus.BAD_GATEWAY, "비즈노 API 호출에 실패했습니다"),
    NTS_API_ERROR(HttpStatus.BAD_GATEWAY, "국세청 API 호출에 실패했습니다");

    private final HttpStatus httpStatus;
    private final String message;
}
