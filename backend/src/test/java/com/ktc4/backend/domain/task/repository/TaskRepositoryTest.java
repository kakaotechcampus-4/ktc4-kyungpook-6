package com.ktc4.backend.domain.task.repository;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskRepositoryTest extends PostgresContainerTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Job job;
    private Store store;

    @BeforeEach
    void setUp() {
        job = jobRepository.saveAndFlush(Job.builder()
                .requestedBy("hongjungi")
                .status(JobStatus.PENDING)
                .targetCount(1)
                .completedCount(0)
                .build());

        store = storeRepository.saveAndFlush(Store.builder()
                .name("가나가게")
                .nameNormalized("가나가게")
                .addressRoad("서울시 강남구 테헤란로 1")
                .addressNormalized("서울시 강남구 테헤란로 1")
                .build());
    }

    @Test
    void 저장한_Task를_조회하면_Job과_Store를_함께_참조한다() {
        Task task = Task.builder()
                .job(job)
                .store(store)
                .classification(TaskClassification.PRIORITY_CHECK)
                .build();

        Long savedId = entityManager.persistAndFlush(task).getTaskId();
        entityManager.clear();

        Task found = taskRepository.findById(savedId).orElseThrow();

        assertThat(found.getJob().getJobId()).isEqualTo(job.getJobId());
        assertThat(found.getStore().getStoreId()).isEqualTo(store.getStoreId());
        assertThat(found.getClassification()).isEqualTo(TaskClassification.PRIORITY_CHECK);
        assertThat(found.getProposedChanges()).isNull();
    }

    @Test
    void proposedChanges는_JSON으로_저장되고_그대로_조회된다() {
        Map<String, Object> changes = Map.of("status", "CLOSED", "phone", "02-1234-5678");
        Task task = Task.builder()
                .job(job)
                .store(store)
                .classification(TaskClassification.ADDITIONAL_CHECK)
                .proposedChanges(changes)
                .build();

        Long savedId = entityManager.persistAndFlush(task).getTaskId();
        entityManager.clear();

        assertThat(taskRepository.findById(savedId).orElseThrow().getProposedChanges())
                .isEqualTo(changes);
    }

    @ParameterizedTest
    @EnumSource(TaskClassification.class)
    void classification은_모든_enum_값이_왕복된다(TaskClassification classification) {
        Task task = Task.builder()
                .job(job)
                .store(store)
                .classification(classification)
                .build();

        Long savedId = entityManager.persistAndFlush(task).getTaskId();
        entityManager.clear();

        assertThat(taskRepository.findById(savedId).orElseThrow().getClassification())
                .isEqualTo(classification);
    }

    @Test
    void job이_없으면_저장에_실패한다() {
        Task task = Task.builder()
                .store(store)
                .classification(TaskClassification.NO_CHANGE)
                .build();

        assertThatThrownBy(() -> taskRepository.saveAndFlush(task))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void store가_없으면_저장에_실패한다() {
        Task task = Task.builder()
                .job(job)
                .classification(TaskClassification.NO_CHANGE)
                .build();

        assertThatThrownBy(() -> taskRepository.saveAndFlush(task))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 조사에_실패한_Task는_분류_없이_실패_이유와_함께_저장된다() {
        // 실패는 판정이 없다는 뜻이라 분류를 비워 둔다 — AI 가 실패를 표현하는 방식(classification=None + failure)과 같다.
        Task task = Task.builder()
                .job(job)
                .store(store)
                .failureReason("AI 응답 시간 초과")
                .build();

        Long savedId = entityManager.persistAndFlush(task).getTaskId();
        entityManager.clear();

        Task found = taskRepository.findById(savedId).orElseThrow();

        assertThat(found.getClassification()).isNull();
        assertThat(found.getFailureReason()).isEqualTo("AI 응답 시간 초과");
        assertThat(found.isFailed()).isTrue();
    }

    @Test
    void 판정이_있는_Task는_실패_이유가_비어_있다() {
        Long savedId = entityManager.persistAndFlush(Task.builder()
                .job(job)
                .store(store)
                .classification(TaskClassification.NO_CHANGE)
                .build()).getTaskId();
        entityManager.clear();

        Task found = taskRepository.findById(savedId).orElseThrow();

        assertThat(found.getFailureReason()).isNull();
        assertThat(found.isFailed()).isFalse();
    }

    @Test
    void failureReason은_500자까지_저장된다() {
        String longest = "가".repeat(500);

        Long savedId = entityManager.persistAndFlush(Task.builder()
                .job(job)
                .store(store)
                .failureReason(longest)
                .build()).getTaskId();
        entityManager.clear();

        assertThat(taskRepository.findById(savedId).orElseThrow().getFailureReason()).isEqualTo(longest);
    }

    @Test
    void 분류도_실패_이유도_없으면_만들_수_없다() {
        // 둘 다 없으면 "판정했다"도 "실패했다"도 아닌 Task 가 된다 — 화면이 어느 섹션에도 넣지 못한다.
        assertThatThrownBy(() -> Task.builder().job(job).store(store).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 분류와_실패_이유가_둘_다_있으면_만들_수_없다() {
        assertThatThrownBy(() -> Task.builder()
                .job(job)
                .store(store)
                .classification(TaskClassification.PRIORITY_CHECK)
                .failureReason("AI 응답 시간 초과")
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 존재하지_않는_Job을_참조하면_저장에_실패한다() {
        // getReference는 실제로 조회하지 않고 프록시만 만든다 — DB에 없는 FK 값을 일부러 참조시키는 용도.
        Job nonExistentJob = entityManager.getEntityManager().getReference(Job.class, 999_999L);
        Task task = Task.builder()
                .job(nonExistentJob)
                .store(store)
                .classification(TaskClassification.NO_CHANGE)
                .build();

        assertThatThrownBy(() -> taskRepository.saveAndFlush(task))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
