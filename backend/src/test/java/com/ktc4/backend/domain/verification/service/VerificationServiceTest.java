package com.ktc4.backend.domain.verification.service;

import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.InvestigationTargetSelector;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.store.util.StoreNormalizer;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.dto.VerificationResponse;
import com.ktc4.backend.domain.verification.entity.Verification;
import com.ktc4.backend.domain.verification.enums.VerificationAction;
import com.ktc4.backend.domain.verification.enums.VerificationResult;
import com.ktc4.backend.domain.verification.repository.VerificationRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조사 결과 카드의 "즉시 수정 반영하기" / "자체 확인 완료" — 실제 DB 에서 가게 값·확인일·확인 기록이 어떻게 남는지.
 *
 * <p>막혀야 하는 경우마다 <b>가게 값이 그대로인지</b>를 함께 본다. 검사를 통과하기 전에 가게를 고치면 409 를 돌려줘도
 * 같은 트랜잭션 안에서는 이미 바뀐 값이 보여, 그 실수를 이 확인이 잡는다.
 */
// 조사 쪽 DB 테스트는 같은 빈 묶음을 써서 스프링 테스트 컨텍스트(와 DB 연결 풀) 하나를 함께 쓴다
@Import({JobQueryService.class, JobService.class, TaskService.class, StoreService.class,
        InvestigationTargetSelector.class, VerificationService.class})
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@DisplayName("수정안 반영·확인 (VerificationService)")
class VerificationServiceTest extends PostgresContainerTest {

    private static final Long ADMIN_ID = 7L;

    @Autowired
    private VerificationService verificationService;

    @Autowired
    private VerificationRepository verificationRepository;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TestEntityManager entityManager;

    private Job job;
    private Store store;

    @BeforeEach
    void setUp() {
        job = entityManager.persistAndFlush(Job.builder()
                .requestedBy("7").status(JobStatus.DONE).targetCount(1).completedCount(1).build());
        store = entityManager.persistAndFlush(Store.builder()
                .name("맛나 치킨").nameNormalized("맛나치킨")
                .addressRoad("대구광역시 북구 대학로 80").addressNormalized("대구광역시북구대학로80")
                .status(StoreStatus.OPEN).phone("053-111-1111")
                .lastCheckedAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build());
    }

    private Task task(TaskClassification classification, Map<String, Object> proposedChanges) {
        return entityManager.persistAndFlush(Task.builder()
                .job(job).store(store).classification(classification).proposedChanges(proposedChanges).build());
    }

    private Task failedTask() {
        return entityManager.persistAndFlush(Task.builder()
                .job(job).store(store).failureReason("AI 응답 시간이 초과됐습니다").build());
    }

    private Store reloadStore() {
        entityManager.flush();
        entityManager.clear();
        return entityManager.find(Store.class, store.getStoreId());
    }

    private List<Verification> verificationsOf(Task task) {
        entityManager.flush();
        entityManager.clear();
        return verificationRepository.findByTask_TaskIdIn(List.of(task.getTaskId()));
    }

    private static void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(expected));
    }

    @Nested
    @DisplayName("즉시 수정 반영 (apply)")
    class Apply {

        @Test
        @DisplayName("수정안대로 가게 값을 바꾸고(정규화 포함), 확인일과 확인 기록을 같은 시각으로 남긴다")
        void appliesProposedChanges() {
            Task task = task(TaskClassification.PRIORITY_CHECK,
                    Map.of("status", "SUSPENDED", "addressRoad", "대구광역시 북구 대학로 79", "phone", "053-222-2222"));

            TaskResultResponse result = verificationService.apply(task.getTaskId(), ADMIN_ID);

            Store changed = reloadStore();
            assertThat(changed.getStatus()).isEqualTo(StoreStatus.SUSPENDED);
            assertThat(changed.getAddressRoad()).isEqualTo("대구광역시 북구 대학로 79");
            assertThat(changed.getAddressNormalized()).isNotEqualTo("대구광역시북구대학로80");
            assertThat(changed.getPhone()).isEqualTo("053-222-2222");

            List<Verification> verifications = verificationsOf(task);
            assertThat(verifications).hasSize(1);
            Verification verification = verifications.get(0);
            assertThat(verification.getResult()).isEqualTo(VerificationResult.CONFIRMED);
            assertThat(verification.getAction()).isEqualTo(VerificationAction.UPDATE_INFO);
            assertThat(verification.getAppliedChanges())
                    .isEqualTo(Map.of("status", "SUSPENDED", "addressRoad", "대구광역시 북구 대학로 79", "phone", "053-222-2222"));
            assertThat(verification.getVerifiedBy()).isEqualTo("7");
            assertThat(changed.getLastCheckedAt()).isEqualTo(verification.getVerifiedAt());

            // 화면이 카드 하나만 다시 그리도록, 바뀐 가게 값과 확인 기록이 담긴 카드 한 장을 돌려준다
            assertThat(result.taskId()).isEqualTo(task.getTaskId());
            assertThat(result.storeStatus()).isEqualTo(StoreStatus.SUSPENDED);
            assertThat(result.storePhone()).isEqualTo("053-222-2222");
            assertThat(result.verification()).isNotNull();
            assertThat(result.verification().action()).isEqualTo(VerificationAction.UPDATE_INFO);
            assertThat(result.lastCheckedAt()).isEqualTo(result.verification().verifiedAt());
            assertThat(result.proposedChanges()).isNotEmpty();
        }

        @Test
        @DisplayName("상호·주소를 바꾸면 정규화 값도 가게 수정과 같은 규칙으로 다시 계산한다")
        void recalculatesNormalizedValues() {
            Task task = task(TaskClassification.PRIORITY_CHECK,
                    Map.of("name", "신나는 자장면", "addressRoad", "대구광역시 북구 대학로 79"));

            verificationService.apply(task.getTaskId(), ADMIN_ID);

            Store changed = reloadStore();
            assertThat(changed.getName()).isEqualTo("신나는 자장면");
            assertThat(changed.getNameNormalized()).isEqualTo(StoreNormalizer.normalizeName("신나는 자장면"));
            assertThat(changed.getAddressNormalized())
                    .isEqualTo(StoreNormalizer.normalizeAddress("대구광역시 북구 대학로 79"));
        }

        @Test
        @DisplayName("AI 조사로 저장된 수정안(jsonb 왕복)을 그대로 반영할 수 있다 — 저장하는 키와 반영하는 키가 같다")
        void appliesProposalSavedFromAi() {
            Job running = entityManager.persistAndFlush(Job.builder()
                    .requestedBy("7").status(JobStatus.IN_PROGRESS).targetCount(1).completedCount(0).build());
            taskService.save(running.getJobId(), AiFinding.success(store.getStoreId(), TaskClassification.PRIORITY_CHECK,
                    Map.of(ChangeField.STATUS, "CLOSED", ChangeField.PHONE, "053-222-2222",
                            ChangeField.ADDRESS_ROAD, "대구광역시 북구 대학로 79", ChangeField.NAME, "맛나 치킨 본점"),
                    List.of()), LocalDateTime.of(2026, 10, 7, 9, 0));
            entityManager.flush();
            entityManager.clear();
            Long taskId = taskService.findResults(running.getJobId()).get(0).taskId();

            verificationService.apply(taskId, ADMIN_ID);

            Store changed = reloadStore();
            assertThat(changed.getStatus()).isEqualTo(StoreStatus.CLOSED);
            assertThat(changed.getPhone()).isEqualTo("053-222-2222");
            assertThat(changed.getAddressRoad()).isEqualTo("대구광역시 북구 대학로 79");
            assertThat(changed.getName()).isEqualTo("맛나 치킨 본점");
        }

        @Test
        @DisplayName("같은 가게라도 다른 조사의 결과는 따로 확인한다 — 한 카드를 처리해도 다른 조사의 카드는 그대로")
        void reviewsEachTaskSeparately() {
            Task earlier = task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"));
            Job laterJob = entityManager.persistAndFlush(Job.builder()
                    .requestedBy("7").status(JobStatus.DONE).targetCount(1).completedCount(1).build());
            Task later = entityManager.persistAndFlush(Task.builder().job(laterJob).store(store)
                    .classification(TaskClassification.PRIORITY_CHECK).proposedChanges(Map.of("phone", "053-222-2222"))
                    .build());

            verificationService.confirm(earlier.getTaskId(), ADMIN_ID);
            verificationService.apply(later.getTaskId(), ADMIN_ID);

            assertThat(verificationsOf(earlier)).extracting(Verification::getAction)
                    .containsExactly(VerificationAction.NO_ACTION);
            assertThat(verificationsOf(later)).extracting(Verification::getAction)
                    .containsExactly(VerificationAction.UPDATE_INFO);
        }

        @Test
        @DisplayName("상태만 바꾸는 수정안(1차 국세청 수정안)은 상태변경으로 기록한다")
        void statusOnlyIsChangeStatus() {
            Task task = task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"));

            verificationService.apply(task.getTaskId(), ADMIN_ID);

            assertThat(reloadStore().getStatus()).isEqualTo(StoreStatus.CLOSED);
            assertThat(verificationsOf(task)).extracting(Verification::getAction)
                    .containsExactly(VerificationAction.CHANGE_STATUS);
        }

        @ParameterizedTest
        @EnumSource(value = StoreStatus.class, names = {"OPEN", "SUSPENDED", "CLOSED"})
        @DisplayName("영업중·휴업·폐업 상태 수정안은 반영한다")
        void appliesKnownStatuses(StoreStatus status) {
            Task task = task(TaskClassification.PRIORITY_CHECK, Map.of("status", status.name()));

            verificationService.apply(task.getTaskId(), ADMIN_ID);

            assertThat(reloadStore().getStatus()).isEqualTo(status);
        }

        @Test
        @DisplayName("이미 확인한 결과는 다시 반영하지 않는다 — 같은 수정안이 두 번 반영되지 않게")
        void rejectsAlreadyConfirmed() {
            Task task = task(TaskClassification.PRIORITY_CHECK, Map.of("phone", "053-222-2222"));
            verificationService.apply(task.getTaskId(), ADMIN_ID);

            assertErrorCode(() -> verificationService.apply(task.getTaskId(), ADMIN_ID), ErrorCode.TASK_ALREADY_CONFIRMED);
            assertThat(verificationsOf(task)).hasSize(1);
        }

        @Test
        @DisplayName("이미 확인 완료한 결과도 반영하지 않는다")
        void rejectsAfterConfirm() {
            Task task = task(TaskClassification.PRIORITY_CHECK, Map.of("phone", "053-222-2222"));
            verificationService.confirm(task.getTaskId(), ADMIN_ID);

            assertErrorCode(() -> verificationService.apply(task.getTaskId(), ADMIN_ID), ErrorCode.TASK_ALREADY_CONFIRMED);
            assertThat(reloadStore().getPhone()).isEqualTo("053-111-1111");
        }

        @Test
        @DisplayName("수정안이 없는 결과(변화없음·추가확인)는 반영할 것이 없다")
        void rejectsEmptyProposal() {
            Task noChange = task(TaskClassification.NO_CHANGE, Map.of());
            Task additional = task(TaskClassification.ADDITIONAL_CHECK, null);

            assertErrorCode(() -> verificationService.apply(noChange.getTaskId(), ADMIN_ID), ErrorCode.NOTHING_TO_APPLY);
            assertErrorCode(() -> verificationService.apply(additional.getTaskId(), ADMIN_ID), ErrorCode.NOTHING_TO_APPLY);
        }

        @Test
        @DisplayName("조사에 실패한 결과는 반영할 것이 없다")
        void rejectsFailedTask() {
            Task task = failedTask();

            assertErrorCode(() -> verificationService.apply(task.getTaskId(), ADMIN_ID), ErrorCode.NOTHING_TO_APPLY);
            assertThat(verificationsOf(task)).isEmpty();
        }

        static Stream<Map<String, Object>> invalidProposals() {
            Map<String, Object> notString = new HashMap<>();
            notString.put("phone", 531112222);
            Map<String, Object> nullValue = new HashMap<>();
            nullValue.put("phone", null);
            return Stream.of(
                    Map.of("phone", "0".repeat(21)),             // 가게 전화번호는 20자까지
                    Map.of("phone", ""),                         // 빈 전화번호는 "지우기"라 AI 가 정하지 않는다
                    Map.of("phone", "   "),                      // 공백뿐인 전화번호도 마찬가지
                    Map.of("status", "UNKNOWN"),                 // 미확인으로 바꾸면 1차 대조에서 빠진다
                    Map.of("status", "영업중"),                    // 모르는 상태
                    Map.of("hours", "09-18"),                    // 모르는 항목
                    Map.of("name", "   "),                       // 공백뿐인 상호
                    Map.of("phone", "053-222-2222", "status", "MAYBE"),  // 하나라도 틀리면 전부 반영하지 않는다
                    notString,                                   // 문자열이 아닌 값
                    nullValue);                                  // 값이 비어 있음(JSON null)
        }

        @ParameterizedTest
        @MethodSource("invalidProposals")
        @DisplayName("가게 규칙에 맞지 않는 수정안은 반영하지 않고, 가게 값도 그대로 둔다")
        void rejectsInvalidProposalWithoutTouchingStore(Map<String, Object> proposedChanges) {
            Task task = task(TaskClassification.PRIORITY_CHECK, proposedChanges);

            assertErrorCode(() -> verificationService.apply(task.getTaskId(), ADMIN_ID), ErrorCode.NOTHING_TO_APPLY);

            Store unchanged = reloadStore();
            assertThat(unchanged.getStatus()).isEqualTo(StoreStatus.OPEN);
            assertThat(unchanged.getPhone()).isEqualTo("053-111-1111");
            assertThat(unchanged.getName()).isEqualTo("맛나 치킨");
            assertThat(unchanged.getLastCheckedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
            assertThat(verificationsOf(task)).isEmpty();
        }

        @Test
        @DisplayName("없는 조사 결과는 TASK_NOT_FOUND")
        void notFound() {
            assertErrorCode(() -> verificationService.apply(999_999L, ADMIN_ID), ErrorCode.TASK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("확인 기록 읽기 (findByTaskIds)")
    class FindByTaskIds {

        private Verification verification(Task task, VerificationAction action, LocalDateTime verifiedAt) {
            return entityManager.persistAndFlush(Verification.builder().store(store).task(task)
                    .result(VerificationResult.CONFIRMED).action(action).verifiedBy("7").verifiedAt(verifiedAt).build());
        }

        @Test
        @DisplayName("한 결과에 기록이 둘 이상이면(직접 넣은 데이터 등) 오류 없이 가장 최근 기록을 쓴다 — 같은 시각이면 나중에 쓴 기록")
        void picksLatestWhenDuplicated() {
            Task byTime = task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"));
            Task byId = task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"));
            LocalDateTime at = LocalDateTime.of(2026, 10, 7, 12, 0);
            verification(byTime, VerificationAction.CHANGE_STATUS, at.plusMinutes(1));
            verification(byTime, VerificationAction.NO_ACTION, at);
            verification(byId, VerificationAction.NO_ACTION, at);
            verification(byId, VerificationAction.CHANGE_STATUS, at);
            entityManager.clear();

            Map<Long, VerificationResponse> found =
                    verificationService.findByTaskIds(List.of(byTime.getTaskId(), byId.getTaskId()));

            assertThat(found.get(byTime.getTaskId())).isEqualTo(
                    new VerificationResponse(VerificationAction.CHANGE_STATUS, at.plusMinutes(1)));
            assertThat(found.get(byId.getTaskId()).action()).isEqualTo(VerificationAction.CHANGE_STATUS);
        }
    }

    @Nested
    @DisplayName("자체 확인 완료 (confirm)")
    class Confirm {

        @Test
        @DisplayName("가게 값은 그대로 두고 확인일과 '조치없음' 확인 기록을 남긴다")
        void confirmsWithoutChangingStore() {
            Task task = task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"));

            TaskResultResponse result = verificationService.confirm(task.getTaskId(), ADMIN_ID);

            Store store = reloadStore();
            assertThat(store.getStatus()).isEqualTo(StoreStatus.OPEN);
            Verification verification = verificationsOf(task).get(0);
            assertThat(verification.getResult()).isEqualTo(VerificationResult.CONFIRMED);
            assertThat(verification.getAction()).isEqualTo(VerificationAction.NO_ACTION);
            assertThat(verification.getAppliedChanges()).isNull();
            assertThat(store.getLastCheckedAt()).isEqualTo(verification.getVerifiedAt());
            assertThat(result.verification().action()).isEqualTo(VerificationAction.NO_ACTION);
        }

        @Test
        @DisplayName("수정안이 없는 결과와 조사에 실패한 결과도 확인 완료할 수 있다 — 담당자가 직접 확인한 뒤 닫는다")
        void confirmsTasksWithoutProposal() {
            Task additional = task(TaskClassification.ADDITIONAL_CHECK, Map.of());
            Task failed = failedTask();

            verificationService.confirm(additional.getTaskId(), ADMIN_ID);
            verificationService.confirm(failed.getTaskId(), ADMIN_ID);

            assertThat(verificationsOf(additional)).hasSize(1);
            assertThat(verificationsOf(failed)).hasSize(1);
        }

        @Test
        @DisplayName("이미 확인한 결과는 다시 확인하지 않는다")
        void rejectsAlreadyConfirmed() {
            Task task = task(TaskClassification.NO_CHANGE, Map.of());
            verificationService.confirm(task.getTaskId(), ADMIN_ID);

            assertErrorCode(() -> verificationService.confirm(task.getTaskId(), ADMIN_ID), ErrorCode.TASK_ALREADY_CONFIRMED);
            assertThat(verificationsOf(task)).hasSize(1);
        }

        @Test
        @DisplayName("없는 조사 결과는 TASK_NOT_FOUND")
        void notFound() {
            assertErrorCode(() -> verificationService.confirm(999_999L, ADMIN_ID), ErrorCode.TASK_NOT_FOUND);
        }
    }
}
