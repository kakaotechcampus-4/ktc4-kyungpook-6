package com.ktc4.backend.domain.job.dto;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobResultResponseTest {

    private static final JobTargetStore FIRST = new JobTargetStore(1L, "첫 가게", "대구 북구", "053-111-1111");
    private static final JobTargetStore SECOND = new JobTargetStore(2L, "둘째 가게", "대구 중구", null);

    @Test
    void pendingStoresExcludeCompletedTasks() {
        Job job = Job.builder().requestedBy("7").status(JobStatus.IN_PROGRESS)
                .targetCount(2).targetStores(List.of(FIRST, SECOND)).completedCount(1).build();
        TaskResultResponse completed = new TaskResultResponse(1L, 1L, "첫 가게", "대구 북구",
                null, null, null, null, null, List.of(), List.of(), null, null);

        JobResultResponse result = JobResultResponse.of(job, List.of(completed));

        assertThat(result.tasks()).containsExactly(completed);
        assertThat(result.pendingStores()).containsExactly(SECOND);
    }

    @Test
    void finishedJobHasNoPendingStores() {
        Job job = Job.builder().requestedBy("7").status(JobStatus.FAILED)
                .targetCount(2).targetStores(List.of(FIRST, SECOND)).completedCount(1).build();

        assertThat(JobResultResponse.of(job, List.of()).pendingStores()).isEmpty();
    }
}
