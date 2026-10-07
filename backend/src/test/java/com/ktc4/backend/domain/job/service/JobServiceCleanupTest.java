package com.ktc4.backend.domain.job.service;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.store.service.InvestigationTargetSelector;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.service.VerificationService;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버 재시작 정리 — 어떤 Job 을 실패로 바꾸고 어떤 Job 은 그대로 두는지.
 */
// 조사 쪽 DB 테스트는 같은 빈 묶음을 써서 스프링 테스트 컨텍스트(와 DB 연결 풀) 하나를 함께 쓴다
@Import({JobQueryService.class, JobService.class, TaskService.class, StoreService.class,
        InvestigationTargetSelector.class, VerificationService.class})
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@DisplayName("서버 재시작 정리 (JobService.failUnfinishedBefore)")
class JobServiceCleanupTest extends PostgresContainerTest {

    private static final LocalDateTime BOOTED_AT = LocalDateTime.of(2026, 10, 6, 9, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 9, 1);

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TestEntityManager entityManager;

    // 만든 시각은 Auditing 이 정하므로, 기동 시각 앞뒤에 놓으려고 DB 값을 직접 고친다
    private Long job(JobStatus status, LocalDateTime createdAt) {
        Long id = entityManager.persistAndFlush(Job.builder()
                .requestedBy("1").status(status).targetCount(2).completedCount(1).build()).getJobId();
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE job SET created_at = :createdAt WHERE job_id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate();
        return id;
    }

    private Job reload(Long id) {
        entityManager.clear();
        return jobRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("기동 전에 만든 대기·진행 중 Job 만 실패로 바꾸고, 끝난 Job 과 진행 수는 그대로 둔다")
    void failsOnlyUnfinishedJobsCreatedBeforeBoot() {
        Long pending = job(JobStatus.PENDING, BOOTED_AT.minusHours(1));
        Long inProgress = job(JobStatus.IN_PROGRESS, BOOTED_AT.minusMinutes(5));
        Long done = job(JobStatus.DONE, BOOTED_AT.minusHours(1));
        Long failed = job(JobStatus.FAILED, BOOTED_AT.minusHours(1));

        int cleaned = jobService.failUnfinishedBefore(BOOTED_AT, "서버 재시작으로 조사가 중단됐습니다", NOW);

        assertThat(cleaned).isEqualTo(2);
        for (Long id : new Long[]{pending, inProgress}) {
            Job job = reload(id);
            assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(job.getErrorMessage()).isEqualTo("서버 재시작으로 조사가 중단됐습니다");
            assertThat(job.getFinishedAt()).isEqualTo(NOW);
            assertThat(job.getCompletedCount()).isEqualTo(1);
        }
        assertThat(reload(done).getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(reload(failed).getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("기동한 시각과 같거나 그 뒤에 만든 Job 은 건드리지 않는다 — 웹 서버가 정리보다 먼저 요청을 받을 수 있다")
    void keepsJobsCreatedAtOrAfterBoot() {
        Long atBoot = job(JobStatus.PENDING, BOOTED_AT);
        Long afterBoot = job(JobStatus.IN_PROGRESS, BOOTED_AT.plusSeconds(1));

        int cleaned = jobService.failUnfinishedBefore(BOOTED_AT, "서버 재시작으로 조사가 중단됐습니다", NOW);

        assertThat(cleaned).isZero();
        assertThat(reload(atBoot).getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(reload(afterBoot).getStatus()).isEqualTo(JobStatus.IN_PROGRESS);
    }
}
