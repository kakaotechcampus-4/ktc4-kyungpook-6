package com.ktc4.backend.domain.job.dto;

import com.ktc4.backend.domain.store.dto.ExcludedStore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 조사 시작 응답 DTO. 조사는 뒤에서 돌기 때문에 결과가 아니라 접수 내용만 담는다 — 진행도와 결과는
 * {@code GET /api/jobs/{jobId}} 로 받는다.
 */
public record JobCreateResponse(
        @Schema(description = "조사 ID — 진행도·결과 조회에 쓴다", example = "42")
        Long jobId,

        @Schema(description = "조사할 가게 수 (국세청 대조로 끝나는 가게 + AI 조사 가게). 제외된 가게는 세지 않는다",
                example = "25")
        int targetCount,

        @Schema(description = "조사에서 뺀 가게와 이유. reason: STORE_NOT_FOUND(없는 가게) · DATA_PROBLEM(사업자번호 문제) · "
                + "NO_NTS_CHECK(국세청과 비교할 수 없음) · ALREADY_CLOSED(우리도 국세청도 폐업) · "
                + "ALREADY_SUSPENDED(우리도 국세청도 휴업)")
        List<ExcludedStore> excluded
) {

    /** 조사를 만든 결과에서 응답을 만든다. */
    public static JobCreateResponse from(CreatedJob created) {
        return new JobCreateResponse(created.jobId(), created.targetCount(), created.excluded());
    }
}
