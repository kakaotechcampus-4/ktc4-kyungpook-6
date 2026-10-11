package com.ktc4.backend.domain.signal.repository;

import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.signal.entity.Signal;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalSource;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
                .classification(TaskClassification.PRIORITY_CHECK)
                .build());
    }

    @Test
    void 저장한_Signal을_조회하면_Task를_함께_참조한다() {
        Signal signal = requiredFields()
                .confidence(0.87)
                .build();

        Long savedId = entityManager.persistAndFlush(signal).getSignalId();
        entityManager.clear();

        Signal found = signalRepository.findById(savedId).orElseThrow();

        assertThat(found.getTask().getTaskId()).isEqualTo(task.getTaskId());
        assertThat(found.getSignalType()).isEqualTo(SignalType.SIGNAL_HIGH);
        assertThat(found.getSource()).isEqualTo(SignalSource.NTS);
        assertThat(found.getField()).isEqualTo(ChangeField.STATUS);
        assertThat(found.getConfidence()).isEqualTo(0.87);
        assertThat(found.getObserved()).isNull();
        assertThat(found.getEvidenceText()).isNull();
        assertThat(found.getEvidenceUrl()).isNull();
    }

    @Test
    void confidence와_observed가_없어도_저장된다() {
        Long savedId = entityManager.persistAndFlush(requiredFields().build()).getSignalId();
        entityManager.clear();

        Signal found = signalRepository.findById(savedId).orElseThrow();

        assertThat(found.getConfidence()).isNull();
        assertThat(found.getObserved()).isNull();
    }

    @Test
    void 선택값은_있으면_그대로_저장된다() {
        Signal signal = requiredFields()
                .signalType(SignalType.SIGNAL_LOW)
                .observed("CLOSED")
                .evidenceText("검색 결과 상 폐업 안내 문구 발견")
                .evidenceUrl("https://example.com/notice")
                .build();

        Long savedId = entityManager.persistAndFlush(signal).getSignalId();
        entityManager.clear();

        Signal found = signalRepository.findById(savedId).orElseThrow();

        assertThat(found.getObserved()).isEqualTo("CLOSED");
        assertThat(found.getEvidenceText()).isEqualTo("검색 결과 상 폐업 안내 문구 발견");
        assertThat(found.getEvidenceUrl()).isEqualTo("https://example.com/notice");
    }

    @Test
    void observed는_가게_주소_최대_길이인_500자까지_저장된다() {
        String longest = "가".repeat(500);
        Signal signal = requiredFields()
                .field(ChangeField.ADDRESS_ROAD)
                .observed(longest)
                .build();

        Long savedId = entityManager.persistAndFlush(signal).getSignalId();
        entityManager.clear();

        assertThat(signalRepository.findById(savedId).orElseThrow().getObserved()).isEqualTo(longest);
    }

    @Test
    void 한_Task에_출처가_다른_Signal이_같은_항목을_다른_값으로_가리킬_수_있다() {
        // "폐업인데 이전 개업" — 국세청은 폐업, 웹은 영업 중. 둘 다 남아야 담당자가 비교할 수 있다.
        entityManager.persist(requiredFields().source(SignalSource.NTS).observed("CLOSED").build());
        entityManager.persist(requiredFields().source(SignalSource.AI_WEB).observed("OPEN").build());
        entityManager.flush();
        entityManager.clear();

        List<Signal> found = signalRepository.findAll();

        assertThat(found)
                .extracting(Signal::getSource, Signal::getField, Signal::getObserved)
                .containsExactlyInAnyOrder(
                        tuple(SignalSource.NTS, ChangeField.STATUS, "CLOSED"),
                        tuple(SignalSource.AI_WEB, ChangeField.STATUS, "OPEN"));
    }

    @ParameterizedTest
    @EnumSource(SignalType.class)
    void signalType은_모든_enum_값이_왕복된다(SignalType signalType) {
        Long savedId = entityManager.persistAndFlush(requiredFields().signalType(signalType).build()).getSignalId();
        entityManager.clear();

        assertThat(signalRepository.findById(savedId).orElseThrow().getSignalType()).isEqualTo(signalType);
    }

    @ParameterizedTest
    @EnumSource(SignalSource.class)
    void source는_모든_enum_값이_왕복된다(SignalSource source) {
        Long savedId = entityManager.persistAndFlush(requiredFields().source(source).build()).getSignalId();
        entityManager.clear();

        assertThat(signalRepository.findById(savedId).orElseThrow().getSource()).isEqualTo(source);
    }

    @ParameterizedTest
    @EnumSource(ChangeField.class)
    void field는_모든_enum_값이_왕복된다(ChangeField field) {
        Long savedId = entityManager.persistAndFlush(requiredFields().field(field).build()).getSignalId();
        entityManager.clear();

        assertThat(signalRepository.findById(savedId).orElseThrow().getField()).isEqualTo(field);
    }

    @Test
    void source와_field는_DB에_상수_이름_문자열로_저장된다() {
        // 왕복 테스트는 ORDINAL(숫자)로 바뀌어도 통과한다. 상수 순서를 바꾸는 순간 기존 데이터의 뜻이
        // 바뀌므로, DB 에 실제로 무엇이 들어갔는지를 직접 읽어 본다.
        Long savedId = entityManager.persistAndFlush(requiredFields()
                .source(SignalSource.AI_WEB)
                .field(ChangeField.ADDRESS_ROAD)
                .build()).getSignalId();

        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT source, field FROM signal WHERE signal_id = :id")
                .setParameter("id", savedId)
                .getSingleResult();

        assertThat(row).containsExactly("AI_WEB", "ADDRESS_ROAD");
    }

    @Test
    void task가_없으면_저장에_실패한다() {
        Signal signal = requiredFields().task(null).build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void signalType이_없으면_저장에_실패한다() {
        Signal signal = requiredFields().signalType(null).build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void source가_없으면_저장에_실패한다() {
        Signal signal = requiredFields().source(null).build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void field가_없으면_저장에_실패한다() {
        Signal signal = requiredFields().field(null).build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1})
    void confidence가_0과_1_사이를_벗어나면_생성_시점에_예외를_던진다(double outOfRange) {
        assertThatThrownBy(() -> requiredFields()
                .confidence(outOfRange)
                .build())
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void 존재하지_않는_Task를_참조하면_저장에_실패한다() {
        Task nonExistentTask = entityManager.getEntityManager().getReference(Task.class, 999_999L);
        Signal signal = requiredFields().task(nonExistentTask).build();

        assertThatThrownBy(() -> signalRepository.saveAndFlush(signal))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** NOT NULL 컬럼만 채운 빌더. 각 테스트가 보려는 값만 덮어쓴다. */
    private Signal.SignalBuilder requiredFields() {
        return Signal.builder()
                .task(task)
                .signalType(SignalType.SIGNAL_HIGH)
                .source(SignalSource.NTS)
                .field(ChangeField.STATUS);
    }
}
