package com.ktc4.backend.domain.call.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 조사 결과의 전화 대상에 한 번에 전화를 넣은 결과.
 *
 * <p>대기열은 전화 API 가 갖고 있습니다 — 여기서 받은 건수만큼 요청이 들어갔다는 뜻이고,
 * 실제 발신 순서와 동시에 몇 통을 거는지는 전화 API 가 정합니다.
 */
public record CallBulkResponse(
        @Schema(description = "전화를 걸 만한 결과 수(우선확인·추가확인 중 아직 확인하지 않은 것)", example = "12")
        int targetCount,

        @Schema(description = "실제로 요청이 들어간 수", example = "11")
        int requested,

        @Schema(description = "거르지 못하고 요청에서 빠진 수 — 전화번호가 없거나 물을 것이 없는 가게, "
                + "전화 API 가 거절한 건. 어느 결과가 빠졌는지는 결과를 다시 조회하면 `call` 이 비어 있는 것으로 알 수 있습니다",
                example = "1")
        int skipped,

        @Schema(description = "요청이 들어간 전화들")
        List<CallResponse> calls
) {
}
