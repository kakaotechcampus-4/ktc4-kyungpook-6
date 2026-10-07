package com.ktc4.backend.domain.investigation.service;

import com.ktc4.backend.domain.business.enums.BusinessState;
import com.ktc4.backend.domain.investigation.client.AiClient;
import com.ktc4.backend.domain.investigation.client.AiContractError;
import com.ktc4.backend.domain.investigation.client.AiQuotaExceeded;
import com.ktc4.backend.domain.investigation.client.AiReadTimeout;
import com.ktc4.backend.domain.investigation.client.AiTransientError;
import com.ktc4.backend.domain.investigation.client.AiUnavailable;
import com.ktc4.backend.domain.investigation.dto.AiFinding;
import com.ktc4.backend.domain.investigation.dto.InvestigationPlan;
import com.ktc4.backend.domain.investigation.dto.InvestigationTarget;
import com.ktc4.backend.domain.investigation.dto.NtsTarget;
import com.ktc4.backend.domain.job.service.JobService;
import com.ktc4.backend.domain.store.enums.StatusComparison;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 조사 실행기 — 실패를 종류별로 어떻게 다루는지.
 *
 * <p>멘토 Q4 설계의 실패 표를 그대로 고정한다. {@code processOne} 에서 잡는 예외는 그 가게만 실패로 남기고
 * 계속하고, 잡지 않는 예외(한도 초과·구현 없음)는 Job 을 멈춘다. 재시도 대기 시간은 0 으로 둔다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("조사 실행기")
class InvestigationRunnerTest {

    private static final Long JOB_ID = 42L;

    @Mock
    private AiClient aiClient;

    @Mock
    private JobService jobService;

    @Mock
    private TaskService taskService;

    private InvestigationRunner runner;

    @BeforeEach
    void setUp() {
        runner = new InvestigationRunner(aiClient, jobService, taskService, 0L);
    }

    @AfterEach
    void clearInterruptFlag() {
        // 인터럽트 테스트가 남긴 플래그가 다음 테스트로 새지 않게 한다
        Thread.interrupted();
    }

    private static InvestigationTarget aiTarget(long storeId) {
        return new InvestigationTarget(storeId, "가게" + storeId, "주소", "1234567890", null, StoreStatus.OPEN, null, null);
    }

    private static NtsTarget ntsTarget(long storeId) {
        return new NtsTarget(storeId, StatusComparison.OPEN_BUT_CLOSED, BusinessState.CLOSED);
    }

    private static AiFinding finding(long storeId) {
        return AiFinding.success(storeId, TaskClassification.NO_CHANGE, Map.of(), List.of());
    }

    private static InvestigationPlan plan(List<NtsTarget> nts, List<InvestigationTarget> ai) {
        return new InvestigationPlan(JOB_ID, nts, ai);
    }

    @Nested
    @DisplayName("멘토 Q4 의 테스트 세 개")
    class MentorCases {

        @Test
        @DisplayName("일시적 오류는 재시도하고, 두 번째에 성공하면 결과를 저장한다")
        void retriesTransientError() {
            InvestigationTarget target = aiTarget(1);
            given(aiClient.investigate(target))
                    .willThrow(new AiTransientError("502"))
                    .willReturn(finding(1));

            runner.run(plan(List.of(), List.of(target)));

            verify(aiClient, times(2)).investigate(target);
            verify(taskService).save(eq(JOB_ID), eq(finding(1)), any());
            verify(jobService).finish(eq(JOB_ID), any());
        }

        @Test
        @DisplayName("타임아웃은 재시도하지 않고 그 건만 실패로 남긴다 — 한 번 더 기다리면 그 가게에 시간을 두 배로 쓴다")
        void doesNotRetryTimeout() {
            InvestigationTarget target = aiTarget(1);
            given(aiClient.investigate(target)).willThrow(new AiReadTimeout("75초"));

            runner.run(plan(List.of(), List.of(target)));

            verify(aiClient, times(1)).investigate(target);
            verify(taskService).saveFailure(JOB_ID, 1L, InvestigationRunner.REASON_TIMEOUT);
            verify(jobService).finish(eq(JOB_ID), any());
        }

        @Test
        @DisplayName("쿼터가 소진되면 남은 건을 시도하지 않고 Job 을 FAILED 로 둔다")
        void abortsOnQuotaExceeded() {
            given(aiClient.investigate(any())).willThrow(new AiQuotaExceeded("daily limit"));

            runner.run(plan(List.of(), List.of(aiTarget(1), aiTarget(2), aiTarget(3))));

            verify(aiClient, times(1)).investigate(any());
            verify(jobService).fail(eq(JOB_ID), eq(InvestigationRunner.ERROR_QUOTA), any());
            verify(jobService, never()).finish(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("그 가게만 실패로 남기고 계속")
    class SkipAndContinue {

        @Test
        @DisplayName("일시적 오류가 두 번 다 나면 실패로 남기고 다음 가게로 간다 — 총 시도는 두 번")
        void transientTwiceIsRecorded() {
            InvestigationTarget first = aiTarget(1);
            InvestigationTarget second = aiTarget(2);
            given(aiClient.investigate(first)).willThrow(new AiTransientError("502"));
            given(aiClient.investigate(second)).willReturn(finding(2));

            runner.run(plan(List.of(), List.of(first, second)));

            verify(aiClient, times(InvestigationRunner.MAX_ATTEMPTS)).investigate(first);
            verify(taskService).saveFailure(JOB_ID, 1L, InvestigationRunner.REASON_TRANSIENT);
            verify(taskService).save(eq(JOB_ID), eq(finding(2)), any());
            verify(jobService).finish(eq(JOB_ID), any());
        }

        @Test
        @DisplayName("AI 가 그 가게를 실패로 답하면(200 + failure) 다시 부르지 않고 실패로 남긴다")
        void aiReportedFailureIsNotRetried() {
            InvestigationTarget target = aiTarget(1);
            given(aiClient.investigate(target)).willReturn(AiFinding.failed(1L, "근거를 찾지 못했습니다"));

            runner.run(plan(List.of(), List.of(target)));

            verify(aiClient, times(1)).investigate(target);
            verify(taskService).saveFailure(JOB_ID, 1L, InvestigationRunner.REASON_AI_FAILED);
            verify(taskService, never()).save(anyLong(), any(), any());
        }

        @Test
        @DisplayName("응답이 약속과 다르면 다시 부르지 않고 실패로 남긴다")
        void contractErrorIsNotRetried() {
            InvestigationTarget target = aiTarget(1);
            given(aiClient.investigate(target)).willThrow(new AiContractError("결과 수 다름"));

            runner.run(plan(List.of(), List.of(target)));

            verify(aiClient, times(1)).investigate(target);
            verify(taskService).saveFailure(JOB_ID, 1L, InvestigationRunner.REASON_CONTRACT);
        }

        @Test
        @DisplayName("실패 이유는 예외 메시지 원문이 아니라 정해 둔 문구다 — 화면에 나간다")
        void failureReasonIsFixedText() {
            InvestigationTarget target = aiTarget(1);
            given(aiClient.investigate(target)).willThrow(new AiContractError("내부 URL http://10.0.0.1 응답 깨짐"));

            runner.run(plan(List.of(), List.of(target)));

            ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
            verify(taskService).saveFailure(eq(JOB_ID), eq(1L), reason.capture());
            assertThat(reason.getValue()).doesNotContain("10.0.0.1");
        }

        @Test
        @DisplayName("결과 저장이 실패하면 그 가게를 실패로 남기고 계속한다 — 요청 수와 Task 수를 맞춘다")
        void saveFailureIsRecorded() {
            InvestigationTarget first = aiTarget(1);
            InvestigationTarget second = aiTarget(2);
            given(aiClient.investigate(first)).willReturn(finding(1));
            given(aiClient.investigate(second)).willReturn(finding(2));
            willThrow(new IllegalStateException("DB")).given(taskService).save(eq(JOB_ID), eq(finding(1)), any());

            runner.run(plan(List.of(), List.of(first, second)));

            verify(taskService).saveFailure(JOB_ID, 1L, InvestigationRunner.REASON_SAVE_FAILED);
            verify(taskService).save(eq(JOB_ID), eq(finding(2)), any());
            verify(jobService).finish(eq(JOB_ID), any());
        }
    }

    @Nested
    @DisplayName("Job 을 멈춤")
    class Abort {

        @Test
        @DisplayName("AI 조사 구현이 없으면(503) 멈추고 FAILED")
        void abortsOnUnavailable() {
            given(aiClient.investigate(any())).willThrow(new AiUnavailable("503"));

            runner.run(plan(List.of(), List.of(aiTarget(1), aiTarget(2))));

            verify(aiClient, times(1)).investigate(any());
            verify(jobService).fail(eq(JOB_ID), eq(InvestigationRunner.ERROR_UNAVAILABLE), any());
            verify(jobService, never()).finish(anyLong(), any());
        }

        @Test
        @DisplayName("실패 기록마저 저장하지 못하면 안전망이 Job 을 FAILED 로 둔다 — 영원히 진행 중으로 남지 않게")
        void safetyNetWhenFailureCannotBeSaved() {
            InvestigationTarget target = aiTarget(1);
            given(aiClient.investigate(target)).willReturn(finding(1));
            willThrow(new IllegalStateException("DB")).given(taskService).save(eq(JOB_ID), any(), any());
            willThrow(new IllegalStateException("DB")).given(taskService).saveFailure(anyLong(), anyLong(), anyString());

            runner.run(plan(List.of(), List.of(target)));

            verify(jobService).fail(eq(JOB_ID), eq(InvestigationRunner.ERROR_INTERNAL), any());
            verify(jobService, never()).finish(anyLong(), any());
        }

        @Test
        @DisplayName("예상 못 한 예외(버그)도 안전망이 받는다")
        void safetyNetCatchesUnexpectedException() {
            given(aiClient.investigate(any())).willThrow(new NullPointerException("버그"));

            runner.run(plan(List.of(), List.of(aiTarget(1))));

            verify(jobService).fail(eq(JOB_ID), eq(InvestigationRunner.ERROR_INTERNAL), any());
        }

        @Test
        @DisplayName("Job 을 시작할 수 없으면(이미 정리됨 등) 아무 가게도 조사하지 않고, 예외가 밖으로 새지 않는다")
        void doesNothingWhenStartFails() {
            willThrow(new IllegalStateException("이미 끝남")).given(jobService).start(JOB_ID);
            willThrow(new IllegalStateException("이미 끝남")).given(jobService).fail(eq(JOB_ID), anyString(), any());

            assertThatCode(() -> runner.run(plan(List.of(ntsTarget(1)), List.of(aiTarget(2)))))
                    .doesNotThrowAnyException();

            verify(taskService, never()).saveNts(anyLong(), any());
            verify(aiClient, never()).investigate(any());
        }
    }

    @Nested
    @DisplayName("1차(국세청) 대상")
    class NtsTargets {

        @Test
        @DisplayName("1차 대상을 AI 대상보다 먼저 저장한다")
        void savesNtsBeforeAi() {
            NtsTarget nts = ntsTarget(1);
            InvestigationTarget ai = aiTarget(2);
            given(aiClient.investigate(ai)).willReturn(finding(2));

            runner.run(plan(List.of(nts), List.of(ai)));

            InOrder order = inOrder(jobService, taskService, aiClient);
            order.verify(jobService).start(JOB_ID);
            order.verify(taskService).saveNts(JOB_ID, nts);
            order.verify(aiClient).investigate(ai);
            order.verify(jobService).finish(eq(JOB_ID), any());
        }

        @Test
        @DisplayName("1차 대상만 있으면 AI 를 부르지 않고 완료한다")
        void ntsOnlyFinishesWithoutAi() {
            runner.run(plan(List.of(ntsTarget(1), ntsTarget(2)), List.of()));

            verify(taskService, times(2)).saveNts(eq(JOB_ID), any());
            verify(aiClient, never()).investigate(any());
            verify(jobService).finish(eq(JOB_ID), any());
        }

        @Test
        @DisplayName("AI 가 멈춰도 먼저 저장한 1차 결과는 남는다")
        void ntsResultSurvivesAiAbort() {
            NtsTarget nts = ntsTarget(1);
            given(aiClient.investigate(any())).willThrow(new AiUnavailable("503"));

            runner.run(plan(List.of(nts), List.of(aiTarget(2))));

            verify(taskService).saveNts(JOB_ID, nts);
            verify(jobService).fail(eq(JOB_ID), eq(InvestigationRunner.ERROR_UNAVAILABLE), any());
        }

        @Test
        @DisplayName("1차 결과 저장이 실패하면 그 가게를 실패로 남기고 계속한다")
        void ntsSaveFailureIsRecorded() {
            NtsTarget nts = ntsTarget(1);
            willThrow(new IllegalStateException("DB")).given(taskService).saveNts(JOB_ID, nts);

            runner.run(plan(List.of(nts), List.of()));

            verify(taskService).saveFailure(JOB_ID, 1L, InvestigationRunner.REASON_SAVE_FAILED);
            verify(jobService).finish(eq(JOB_ID), any());
        }
    }

    @Nested
    @DisplayName("서버 종료(인터럽트)")
    class Interrupts {

        @Test
        @DisplayName("인터럽트(서버 종료)되면 남은 가게를 처리하지 않고 빠져나온다 — 정리는 재시작 때 한다")
        void stopsWhenInterrupted() {
            Thread.currentThread().interrupt();

            runner.run(plan(List.of(ntsTarget(1)), List.of(aiTarget(2))));

            verify(taskService, never()).saveNts(anyLong(), any());
            verify(aiClient, never()).investigate(any());
            verify(jobService, never()).finish(anyLong(), any());
            verify(jobService, never()).fail(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("마지막 시도 도중 인터럽트되면 그 가게를 실패로 남기지 않는다 — 가게 탓이 아니라 서버 종료다")
        void interruptDuringLastAttemptIsNotRecorded() {
            InvestigationTarget target = aiTarget(1);
            given(aiClient.investigate(target))
                    .willThrow(new AiTransientError("502"))
                    .willAnswer(invocation -> {
                        // JDK 클라이언트처럼 플래그를 되살린 채 I/O 실패로 던진다
                        Thread.currentThread().interrupt();
                        throw new AiTransientError("Request was interrupted");
                    });

            runner.run(plan(List.of(), List.of(target)));

            verify(taskService, never()).saveFailure(anyLong(), anyLong(), anyString());
            verify(jobService, never()).finish(anyLong(), any());
            verify(jobService, never()).fail(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("재시도 대기 중 인터럽트되면 다시 부르지 않고 빠져나온다")
        void interruptDuringBackoffStops() {
            InvestigationRunner slowRunner = new InvestigationRunner(aiClient, jobService, taskService, 60_000L);
            InvestigationTarget target = aiTarget(1);
            given(aiClient.investigate(target)).willAnswer(invocation -> {
                Thread.currentThread().interrupt();  // 대기(sleep) 를 바로 깨운다
                throw new AiTransientError("502");
            });

            slowRunner.run(plan(List.of(), List.of(target)));

            verify(aiClient, times(1)).investigate(target);
            verify(taskService, never()).saveFailure(anyLong(), anyLong(), anyString());
            verify(jobService, never()).finish(anyLong(), any());
        }
    }

    @Test
    @DisplayName("Error 가 나도 Job 을 실패로 끝내 두고, Error 는 삼키지 않는다")
    void errorFailsJobAndPropagates() {
        given(aiClient.investigate(any())).willThrow(new StackOverflowError());

        assertThatThrownBy(() -> runner.run(plan(List.of(), List.of(aiTarget(1)))))
                .isInstanceOf(StackOverflowError.class);

        verify(jobService).fail(eq(JOB_ID), eq(InvestigationRunner.ERROR_INTERNAL), any());
    }
}
