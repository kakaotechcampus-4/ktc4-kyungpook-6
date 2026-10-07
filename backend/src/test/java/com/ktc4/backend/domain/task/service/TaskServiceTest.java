package com.ktc4.backend.domain.task.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.AiSignal;
import com.ktc4.backend.domain.investigation.dto.NtsTarget;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.signal.entity.Signal;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalSource;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.signal.repository.SignalRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.InvestigationTargetSelector;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.repository.TaskRepository;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조사 결과 저장 — Task·Signal 이 실제 DB 에 어떤 값으로 남는지와, 진행 수가 함께 오르는지.
 */
// 조사 쪽 DB 테스트는 같은 빈 묶음을 써서 스프링 테스트 컨텍스트(와 DB 연결 풀) 하나를 함께 쓴다
@Import({JobQueryService.class, JobService.class, TaskService.class, StoreService.class,
        InvestigationTargetSelector.class})
@DisplayName("조사 결과 저장 (TaskService)")
class TaskServiceTest extends PostgresContainerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 12, 0);

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Job job;

    @BeforeEach
    void setUp() {
        job = entityManager.persistAndFlush(Job.builder()
                .requestedBy("7")
                .status(JobStatus.IN_PROGRESS)
                .targetCount(5)
                .completedCount(0)
                .build());
    }

    private Store store(LocalDateTime lastCheckedAt) {
        return entityManager.persistAndFlush(Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN)
                .lastCheckedAt(lastCheckedAt)
                .build());
    }

    // 서비스가 바꾼 Job(진행 수)은 아직 flush 전이라, clear 전에 flush 해야 DB 값을 다시 읽는다
    private Task onlyTask() {
        entityManager.flush();
        entityManager.clear();
        List<Task> tasks = taskRepository.findAll();
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    private List<Signal> signalsOf(Task task) {
        return signalRepository.findAll().stream()
                .filter(signal -> signal.getTask().getTaskId().equals(task.getTaskId()))
                .toList();
    }

    private int completedCount() {
        entityManager.flush();
        entityManager.clear();
        return entityManager.find(Job.class, job.getJobId()).getCompletedCount();
    }

    @Nested
    @DisplayName("1차(국세청) 결과")
    class Nts {

        @Test
        @DisplayName("국세청 상태로 바꾸자는 우선확인 Task 와 국세청 근거 하나를 남긴다")
        void savesNtsResult() {
            Store store = store(null);

            taskService.saveNts(job.getJobId(), new NtsTarget(store.getStoreId(), StatusComparison.OPEN_BUT_CLOSED, BusinessState.CLOSED));

            Task task = onlyTask();
            assertThat(task.getClassification()).isEqualTo(TaskClassification.PRIORITY_CHECK);
            assertThat(task.getProposedChanges()).isEqualTo(Map.of("status", "CLOSED"));
            assertThat(task.getFailureReason()).isNull();

            List<Signal> signals = signalsOf(task);
            assertThat(signals).hasSize(1);
            Signal signal = signals.get(0);
            assertThat(signal.getSource()).isEqualTo(SignalSource.NTS);
            assertThat(signal.getField()).isEqualTo(ChangeField.STATUS);
            assertThat(signal.getObserved()).isEqualTo("CLOSED");
            assertThat(signal.getSignalType()).isEqualTo(SignalType.SIGNAL_HIGH);
            assertThat(signal.getEvidenceText()).contains("폐업자");
            assertThat(signal.getEvidenceUrl()).isNull();
            assertThat(completedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("본 값은 국세청 이름(ACTIVE)이 아니라 가게 상태 이름(OPEN)이다 — 수정안·AI 와 같은 값")
        void observedIsStoreStatusName() {
            Store store = store(null);

            taskService.saveNts(job.getJobId(), new NtsTarget(store.getStoreId(), StatusComparison.CLOSED_BUT_ACTIVE, BusinessState.ACTIVE));

            Task task = onlyTask();
            assertThat(task.getProposedChanges()).isEqualTo(Map.of("status", "OPEN"));
            assertThat(signalsOf(task)).extracting(Signal::getObserved).containsExactly("OPEN");
        }

        @Test
        @DisplayName("1차는 변화가 잡힌 것이라 확인한 지 오래됐어도 우선확인이다 — 추가확인 규칙을 타지 않는다")
        void ntsIgnoresRecheckRule() {
            Store store = store(NOW.minusDays(365));

            taskService.saveNts(job.getJobId(), new NtsTarget(store.getStoreId(), StatusComparison.OPEN_BUT_SUSPENDED, BusinessState.SUSPENDED));

            assertThat(onlyTask().getClassification()).isEqualTo(TaskClassification.PRIORITY_CHECK);
        }
    }

    @Nested
    @DisplayName("2차(AI) 결과")
    class Ai {

        @Test
        @DisplayName("수정안은 Store 필드명 키로, 근거는 AI 출처로 남긴다")
        void savesAiResult() {
            Store store = store(NOW.minusDays(10));
            AiFinding finding = AiFinding.success(store.getStoreId(), TaskClassification.PRIORITY_CHECK,
                    Map.of(ChangeField.PHONE, "053-222-2222", ChangeField.ADDRESS_ROAD, "동성로 5"),
                    List.of(new AiSignal(SignalType.SIGNAL_HIGH, ChangeField.PHONE, "053-222-2222",
                                    "전화번호: 053-222-2222", "https://example.com/a"),
                            new AiSignal(SignalType.SIGNAL_HIGH, ChangeField.ADDRESS_ROAD, "동성로 5",
                                    "주소: 동성로 5", "https://example.com/b")));

            taskService.save(job.getJobId(), finding, NOW);

            Task task = onlyTask();
            assertThat(task.getClassification()).isEqualTo(TaskClassification.PRIORITY_CHECK);
            assertThat(task.getProposedChanges())
                    .isEqualTo(Map.of("phone", "053-222-2222", "addressRoad", "동성로 5"));
            assertThat(signalsOf(task))
                    .extracting(Signal::getSource, Signal::getField, Signal::getObserved, Signal::getEvidenceUrl)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(SignalSource.AI_WEB, ChangeField.PHONE,
                                    "053-222-2222", "https://example.com/a"),
                            org.assertj.core.groups.Tuple.tuple(SignalSource.AI_WEB, ChangeField.ADDRESS_ROAD,
                                    "동성로 5", "https://example.com/b"));
            assertThat(completedCount()).isEqualTo(1);
        }

        @ParameterizedTest(name = "[{index}] 확인한 지 {0}일 → {1}")
        @CsvSource({"89, NO_CHANGE", "90, ADDITIONAL_CHECK", "400, ADDITIONAL_CHECK"})
        @DisplayName("변화가 없어도 확인한 지 90일 이상이면 추가확인 — 정확히 90일째 포함 (용어집 >= 90)")
        void additionalCheckAfter90Days(int days, TaskClassification expected) {
            Store store = store(NOW.minusDays(days));

            taskService.save(job.getJobId(),
                    AiFinding.success(store.getStoreId(), TaskClassification.NO_CHANGE, Map.of(), List.of()), NOW);

            assertThat(onlyTask().getClassification()).isEqualTo(expected);
        }

        @Test
        @DisplayName("한 번도 확인하지 않은 가게는 변화가 없어도 추가확인이다")
        void neverCheckedIsAdditionalCheck() {
            Store store = store(null);

            taskService.save(job.getJobId(),
                    AiFinding.success(store.getStoreId(), TaskClassification.NO_CHANGE, Map.of(), List.of()), NOW);

            assertThat(onlyTask().getClassification()).isEqualTo(TaskClassification.ADDITIONAL_CHECK);
        }

        @Test
        @DisplayName("변화가 잡힌 결과는 확인한 지 오래됐어도 우선확인 그대로다")
        void priorityStaysPriority() {
            Store store = store(null);

            taskService.save(job.getJobId(), AiFinding.success(store.getStoreId(), TaskClassification.PRIORITY_CHECK,
                    Map.of(ChangeField.NAME, "새 상호"),
                    List.of(new AiSignal(SignalType.SIGNAL_HIGH, ChangeField.NAME, "새 상호", "상호: 새 상호", null))),
                    NOW);

            assertThat(onlyTask().getClassification()).isEqualTo(TaskClassification.PRIORITY_CHECK);
        }
    }

    @Test
    @DisplayName("조사에 실패한 가게는 판정 없이 실패 이유만 남기고, 진행 수는 똑같이 오른다")
    void savesFailure() {
        Store store = store(null);

        taskService.saveFailure(job.getJobId(), store.getStoreId(), "AI 응답 시간이 초과됐습니다");

        Task task = onlyTask();
        assertThat(task.getClassification()).isNull();
        assertThat(task.getFailureReason()).isEqualTo("AI 응답 시간이 초과됐습니다");
        assertThat(task.getProposedChanges()).isNullOrEmpty();
        assertThat(signalsOf(task)).isEmpty();
        assertThat(completedCount()).isEqualTo(1);
    }
}
