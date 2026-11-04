package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.dto.CallBulkResponse;
import com.ktc4.backend.domain.call.dto.CallResponse;
import com.ktc4.backend.domain.call.enums.CallReason;
import com.ktc4.backend.domain.call.enums.CallStatus;
import com.ktc4.backend.domain.job.dto.JobResultResponse;
import com.ktc4.backend.domain.job.enums.JobStatus;
import com.ktc4.backend.domain.job.service.JobQueryService;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.task.dto.ProposedChangeResponse;
import com.ktc4.backend.domain.task.dto.TaskResultResponse;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.verification.dto.VerificationResponse;
import com.ktc4.backend.domain.verification.enums.VerificationAction;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link CallBulkService} — 조사 결과의 전화 대상에 한 번에 전화를 넣는다.
 *
 * <p><b>한 건의 실패가 나머지를 멈추지 않는지</b>가 핵심이다. 전화는 되돌릴 수 없어서, 앞에서 이미
 * 걸린 전화의 기록이 응답에서 사라지는 것 — "걸렸는데 안 걸린 것으로 보이는" 것 — 이 가장 나쁘다.
 */
@DisplayName("확인 전화 일괄 요청 (CallBulkService)")
class CallBulkServiceTest {

    private static final Long JOB_ID = 1L;
    private static final Long ADMIN_ID = 7L;

    private JobQueryService jobQueryService;
    private CallService callService;
    private CallBulkService bulkService;

    @BeforeEach
    void setUp() {
        jobQueryService = mock(JobQueryService.class);
        callService = mock(CallService.class);
        bulkService = new CallBulkService(jobQueryService, callService);
    }

    private static TaskResultResponse task(Long taskId, TaskClassification classification,
                                           VerificationResponse verification, CallResponse call) {
        return new TaskResultResponse(taskId, taskId + 100, "예시분식", "대구광역시 북구 대학로 80",
                StoreStatus.OPEN, "053-111-1111", null, classification, null,
                List.of(new ProposedChangeResponse("status", "CLOSED")), List.of(), verification, call);
    }

    private static CallResponse call(CallStatus status) {
        return new CallResponse(9L, status, CallReason.CHANGE_DETECTED,
                LocalDateTime.of(2026, 11, 4, 11, 0), null, null, List.of());
    }

    private void givenTasks(TaskResultResponse... tasks) {
        given(jobQueryService.getJob(JOB_ID)).willReturn(new JobResultResponse(
                JOB_ID, JobStatus.DONE, tasks.length, tasks.length, null, null, null, List.of(tasks)));
    }

    private void givenCallSucceeds() {
        given(callService.request(anyLong(), eq(ADMIN_ID))).willReturn(call(CallStatus.PENDING));
    }

    @Test
    @DisplayName("우선확인·추가확인만 대상이다 — 변화없음과 조사 실패는 세지 않는다")
    void 대상을_고른다() {
        givenTasks(
                task(1L, TaskClassification.PRIORITY_CHECK, null, null),
                task(2L, TaskClassification.ADDITIONAL_CHECK, null, null),
                task(3L, TaskClassification.NO_CHANGE, null, null),
                task(4L, null, null, null));
        givenCallSucceeds();

        CallBulkResponse response = bulkService.requestAll(JOB_ID, ADMIN_ID);

        assertThat(response.targetCount()).isEqualTo(2);
        assertThat(response.requested()).isEqualTo(2);
        verify(callService).request(1L, ADMIN_ID);
        verify(callService).request(2L, ADMIN_ID);
        verify(callService, never()).request(3L, ADMIN_ID);
        verify(callService, never()).request(4L, ADMIN_ID);
    }

    @Test
    @DisplayName("이미 확인한 카드와 걸어 둔 전화가 있는 카드는 빼고, 실패한 전화는 다시 대상이 된다")
    void 이미_처리한_것은_뺀다() {
        givenTasks(
                task(1L, TaskClassification.PRIORITY_CHECK,
                        new VerificationResponse(VerificationAction.CHANGE_STATUS, LocalDateTime.now()), null),
                task(2L, TaskClassification.PRIORITY_CHECK, null, call(CallStatus.IN_PROGRESS)),
                task(3L, TaskClassification.PRIORITY_CHECK, null, call(CallStatus.COMPLETED)),
                task(4L, TaskClassification.PRIORITY_CHECK, null, call(CallStatus.FAILED)));
        givenCallSucceeds();

        CallBulkResponse response = bulkService.requestAll(JOB_ID, ADMIN_ID);

        assertThat(response.targetCount()).isEqualTo(1);
        verify(callService).request(4L, ADMIN_ID);
    }

    @Test
    @DisplayName("걸 수 없는 한 건은 건너뛰고 나머지는 넣는다")
    void 거절된_건만_건너뛴다() {
        givenTasks(
                task(1L, TaskClassification.PRIORITY_CHECK, null, null),
                task(2L, TaskClassification.PRIORITY_CHECK, null, null));
        given(callService.request(1L, ADMIN_ID))
                .willThrow(new CustomException(ErrorCode.CALL_TARGET_NOT_ELIGIBLE));
        given(callService.request(2L, ADMIN_ID)).willReturn(call(CallStatus.PENDING));

        CallBulkResponse response = bulkService.requestAll(JOB_ID, ADMIN_ID);

        assertThat(response.targetCount()).isEqualTo(2);
        assertThat(response.requested()).isEqualTo(1);
        assertThat(response.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("우리가 가려낸 경우가 아닌 실패도 그 건만 건너뛴다 — 요청 전체가 500 이 되면 이미 걸린 전화의 기록이 사라진다")
    void 뜻밖의_실패도_건너뛴다() {
        givenTasks(
                task(1L, TaskClassification.PRIORITY_CHECK, null, null),
                task(2L, TaskClassification.PRIORITY_CHECK, null, null));
        // 전화 API 가 같은 verificationId 를 두 번 주면 DB 의 unique 제약에 걸린다 — 실제로 겪은 경우다
        given(callService.request(1L, ADMIN_ID))
                .willThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        given(callService.request(2L, ADMIN_ID)).willReturn(call(CallStatus.PENDING));

        CallBulkResponse response = bulkService.requestAll(JOB_ID, ADMIN_ID);

        assertThat(response.requested()).isEqualTo(1);
        assertThat(response.skipped()).isEqualTo(1);
        assertThat(response.calls()).hasSize(1);
    }

    @Test
    @DisplayName("전화 API 주소·키가 없으면 대상을 보기 전에 거절한다 — 대상 0건과 '값이 없어 아무것도 못 함' 은 다르다")
    void 설정이_없으면_먼저_거절() {
        willThrow(new CustomException(ErrorCode.CALL_NOT_AVAILABLE)).given(callService).requireAvailable();

        assertThatThrownBy(() -> bulkService.requestAll(JOB_ID, ADMIN_ID))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CALL_NOT_AVAILABLE);
        verify(jobQueryService, never()).getJob(anyLong());
    }
}
