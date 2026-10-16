package com.ktc4.backend.domain.store.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.checkin.entity.CheckIn;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.signal.entity.Signal;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalSource;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.signal.repository.SignalRepository;
import com.ktc4.backend.domain.store.dto.StoreChange;
import com.ktc4.backend.domain.store.dto.StoreDeleteResponse;
import com.ktc4.backend.domain.store.dto.StoreDeletionRequest;
import com.ktc4.backend.domain.store.dto.StoreDeletionResponse;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.entity.StoreChangeHistory;
import com.ktc4.backend.domain.store.enums.StoreChangeField;
import com.ktc4.backend.domain.store.enums.StoreChangeSource;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsChange;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsChangeRepository;
import com.ktc4.backend.domain.store.ntscheck.repository.StoreNtsCheckRepository;
import com.ktc4.backend.domain.store.repository.StoreChangeHistoryRepository;
import com.ktc4.backend.domain.store.repository.StoreRepository;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.repository.TaskRepository;
import com.ktc4.backend.domain.verification.entity.Verification;
import com.ktc4.backend.domain.verification.enums.VerificationAction;
import com.ktc4.backend.domain.verification.enums.VerificationResult;
import com.ktc4.backend.domain.verification.repository.VerificationRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가게 삭제를 <b>실제 Postgres</b> 에 붙여 확인한다.
 *
 * <p>가짜 리포지터리로는 이 기능의 진짜 위험을 볼 수 없다. 가게를 가리키는 표가 여덟 개인데
 * {@code Store} 에 {@code @OneToMany} 가 없어 cascade 가 걸리지 않으므로, <b>지우는 순서를 틀리면
 * 외래키 제약에 걸려 터진다.</b> 그건 실제 DB 에서만 드러난다.
 *
 * <p>서비스는 {@code @Service} 라 {@code @DataJpaTest} 슬라이스가 기본으로 올리지 않는다 —
 * 리포지터리만 의존하므로 {@code @Import} 로 직접 올린다.
 *
 * <p>가게 이름·주소·사업자등록번호는 모두 꾸며낸 값이다.
 */
@Import(StoreDeletionService.class)
@DisplayName("StoreDeletionService — 실제 DB")
class StoreDeletionServiceIntegrationTest extends PostgresContainerTest {

    @Autowired
    private StoreDeletionService storeDeletionService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private StoreNtsCheckRepository storeNtsCheckRepository;

    @Autowired
    private StoreNtsChangeRepository storeNtsChangeRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private StoreChangeHistoryRepository storeChangeHistoryRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("딸린 기록이 모두 있는 가게를 지운다 — 외래키 순서가 맞는지가 핵심이다")
    void 딸린_기록이_있는_가게를_지운다() {
        Store store = saveStore("가나다분식");
        saveNtsCheck(store);
        saveNtsChange(store);
        Task task = saveFinishedTask(store);
        saveVerification(store, task);
        saveSignal(task);
        entityManager.flush();
        entityManager.clear();

        StoreDeleteResponse response = storeDeletionService.delete(store.getStoreId(), false);

        assertThat(response.storeId()).isEqualTo(store.getStoreId());
        assertThat(response.name()).isEqualTo("가나다분식");
        assertThat(response.ntsChecks()).isEqualTo(1);
        assertThat(response.ntsChanges()).isEqualTo(1);
        assertThat(response.tasks()).isEqualTo(1);
        assertThat(response.verifications()).isEqualTo(1);
        assertThat(response.signals()).isEqualTo(1);

        entityManager.clear();
        assertThat(storeRepository.findById(store.getStoreId())).isEmpty();
        assertThat(storeNtsCheckRepository.count()).isZero();
        assertThat(storeNtsChangeRepository.count()).isZero();
        assertThat(taskRepository.count()).isZero();
        assertThat(verificationRepository.count()).isZero();
        assertThat(signalRepository.count()).isZero();
        // 조사 자체는 남는다 — 어떤 조사를 언제 돌렸는지는 가게와 별개의 기록이다.
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("딸린 기록이 없는 가게도 지운다")
    void 딸린_기록이_없는_가게를_지운다() {
        Store store = saveStore("들꽃반찬");
        entityManager.flush();
        entityManager.clear();

        StoreDeleteResponse response = storeDeletionService.delete(store.getStoreId(), false);

        assertThat(response.ntsChecks()).isZero();
        assertThat(response.tasks()).isZero();
        assertThat(storeRepository.findById(store.getStoreId())).isEmpty();
    }

    @Test
    @DisplayName("없는 가게를 지우면 STORE_NOT_FOUND")
    void 없는_가게는_404() {
        assertThatThrownBy(() -> storeDeletionService.delete(999_999L, false))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STORE_NOT_FOUND));
    }

    @Test
    @DisplayName("조사가 돌고 있으면 지우지 않는다")
    void 조사가_돌면_거절한다() {
        Store store = saveStore("미리내카페");
        Job running = jobRepository.saveAndFlush(Job.builder()
                .requestedBy("admin")
                .status(JobStatus.IN_PROGRESS)
                .targetCount(1)
                .completedCount(0)
                .build());
        entityManager.persistAndFlush(Task.builder()
                .job(running)
                .store(store)
                .classification(TaskClassification.PRIORITY_CHECK)
                .build());
        entityManager.clear();

        assertThatThrownBy(() -> storeDeletionService.delete(store.getStoreId(), false))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STORE_UNDER_INVESTIGATION));

        entityManager.clear();
        assertThat(storeRepository.findById(store.getStoreId())).isPresent();
    }

    @Test
    @DisplayName("조사 차례가 아직 안 온 가게도 거절한다 — Task 가 없어 가게로는 판정할 수 없다")
    void 조사_차례가_안_온_가게도_거절한다() {
        // 조사는 시작할 때 Job 만 만들고, Task 는 가게 한 곳이 끝날 때마다 생긴다. 그래서 이 가게는
        // 조사 대상인데도 어느 표에도 없다. 가게로 물으면 통과해 버리고, 지우면 그 차례에 조사가 멈춘다.
        Store store = saveStore("들꽃반찬");
        jobRepository.saveAndFlush(Job.builder()
                .requestedBy("admin")
                .status(JobStatus.IN_PROGRESS)
                .targetCount(3)
                .completedCount(0)
                .build());
        entityManager.clear();

        assertThat(taskRepository.count()).isZero();
        assertThatThrownBy(() -> storeDeletionService.delete(store.getStoreId(), false))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STORE_UNDER_INVESTIGATION));

        entityManager.clear();
        assertThat(storeRepository.findById(store.getStoreId())).isPresent();
    }

    @Test
    @DisplayName("변경 기록이 남은 가게도 지운다 — #114 로 늘어난 표다")
    void 변경_기록이_있는_가게를_지운다() {
        Store store = saveStore("구름빵집");
        saveChangeHistory(store, StoreChangeField.PHONE, "\"053-000-0000\"", "\"053-111-1111\"");
        saveChangeHistory(store, StoreChangeField.STATUS, "\"OPEN\"", "\"CLOSED\"");
        entityManager.flush();
        entityManager.clear();

        StoreDeleteResponse response = storeDeletionService.delete(store.getStoreId(), false);

        assertThat(response.changeHistories()).isEqualTo(2);
        entityManager.clear();
        assertThat(storeRepository.findById(store.getStoreId())).isEmpty();
        assertThat(storeChangeHistoryRepository.count()).isZero();
    }

    @Test
    @DisplayName("끝난 조사에 들어 있는 가게는 지운다 — 막는 것은 돌고 있을 때만이다")
    void 끝난_조사에_든_가게는_지운다() {
        Store store = saveStore("한빛국밥");
        saveFinishedTask(store);
        entityManager.flush();
        entityManager.clear();

        StoreDeleteResponse response = storeDeletionService.delete(store.getStoreId(), false);

        assertThat(response.tasks()).isEqualTo(1);
        assertThat(storeRepository.findById(store.getStoreId())).isEmpty();
    }

    @Test
    @DisplayName("여러 곳을 지울 때, 거절된 가게는 결과에 담고 나머지는 지운다")
    void 여러곳_부분_성공() {
        // 거절 사유는 가게 단위 조건(체크인)으로 만든다 — 조사는 조사 단위로 막으므로 한 곳만 거절하는
        // 상황을 만들 수 없다.
        Store deletable = saveStore("구름빵집");
        Store blocked = saveStore("해오름식당");
        entityManager.persistAndFlush(CheckIn.of(1L, blocked));
        entityManager.clear();

        StoreDeletionResponse response = storeDeletionService.deleteAll(new StoreDeletionRequest(
                List.of(deletable.getStoreId(), blocked.getStoreId(), 999_999L), false));

        assertThat(response.requested()).isEqualTo(3);
        assertThat(response.deleted()).isEqualTo(1);
        assertThat(response.notFound()).isEqualTo(1);
        assertThat(response.refused()).isEqualTo(1);
        assertThat(response.refusals()).singleElement()
                .satisfies(refusal -> {
                    assertThat(refusal.storeId()).isEqualTo(blocked.getStoreId());
                    assertThat(refusal.name()).isEqualTo("해오름식당");
                    assertThat(refusal.reason()).isEqualTo(ErrorCode.STORE_HAS_CHECK_IN.getTitle());
                });

        entityManager.clear();
        assertThat(storeRepository.findById(deletable.getStoreId())).isEmpty();
        assertThat(storeRepository.findById(blocked.getStoreId())).isPresent();
    }

    @Test
    @DisplayName("같은 ID 를 여러 번 보내도 한 번만 처리한다")
    void 중복_ID는_한_번만() {
        Store store = saveStore("달빛국수");
        entityManager.flush();
        entityManager.clear();

        StoreDeletionResponse response = storeDeletionService.deleteAll(
                new StoreDeletionRequest(List.of(store.getStoreId(), store.getStoreId()), false));

        assertThat(response.requested()).isEqualTo(1);
        assertThat(response.deleted()).isEqualTo(1);
        assertThat(response.notFound()).isZero();
    }

    @Test
    @DisplayName("force 면 조사가 돌고 있어도 지운다")
    void force_면_진행중_조사도_넘긴다() {
        Store store = saveStore("미리내카페");
        Job running = jobRepository.saveAndFlush(Job.builder()
                .requestedBy("admin")
                .status(JobStatus.IN_PROGRESS)
                .targetCount(1)
                .completedCount(0)
                .build());
        entityManager.persistAndFlush(Task.builder()
                .job(running)
                .store(store)
                .classification(TaskClassification.PRIORITY_CHECK)
                .build());
        entityManager.clear();

        StoreDeleteResponse response = storeDeletionService.delete(store.getStoreId(), true);

        assertThat(response.tasks()).isEqualTo(1);
        entityManager.clear();
        assertThat(storeRepository.findById(store.getStoreId())).isEmpty();
    }

    @Test
    @DisplayName("체크인 기록은 force 여도 지우지 않는다 — 보관할 자리가 없다")
    void 체크인은_force_로도_못_넘긴다() {
        Store store = saveStore("해오름식당");
        entityManager.persistAndFlush(CheckIn.of(1L, store));
        entityManager.clear();

        assertThatThrownBy(() -> storeDeletionService.delete(store.getStoreId(), true))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STORE_HAS_CHECK_IN));

        entityManager.clear();
        assertThat(storeRepository.findById(store.getStoreId())).isPresent();
    }

    private void saveChangeHistory(Store store, StoreChangeField field, String before, String after) {
        entityManager.persist(StoreChangeHistory.of(
                store,
                field,
                new StoreChange(StoreChangeSource.STORE_EDIT, 1L, null, LocalDateTime.now()),
                before,
                after));
    }

    private Store saveStore(String name) {
        return storeRepository.saveAndFlush(Store.builder()
                .name(name)
                .nameNormalized(name)
                .addressRoad("대구광역시 북구 대학로 80")
                .addressNormalized("대구광역시북구대학로80")
                .build());
    }

    private void saveNtsCheck(Store store) {
        entityManager.persist(StoreNtsCheck.builder()
                .store(store)
                .bizNo("1234567890")
                .checkResult(NtsLookupResult.CONFIRMED)
                .ntsState(BusinessState.CLOSED)
                .ntsClosedAt(LocalDate.of(2026, 3, 1))
                .lastAttemptAt(LocalDateTime.of(2026, 10, 16, 3, 0))
                .lastSuccessAt(LocalDateTime.of(2026, 10, 16, 3, 0))
                .build());
    }

    private void saveNtsChange(Store store) {
        entityManager.persist(StoreNtsChange.builder()
                .store(store)
                .fromState(BusinessState.ACTIVE)
                .toState(BusinessState.CLOSED)
                .detectedAt(LocalDateTime.of(2026, 10, 16, 3, 0))
                .build());
    }

    private Task saveFinishedTask(Store store) {
        Job done = jobRepository.saveAndFlush(Job.builder()
                .requestedBy("admin")
                .status(JobStatus.DONE)
                .targetCount(1)
                .completedCount(1)
                .build());
        return entityManager.persistAndFlush(Task.builder()
                .job(done)
                .store(store)
                .classification(TaskClassification.PRIORITY_CHECK)
                .build());
    }

    private void saveVerification(Store store, Task task) {
        entityManager.persist(Verification.builder()
                .store(store)
                .task(task)
                .result(VerificationResult.CONFIRMED)
                .action(VerificationAction.UPDATE_INFO)
                .verifiedBy("admin")
                .verifiedAt(LocalDateTime.of(2026, 10, 16, 4, 0))
                .build());
    }

    private void saveSignal(Task task) {
        entityManager.persist(Signal.builder()
                .task(task)
                .signalType(SignalType.SIGNAL_HIGH)
                .source(SignalSource.NTS)
                .field(ChangeField.STATUS)
                .build());
    }
}
