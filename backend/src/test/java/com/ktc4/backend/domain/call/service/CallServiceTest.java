package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.client.CallClient;
import com.ktc4.backend.domain.call.client.CallNotConfigured;
import com.ktc4.backend.domain.call.client.CallTransientError;
import com.ktc4.backend.domain.call.dto.CallOrder;
import com.ktc4.backend.domain.call.dto.CallResponse;
import com.ktc4.backend.domain.call.entity.VerificationCall;
import com.ktc4.backend.domain.call.enums.CallFailureKind;
import com.ktc4.backend.domain.call.enums.CallReason;
import com.ktc4.backend.domain.call.enums.CallStatus;
import com.ktc4.backend.domain.call.repository.VerificationCallRepository;
import com.ktc4.backend.domain.store.entity.Store;
import com.ktc4.backend.domain.store.enums.StoreStatus;
import com.ktc4.backend.domain.store.service.StoreService;
import com.ktc4.backend.domain.task.entity.Task;
import com.ktc4.backend.domain.task.enums.TaskClassification;
import com.ktc4.backend.domain.task.service.TaskService;
import com.ktc4.backend.domain.verification.service.VerificationService;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link CallService} — 전화를 걸지 말지 정하고, 전화 API 결과를 기록에 옮기는 규칙.
 *
 * <p><b>"걸지 않아야 하는 경우" 를 가장 많이 본다.</b> 전화는 되돌릴 수 없다 — 잘못 걸면 가게에 실제로
 * 전화가 가고 사과할 방법이 없다. 그래서 막혀야 하는 경우마다 <b>전화 API 를 부르지 않았는지</b>를 함께 확인한다.
 *
 * <p>DB 없이 돌린다. 저장소와 전화 API 는 가짜로 두고, 여기서 보는 것은 판단이다 — 실제 DB 에 어떻게
 * 남는지는 통합 테스트가 볼 몫이다.
 */
@DisplayName("확인 전화 (CallService)")
class CallServiceTest {

    private static final Long TASK_ID = 1L;
    private static final Long ADMIN_ID = 7L;
    private static final String EXTERNAL_ID = "11111111-2222-4333-8444-555555555555";
    private static final long MAX_WAIT_MINUTES = 15;

    private VerificationCallRepository callRepository;
    private CallClient callClient;
    private TaskService taskService;
    private VerificationService verificationService;
    private StoreService storeService;
    private Validator validator;
    private CallService callService;

    @BeforeEach
    void setUp() {
        callRepository = mock(VerificationCallRepository.class);
        callClient = mock(CallClient.class);
        taskService = mock(TaskService.class);
        verificationService = mock(VerificationService.class);
        storeService = mock(StoreService.class);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        callService = new CallService(callRepository, callClient, taskService, verificationService,
                storeService, validator, MAX_WAIT_MINUTES);
    }

    private static Store store(String phone) {
        return Store.builder()
                .name("예시분식")
                .nameNormalized("예시분식")
                .addressRoad("대구광역시 북구 대학로 80")
                .addressNormalized("대구광역시 북구 대학로 80")
                .status(StoreStatus.OPEN)
                .phone(phone)
                .build();
    }

    private static Task task(TaskClassification classification, Map<String, Object> proposed, String phone) {
        return Task.builder()
                .store(store(phone))
                .classification(classification)
                .proposedChanges(proposed)
                .build();
    }

    /** 전화를 걸 수 있는 평범한 상태로 맞춰 둔다. 막히는 경우를 보는 테스트가 조건 하나씩만 뒤집는다. */
    private void given(Task task) {
        org.mockito.BDDMockito.given(taskService.lockForReview(TASK_ID)).willReturn(task);
        org.mockito.BDDMockito.given(verificationService.isConfirmed(TASK_ID)).willReturn(false);
        org.mockito.BDDMockito.given(callRepository.existsLiveByTaskId(TASK_ID)).willReturn(false);
        org.mockito.BDDMockito.given(callClient.request(any())).willReturn(EXTERNAL_ID);
        // saveAndFlush 다 — save 만 하면 INSERT 가 커밋 때 나가 서비스의 catch 가 잡지 못한다
        org.mockito.BDDMockito.given(callRepository.saveAndFlush(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("전화를 건다")
    class Request {

        @Test
        @DisplayName("변화가 잡힌 결과는 '조사가 찾은 값이 맞는지' 를 묻는 전화로 걸린다")
        void 변화가_잡히면_확인_전화() {
            given(task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"), "053-111-1111"));

            CallResponse response = callService.request(TASK_ID, ADMIN_ID);

            assertThat(response.reason()).isEqualTo(CallReason.CHANGE_DETECTED);
            assertThat(response.status()).isEqualTo(CallStatus.PENDING);
            assertThat(response.answers()).isEmpty();
        }

        @Test
        @DisplayName("90일이 지난 결과는 '그대로인지' 를 묻는 전화로 걸린다 — 수정안이 없어도 걸 수 있다")
        void 추가확인은_재확인_전화() {
            given(task(TaskClassification.ADDITIONAL_CHECK, Map.of(), "053-111-1111"));

            assertThat(callService.request(TASK_ID, ADMIN_ID).reason()).isEqualTo(CallReason.PERIODIC_RECHECK);
        }

        @Test
        @DisplayName("걸 곳과 질문을 전화 API 에 넘기고, 받은 verificationId 를 기록에 남긴다")
        void 전화_API_에_넘기는_것() {
            given(task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"), "053-111-1111"));

            callService.request(TASK_ID, ADMIN_ID);

            ArgumentCaptor<CallOrder> order = ArgumentCaptor.forClass(CallOrder.class);
            verify(callClient).request(order.capture());
            assertThat(order.getValue().storeName()).isEqualTo("예시분식");
            assertThat(order.getValue().storeLocation()).isEqualTo("대구광역시 북구 대학로 80");
            assertThat(order.getValue().phoneNumber()).isEqualTo("053-111-1111");
            assertThat(order.getValue().questions()).hasSize(1);

            ArgumentCaptor<VerificationCall> saved = ArgumentCaptor.forClass(VerificationCall.class);
            verify(callRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getExternalId()).isEqualTo(EXTERNAL_ID);
            assertThat(saved.getValue().getRequestedBy()).isEqualTo(String.valueOf(ADMIN_ID));
        }
    }

    @Nested
    @DisplayName("전화를 걸지 않는다 — 막힌 경우마다 전화 API 를 부르지 않는다")
    class NotEligible {

        private void assertBlocked(ErrorCode expected) {
            assertThatThrownBy(() -> callService.request(TASK_ID, ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(expected);
            verifyNoInteractions(callClient);
            verify(callRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("변화도 없고 최근에 확인한 결과에는 걸지 않는다")
        void 변화없음() {
            given(task(TaskClassification.NO_CHANGE, Map.of(), "053-111-1111"));

            assertBlocked(ErrorCode.CALL_TARGET_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("조사에 실패한 결과에는 걸지 않는다 — 무엇을 물어야 할지 모르는 상태다")
        void 조사_실패() {
            given(Task.builder().store(store("053-111-1111")).failureReason("AI 응답 시간이 초과됐습니다").build());

            assertBlocked(ErrorCode.CALL_TARGET_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("담당자가 이미 확인을 끝낸 결과에는 걸지 않는다 — 답이 나온 뒤에 전화가 가면 민폐다")
        void 이미_확인함() {
            given(task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"), "053-111-1111"));
            org.mockito.BDDMockito.given(verificationService.isConfirmed(TASK_ID)).willReturn(true);

            assertBlocked(ErrorCode.CALL_TARGET_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("물을 질문이 만들어지지 않으면 걸지 않는다 — 휴업에서 폐업 제안은 전화로 가를 수 없다")
        void 물을_것이_없다() {
            Store suspended = Store.builder()
                    .name("예시분식").nameNormalized("예시분식")
                    .addressRoad("대구광역시 북구 대학로 80").addressNormalized("대구광역시 북구 대학로 80")
                    .status(StoreStatus.SUSPENDED).phone("053-111-1111").build();
            given(Task.builder().store(suspended).classification(TaskClassification.PRIORITY_CHECK)
                    .proposedChanges(Map.of("status", "CLOSED")).build());

            assertBlocked(ErrorCode.CALL_TARGET_NOT_ELIGIBLE);
        }

        @ParameterizedTest(name = "번호 [{0}]")
        @ValueSource(strings = {"", "   ", "123456", "053-111-1111-1111-1111", "전화번호없음"})
        @DisplayName("걸 수 없는 번호면 걸지 않는다 — 전화 API 의 거절(502)이 아니라 우리 쪽에서 막는다")
        void 걸_수_없는_번호(String phone) {
            given(task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"), phone));

            assertBlocked(ErrorCode.CALL_TARGET_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("번호가 비어 있으면 걸지 않는다")
        void 번호가_없다() {
            given(task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"), null));

            assertBlocked(ErrorCode.CALL_TARGET_NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("걸어 둔 전화가 있으면 두 번 걸지 않는다")
        void 이미_걸어_둔_전화() {
            given(task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"), "053-111-1111"));
            org.mockito.BDDMockito.given(callRepository.existsLiveByTaskId(TASK_ID)).willReturn(true);

            assertBlocked(ErrorCode.CALL_ALREADY_REQUESTED);
        }

        @Test
        @DisplayName("하이픈·공백·괄호가 섞인 번호는 걸 수 있다 — 지우는 일은 전화 API 가 한다")
        void 구분자가_섞인_번호는_걸린다() {
            given(task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"), "(053) 111-1111"));

            assertThat(callService.request(TASK_ID, ADMIN_ID)).isNotNull();
            // 우리 DB 의 값을 그대로 보낸다
            ArgumentCaptor<CallOrder> order = ArgumentCaptor.forClass(CallOrder.class);
            verify(callClient).request(order.capture());
            assertThat(order.getValue().phoneNumber()).isEqualTo("(053) 111-1111");
        }
    }

    @Nested
    @DisplayName("전화 API 가 받아 주지 않을 때")
    class ApiFailure {

        @BeforeEach
        void callable() {
            given(task(TaskClassification.PRIORITY_CHECK, Map.of("status", "CLOSED"), "053-111-1111"));
        }

        @Test
        @DisplayName("주소·키가 없으면 '지금 전화를 걸 수 없습니다' 로 가른다 — 설정 문제와 장애를 섞지 않는다")
        void 설정이_없다() {
            willThrow(new CallNotConfigured("주소 없음")).given(callClient).request(any());

            assertThatThrownBy(() -> callService.request(TASK_ID, ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CALL_NOT_AVAILABLE);
        }

        @Test
        @DisplayName("기록 저장이 실패하면 삼키지 않는다 — 전화는 이미 걸렸고 그 사실이 드러나야 한다")
        void 저장_실패는_삼키지_않는다() {
            // 전화 API 가 같은 verificationId 를 두 번 주면 DB 의 unique 제약에 걸린다.
            // saveAndFlush 라 이 예외가 서비스 안에서 터지고, 서비스가 verificationId 를 로그에 남긴 뒤 다시 던진다
            willThrow(new DataIntegrityViolationException("duplicate key"))
                    .given(callRepository).saveAndFlush(any());

            assertThatThrownBy(() -> callService.request(TASK_ID, ADMIN_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("전화 API 가 거절하거나 닿지 않으면 기록을 남기지 않는다")
        void 전화_API_실패() {
            willThrow(new CallTransientError("닿지 않음")).given(callClient).request(any());

            assertThatThrownBy(() -> callService.request(TASK_ID, ADMIN_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CALL_API_ERROR);
            verify(callRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("상태 받아 오기")
    class Refresh {

        private VerificationCall call(LocalDateTime requestedAt) {
            VerificationCall call = VerificationCall.builder()
                    .store(store("053-111-1111"))
                    .reason(CallReason.CHANGE_DETECTED)
                    .externalId(EXTERNAL_ID)
                    .requestedBy(String.valueOf(ADMIN_ID))
                    .requestedAt(requestedAt)
                    .build();
            org.mockito.BDDMockito.given(callRepository.findById(9L)).willReturn(Optional.of(call));
            return call;
        }

        @Test
        @DisplayName("너무 오래 끝나지 않은 전화는 전화 API 에 묻지 않고 실패로 닫는다 — 안 닫으면 영영 '전화 중' 이다")
        void 오래_기다린_전화는_닫는다() {
            VerificationCall call = call(LocalDateTime.now().minusMinutes(MAX_WAIT_MINUTES + 1));

            assertThat(callService.refresh(9L)).isTrue();
            assertThat(call.getStatus()).isEqualTo(CallStatus.FAILED);
            assertThat(call.getEndedAt()).isNotNull();
            // 사유는 늘 코드다 — 사람 문장을 넣으면 이 한 경우가 담당자 분류에서 빠진다
            assertThat(call.getFailureReason()).isEqualTo(CallFailureKind.GAVE_UP_WAITING);
            assertThat(CallFailureKind.of(call.getFailureReason())).isEqualTo(CallFailureKind.OUR_SIDE);
            verifyNoInteractions(callClient);
        }

        @Test
        @DisplayName("전화 API 를 읽지 못해도 예외를 밖으로 내지 않는다 — 한 건 때문에 남은 전화의 갱신이 멈추면 안 된다")
        void 읽기_실패는_삼킨다() {
            VerificationCall call = call(LocalDateTime.now());
            willThrow(new CallTransientError("닿지 않음")).given(callClient).read(EXTERNAL_ID);

            assertThat(callService.refresh(9L)).isFalse();
            assertThat(call.getStatus()).isEqualTo(CallStatus.PENDING);
        }

        @Test
        @DisplayName("없는 전화는 조용히 지나간다")
        void 없는_전화() {
            org.mockito.BDDMockito.given(callRepository.findById(9L)).willReturn(Optional.empty());

            assertThat(callService.refresh(9L)).isFalse();
            verifyNoInteractions(callClient);
        }
    }
}
