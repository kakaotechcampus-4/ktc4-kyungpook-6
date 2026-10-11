package com.ktc4.backend.domain.job.dto;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 조사 진행도와 결과. 화면은 이 응답 하나를 몇 초마다 다시 불러 진행도를 보여 주고,
 * {@code status} 가 {@code DONE}·{@code FAILED} 가 되면 폴링을 멈춘다.
 */
public record JobResultResponse(
        @Schema(description = "조사 ID", example = "42")
        Long jobId,

        @Schema(description = "PENDING(접수됨 — 곧 시작) · IN_PROGRESS(진행 중) · DONE(완료) · FAILED(실패). "
                + "FAILED 여도 그때까지의 결과가 tasks 에 있을 수 있다", example = "IN_PROGRESS")
        JobStatus status,

        @Schema(description = "조사할 가게 수", example = "25")
        int targetCount,

        @Schema(description = "끝난 가게 수 (성공·실패 모두)", example = "12")
        int completedCount,

        @Schema(description = "조사를 만든 시각. 끝나지 않아 finishedAt 이 없을 때 화면이 날짜로 쓴다")
        LocalDateTime createdAt,

        @Schema(description = "끝난 시각. 끝나지 않았으면 null", nullable = true)
        LocalDateTime finishedAt,

        @Schema(description = "조사를 멈춘 이유. FAILED 일 때만", nullable = true, example = "AI 조사를 쓸 수 없어 조사를 멈췄습니다")
        String errorMessage,

        @Schema(description = "끝난 가게의 결과. taskId 오름차순")
        List<TaskResultResponse> tasks
) {

    /** 조사와 그 결과로 응답을 만든다. */
    public static JobResultResponse of(Job job, List<TaskResultResponse> tasks) {
        return new JobResultResponse(job.getJobId(), job.getStatus(), job.getTargetCount(), job.getCompletedCount(),
                job.getCreatedAt(), job.getFinishedAt(), job.getErrorMessage(), List.copyOf(tasks));
    }
}
