package com.ktc4.backend.domain.job.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;
import com.ktc4.backend.domain.investigation.dto.NtsTarget;
import com.ktc4.backend.domain.job.dto.CreatedJob;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.repository.JobRepository;
import com.ktc4.backend.domain.store.dto.ExcludedStore;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.InvestigationExclusionReason;
import com.ktc4.backend.domain.store.enums.NtsLookupResult;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.ntscheck.entity.StoreNtsCheck;
import com.ktc4.backend.domain.store.service.InvestigationTargetSelector;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.service.VerificationService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조사 시작 — 실제 DB 의 가게·국세청 기록으로 Job 을 만든다.
 *
 * <p>가게를 어떻게 나누는지는 {@code InvestigationTargetSelector}(PR #68) 테스트가 고정한다. 여기서는 나눈 결과로
 * Job 을 어떻게 만드는지(대상 수, 실행기에 넘길 값, 0곳 처리, 개수 상한)만 본다.
 */
// 조사 쪽 DB 테스트는 같은 빈 묶음을 써서 스프링 테스트 컨텍스트(와 DB 연결 풀) 하나를 함께 쓴다
@Import({JobQueryService.class, JobService.class, TaskService.class, StoreService.class,
        InvestigationTargetSelector.class, VerificationService.class})
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@DisplayName("조사 시작 (JobService.create)")
class JobServiceCreateTest extends PostgresContainerTest {

    private static final Long ADMIN_ID = 7L;
    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 10, 6, 3, 0);

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Store store(String name, StoreStatus status, String bizNo, BusinessState ntsState) {
        Store store = entityManager.persistAndFlush(Store.builder()
                .name(name)
                .nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123")
                .addressNormalized("가상특별시예시구샘플로123")
                .status(status)
                .bizNo(bizNo)
                .phone("053-111-1111")
                .lat(35.89)
                .lng(128.61)
                .build());
        if (ntsState != null) {
            entityManager.persistAndFlush(StoreNtsCheck.builder()
                    .store(store)
                    .bizNo(bizNo)
                    .checkResult(NtsLookupResult.CONFIRMED)
                    .ntsState(ntsState)
                    .lastAttemptAt(CHECKED_AT)
                    .lastSuccessAt(CHECKED_AT)
                    .build());
        }
        return store;
    }

    @Test
    @DisplayName("1차 대상과 AI 대상을 합친 수로 대기 중인 Job 을 만든다 — 제외된 가게는 세지 않는다")
    void createsPendingJob() {
        Store closedByNts = store("폐업가게", StoreStatus.OPEN, "1111111111", BusinessState.CLOSED);
        Store matched = store("정상가게", StoreStatus.OPEN, "2222222222", BusinessState.ACTIVE);
        Store noBizNo = store("번호없는가게", StoreStatus.OPEN, null, null);

        CreatedJob created = jobService.create(
                List.of(closedByNts.getStoreId(), matched.getStoreId(), noBizNo.getStoreId()), ADMIN_ID);
        entityManager.clear();

        Job job = jobRepository.findById(created.jobId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getTargetCount()).isEqualTo(2);
        assertThat(job.getCompletedCount()).isZero();
        assertThat(job.getRequestedBy()).isEqualTo("7");
        assertThat(created.targetCount()).isEqualTo(2);
        assertThat(created.excluded()).extracting(ExcludedStore::storeId, ExcludedStore::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        noBizNo.getStoreId(), InvestigationExclusionReason.NO_NTS_CHECK));
    }

    @Test
    @DisplayName("실행기에 넘길 값은 엔티티가 아니라 1차·AI 대상별로 나눈 값이다")
    void buildsPlanForRunner() {
        Store closedByNts = store("폐업가게", StoreStatus.OPEN, "1111111111", BusinessState.CLOSED);
        Store matched = store("정상가게", StoreStatus.OPEN, "2222222222", BusinessState.ACTIVE);

        CreatedJob created = jobService.create(List.of(matched.getStoreId(), closedByNts.getStoreId()), ADMIN_ID);

        assertThat(created.plan().jobId()).isEqualTo(created.jobId());
        assertThat(created.plan().ntsTargets())
                .containsExactly(new NtsTarget(closedByNts.getStoreId(), StatusComparison.OPEN_BUT_CLOSED, BusinessState.CLOSED));
        assertThat(created.plan().aiTargets()).containsExactly(new InvestigationTarget(
                matched.getStoreId(), "정상가게", "가상특별시 예시구 샘플로 123", "2222222222",
                "053-111-1111", StoreStatus.OPEN, 35.89, 128.61));
    }

    @Test
    @DisplayName("같은 가게가 여러 번 와도 한 번만 센다")
    void countsDuplicatesOnce() {
        Store matched = store("정상가게", StoreStatus.OPEN, "2222222222", BusinessState.ACTIVE);

        CreatedJob created = jobService.create(
                List.of(matched.getStoreId(), matched.getStoreId()), ADMIN_ID);

        assertThat(created.targetCount()).isEqualTo(1);
        assertThat(created.plan().aiTargets()).hasSize(1);
    }

    @Test
    @DisplayName("조사할 가게가 하나도 없으면 Job 을 만들지 않는다")
    void rejectsWhenNothingToInvestigate() {
        Store noBizNo = store("번호없는가게", StoreStatus.OPEN, null, null);
        long jobsBefore = jobRepository.count();

        assertThatThrownBy(() -> jobService.create(List.of(noBizNo.getStoreId(), 999_999L), ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NO_INVESTIGATION_TARGET));
        assertThat(jobRepository.count()).isEqualTo(jobsBefore);
    }

    @Test
    @DisplayName("중복을 뺀 가게가 100곳을 넘으면 거절한다")
    void rejectsMoreThanMaxStores() {
        List<Long> ids = LongStream.rangeClosed(1, JobService.MAX_STORES + 1).boxed().toList();

        assertThatThrownBy(() -> jobService.create(ids, ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("상한은 중복을 뺀 뒤에 센다 — 100곳 + 중복 하나는 통과한다")
    void maxIsCountedAfterDedup() {
        List<Long> ids = new ArrayList<>(LongStream.rangeClosed(1, JobService.MAX_STORES).boxed().toList());
        ids.add(1L);

        // 없는 가게 번호뿐이라 상한은 통과하고 "조사할 가게 없음"으로 끝난다
        assertThatThrownBy(() -> jobService.create(ids, ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NO_INVESTIGATION_TARGET));
    }

    @Test
    @DisplayName("끝나지 않은 조사(대기·진행 중)가 있으면 새 조사를 만들지 않는다 — 같은 가게를 AI 가 다시 조사하지 않게")
    void rejectsWhileAnotherJobIsUnfinished() {
        Store matched = store("정상가게", StoreStatus.OPEN, "2222222222", BusinessState.ACTIVE);
        for (JobStatus unfinished : new JobStatus[]{JobStatus.PENDING, JobStatus.IN_PROGRESS}) {
            Job running = entityManager.persistAndFlush(Job.builder()
                    .requestedBy("1").status(unfinished).targetCount(1).completedCount(0).build());
            long jobsBefore = jobRepository.count();

            assertThatThrownBy(() -> jobService.create(List.of(matched.getStoreId()), ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                            .isEqualTo(ErrorCode.JOB_ALREADY_RUNNING));
            assertThat(jobRepository.count()).isEqualTo(jobsBefore);

            entityManager.remove(running);
            entityManager.flush();
        }
    }

    @Test
    @DisplayName("끝난 조사(완료·실패)만 있으면 새 조사를 만들 수 있다")
    void allowsWhenPreviousJobsAreFinished() {
        Store matched = store("정상가게", StoreStatus.OPEN, "2222222222", BusinessState.ACTIVE);
        for (JobStatus finished : new JobStatus[]{JobStatus.DONE, JobStatus.FAILED}) {
            entityManager.persistAndFlush(Job.builder()
                    .requestedBy("1").status(finished).targetCount(1).completedCount(1).build());
        }

        CreatedJob created = jobService.create(List.of(matched.getStoreId()), ADMIN_ID);

        assertThat(created.targetCount()).isEqualTo(1);
    }
}
