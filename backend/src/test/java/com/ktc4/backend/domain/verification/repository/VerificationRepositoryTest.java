package com.ktc4.backend.domain.verification.repository;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.repository.TaskRepository;
import com.ktc4.backend.domain.verification.entity.Verification;
import com.ktc4.backend.domain.verification.enums.VerificationAction;
import com.ktc4.backend.domain.verification.enums.VerificationResult;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationRepositoryTest extends PostgresContainerTest {

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Store store;
    private Task task;

    @BeforeEach
    void setUp() {
        Job job = jobRepository.saveAndFlush(Job.builder()
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

        task = taskRepository.saveAndFlush(Task.builder()
                .job(job)
                .store(store)
                .classification(TaskClassification.PRIORITY_CHECK)
                .build());
    }

    @Test
    void 저장한_Verification을_조회하면_Store와_Task를_함께_참조한다() {
        LocalDateTime verifiedAt = LocalDateTime.now();
        Verification verification = Verification.builder()
                .store(store)
                .task(task)
                .result(VerificationResult.CONFIRMED)
                .action(VerificationAction.UPDATE_INFO)
                .verifiedBy("hongjungi")
                .verifiedAt(verifiedAt)
                .build();

        Long savedId = entityManager.persistAndFlush(verification).getVerificationId();
        entityManager.clear();

        Verification found = verificationRepository.findById(savedId).orElseThrow();

        assertThat(found.getStore().getStoreId()).isEqualTo(store.getStoreId());
        assertThat(found.getTask().getTaskId()).isEqualTo(task.getTaskId());
        assertThat(found.getResult()).isEqualTo(VerificationResult.CONFIRMED);
        assertThat(found.getAction()).isEqualTo(VerificationAction.UPDATE_INFO);
        assertThat(found.getVerifiedBy()).isEqualTo("hongjungi");
        assertThat(found.getVerifiedAt()).isEqualToIgnoringNanos(verifiedAt);
        assertThat(found.getAppliedChanges()).isNull();
        assertThat(found.getNote()).isNull();
    }

    @Test
    void task_없이_담당자가_직접_수동으로_확인한_경우도_저장된다() {
        Verification verification = Verification.builder()
                .store(store)
                .task(null)
                .result(VerificationResult.NOT_APPLICABLE)
                .action(VerificationAction.NO_ACTION)
                .verifiedBy("hongjungi")
                .verifiedAt(LocalDateTime.now())
                .build();

        Long savedId = entityManager.persistAndFlush(verification).getVerificationId();
        entityManager.clear();

        assertThat(verificationRepository.findById(savedId).orElseThrow().getTask()).isNull();
    }

    @Test
    void appliedChanges는_JSON으로_저장되고_그대로_조회된다() {
        Map<String, Object> changes = Map.of("status", "CLOSED");
        Verification verification = Verification.builder()
                .store(store)
                .task(task)
                .result(VerificationResult.CONFIRMED)
                .action(VerificationAction.UPDATE_INFO)
                .appliedChanges(changes)
                .note("전화로 폐업 확인함")
                .verifiedBy("hongjungi")
                .verifiedAt(LocalDateTime.now())
                .build();

        Long savedId = entityManager.persistAndFlush(verification).getVerificationId();
        entityManager.clear();

        Verification found = verificationRepository.findById(savedId).orElseThrow();
        assertThat(found.getAppliedChanges()).isEqualTo(changes);
        assertThat(found.getNote()).isEqualTo("전화로 폐업 확인함");
    }

    @ParameterizedTest
    @EnumSource(VerificationResult.class)
    void result은_모든_enum_값이_왕복된다(VerificationResult result) {
        Verification verification = Verification.builder()
                .store(store)
                .result(result)
                .action(VerificationAction.NO_ACTION)
                .verifiedBy("hongjungi")
                .verifiedAt(LocalDateTime.now())
                .build();

        Long savedId = entityManager.persistAndFlush(verification).getVerificationId();
        entityManager.clear();

        assertThat(verificationRepository.findById(savedId).orElseThrow().getResult()).isEqualTo(result);
    }

    @ParameterizedTest
    @EnumSource(VerificationAction.class)
    void action은_모든_enum_값이_왕복된다(VerificationAction action) {
        Verification verification = Verification.builder()
                .store(store)
                .result(VerificationResult.PENDING)
                .action(action)
                .verifiedBy("hongjungi")
                .verifiedAt(LocalDateTime.now())
                .build();

        Long savedId = entityManager.persistAndFlush(verification).getVerificationId();
        entityManager.clear();

        assertThat(verificationRepository.findById(savedId).orElseThrow().getAction()).isEqualTo(action);
    }

    @Test
    void store가_없으면_저장에_실패한다() {
        Verification verification = Verification.builder()
                .result(VerificationResult.PENDING)
                .action(VerificationAction.NO_ACTION)
                .verifiedBy("hongjungi")
                .verifiedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> verificationRepository.saveAndFlush(verification))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void verifiedBy가_없으면_저장에_실패한다() {
        Verification verification = Verification.builder()
                .store(store)
                .result(VerificationResult.PENDING)
                .action(VerificationAction.NO_ACTION)
                .verifiedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> verificationRepository.saveAndFlush(verification))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void verifiedAt이_없으면_저장에_실패한다() {
        Verification verification = Verification.builder()
                .store(store)
                .result(VerificationResult.PENDING)
                .action(VerificationAction.NO_ACTION)
                .verifiedBy("hongjungi")
                .build();

        assertThatThrownBy(() -> verificationRepository.saveAndFlush(verification))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 존재하지_않는_Store를_참조하면_저장에_실패한다() {
        Store nonExistentStore = entityManager.getEntityManager().getReference(Store.class, 999_999L);
        Verification verification = Verification.builder()
                .store(nonExistentStore)
                .result(VerificationResult.PENDING)
                .action(VerificationAction.NO_ACTION)
                .verifiedBy("hongjungi")
                .verifiedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> verificationRepository.saveAndFlush(verification))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
