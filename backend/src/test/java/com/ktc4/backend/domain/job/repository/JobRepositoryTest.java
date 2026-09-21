package com.ktc4.backend.domain.job.repository;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Job 저장/조회 통합 테스트.
 *
 * <p>{@code save() 직후 findById()}는 같은 영속성 컨텍스트의 1차 캐시를 반환할 뿐, 진짜 SELECT를
 * 태우지 않는다({@code IDENTITY} 전략이라 save() 시점 INSERT는 실제로 나가지만, 그 뒤 findById는
 * DB를 안 거침). 그래서 실제로 저장된 값이 맞는지 보려면 {@link TestEntityManager}로 flush 후
 * 영속성 컨텍스트를 비우고(clear) 다시 조회해야 한다.
 */
class JobRepositoryTest extends PostgresContainerTest {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void 저장한_Job을_그대로_조회할_수_있다() {
        Job job = Job.builder()
                .requestedBy("hongjungi")
                .status(JobStatus.PENDING)
                .targetCount(10)
                .completedCount(0)
                .build();

        Long savedId = entityManager.persistAndFlush(job).getJobId();
        entityManager.clear();

        Job found = jobRepository.findById(savedId).orElseThrow();

        assertThat(found.getRequestedBy()).isEqualTo("hongjungi");
        assertThat(found.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(found.getTargetCount()).isEqualTo(10);
        assertThat(found.getCompletedCount()).isEqualTo(0);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getFinishedAt()).isNull();
        assertThat(found.getErrorMessage()).isNull();
    }

    @Test
    void 완료되거나_실패한_Job은_finishedAt과_errorMessage를_가질_수_있다() {
        LocalDateTime finishedAt = LocalDateTime.now();
        Job job = Job.builder()
                .requestedBy("hongjungi")
                .status(JobStatus.FAILED)
                .targetCount(5)
                .completedCount(2)
                .finishedAt(finishedAt)
                .errorMessage("외부 API 타임아웃")
                .build();

        Long savedId = entityManager.persistAndFlush(job).getJobId();
        entityManager.clear();

        Job found = jobRepository.findById(savedId).orElseThrow();

        assertThat(found.getFinishedAt()).isEqualToIgnoringNanos(finishedAt);
        assertThat(found.getErrorMessage()).isEqualTo("외부 API 타임아웃");
    }

    @ParameterizedTest
    @EnumSource(JobStatus.class)
    void status는_모든_enum_값이_왕복된다(JobStatus status) {
        Job job = Job.builder()
                .requestedBy("hongjungi")
                .status(status)
                .targetCount(1)
                .completedCount(0)
                .build();

        Long savedId = entityManager.persistAndFlush(job).getJobId();
        entityManager.clear();

        assertThat(jobRepository.findById(savedId).orElseThrow().getStatus()).isEqualTo(status);
    }

    @Test
    void requestedBy가_없으면_저장에_실패한다() {
        Job job = Job.builder()
                .status(JobStatus.PENDING)
                .targetCount(1)
                .completedCount(0)
                .build();

        assertThatThrownBy(() -> jobRepository.saveAndFlush(job))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void status가_없으면_저장에_실패한다() {
        Job job = Job.builder()
                .requestedBy("hongjungi")
                .targetCount(1)
                .completedCount(0)
                .build();

        assertThatThrownBy(() -> jobRepository.saveAndFlush(job))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
