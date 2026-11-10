package com.ktc4.backend.domain.call.service;

import com.ktc4.backend.domain.call.client.CallClient;
import com.ktc4.backend.domain.call.client.CallContractError;
import com.ktc4.backend.domain.call.client.CallNotConfigured;
import com.ktc4.backend.domain.call.entity.VerificationCall;
import com.ktc4.backend.domain.call.repository.VerificationCallRepository;
import com.ktc4.backend.global.error.CustomException;
import com.ktc4.backend.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CallLogServiceTest {

    private final VerificationCallRepository repository = mock(VerificationCallRepository.class);
    private final CallClient client = mock(CallClient.class);
    private final CallLogService service = new CallLogService(repository, client);

    @BeforeEach
    void existingCall() {
        VerificationCall call = mock(VerificationCall.class);
        given(call.getExternalId()).willReturn("external-id");
        given(repository.findById(7L)).willReturn(Optional.of(call));
    }

    @Test
    void 로컬_전화_ID를_외부_ID로_바꾸고_본문을_보존한다() {
        given(client.readLogs("external-id", 2)).willReturn("{\"nextCursor\":3}");

        assertThat(service.read(7L, 2)).isEqualTo("{\"nextCursor\":3}");
        verify(client).readLogs("external-id", 2);
    }

    @Test
    void 없는_전화는_외부_호출_없이_404() {
        assertThatThrownBy(() -> service.read(99L, 0))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CALL_NOT_FOUND);
        verifyNoInteractions(client);
    }

    @Test
    void 설정_없음과_상위_서버_실패를_구분한다() {
        given(client.readLogs("external-id", 0))
                .willThrow(new CallNotConfigured("설정 없음"))
                .willThrow(new CallContractError("상위 서버 404"));

        assertThatThrownBy(() -> service.read(7L, 0))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CALL_NOT_AVAILABLE);
        assertThatThrownBy(() -> service.read(7L, 0))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CALL_API_ERROR);
    }
}
