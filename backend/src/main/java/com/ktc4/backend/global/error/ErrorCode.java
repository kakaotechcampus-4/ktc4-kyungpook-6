package com.ktc4.backend.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다");

    private final HttpStatus httpStatus;
    private final String message;
}
