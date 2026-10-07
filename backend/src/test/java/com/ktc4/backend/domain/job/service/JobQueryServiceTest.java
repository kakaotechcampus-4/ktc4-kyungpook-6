package com.ktc4.backend.domain.job.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.AiSignal;
import com.ktc4.backend.domain.investigation.dto.NtsTarget;
import com.ktc4.backend.domain.job.dto.JobResultResponse;
import com.ktc4.backend.domain.job.entity.Job;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.signal.enums.ChangeField;
import com.ktc4.backend.domain.signal.enums.SignalType;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.InvestigationTargetSelector;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.task.dto.EvidenceResponse;
import com.ktc4.backend.domain.task.dto.ProposedChangeResponse;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import com.ktc4.backend.support.PostgresContainerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 조사 진행도·결과 조회 — 실제 DB 에 저장된 Task·Signal·가게를 한 응답으로 읽는다.
 *
 * <p>결과는 저장 서비스로 넣는다 — 손으로 행을 만들면 저장과 조회가 서로 다른 모양을 가정해도 통과한다.
 */
// 조사 쪽 DB 테스트는 같은 빈 묶음을 써서 스프링 테스트 컨텍스트(와 DB 연결 풀) 하나를 함께 쓴다
@Import({JobQueryService.class, JobService.class, TaskService.class, StoreService.class,
        InvestigationTargetSelector.class})
@DisplayName("조사 결과 조회 (JobQueryService)")
class JobQueryServiceTest extends PostgresContainerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 12, 0);

    @Autowired
    private JobQueryService jobQueryService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TestEntityManager entityManager;

    private Job job(JobStatus status, int targetCount) {
        return entityManager.persistAndFlush(Job.builder()
                .requestedBy("7").status(status).targetCount(targetCount).completedCount(0).build());
    }

    private Store store(String name) {
        return entityManager.persistAndFlush(Store.builder()
                .name(name).nameNormalized(name)
                .addressRoad("가상특별시 예시구 샘플로 123").addressNormalized("가상특별시예시구샘플로123")
                .status(StoreStatus.OPEN).phone("053-111-1111").lastCheckedAt(NOW.minusDays(3))
                .build());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("진행 수와 함께 가게별 판정·수정안·근거를 taskId 순서로 내려준다")
    void returnsProgressAndResults() {
        Job job = job(JobStatus.IN_PROGRESS, 3);
        Store closed = store("폐업가게");
        Store changed = store("이전가게");
        Store failed = store("실패가게");
        taskService.saveNts(job.getJobId(),
                new NtsTarget(closed.getStoreId(), StatusComparison.OPEN_BUT_CLOSED, BusinessState.CLOSED));
        taskService.save(job.getJobId(), AiFinding.success(changed.getStoreId(), TaskClassification.PRIORITY_CHECK,
                Map.of(ChangeField.PHONE, "053-222-2222", ChangeField.STATUS, "OPEN"),
                List.of(new AiSignal(SignalType.SIGNAL_HIGH, ChangeField.PHONE, "053-222-2222",
                        "전화번호: 053-222-2222", "https://example.com/a"))), NOW);
        taskService.saveFailure(job.getJobId(), failed.getStoreId(), "AI 응답 시간이 초과됐습니다");
        flushAndClear();

        JobResultResponse result = jobQueryService.getJob(job.getJobId());

        assertThat(result.status()).isEqualTo(JobStatus.IN_PROGRESS);
        assertThat(result.targetCount()).isEqualTo(3);
        assertThat(result.completedCount()).isEqualTo(3);
        assertThat(result.tasks()).extracting(TaskResultResponse::storeId)
                .containsExactly(closed.getStoreId(), changed.getStoreId(), failed.getStoreId());

        TaskResultResponse nts = result.tasks().get(0);
        assertThat(nts.storeName()).isEqualTo("폐업가게");
        assertThat(nts.storeAddress()).isEqualTo("가상특별시 예시구 샘플로 123");
        assertThat(nts.storeStatus()).isEqualTo(StoreStatus.OPEN);
        assertThat(nts.storePhone()).isEqualTo("053-111-1111");
        assertThat(nts.lastCheckedAt()).isEqualTo(NOW.minusDays(3));
        assertThat(nts.classification()).isEqualTo(TaskClassification.PRIORITY_CHECK);
        assertThat(nts.proposedChanges()).containsExactly(new ProposedChangeResponse("status", "CLOSED"));
        assertThat(nts.evidences()).containsExactly(
                new EvidenceResponse("status", "NTS", "국세청 사업자 상태: 폐업자", null, null));

        TaskResultResponse ai = result.tasks().get(1);
        // 수정안은 항목 순서(상태 → 전화 → 주소 → 상호)로 늘 같게 나온다
        assertThat(ai.proposedChanges()).containsExactly(
                new ProposedChangeResponse("status", "OPEN"),
                new ProposedChangeResponse("phone", "053-222-2222"));
        assertThat(ai.evidences()).containsExactly(new EvidenceResponse(
                "phone", "AI_WEB", "전화번호: 053-222-2222", null, "https://example.com/a"));

        TaskResultResponse fail = result.tasks().get(2);
        assertThat(fail.classification()).isNull();
        assertThat(fail.failureReason()).isEqualTo("AI 응답 시간이 초과됐습니다");
        assertThat(fail.proposedChanges()).isEmpty();
        assertThat(fail.evidences()).isEmpty();
    }

    @Test
    @DisplayName("아직 끝난 가게가 없으면 결과가 비어 있다")
    void emptyWhileWaiting() {
        Job job = job(JobStatus.PENDING, 2);
        flushAndClear();

        JobResultResponse result = jobQueryService.getJob(job.getJobId());

        assertThat(result.status()).isEqualTo(JobStatus.PENDING);
        assertThat(result.tasks()).isEmpty();
    }

    @Test
    @DisplayName("실패한 조사는 멈춘 이유와 끝난 시각을 내려준다")
    void failedJob() {
        Job job = job(JobStatus.IN_PROGRESS, 2);
        job.fail("AI 조사를 쓸 수 없어 조사를 멈췄습니다", NOW);
        flushAndClear();

        JobResultResponse result = jobQueryService.getJob(job.getJobId());

        assertThat(result.status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.errorMessage()).isEqualTo("AI 조사를 쓸 수 없어 조사를 멈췄습니다");
        assertThat(result.finishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("가장 최근에 만든 조사를 내려준다")
    void latest() {
        job(JobStatus.DONE, 1);
        Job latest = job(JobStatus.PENDING, 1);
        flushAndClear();

        assertThat(jobQueryService.getLatest().jobId()).isEqualTo(latest.getJobId());
    }

    @Test
    @DisplayName("없는 조사는 JOB_NOT_FOUND")
    void notFound() {
        assertThatThrownBy(() -> jobQueryService.getJob(999_999L))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.JOB_NOT_FOUND));
    }
}
