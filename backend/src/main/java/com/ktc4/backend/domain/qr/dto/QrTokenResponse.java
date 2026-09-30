package com.ktc4.backend.domain.qr.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * QR 발급 응답 DTO.
 *
 * <p>이 값은 응답에서 딱 한 번만 나간다. 서버는 해시만 보관하므로 다시 알려줄 수 없고,
 * 잃어버리면 재발급해야 한다.
 */
public record QrTokenResponse(
        @Schema(description = "QR 이미지에 그대로 담을 문자열. 앱이 기기에 저장해 두고 오프라인에서 QR 로 그린다",
                example = "v1.q3Zb0cN8dTf1mK2xR7pYwL4sE9hJ6uA5vG0iO3nB2kC")
        String qrPayload
) {
}
