package com.ktc4.backend.domain.signal.repository;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.signal.entity.Signal;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.repository.TaskRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalRepositoryTest extends PostgresContainerTest {

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Task task;

    @BeforeEach
    void setUp() {
        Job job = jobRepository.saveAndFlush(Job.builder()
                .requestedBy("hongjungi")
                .status(JobStatus.PENDING)
                .targetCount(1)
                .completedCount(0)
                .build());

        Store store = storeRepository.saveAndFlush(Store.builder()
                .name("가나가게")
                .nameNormalized("가나가게")
                .addressRoad("서울시 강남구 테헤란로 1")
                .addressNormalized("서울시 강남구 테헤란로 1")
                .build());

        task = taskRepository.saveAndFlush(Task.builder()
                .job(job)
                .store(store)
                .classification(TaskClassification.TASK_HIGH)
                .build());
    }

    @Test
    void 저장한_Signal을_조회하면_Task를_함께_참조한다() {
        Signal signal = Signal.builder()
                .task(task)
                .signalType(SignalType.SIGNAL_HIGH)
                .confidence(0.87)
                .build();

        Long savedId = entityManager.persistAndFlush(signal).getSignalId();
        entityManager.clear();

        Signal found = signalRepository.findById(savedId).orElseThrow();

        assertThat(found.getTask().getTaskId()).isEqualTo(task.getTaskId());
        assertThat(found.getSignalType()).isEqualTo(SignalType.SIGNAL_HIGH);
        assertThat(found.getConfidence()).isEqualTo(0.87);
        assertThat(found.getEvidenceText()).isNull();
        assertThat(found.getEvidenceUrl()).isNull();
    }

    @Test
    void evidenceText와_evidenceUrl은_선택값이지만_있으면_그대로_저장된다() {
        Signal signal = Signal.builder()
                .task(task)
                .signalType(SignalType.SIGNAL_LOW)
                .confidence(0.5)
                .evidenceText("검색 결과 상 폐업 안내 문구 발견")
                .evidenceUrl("https://example.com/notice")
                .build();

        Long savedId = entityManager.persistAndFlush(signal).getSignalId();
        entityManager.clear();

        Signal found = signalRepository.findById(savedId).orElseThrow();

        assertThat(found.getEvidenceText()).isEqualTo("검색 결과 상 폐업 안내 문구 발견");
        assertThat(found.getEvidenceUrl()).isEqualTo("https://example.com/notice");
    }

    @ParameterizedTest
    @EnumSource(SignalType.class)
    void signalType은_모든_enum_값이_왕복된다(SignalType signalType) {
        Signal signal = Signal.builder()
                .task(task)
                .signalType(signalType)
                .confidence(0.5)
                .build();

        Long savedId = entityManager.persistAndFlush(signal).getSignalId();
        entityManager.clear();

        assertThat(signalRepository.findById(savedId).orElseThrow().getSignalType()).isEqualTo(signalType);
    }

    @Test
    void task가_없으면_저장에_실패한다() {
        Signal signal = Signal.builder()
                .signalType(SignalType.SIGNAL_HIGH)
                .confidence(0.5)
                .build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void signalType이_없으면_저장에_실패한다() {
        Signal signal = Signal.builder()
                .task(task)
                .confidence(0.5)
                .build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void confidence가_없으면_저장에_실패한다() {
        Signal signal = Signal.builder()
                .task(task)
                .signalType(SignalType.SIGNAL_HIGH)
                .build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    void confidence가_0과_1_사이를_벗어나면_생성_시점에_예외를_던진다(double outOfRange) {
        assertThatThrownBy(() -> Signal.builder()
                .task(task)
                .signalType(SignalType.SIGNAL_HIGH)
                .confidence(outOfRange)
                .build())
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void 존재하지_않는_Task를_참조하면_저장에_실패한다() {
        Task nonExistentTask = entityManager.getEntityManager().getReference(Task.class, 999_999L);
        Signal signal = Signal.builder()
                .task(nonExistentTask)
                .signalType(SignalType.SIGNAL_HIGH)
                .confidence(0.5)
                .build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
