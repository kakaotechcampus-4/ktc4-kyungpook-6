package com.ktc4.backend.domain.checkin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 체크인 요청 DTO.
 *
 * <p>여기서는 "값이 있는가"만 본다. 값이 QR 형식에 맞는지는 서비스가 판단하고,
 * 맞지 않으면 {@code invalid-qr-token} 으로 응답한다. 길이 상한은 형식(46글자)보다 넉넉하게 두어,
 * 비정상적으로 긴 값만 여기서 걸러낸다.
 */
public record CheckInRequest(
        @Schema(description = "점주 앱이 아동의 QR 을 스캔해 읽은 문자열 그대로",
                example = "v1.q3Zb0cN8dTf1mK2xR7pYwL4sE9hJ6uA5vG0iO3nB2kC")
        @NotBlank(message = "QR 문자열이 비어 있습니다")
        @Size(max = 100, message = "QR 문자열은 100자를 넘을 수 없습니다")
        String qrPayload
) {
}
