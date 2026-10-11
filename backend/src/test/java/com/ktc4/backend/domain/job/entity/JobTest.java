package com.ktc4.backend.domain.job.entity;

import com.ktc4.backend.domain.job.enums.JobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Job 상태 전이 규칙.
 *
 * <p>실행기(@Async)와 서버 재시작 정리가 같은 Job 을 건드릴 수 있어, 순서가 어긋난 호출이 서로의 결과를
 * 덮어쓰지 않도록 허용된 전이만 통과시킨다.
 */
@DisplayName("Job 상태 전이")
class JobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 12, 0);

    private static Job job(JobStatus status, int targetCount, int completedCount) {
        return Job.builder()
                .requestedBy("1")
                .status(status)
                .targetCount(targetCount)
                .completedCount(completedCount)
                .build();
    }

    @Test
    @DisplayName("대기 중인 Job 을 시작하면 진행 중이 된다")
    void startsPendingJob() {
        Job job = job(JobStatus.PENDING, 3, 0);

        job.start();

        assertThat(job.getStatus()).isEqualTo(JobStatus.IN_PROGRESS);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"IN_PROGRESS", "DONE", "FAILED"})
    @DisplayName("대기 중이 아닌 Job 은 다시 시작할 수 없다")
    void cannotStartUnlessPending(JobStatus status) {
        Job job = job(status, 3, 0);

        assertThatThrownBy(job::start).isInstanceOf(IllegalStateException.class);
        assertThat(job.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("진행 중인 Job 은 한 건 끝날 때마다 완료 수가 1씩 오른다")
    void recordsProgress() {
        Job job = job(JobStatus.IN_PROGRESS, 2, 0);

        job.recordProgress();
        job.recordProgress();

        assertThat(job.getCompletedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("완료 수는 대상 수를 넘을 수 없다 — 넘으면 같은 가게를 두 번 셌다는 뜻이다")
    void cannotExceedTargetCount() {
        Job job = job(JobStatus.IN_PROGRESS, 1, 1);

        assertThatThrownBy(job::recordProgress).isInstanceOf(IllegalStateException.class);
        assertThat(job.getCompletedCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"PENDING", "DONE", "FAILED"})
    @DisplayName("진행 중이 아니면 완료 수를 올릴 수 없다")
    void cannotRecordProgressUnlessInProgress(JobStatus status) {
        Job job = job(status, 3, 0);

        assertThatThrownBy(job::recordProgress).isInstanceOf(IllegalStateException.class);
        assertThat(job.getCompletedCount()).isZero();
    }

    @Test
    @DisplayName("진행 중인 Job 을 끝내면 완료가 되고 끝난 시각이 남는다")
    void finishesInProgressJob() {
        Job job = job(JobStatus.IN_PROGRESS, 1, 1);

        job.finish(NOW);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(job.getFinishedAt()).isEqualTo(NOW);
        assertThat(job.getErrorMessage()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"PENDING", "DONE", "FAILED"})
    @DisplayName("진행 중이 아니면 완료로 끝낼 수 없다")
    void cannotFinishUnlessInProgress(JobStatus status) {
        Job job = job(status, 1, 0);

        assertThatThrownBy(() -> job.finish(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(job.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"PENDING", "IN_PROGRESS"})
    @DisplayName("끝나지 않은 Job 은 실패로 끝낼 수 있고, 이유와 끝난 시각이 남는다")
    void failsUnfinishedJob(JobStatus status) {
        Job job = job(status, 3, 1);

        job.fail("AI 조사를 쓸 수 없습니다", NOW);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("AI 조사를 쓸 수 없습니다");
        assertThat(job.getFinishedAt()).isEqualTo(NOW);
        assertThat(job.getCompletedCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"DONE", "FAILED"})
    @DisplayName("이미 끝난 Job 은 실패로 덮어쓰지 않는다")
    void cannotFailFinishedJob(JobStatus status) {
        Job job = job(status, 1, 1);

        assertThatThrownBy(() -> job.fail("늦게 온 실패", NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getErrorMessage()).isNull();
    }
}
