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
import java.util.List;

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

    @Test
    void 상태_메서드로_바꾼_값이_변경_감지로_DB에_반영된다() {
        // 실행기는 엔티티를 읽어 상태 메서드만 부르고 save 를 따로 부르지 않는다 — 변경 감지로 UPDATE 가 나가야 한다.
        Long savedId = entityManager.persistAndFlush(Job.builder()
                .requestedBy("1")
                .status(JobStatus.PENDING)
                .targetCount(2)
                .completedCount(0)
                .build()).getJobId();
        entityManager.clear();

        LocalDateTime failedAt = LocalDateTime.of(2026, 10, 6, 12, 0);
        Job job = jobRepository.findById(savedId).orElseThrow();
        job.start();
        job.recordProgress();
        job.fail("AI 조사를 쓸 수 없습니다", failedAt);
        entityManager.flush();
        entityManager.clear();

        Job found = jobRepository.findById(savedId).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(found.getCompletedCount()).isEqualTo(1);
        assertThat(found.getErrorMessage()).isEqualTo("AI 조사를 쓸 수 없습니다");
        assertThat(found.getFinishedAt()).isEqualTo(failedAt);
    }

    @Test
    void 기동_전에_만든_끝나지_않은_Job만_한번에_실패로_바꾼다() {
        Long pending = persistJob(JobStatus.PENDING);
        Long inProgress = persistJob(JobStatus.IN_PROGRESS);
        Long done = persistJob(JobStatus.DONE);
        LocalDateTime failedAt = LocalDateTime.of(2026, 10, 6, 12, 0);

        // 방금 만든 Job 들이 "기동 전"이 되도록 기준을 미래로 잡는다
        int changed = jobRepository.failUnfinishedCreatedBefore(
                List.of(JobStatus.PENDING, JobStatus.IN_PROGRESS), LocalDateTime.now().plusMinutes(1),
                "서버 재시작으로 조사가 중단됐습니다", failedAt);
        // 벌크 UPDATE 는 영속성 컨텍스트를 거치지 않는다 — 비운 뒤 다시 읽어야 DB 값이다
        entityManager.clear();

        assertThat(changed).isEqualTo(2);
        for (Long id : List.of(pending, inProgress)) {
            Job found = jobRepository.findById(id).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(found.getErrorMessage()).isEqualTo("서버 재시작으로 조사가 중단됐습니다");
            assertThat(found.getFinishedAt()).isEqualTo(failedAt);
            assertThat(found.getUpdatedAt()).isEqualTo(failedAt);
        }
        assertThat(jobRepository.findById(done).orElseThrow().getStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void 기동_뒤에_만든_Job은_정리하지_않는다() {
        Long pending = persistJob(JobStatus.PENDING);

        int changed = jobRepository.failUnfinishedCreatedBefore(
                List.of(JobStatus.PENDING, JobStatus.IN_PROGRESS), LocalDateTime.now().minusMinutes(1),
                "서버 재시작으로 조사가 중단됐습니다", LocalDateTime.now());
        entityManager.clear();

        assertThat(changed).isZero();
        assertThat(jobRepository.findById(pending).orElseThrow().getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void 가장_최근에_만든_Job을_찾는다() {
        persistJob(JobStatus.DONE);
        Long latest = persistJob(JobStatus.IN_PROGRESS);
        entityManager.clear();

        assertThat(jobRepository.findFirstByOrderByJobIdDesc()).get()
                .extracting(Job::getJobId).isEqualTo(latest);
    }

    private Long persistJob(JobStatus status) {
        return entityManager.persistAndFlush(Job.builder()
                .requestedBy("1")
                .status(status)
                .targetCount(1)
                .completedCount(0)
                .build()).getJobId();
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
