package com.ktc4.backend.domain.job.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 조사 시작 요청 DTO. 담당자가 가게 목록에서 체크한 가게 번호를 그대로 보낸다.
 *
 * <p>같은 번호가 여러 번 와도 한 번만 다루고, 중복을 뺀 뒤 100곳을 넘으면 거절한다(서버가 센다).
 */
public record JobCreateRequest(
        @ArraySchema(schema = @Schema(description = "조사할 가게 ID", example = "1"),
                arraySchema = @Schema(description = "조사할 가게 ID 목록 (중복 제외 최대 100곳)"))
        @NotEmpty(message = "조사할 가게를 한 곳 이상 골라야 합니다")
        // 100곳 상한은 중복을 뺀 뒤 서비스가 센다. 여기서는 터무니없이 큰 요청을 파싱 직후에 거절한다
        @Size(max = MAX_REQUEST_SIZE, message = "한 번에 보낼 수 있는 가게 ID 는 " + MAX_REQUEST_SIZE + "개까지입니다")
        List<@NotNull(message = "가게 ID 는 비어 있을 수 없습니다")
             @Positive(message = "가게 ID 는 1 이상이어야 합니다") Long> storeIds
) {

    /** 요청 본문 자체의 상한 (중복 포함). */
    public static final int MAX_REQUEST_SIZE = 1000;
}
